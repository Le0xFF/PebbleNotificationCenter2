package com.matejdro.pebblenotificationcenter.notification

import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.time.Instant
import com.matejdro.pebblenotificationcenter.common.preferences.plus
import com.matejdro.pebblenotificationcenter.notification.model.ParsedNotification
import com.matejdro.pebblenotificationcenter.rules.RULE_ID_DEFAULT_SETTINGS
import com.matejdro.pebblenotificationcenter.rules.RuleOption
import com.matejdro.pebblenotificationcenter.rules.RulesRepository
import com.matejdro.pebblenotificationcenter.rules.keys.get
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import si.inova.kotlinova.core.outcome.Outcome

@Inject
class RuleResolver(private val rulesRepository: RulesRepository) {
   suspend fun resolveRules(notification: ParsedNotification): ResolvedRules {
      val rules = rulesRepository.getAll().firstSuccessOrThrow()

      val matchingRules = rules.mapNotNull { rule ->
         val preferences = rulesRepository.getRulePreferences(rule.id).first()

         val nameIfNotDefault = rule.name.takeIf { rule.id != RULE_ID_DEFAULT_SETTINGS }

         (nameIfNotDefault to preferences).takeIf { rule.id == RULE_ID_DEFAULT_SETTINGS || preferences.matches(notification) }
      }

      return ResolvedRules(
         matchingRules.mapNotNull { (name, _) -> name },
         matchingRules.map { (_, preferences) -> preferences }.fold(emptyPreferences(), Preferences::plus)
      )
   }

   private fun Preferences.matches(notification: ParsedNotification): Boolean {
      val conditionPkg = this[RuleOption.conditionAppPackage]
      if (conditionPkg != null && conditionPkg != notification.pkg) {
         return false
      }

      val conditionChannels = this[RuleOption.conditionNotificationChannels]
      if (conditionChannels.isNotEmpty() && !conditionChannels.contains(notification.channel)) {
         return false
      }

      val whitelistRegexes = this[RuleOption.conditionWhitelistRegexes].map { Regex(it) }
      if (whitelistRegexes.isNotEmpty() && !whitelistRegexes.all(notification::containsRegex)) {
         return false
      }

      val blacklistRegexes = this[RuleOption.conditionBlacklistRegexes].map { Regex(it) }
      if (blacklistRegexes.any(notification::containsRegex)) {
         return false
      }

      return true
   }
}

data class ResolvedRules(
   val involvedRules: List<String>,
   val preferences: Preferences,
)

// Resolves rule preferences from a raw StatusBarNotification, before parsing: builds a minimal
// ParsedNotification (empty text fields) so rule matching works on pkg/channel/flags only.
suspend fun RuleResolver.rulesPreferencesFor(sbn: StatusBarNotification): Preferences =
   resolveRules(
      ParsedNotification(
         key = sbn.key,
         pkg = sbn.packageName,
         title = "",
         subtitle = "",
         body = "",
         timestamp = Instant.ofEpochMilli(sbn.postTime),
         channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) sbn.notification.channelId else null,
         isOngoing = sbn.isOngoing,
         groupSummary = NotificationCompat.isGroupSummary(sbn.notification),
         localOnly = NotificationCompat.getLocalOnly(sbn.notification),
         media = sbn.notification.extras.containsKey(NotificationCompat.EXTRA_MEDIA_SESSION),
         id = sbn.id,
         tag = sbn.tag,
      )
   ).preferences

private fun ParsedNotification.containsRegex(regex: Regex): Boolean {
   return regex.containsMatchIn(title) || regex.containsMatchIn(subtitle) || regex.containsMatchIn(body)
}

private suspend fun <T> Flow<Outcome<T>>.firstSuccessOrThrow(): T {
   val result = first {
      it is Outcome.Success || it is Outcome.Error
   }

   return when (result) {
      is Outcome.Success -> result.data
      is Outcome.Error -> throw result.exception
      is Outcome.Progress -> error("Result should never be progress")
   }
}
