package com.mobilerun.portal.ui.taskprompt

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.mobilerun.portal.R
import com.mobilerun.portal.config.ConfigManager
import com.mobilerun.portal.databinding.ActivityTaskHistoryBinding
import com.mobilerun.portal.databinding.ItemTaskHistoryBinding
import com.mobilerun.portal.taskprompt.PortalCloudClient
import com.mobilerun.portal.taskprompt.PortalDashboardStats
import com.mobilerun.portal.taskprompt.PortalTaskHistoryItem
import com.mobilerun.portal.taskprompt.PortalTaskHistoryPage
import com.mobilerun.portal.taskprompt.PortalTaskHistoryResult
import com.mobilerun.portal.taskprompt.PortalTaskStatusAppearance
import com.mobilerun.portal.taskprompt.PortalTaskUiSupport
import com.google.android.material.tabs.TabLayout
import java.util.Locale

class TaskHistoryActivity : AppCompatActivity() {
    companion object {
        private const val PAGE_SIZE = 20
        private const val DASHBOARD_PAGE_SIZE = 100
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun createIntent(context: Context): Intent {
            return Intent(context, TaskHistoryActivity::class.java)
        }
    }

    private val portalCloudClient = PortalCloudClient()
    private val handler = Handler(Looper.getMainLooper())
    private val items = mutableListOf<PortalTaskHistoryItem>()

    private lateinit var binding: ActivityTaskHistoryBinding
    private lateinit var footerView: LinearLayout
    private lateinit var adapter: TaskHistoryAdapter

    private var searchRunnable: Runnable? = null
    private var currentPage = 0
    private var hasNextPage = false
    private var isInitialLoading = false
    private var isLoadingMore = false
    private var isLoadMorePausedAfterFailure = false
    private var requestToken = 0
    private var errorMessage: String? = null
    private var hasLoadedHistory = false
    private var loadedHistoryQuery: String? = null

    private var dataRequestToken = 0
    private var isDataLoading = false
    private var hasLoadedData = false
    private var dashboardStats: PortalDashboardStats? = null
    private var hasBoundDashboardStats = false
    private var cachedTaskPage: PortalTaskHistoryPage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTaskHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.taskHistoryBackButton.setOnClickListener { finish() }

        setupTabs()
        setupHistoryTab()
        setupDashboardTab()

        if (!hasValidSession(showToast = true)) {
            finish()
            return
        }

