package com.matejdro.pebblenotificationcenter.notification.parsing

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import logcat.LogPriority
import logcat.logcat

object NotificationExtraExtractor {

   fun extractExtraInfo(sbn: StatusBarNotification): Map<String, String> {
      val notification = sbn.notification
      val extras = notification.extras ?: return emptyMap()

      val extraTitle = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString()
      val extraSubtitle = extras.getString("EXTRA_SUBTITLE")
      val extraBigText = extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT)?.toString()
      val extraThreadId = extras.getString("EXTRA_THREAD_ID")
      val extraId = extras.getString("EXTRA_ID")
      val extraTag = extras.getString("EXTRA_TAG")
      val extraPackageName = extras.getString("EXTRA_PACKAGE_NAME")?.takeIf { it != sbn.packageName }
      val alertOnce = extras.getBoolean("EXTRA_ONLY_ALERT_ONCE", false)
      val visibility = extras.getInt("EXTRA_VISIBILITY", NotificationCompat.VISIBILITY_PUBLIC)
      val stickySummary = extras.getBoolean("EXTRA_USE_STICKY_SUMMARY", false)
      val allowDuplicate = extras.getBoolean("EXTRA_ALLOW_DUPLICATE_NOTIFICATIONS", false)
      val dontShowBrightness = extras.getBoolean("EXTRA_DONT_SHOW_BRIGHTNESS_IN_LED_COLOR", false)
      val localOnly = extras.getBoolean("EXTRA_LOCAL_ONLY", false)
      val groupKey = extras.getString("EXTRA_GROUP_KEY")
      val customActions = extras.getStringArray("EXTRA_ACTIONS")
      val intentExtras = extras.getStringArray("EXTRA_INTENT")
      val stagingNotification = extras.getBoolean("EXTRA_USE_STAGING_NOTIFICATION", false)
      val stagingBundle = extras.getBoolean("EXTRA_USE_STAGING_BUNDLE", false)

      return buildMap<String, String> {
         extraTitle?.takeIf { it.isNotBlank() }?.let { this["EXTRA_TITLE"] = it }
         extraSubtitle?.takeIf { it.isNotBlank() }?.let { this["EXTRA_SUBTITLE"] = it }
         extraBigText?.takeIf { it.isNotBlank() }?.let { this["EXTRA_BIG_TEXT"] = it }
         extraThreadId?.takeIf { it.isNotBlank() }?.let { this["EXTRA_THREAD_ID"] = it }
         extraId?.takeIf { it.isNotBlank() }?.let { this["EXTRA_ID"] = it }
         extraTag?.takeIf { it.isNotBlank() }?.let { this["EXTRA_TAG"] = it }
         extraPackageName?.takeIf { it.isNotBlank() }?.let { this["EXTRA_PACKAGE_NAME"] = it }
         this["EXTRA_ONLY_ALERT_ONCE"] = alertOnce.toString()
         this["EXTRA_VISIBILITY"] = visibility.toString()
         this["EXTRA_USE_STICKY_SUMMARY"] = stickySummary.toString()
         this["EXTRA_ALLOW_DUPLICATE"] = allowDuplicate.toString()
         this["EXTRA_DONT_SHOW_BRIGHTNESS"] = dontShowBrightness.toString()
         this["EXTRA_LOCAL_ONLY"] = localOnly.toString()
         groupKey?.takeIf { it.isNotBlank() }?.let { this["EXTRA_GROUP_KEY"] = it }
         customActions?.takeIf { it.isNotEmpty() }?.let { this["EXTRA_ACTIONS"] = it.joinToString(", ") }
         intentExtras?.takeIf { it.isNotEmpty() }?.let { this["EXTRA_INTENTS"] = it.joinToString(", ") }
         this["EXTRA_USE_STAGING_NOTIFICATION"] = stagingNotification.toString()
         this["EXTRA_USE_STAGING_BUNDLE"] = stagingBundle.toString()
      }
   }

   fun formatExtraInfo(extraInfo: Map<String, String>): String {
      if (extraInfo.isEmpty()) {
         return ""
      }

      return extraInfo.entries
         .sortedBy { it.key }
         .joinToString(", ") { (key, value) ->
            "$key: $value"
         }
   }

   fun logExtraInfo(message: String, extraInfo: Map<String, String>) {
      val formatted = formatExtraInfo(extraInfo)
      if (formatted.isNotEmpty()) {
         logcat(LogPriority.DEBUG, "NotificationExtra") { "$message - Extra: $formatted" }
      }
   }
}
