package eu.kanade.tachiyomi.data.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Daily-quota signal from the LLM provider (free tiers have a per-day request cap).
 *
 * Once the day's quota is used up every further request fails immediately, so letting the queue keep going only
 * turns every remaining chapter red. The engine reports it with a fixed message ([MARKER]); page failures are fed
 * through [note], and the queue checks [hitSince] to stop the running chapter, put it back in the queue and pause
 * the translation pool instead of failing everything that follows.
 */
object DailyQuota {

    /** Prefix of the engine's error for a used-up daily quota (see the engine's LlmTranslator). */
    const val MARKER = "Daily quota for this model is used up"

    @Volatile
    private var lastHitAt = 0L

    fun isDailyQuota(reason: String?): Boolean = reason != null && reason.contains(MARKER)

    /** Record a page failure reason; marks the quota as hit when it is the daily-quota error. */
    fun note(reason: String?) {
        if (isDailyQuota(reason)) lastHitAt = System.currentTimeMillis()
    }

    /** True when the daily quota was reported at or after [sinceMs]. */
    fun hitSince(sinceMs: Long): Boolean = lastHitAt >= sinceMs && lastHitAt != 0L

    /** Tell the user why the queue stopped (best-effort: silently skipped without notification permission). */
    fun notifyPaused(context: Context) {
        try {
            val text = context.stringResource(MR.strings.translation_daily_quota_text)
            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_TRANSLATOR_PROGRESS)
                .setSmallIcon(R.drawable.ic_karaza)
                .setContentTitle(context.stringResource(MR.strings.translation_daily_quota_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(Notifications.ID_TRANSLATION_DAILY_QUOTA, notification)
        } catch (_: Throwable) {
            // The paused queue already shows the state; the notification is only a hint.
        }
    }
}