        // Start on Dashboard tab
        showDashboardTab()
    }

    override fun onDestroy() {
        searchRunnable?.let(handler::removeCallbacks)
        super.onDestroy()
    }

    private fun setupTabs() {
        binding.taskTabs.addTab(binding.taskTabs.newTab().setText(R.string.tasks_tab_dashboard))
        binding.taskTabs.addTab(binding.taskTabs.newTab().setText(R.string.tasks_tab_history))

        binding.taskTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> showDashboardTab()
                    1 -> showHistoryTab()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
    }

    private fun showDashboardTab() {
        searchRunnable?.let(handler::removeCallbacks)
        binding.taskHistoryContainer.visibility = View.GONE
        if (!hasLoadedData && !isDataLoading) {
            loadData()
        } else {
            renderDashboardState()
        }
    }

    private fun showHistoryTab() {
        binding.taskDashboardSwipeRefresh.visibility = View.GONE
        binding.taskDashboardLoading.visibility = View.GONE
        binding.taskDashboardError.visibility = View.GONE
        binding.taskHistoryContainer.visibility = View.VISIBLE
        val query = currentHistoryQuery()
        if (
            !isInitialLoading &&
            TaskHistoryQueryState.shouldLoadHistory(
                hasLoadedHistory = hasLoadedHistory,
                loadedHistoryQuery = loadedHistoryQuery,
                currentQuery = query,
            )
        ) {
            loadHistoryForCurrentQuery()
        }
    }

    private fun loadHistoryForCurrentQuery() {
        searchRunnable?.let(handler::removeCallbacks)
        searchRunnable = null
        if (currentHistoryQuery().isBlank() && seedHistoryFromDashboard()) return
        loadTasks(reset = true)
    }

    private fun seedHistoryFromDashboard(): Boolean {
        val cached = cachedTaskPage ?: return false
        val query = currentHistoryQuery()
        if (query.isNotBlank()) return false

        // The seeded page replaces any history request still in flight.
        requestToken++
        isInitialLoading = false
        isLoadingMore = false
        binding.taskHistorySwipeRefresh.isRefreshing = false
        items.clear()
        items.addAll(cached.items)
        isLoadMorePausedAfterFailure = false
        currentPage = (cached.items.size + PAGE_SIZE - 1) / PAGE_SIZE
        hasNextPage = cached.total > cached.items.size
        hasLoadedHistory = true
        loadedHistoryQuery = query
        errorMessage = null
        adapter.notifyDataSetChanged()
        renderState()
        return true
    }

    private fun currentHistoryQuery(): String {
        return TaskHistoryQueryState.normalizeQuery(binding.taskHistorySearchInput.text)
    }

    private fun configureSwipeRefresh(
        swipeRefresh: SwipeRefreshLayout,
        onRefresh: () -> Unit,
    ) {
        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(this, R.color.task_prompt_accent),
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(this, R.color.background_card),
        )
        swipeRefresh.setOnRefreshListener { onRefresh() }
    }

    private fun setupDashboardTab() {
        binding.taskDashboardRetryButton.setOnClickListener { loadData() }
        configureSwipeRefresh(binding.taskDashboardSwipeRefresh) { refreshDashboard() }
    }

    private fun loadData() {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            hasLoadedData = false
            dashboardStats = null
            renderDashboardState()
            return
        }

        isDataLoading = true
        renderDashboardState()

        val localToken = ++dataRequestToken
        portalCloudClient.listTasks(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = null,
            page = 1,
            pageSize = DASHBOARD_PAGE_SIZE,
        ) { result ->
            val stats = when (result) {
                is PortalTaskHistoryResult.Success -> PortalDashboardStats.compute(
                    items = result.value.items,
                    total = result.value.total,
                )
                is PortalTaskHistoryResult.Error -> null
            }
            val dashboardItems = when (result) {
                is PortalTaskHistoryResult.Success -> result.value
                is PortalTaskHistoryResult.Error -> null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || localToken != dataRequestToken) {
                    return@runOnUiThread
                }
                isDataLoading = false
                if (stats != null) {
                    hasLoadedData = true
                    dashboardStats = stats
                    cachedTaskPage = dashboardItems
                } else {
                    hasLoadedData = false
                    dashboardStats = null
                    cachedTaskPage = null
                }
                if (binding.taskTabs.selectedTabPosition == 1) {
                    if (dashboardItems != null) {
                        seedHistoryFromDashboard()
                    } else {
                        hasLoadedHistory = items.isNotEmpty()
                        Toast.makeText(this, getString(R.string.dashboard_error), Toast.LENGTH_SHORT).show()
                    }
                }
                renderDashboardState()
            }
        }
    }

    private fun refreshDashboard() {
        if (isDataLoading) return
        hasLoadedData = false
        dashboardStats = null
        cachedTaskPage = null
        loadData()
    }

    private fun renderDashboardState() {
        if (binding.taskTabs.selectedTabPosition != 0) return

        if (isDataLoading) {
            if (binding.taskDashboardSwipeRefresh.isRefreshing && hasBoundDashboardStats) {
                // Keep the refreshing content visible; the History tab may have hidden it.
                binding.taskDashboardSwipeRefresh.visibility = View.VISIBLE
                binding.taskDashboardLoading.visibility = View.GONE
            } else {
                binding.taskDashboardSwipeRefresh.visibility = View.GONE
                binding.taskDashboardLoading.visibility = View.VISIBLE
            }
            binding.taskDashboardError.visibility = View.GONE
            return
        }

        binding.taskDashboardSwipeRefresh.isRefreshing = false

        val stats = dashboardStats
        if (stats == null) {
            binding.taskDashboardSwipeRefresh.visibility = View.GONE
            binding.taskDashboardLoading.visibility = View.GONE
            binding.taskDashboardError.visibility = View.VISIBLE
            return
        }

        binding.taskDashboardSwipeRefresh.visibility = View.VISIBLE
        binding.taskDashboardLoading.visibility = View.GONE
        binding.taskDashboardError.visibility = View.GONE
        hasBoundDashboardStats = true

        val na = getString(R.string.dashboard_not_available)

        // Performance
        binding.dashboardAvgDurationValue.text = stats.avgDurationMs?.let { PortalTaskUiSupport.formatDuration(it) } ?: na
        binding.dashboardAvgStepsValue.text = stats.avgSteps?.toString() ?: na
        binding.dashboardTopModelValue.text = stats.topModel?.let { PortalCloudClient.formatModelLabel(it) } ?: na
        val sampleHint = if (stats.sampleSize < stats.totalRuns) {
            getString(R.string.dashboard_sample_hint, stats.sampleSize)
        } else ""
        binding.dashboardPerformanceSampleHint.text = sampleHint

        // Sparkline
        binding.dashboardSparkline.setData(
            stats.activityByDay.map { it.count },
            stats.activityByDay.map { it.label },
        )

        // Success Rate + Ring
        binding.dashboardSuccessRateSampleHint.text = sampleHint
        binding.dashboardSuccessRing.setData(stats.statusCounts)

        // Status Legend
        binding.dashboardStatusLegend.removeAllViews()
        val rowPadding = (3 * resources.displayMetrics.density).toInt()
        val dotSize = (8 * resources.displayMetrics.density).toInt()
        val secondaryColor = ContextCompat.getColor(this, R.color.task_prompt_text_secondary)
        val primaryColor = ContextCompat.getColor(this, R.color.text_white)
        for (sc in stats.statusCounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, rowPadding, 0, rowPadding)
            }
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    marginEnd = dotSize
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(ContextCompat.getColor(this@TaskHistoryActivity, sc.colorRes))
                }
            }
            val label = TextView(this).apply {
                text = PortalTaskUiSupport.statusLabel(this@TaskHistoryActivity, sc.status)
                setTextColor(secondaryColor)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val count = TextView(this).apply {
                text = sc.count.toString()
                setTextColor(primaryColor)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(dot)
            row.addView(label)
            row.addView(count)
            binding.dashboardStatusLegend.addView(row)
        }
        if (stats.successRate != null) {
            binding.dashboardSuccessRateValue.text = String.format(Locale.US, "%.1f%%", stats.successRate)
        } else {
            binding.dashboardSuccessRateValue.text = "—"
        }
        binding.dashboardSuccessRateDetail.text = getString(
            R.string.dashboard_done_failed_format,
            stats.completedCount,
            stats.failedCount,
        )

        // Total Runs
        binding.dashboardTotalRunsValue.text = String.format(Locale.US, "%,d", stats.totalRuns)
        binding.dashboardTotalRunsDetail.text = stats.lastTaskAgoMs?.let { ms ->
            getString(R.string.dashboard_last_task_format, PortalTaskUiSupport.formatTimeAgo(ms))
        } ?: getString(R.string.dashboard_no_tasks_yet)
    }

    private fun setupHistoryTab() {
        footerView = buildFooterView()
        binding.taskHistoryList.addFooterView(footerView, null, false)
        footerView.visibility = View.GONE
        adapter = TaskHistoryAdapter()
        binding.taskHistoryList.adapter = adapter
        binding.taskHistoryList.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position) as? PortalTaskHistoryItem ?: return@setOnItemClickListener
            startActivity(TaskDetailsActivity.createIntent(this, item.taskId))
        }
        binding.taskHistoryList.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                if (
                    isLoadMorePausedAfterFailure &&
                    !TaskHistoryLoadMoreState.isNearEnd(firstVisibleItem, visibleItemCount, totalItemCount)
                ) {
                    isLoadMorePausedAfterFailure = false
                }
                if (
                    TaskHistoryLoadMoreState.shouldLoadMore(
                        hasNextPage = hasNextPage,
                        isLoading = isInitialLoading || isLoadingMore,
                        isPausedAfterFailure = isLoadMorePausedAfterFailure,
                        firstVisibleItem = firstVisibleItem,
                        visibleItemCount = visibleItemCount,
                        totalItemCount = totalItemCount,
                    )
                ) {
                    loadTasks(reset = false)
                }
            }
        })

        binding.taskHistoryRetryButton.setOnClickListener { loadTasks(reset = true) }
        binding.taskHistorySearchInput.doAfterTextChanged { scheduleSearch() }
        configureSwipeRefresh(binding.taskHistorySwipeRefresh) { loadTasks(reset = true) }
        binding.taskHistorySwipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.taskHistoryList.visibility == View.VISIBLE && binding.taskHistoryList.canScrollVertically(-1)
        }
    }

    private fun scheduleSearch() {
        searchRunnable?.let(handler::removeCallbacks)
        searchRunnable = Runnable { loadTasks(reset = true) }
        handler.postDelayed(searchRunnable!!, SEARCH_DEBOUNCE_MS)
    }

    private fun loadTasks(reset: Boolean) {
        val authToken = currentAuthToken()
        val restBaseUrl = currentRestBaseUrl()
        if (authToken.isBlank() || restBaseUrl == null) {
            binding.taskHistorySwipeRefresh.isRefreshing = false
            if (hasValidSession(showToast = true)) return
            finish()
            return
        }

        if (reset) {
            isInitialLoading = true
            isLoadingMore = false
            isLoadMorePausedAfterFailure = false
            currentPage = 0
            hasNextPage = false
            errorMessage = null
        } else {
            if (!hasNextPage || isInitialLoading || isLoadingMore) return
            isLoadingMore = true
        }
        renderState()

        val nextPage = if (reset) 1 else currentPage + 1
        val requestQuery = currentHistoryQuery()
        val query = requestQuery.takeIf { it.isNotBlank() }
        val localRequestToken = ++requestToken
        portalCloudClient.listTasks(
            restBaseUrl = restBaseUrl,
            authToken = authToken,
            query = query,
            page = nextPage,
            pageSize = PAGE_SIZE,
        ) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed || localRequestToken != requestToken) {
                    return@runOnUiThread
                }

                isInitialLoading = false
                isLoadingMore = false
                binding.taskHistorySwipeRefresh.isRefreshing = false
                if (
                    reset &&
                    TaskHistoryQueryState.hasQueryChangedSinceRequest(
                        requestQuery = requestQuery,
                        currentQuery = currentHistoryQuery(),
                    )
                ) {
                    renderState()
                    if (binding.taskTabs.selectedTabPosition == 1) {
                        loadHistoryForCurrentQuery()
                    }
                    return@runOnUiThread
                }

                when (result) {
                    is PortalTaskHistoryResult.Success -> {
                        errorMessage = null
                        hasLoadedHistory = true
                        if (reset) {
                            loadedHistoryQuery = requestQuery
                        }
                        if (reset) {
                            items.clear()
                        }
                        items.addAll(result.value.items)
                        currentPage = result.value.page
                        hasNextPage = result.value.hasNext
                    }

                    is PortalTaskHistoryResult.Error -> {
                        if (!reset) {
                            // Relayout calls onScroll again at the end; retry only after scrolling away.
                            isLoadMorePausedAfterFailure = true
                            Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        } else {
                            items.clear()
                            errorMessage = result.message
                            hasNextPage = false
                            currentPage = 0
                        }
                    }
                }

                adapter.notifyDataSetChanged()
                renderState()
            }
        }
    }

    private fun renderState() {
        val query = currentHistoryQuery()
        val showLoading = isInitialLoading && items.isEmpty()
        val showList = items.isNotEmpty()
        val showEmpty = !showLoading && !showList

        binding.taskHistoryLoadingView.visibility = if (showLoading) View.VISIBLE else View.GONE
        binding.taskHistoryList.visibility = if (showList) View.VISIBLE else View.GONE
        binding.taskHistoryEmptyView.visibility = if (showEmpty) View.VISIBLE else View.GONE
        footerView.visibility = if (isLoadingMore) View.VISIBLE else View.GONE

        if (showEmpty) {
            binding.taskHistoryEmptyText.text = when {
                !errorMessage.isNullOrBlank() -> errorMessage
                query.isNotBlank() -> getString(R.string.task_history_empty_search)
                else -> getString(R.string.task_history_empty)
            }
            binding.taskHistoryRetryButton.visibility = if (errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
        } else {
            binding.taskHistoryRetryButton.visibility = View.GONE
        }
    }

    private fun hasValidSession(showToast: Boolean): Boolean {
        val authToken = currentAuthToken()
        if (authToken.isBlank()) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_missing_api_key), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        if (currentRestBaseUrl() == null) {
            if (showToast) {
                Toast.makeText(this, getString(R.string.task_prompt_unsupported_custom_url), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        return true
    }

    private fun currentAuthToken(): String {
        return ConfigManager.getInstance(this).reverseConnectionToken.trim()
    }

    private fun currentRestBaseUrl(): String? {
        return PortalCloudClient.deriveRestBaseUrl(
            ConfigManager.getInstance(this).reverseConnectionUrlOrDefault,
        )
    }

    private fun buildFooterView(): LinearLayout {
        val padding = (12 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            addView(ProgressBar(context))
            addView(TextView(context).apply {
                text = getString(R.string.task_history_load_more)
                setTextColor(ContextCompat.getColor(context, R.color.task_prompt_text_secondary))
                textSize = 13f
                setPadding(padding / 2, 0, 0, 0)
            })
        }
    }

    private inner class TaskHistoryAdapter : BaseAdapter() {
        private val inflater = LayoutInflater.from(this@TaskHistoryActivity)

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): Any = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val itemBinding = if (convertView == null) {
                ItemTaskHistoryBinding.inflate(inflater, parent, false).also { rowBinding ->
                    rowBinding.root.tag = rowBinding
                }
            } else {
                (convertView.tag as? ItemTaskHistoryBinding)
                    ?: ItemTaskHistoryBinding.bind(convertView).also { rowBinding ->
                        rowBinding.root.tag = rowBinding
                    }
            }
            val item = items[position]

            itemBinding.taskHistoryItemStatusChip.text =
                PortalTaskUiSupport.statusLabel(this@TaskHistoryActivity, item.status)
            itemBinding.taskHistoryItemStatusChip.background =
                createChipBackground(PortalTaskUiSupport.statusAppearance(item.status))
            itemBinding.taskHistoryItemPrompt.text =
                item.promptPreview.ifBlank { item.prompt.ifBlank { item.taskId } }

            val metaParts = mutableListOf<String>()
            PortalTaskUiSupport.formatTimestamp(item.createdAt)?.let(metaParts::add)
            item.steps?.let { metaParts.add(getString(R.string.task_history_steps, it)) }
            itemBinding.taskHistoryItemMeta.text = metaParts.joinToString(" • ").ifBlank { item.taskId }

            val summary = PortalTaskUiSupport.buildSummary(
                context = this@TaskHistoryActivity,
                status = item.status,
                summary = item.summary,
                steps = item.steps,
            )
            itemBinding.taskHistoryItemSummary.text = summary.orEmpty()
            itemBinding.taskHistoryItemSummary.visibility =
                if (summary.isNullOrBlank()) View.GONE else View.VISIBLE
            return itemBinding.root
        }
    }

    private fun createChipBackground(appearance: PortalTaskStatusAppearance): GradientDrawable {
        val colorRes = when (appearance) {
            PortalTaskStatusAppearance.INFO -> R.color.task_prompt_chip_info_bg
            PortalTaskStatusAppearance.SUCCESS -> R.color.task_prompt_chip_success_bg
            PortalTaskStatusAppearance.ERROR -> R.color.task_prompt_chip_error_bg
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 999f
            setColor(ContextCompat.getColor(this@TaskHistoryActivity, colorRes))
        }
    }
}
