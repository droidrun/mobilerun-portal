package com.mobilerun.portal.triggers

import android.content.Context
import com.mobilerun.portal.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TriggerUiSupport {
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** Only the visible Android UI uses translated names; API/catalog labels stay stable. */
    fun sourceLabel(source: TriggerSource, context: Context): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> context.getString(R.string.trigger_source_time_delay_label)
            TriggerSource.TIME_ABSOLUTE -> context.getString(R.string.trigger_source_time_absolute_label)
            TriggerSource.TIME_DAILY -> context.getString(R.string.trigger_source_time_daily_label)
            TriggerSource.TIME_WEEKLY -> context.getString(R.string.trigger_source_time_weekly_label)
            TriggerSource.NOTIFICATION_POSTED -> context.getString(R.string.trigger_source_notification_posted_label)
            TriggerSource.NOTIFICATION_REMOVED -> context.getString(R.string.trigger_source_notification_removed_label)
            TriggerSource.APP_ENTERED -> context.getString(R.string.trigger_source_app_entered_label)
            TriggerSource.APP_EXITED -> context.getString(R.string.trigger_source_app_exited_label)
            TriggerSource.BATTERY_LOW -> context.getString(R.string.trigger_source_battery_low_label)
            TriggerSource.BATTERY_OKAY -> context.getString(R.string.trigger_source_battery_okay_label)
            TriggerSource.BATTERY_LEVEL_CHANGED -> context.getString(R.string.trigger_source_battery_level_changed_label)
            TriggerSource.POWER_CONNECTED -> context.getString(R.string.trigger_source_power_connected_label)
            TriggerSource.POWER_DISCONNECTED -> context.getString(R.string.trigger_source_power_disconnected_label)
            TriggerSource.USER_PRESENT -> context.getString(R.string.trigger_source_user_present_label)
            TriggerSource.NETWORK_CONNECTED -> context.getString(R.string.trigger_source_network_connected_label)
            TriggerSource.NETWORK_TYPE_CHANGED -> context.getString(R.string.trigger_source_network_type_changed_label)
            TriggerSource.SMS_RECEIVED -> context.getString(R.string.trigger_source_sms_received_label)
        }
    }

    fun sourceDescription(source: TriggerSource, context: Context): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> context.getString(R.string.trigger_source_time_delay_description)
            TriggerSource.TIME_ABSOLUTE -> context.getString(R.string.trigger_source_time_absolute_description)
            TriggerSource.TIME_DAILY -> context.getString(R.string.trigger_source_time_daily_description)
            TriggerSource.TIME_WEEKLY -> context.getString(R.string.trigger_source_time_weekly_description)
            TriggerSource.NOTIFICATION_POSTED -> context.getString(R.string.trigger_source_notification_posted_description)
            TriggerSource.NOTIFICATION_REMOVED -> context.getString(R.string.trigger_source_notification_removed_description)
            TriggerSource.APP_ENTERED -> context.getString(R.string.trigger_source_app_entered_description)
            TriggerSource.APP_EXITED -> context.getString(R.string.trigger_source_app_exited_description)
            TriggerSource.BATTERY_LOW -> context.getString(R.string.trigger_source_battery_low_description)
            TriggerSource.BATTERY_OKAY -> context.getString(R.string.trigger_source_battery_okay_description)
            TriggerSource.BATTERY_LEVEL_CHANGED -> context.getString(R.string.trigger_source_battery_level_changed_description)
            TriggerSource.POWER_CONNECTED -> context.getString(R.string.trigger_source_power_connected_description)
            TriggerSource.POWER_DISCONNECTED -> context.getString(R.string.trigger_source_power_disconnected_description)
            TriggerSource.USER_PRESENT -> context.getString(R.string.trigger_source_user_present_description)
            TriggerSource.NETWORK_CONNECTED -> context.getString(R.string.trigger_source_network_connected_description)
            TriggerSource.NETWORK_TYPE_CHANGED -> context.getString(R.string.trigger_source_network_type_changed_description)
            TriggerSource.SMS_RECEIVED -> context.getString(R.string.trigger_source_sms_received_description)
        }
    }

    fun dispositionLabel(disposition: TriggerRunDisposition, context: Context): String {
        return when (disposition) {
            TriggerRunDisposition.MATCHED -> context.getString(R.string.trigger_disposition_matched)
            TriggerRunDisposition.LAUNCHED -> context.getString(R.string.trigger_disposition_launched)
            TriggerRunDisposition.SKIPPED_BUSY -> context.getString(R.string.trigger_disposition_skipped_busy)
            TriggerRunDisposition.BUFFERED -> context.getString(R.string.trigger_disposition_buffered)
            TriggerRunDisposition.DEBOUNCED -> context.getString(R.string.trigger_disposition_debounced)
            TriggerRunDisposition.PERMISSION_MISSING -> context.getString(R.string.trigger_disposition_permission_missing)
            TriggerRunDisposition.LAUNCH_FAILED -> context.getString(R.string.trigger_disposition_launch_failed)
            TriggerRunDisposition.RULE_DISABLED -> context.getString(R.string.trigger_disposition_rule_disabled)
            TriggerRunDisposition.TEST_LAUNCHED -> context.getString(R.string.trigger_disposition_test_launched)
        }
    }

    fun dayOfWeekLabel(dayOfWeek: Int, context: Context): String? {
        return when (dayOfWeek) {
            java.util.Calendar.SUNDAY -> context.getString(R.string.trigger_day_sunday)
            java.util.Calendar.MONDAY -> context.getString(R.string.trigger_day_monday)
            java.util.Calendar.TUESDAY -> context.getString(R.string.trigger_day_tuesday)
            java.util.Calendar.WEDNESDAY -> context.getString(R.string.trigger_day_wednesday)
            java.util.Calendar.THURSDAY -> context.getString(R.string.trigger_day_thursday)
            java.util.Calendar.FRIDAY -> context.getString(R.string.trigger_day_friday)
            java.util.Calendar.SATURDAY -> context.getString(R.string.trigger_day_saturday)
            else -> null
        }
    }


    fun sourceLabel(source: TriggerSource): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> "After a delay"
            TriggerSource.TIME_ABSOLUTE -> "At a specific time"
            TriggerSource.TIME_DAILY -> "Repeat every day"
            TriggerSource.TIME_WEEKLY -> "Repeat on selected weekdays"
            else -> source.name
                .lowercase(Locale.US)
                .split('_')
                .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
        }
    }

    fun sourceDescription(source: TriggerSource): String {
        return when (source) {
            TriggerSource.TIME_DELAY -> "Run once after the selected delay."
            TriggerSource.TIME_ABSOLUTE -> "Run once at the selected date and time."
            TriggerSource.TIME_DAILY -> "Run every day at the selected time."
            TriggerSource.TIME_WEEKLY -> "Run on the selected weekdays at the selected time."
            TriggerSource.NOTIFICATION_POSTED -> "Run when a matching notification appears."
            TriggerSource.NOTIFICATION_REMOVED -> "Run when a matching notification is dismissed or disappears."
            TriggerSource.APP_ENTERED -> "Run when the selected app comes to the foreground."
            TriggerSource.APP_EXITED -> "Run when the selected app leaves the foreground."
            TriggerSource.BATTERY_LOW -> "Run when Android reports that the battery is low."
            TriggerSource.BATTERY_OKAY -> "Run when Android reports that the battery is okay again."
            TriggerSource.BATTERY_LEVEL_CHANGED -> "Run when the battery level crosses the selected threshold."
            TriggerSource.POWER_CONNECTED -> "Run when charging starts."
            TriggerSource.POWER_DISCONNECTED -> "Run when charging stops."
            TriggerSource.USER_PRESENT -> "Run when the device is unlocked and the user becomes active."
            TriggerSource.NETWORK_CONNECTED -> "Run when the device gains network connectivity."
            TriggerSource.NETWORK_TYPE_CHANGED -> "Run when the connection type changes, such as Wi-Fi to cellular."
            TriggerSource.SMS_RECEIVED -> "Run when a matching SMS arrives."
        }
    }

    fun summary(rule: TriggerRule, context: Context? = null): String {
        return buildList {
            add(if (context == null) sourceLabel(rule.source) else sourceLabel(rule.source, context))
            rule.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                val label = resolveAppLabel(context, pkg)
                add(if (label != null) {
                    if (context == null) "app=$label" else context.getString(R.string.trigger_summary_app, label)
                } else {
                    if (context == null) "pkg=$pkg" else context.getString(R.string.trigger_summary_pkg, pkg)
                })
            }
            rule.titleFilter?.takeIf { it.isNotBlank() }?.let { add(if (context == null) "title=$it" else context.getString(R.string.trigger_summary_title, it)) }
            rule.textFilter?.takeIf { it.isNotBlank() }?.let { add(if (context == null) "text=$it" else context.getString(R.string.trigger_summary_text, it)) }
            rule.networkType?.let { add(if (context == null) "network=${it.name}" else context.getString(R.string.trigger_summary_network, it.name)) }
            rule.thresholdValue?.let { add(if (context == null) "threshold=${rule.thresholdComparison.name.lowercase()} $it" else context.getString(R.string.trigger_summary_threshold, rule.thresholdComparison.name.lowercase(), it.toString())) }
            rule.phoneNumberFilter?.takeIf { it.isNotBlank() }?.let { add(if (context == null) "phone=$it" else context.getString(R.string.trigger_summary_phone, it)) }
            rule.delayMinutes?.let { add(if (context == null) "delay=${it}m" else context.getString(R.string.trigger_summary_delay, it)) }
            if (rule.source == TriggerSource.TIME_ABSOLUTE) {
                rule.absoluteTimeMillis?.let { add(if (context == null) "at=${formatTimestamp(it)}" else context.getString(R.string.trigger_summary_at, formatTimestamp(it))) }
            }
            if (rule.source == TriggerSource.TIME_DAILY || rule.source == TriggerSource.TIME_WEEKLY) {
                val hour = rule.dailyHour ?: 0
                val minute = rule.dailyMinute ?: 0
                add(String.format(Locale.US, "%02d:%02d", hour, minute))
                if (rule.source == TriggerSource.TIME_WEEKLY) {
                    val labels = rule.resolvedWeeklyDaysOfWeek()
                        .mapNotNull { if (context == null) dayOfWeekLabel(it) else dayOfWeekLabel(it, context) }
                    if (labels.isNotEmpty()) {
                        add(labels.joinToString(", "))
                    }
                }
            }
        }.joinToString(" • ")
    }

    fun dispositionLabel(disposition: TriggerRunDisposition): String {
        return disposition.name
            .lowercase(Locale.US)
            .split('_')
            .joinToString(" ") { it.replaceFirstChar { char -> char.titlecase(Locale.US) } }
    }

    fun formatTimestamp(timestampMs: Long): String {
        return timestampFormatter.format(Date(timestampMs))
    }

    fun dayOfWeekEntries(): List<Pair<String, Int>> {
        return listOf(
            "Sunday" to java.util.Calendar.SUNDAY,
            "Monday" to java.util.Calendar.MONDAY,
            "Tuesday" to java.util.Calendar.TUESDAY,
            "Wednesday" to java.util.Calendar.WEDNESDAY,
            "Thursday" to java.util.Calendar.THURSDAY,
            "Friday" to java.util.Calendar.FRIDAY,
            "Saturday" to java.util.Calendar.SATURDAY,
        )
    }

    fun dayOfWeekLabel(dayOfWeek: Int): String? {
        return dayOfWeekEntries().firstOrNull { it.second == dayOfWeek }?.first
    }

    fun resolveAppLabel(context: Context?, packageName: String?): String? {
        if (context == null || packageName.isNullOrBlank()) return null
        return try {
            val pm = context.packageManager
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            null
        }
    }
}
