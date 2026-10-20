package com.mobilerun.portal.streaming

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mobilerun.portal.input.MobilerunKeyboardIME
import com.mobilerun.portal.service.MobilerunAccessibilityService
import com.mobilerun.portal.service.GestureController
import org.webrtc.DataChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScrcpyControlChannel : DataChannel.Observer {
    companion object {
        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TEXT = 1
        private const val TYPE_INJECT_TOUCH_EVENT = ScrcpyTouchPacketDecoder.MESSAGE_TYPE
        private const val TYPE_INJECT_SCROLL_EVENT = ScrcpyScrollPacketDecoder.MESSAGE_TYPE
        private const val TYPE_BACK_OR_SCREEN_ON = 4
        private const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        private const val TYPE_EXPAND_SETTINGS_PANEL = 6
        private const val TYPE_COLLAPSE_PANELS = 7
        private const val TYPE_SET_CLIPBOARD = 9

        private const val ACTION_DOWN = 0
        private const val MIN_GESTURE_DURATION_MS = 50L
        private const val MAX_GESTURE_DURATION_MS = 5000L

        // Android's default wheel scroll factor is 64dp per tick.
        private const val SCROLL_DP_PER_TICK = 64f
        private const val SCROLL_MIN_DISTANCE_DP = 16f
        private const val SCROLL_FLUSH_INTERVAL_MS = 120L
        private const val SCROLL_SWIPE_DURATION_MS = 100
        private const val SCROLL_IDLE_RESET_MS = 500L
        private const val MAX_SCROLL_TARGET_DEPTH = 64
    }

    private val touchPath = mutableListOf<Pair<Float, Float>>()
    private val touchState = SinglePointerGestureState()
    private var touchStartTime = 0L

    private val scrollHandler = Handler(Looper.getMainLooper())
    private val scrollTicks = ScrollTickAccumulator(SCROLL_IDLE_RESET_MS)
    private var scrollFlushScheduled = false

    override fun onBufferedAmountChange(previousAmount: Long) {}

    override fun onStateChange() {}

    override fun onMessage(buffer: DataChannel.Buffer) {
        val data = ByteArray(buffer.data.remaining())
        buffer.data.get(data)
        handleMessage(data)
    }

    private fun handleMessage(data: ByteArray) {
        if (data.isEmpty()) return

        val type = data[0].toInt() and 0xFF
        when (type) {
            TYPE_INJECT_TOUCH_EVENT -> handleTouch(data)
            TYPE_INJECT_SCROLL_EVENT -> handleScroll(data)
            TYPE_BACK_OR_SCREEN_ON -> handleBack(data)
            TYPE_INJECT_TEXT -> handleText(data)
            TYPE_INJECT_KEYCODE -> handleKeycode(data)
            TYPE_SET_CLIPBOARD -> handleSetClipboard(data)
            TYPE_EXPAND_NOTIFICATION_PANEL -> expandNotificationPanel()
            TYPE_EXPAND_SETTINGS_PANEL -> expandSettingsPanel()
            TYPE_COLLAPSE_PANELS -> collapsePanels()
        }
    }

    private fun handleTouch(data: ByteArray) {
        val packet = ScrcpyTouchPacketDecoder.decode(data) ?: return
        val mappedPoint =
            scaleCoordinates(
                packet.x,
                packet.y,
                packet.videoWidth,
                packet.videoHeight,
            )

        when (val update = touchState.consume(packet.action, packet.pointerId, mappedPoint)) {
            is TouchGestureUpdate.Started -> {
                touchPath.clear()
                touchPath.add(Pair(update.point.x, update.point.y))
                touchStartTime = System.currentTimeMillis()
            }
            is TouchGestureUpdate.Moved -> {
                touchPath.add(Pair(update.point.x, update.point.y))
            }
            is TouchGestureUpdate.Finished -> {
                touchPath.add(Pair(update.point.x, update.point.y))
                val duration = (System.currentTimeMillis() - touchStartTime)
                    .coerceIn(MIN_GESTURE_DURATION_MS, MAX_GESTURE_DURATION_MS)
                dispatchPath(touchPath.toList(), duration)
                touchPath.clear()
            }
            TouchGestureUpdate.Cancelled -> touchPath.clear()
            TouchGestureUpdate.Ignored -> Unit
        }
    }

    private fun handleScroll(data: ByteArray) {
        val packet = ScrcpyScrollPacketDecoder.decode(data) ?: return
        val point =
            scaleCoordinates(packet.x, packet.y, packet.videoWidth, packet.videoHeight)
                ?: return

        scrollHandler.post {
            scrollTicks.add(point, packet.hScrollTicks, packet.vScrollTicks, SystemClock.uptimeMillis())
            if (!scrollFlushScheduled) {
                scrollFlushScheduled = true
                scrollHandler.postDelayed(::flushScroll, SCROLL_FLUSH_INTERVAL_MS)
            }
        }
    }

    private fun flushScroll() {
        scrollFlushScheduled = false
        val anchor = scrollTicks.anchor ?: return
        val (screenW, screenH) = screenSize()
        val density = Resources.getSystem().displayMetrics.density
        val pixelsPerTick = SCROLL_DP_PER_TICK * density
        val minDistancePx = SCROLL_MIN_DISTANCE_DP * density
        val wantsHorizontal =
            ScrollSwipePlanner.wantsHorizontal(
                scrollTicks.hScrollTicks,
                scrollTicks.vScrollTicks,
                pixelsPerTick,
                minDistancePx,
            )
        val allowHorizontal = wantsHorizontal && hasHorizontalScrollTargetAt(anchor)

        val swipe =
            ScrollSwipePlanner.plan(
                anchor,
                scrollTicks.hScrollTicks,
                scrollTicks.vScrollTicks,
                screenW,
                screenH,
                pixelsPerTick = pixelsPerTick,
                minDistancePx = minDistancePx,
                allowHorizontal = allowHorizontal,
            )
        if (swipe == null) {
            // A sideways scroll with nothing to scroll sideways is dropped whole.
            if (wantsHorizontal && !allowHorizontal) scrollTicks.clear()
            return
        }

        scrollTicks.clear()
        GestureController.swipe(
            swipe.start.x.toInt(), swipe.start.y.toInt(),
            swipe.end.x.toInt(), swipe.end.y.toInt(),
            SCROLL_SWIPE_DURATION_MS,
        )
    }

    private fun hasHorizontalScrollTargetAt(point: ScreenPoint): Boolean {
        val service = MobilerunAccessibilityService.getInstance() ?: return false
        val x = point.x.toInt()
        val y = point.y.toInt()
        val bounds = Rect()
        val windows =
            try {
                service.windows
            } catch (_: RuntimeException) {
                emptyList()
            }
        var root: AccessibilityNodeInfo? = null
        return try {
            val window =
                windows
                    .filter {
                        it.getBoundsInScreen(bounds)
                        it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && bounds.contains(x, y)
                    }
                    .maxByOrNull { it.layer }
            root = window?.root ?: service.rootInActiveWindow
            root != null && containsHorizontalScrollTarget(root, x, y, bounds, depth = 0)
        } catch (_: RuntimeException) {
            false
        } finally {
            root?.recycle()
            windows.forEach { it.recycle() }
        }
    }

    private fun containsHorizontalScrollTarget(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        bounds: Rect,
        depth: Int,
    ): Boolean {
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return false
        val collection = node.collectionInfo
        val qualifies =
            HorizontalScrollTarget.qualifies(
                node.actionList.map { it.id },
                collection?.rowCount,
                collection?.columnCount,
                node.className,
            )
        if (qualifies) return true
        if (depth >= MAX_SCROLL_TARGET_DEPTH) return false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsHorizontalScrollTarget(child, x, y, bounds, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    @Suppress("UNUSED_PARAMETER")
    private fun handleBack(data: ByteArray) {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun handleText(data: ByteArray) {
        if (data.size < 5) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.get()
        val length = buffer.int

        if (data.size < 5 + length) return
        val textBytes = ByteArray(length)
        buffer.get(textBytes)
        val text = String(textBytes, Charsets.UTF_8)

        typeText(text)
    }

    private fun handleKeycode(data: ByteArray) {
        if (data.size < 14) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.get() // skip type byte
        val action = buffer.get().toInt() and 0xFF
        val keycode = buffer.int
        buffer.int // repeat
        val metaState = buffer.int

        // Only handle key down events
        if (action != ACTION_DOWN) return

        // Handle special system keycodes
        when (keycode) {
            KeyEvent.KEYCODE_BACK -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
            KeyEvent.KEYCODE_HOME -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                return
            }
            KeyEvent.KEYCODE_APP_SWITCH -> {
                GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                return
            }
            KeyEvent.KEYCODE_DEL -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }
                service?.deleteText(1)
                return
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                // Forward delete
                val service = MobilerunAccessibilityService.getInstance() ?: return
                service.deleteText(1, forward = true)
                return
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }

                typeText("\n")
                return

            }
            KeyEvent.KEYCODE_TAB -> {
                val service = MobilerunAccessibilityService.getInstance()
                if (service != null && MobilerunKeyboardIME.isAvailable() && MobilerunKeyboardIME.isSelected(service)) {
                    val keyboard = MobilerunKeyboardIME.getInstance()
                    if (keyboard != null) {
                        keyboard.sendKeyEventDirect(keycode)
                        return
                    }
                }

                typeText("\t")
                return
            }
        }

        // For other keycodes, convert to character using KeyEvent
        val keyEvent = KeyEvent(KeyEvent.ACTION_DOWN, keycode)
        val unicodeChar = keyEvent.getUnicodeChar(metaState)

        if (unicodeChar > 0) {
            val char = unicodeChar.toChar()
            typeText(char.toString())
        }
    }

    private fun handleSetClipboard(data: ByteArray) {
        if (data.size < 14) return

        val buffer = ByteBuffer.wrap(data)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.get()
        buffer.long
        val paste = buffer.get().toInt() != 0

        val length = buffer.int
        if (data.size < 14 + length) return

        val textBytes = ByteArray(length)
        buffer.get(textBytes)
        val text = String(textBytes, Charsets.UTF_8)

        if (paste) {
            typeText(text)
        }
    }

    private fun expandNotificationPanel() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    }

    private fun expandSettingsPanel() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    }

    private fun collapsePanels() {
        GestureController.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun typeText(text: String) {
        val service = MobilerunAccessibilityService.getInstance() ?: return
        service.inputText(text, false)
    }

    private fun scaleCoordinates(x: Int, y: Int, videoW: Int, videoH: Int): ScreenPoint? {
        val (screenW, screenH) = screenSize()
        return FrameToScreenMapper.map(x, y, videoW, videoH, screenW, screenH)
    }

    private fun screenSize(): Pair<Int, Int> {
        val service = MobilerunAccessibilityService.getInstance()
        val wm = service?.getSystemService(android.content.Context.WINDOW_SERVICE) as? android.view.WindowManager

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val bounds = wm?.maximumWindowMetrics?.bounds
            Pair(bounds?.width() ?: Resources.getSystem().displayMetrics.widthPixels,
                 bounds?.height() ?: Resources.getSystem().displayMetrics.heightPixels)
        } else {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun dispatchPath(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.isEmpty()) return

        val service = MobilerunAccessibilityService.getInstance() ?: return

        try {
            val path = Path().apply {
                moveTo(points[0].first, points[0].second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
            }

            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            service.dispatchGesture(gesture, null, null)
        } catch (_: Exception) {}
    }
}
