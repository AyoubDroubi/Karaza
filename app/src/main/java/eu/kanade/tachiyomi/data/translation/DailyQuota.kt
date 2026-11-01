package eu.kanade.tachiyomi.data.translation

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import li.joye.yakuyomi.engine.LlmTranslator
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
    const val MARKER = LlmTranslator.DAILY_QUOTA_MESSAGE

    /** Monotonic clock (immune to wall-clock changes) used for [note] / [hitSince]. */
    fun now(): Long = SystemClock.elapsedRealtime()

    @Volatile
    private var lastHitAt = -1L

    @Volatile
    private var held = false

    fun isDailyQuota(reason: String?): Boolean = reason != null && reason.contains(MARKER)

    /** Record a page failure reason; marks the quota as hit when it is the daily-quota error. */
    fun note(reason: String?) {
        if (isDailyQuota(reason)) lastHitAt = now()
    }

    /** True when the daily quota was reported at or after [sinceMs] (a [now] value). */
    fun hitSince(sinceMs: Long): Boolean = lastHitAt >= 0 && lastHitAt >= sinceMs

    /** The queue was paused for the quota: automatic enqueues must not restart it. Tells the user why. */
    fun hold(context: Context) {
        held = true
        notifyPaused(context)
    }

    fun isHeld(): Boolean = held

    /** The user resumed the queue: lift the hold and clear the notification. */
    fun release(context: Context) {
        if (!held) return
        held = false
        runCatching { NotificationManagerCompat.from(context).cancel(Notifications.ID_TRANSLATION_DAILY_QUOTA) }
    }

    /** Tell the user why the queue stopped (best-effort: silently skipped without notification permission). */
    private fun notifyPaused(context: Context) {
        try {
            val text = context.stringResource(MR.strings.translation_daily_quota_text)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val notification = NotificationCompat.Builder(context, Notifications.CHANNEL_TRANSLATOR_PROGRESS)
                .setSmallIcon(R.drawable.ic_karaza)
                .setContentTitle(context.stringResource(MR.strings.translation_daily_quota_title))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(Notifications.ID_TRANSLATION_DAILY_QUOTA, notification)
        } catch (_: Throwable) {
            // The paused queue already shows the state; the notification is only a hint.
        }
    }
}
