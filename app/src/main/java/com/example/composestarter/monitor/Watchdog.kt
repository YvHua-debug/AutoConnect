package com.example.composestarter.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 服务看门狗：定时检查监控服务还在不在，被系统回收就拉起来。
 *
 * 国产 ROM（小米 / 华为 / OPPO…）在没有加入白名单时会随手冻结甚至杀掉后台进程，
 * 只靠 `START_STICKY` 不一定能复活，所以额外加一层闹钟兜底。
 *
 * 用 `setAndAllowWhileIdle` 而不是精确闹钟：不需要 SCHEDULE_EXACT_ALARM 权限，
 * 也不会被系统的省电策略拒掉。
 */
object Watchdog {

    private const val REQUEST_CODE = 2001
    private const val RESTART_REQUEST_CODE = 2002
    private const val INTERVAL_MS = 15 * 60 * 1000L

    /**
     * 「任务被划掉后尽快拉回来」用的延迟。
     *
     * 不精确闹钟的投递窗口是按「距离触发还有多久」算的，1 秒的闹钟几秒内就会到，
     * 因此不需要 SCHEDULE_EXACT_ALARM 这个特殊权限也能做到接近立刻。
     */
    private const val RESTART_SOON_MS = 1_000L

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + INTERVAL_MS,
                pendingIntent(context),
            )
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context)) }
    }

    /**
     * 用户在最近任务里把卡片划掉时调用。
     *
     * 小米这类 ROM 会连进程一起杀掉（`am_kill ... SwipeUpClean`），服务收不到 `onDestroy`，
     * `START_STICKY` 实测也不会重拉。所以趁进程还在，先把一个马上到点的闹钟排上，
     * 让看门狗尽快把服务接回来；否则只能等下一次例行检查（最长 15 分钟），
     * 这段时间里打开名单应用不会有任何反应。
     */
    fun scheduleRestartSoon(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + RESTART_SOON_MS,
                restartPendingIntent(context),
            )
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun restartPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        RESTART_REQUEST_CODE,
        Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
