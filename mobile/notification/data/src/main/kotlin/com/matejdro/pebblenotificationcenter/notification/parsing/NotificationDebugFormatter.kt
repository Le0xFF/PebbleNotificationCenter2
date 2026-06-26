package com.matejdro.pebblenotificationcenter.notification.parsing

import com.matejdro.pebblenotificationcenter.notification.model.ParsedNotification
import logcat.logcat

object NotificationDebugFormatter {

   fun format(parsedNotification: ParsedNotification): String {
      val body = truncate(parsedNotification.body)
      val tag = parsedNotification.tag ?: "null"
      val channel = parsedNotification.channel ?: "null"
      val extraInfoText = if (parsedNotification.extraInfo.isEmpty()) {
         "empty"
      } else {
         parsedNotification.extraInfo.entries.joinToString(", ") { "${it.key}: ${it.value}" }
      }
      return buildString {
         appendLine("--- Notification Debug ---")
         appendLine("pkg: ${parsedNotification.pkg}")
         appendLine("title: ${parsedNotification.title}")
         appendLine("subtitle: ${parsedNotification.subtitle}")
         appendLine("body: $body")
         appendLine("channel: $channel")
         appendLine("timestamp: ${parsedNotification.timestamp}")
         appendLine("id: ${parsedNotification.id}")
         appendLine("tag: $tag")
         appendLine("isSilent: ${parsedNotification.isSilent}")
         appendLine("isOngoing: ${parsedNotification.isOngoing}")
         appendLine("groupSummary: ${parsedNotification.groupSummary}")
         appendLine("localOnly: ${parsedNotification.localOnly}")
         appendLine("media: ${parsedNotification.media}")
         appendLine("forceVibrate: ${parsedNotification.forceVibrate}")
         appendLine("nativeActions: ${parsedNotification.nativeActions.size}")
         appendLine("extraInfo: $extraInfoText")
      }
   }
   fun log(parsedNotification: ParsedNotification) {
      logcat(tag = "NotificationDebug") { format(parsedNotification) }
   }
   private fun truncate(text: String): String {
      if (text.length <= MAX_BODY_LENGTH) return text
      return text.substring(0, MAX_BODY_LENGTH) + "..."
   }
   private const val MAX_BODY_LENGTH = 100
}
