package com.example.composestarter.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.composestarter.MainActivity
import com.example.composestarter.R
import com.example.composestarter.data.AppRepository
import com.example.composestarter.data.SettingsStore
import com.example.composestarter.data.VpnAppCatalog
import com.example.composestarter.data.VpnRequiredApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：轮询前台应用，命中名单时记录并推送通知。
 *
 * 前台服务的意义是让进程不被系统回收，从而做到「一直在线」。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val notificationManager: NotificationManager? by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val powerManager: PowerManager? by lazy {
        getSystemService(PowerManager::class.java)
    }

    /** 轮询循环。持有一份引用，万一它异常退出还能重新拉起来。 */
    private var monitorJob: Job? = null

    private lateinit var detector: ForegroundAppDetector
    private lateinit var appRepository: AppRepository
    private lateinit var clashController: ClashController

    private var lastPackage: String? = null
    private var lastHitAt = 0L

    /**
     * 「把 VPN 连上」这件事的进行时状态，由轮询协程推进（见 [updateConnectTask]）。
     * 只在这一个协程里读写，所以不需要加锁。
     */
    private var connectTask: ConnectTask? = null

    /** 最近一次读到的 VPN 状态与读取时间，避免每轮都去问系统。 */
    private var lastVpnActive = false
    private var vpnCheckedAt = 0L

    /** 上次统计「已安装应用」时的自定义名单，名单没变就不用重新查 PackageManager。 */
    private var installedPackagesKey: List<VpnRequiredApp>? = null

    /** 最近一次推送到通知栏的文案，内容没变就不重复推送。 */
    private var lastNotificationText: String? = null

    /** 心跳日志节流用。 */
    private var lastHeartbeatAt = 0L
    private var heartbeatCount = 0L

    /** 上一轮轮询的时间，用来统计「被系统冻结」造成的空档。 */
    private var previousPollAt = 0L

    override fun onCreate() {
        super.onCreate()
        SettingsStore.attach(applicationContext)
        detector = ForegroundAppDetector(this)
        appRepository = AppRepository(this)
        clashController = ClashController(this)

        createNotificationChannels()
        refreshStatusNotification()

        Watchdog.schedule(this)
        Log.d(TAG, "监控服务已启动 pid=${Process.myPid()}")
        MonitorRepository.setServiceRunning(true)
        startMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 轮询循环要是意外退出了，任何一次启动请求都顺手把它拉回来
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户在最近任务里把卡片划掉。
     *
     * 国产 ROM（实测小米 HyperOS 3）在这个时机会直接杀进程（日志 `am_kill … SwipeUpClean`），
     * `onDestroy` 不会执行，`START_STICKY` 也不会把服务拉回来。所以趁现在把「马上重开」的
     * 闹钟排上，让看门狗一两秒后把监控接回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "任务被划掉，安排尽快重启监控服务")
        Watchdog.scheduleRestartSoon(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        MonitorRepository.setServiceRunning(false)
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch(CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "轮询循环异常退出，等待下一次启动请求自愈", error)
        }) {
            // 这两件事都要走 PackageManager，放到后台线程做，别拖慢服务启动。
            refreshInstalledPackages()
            MonitorRepository.setClashInstalled(clashController.isInstalled())

            while (isActive) {
                val now = System.currentTimeMillis()
                val packageName = detector.currentForegroundPackage()
                val entry = packageName?.let { VpnAppCatalog.find(it) }
                val interactive = powerManager?.isInteractive ?: true

                val pollGap = if (previousPollAt == 0L) 0L else now - previousPollAt
                previousPollAt = now
                MonitorRepository.onPoll(now, pollGap)
                logHeartbeat(now, packageName, interactive)

                // 用户刚在界面里加了 / 删了自定义应用时，重新确认一次哪些已安装
                refreshInstalledPackages()

                // VPN 状态是「有没有连上」的唯一依据，所以名单应用在前台、或正在补连时
                // 都要拿实时值，不能走那 5 秒的缓存。
                val vpnActive = currentVpnState(now, force = entry != null || hasPendingConnect())

                if (packageName != null) {
                    handleForegroundChange(packageName, entry, vpnActive, now)
                }
                updateConnectTask(entry, vpnActive, now)

                // 名单应用在前台时只需要关心「什么时候离开」，间隔可以放宽；
                // 空闲时用短间隔，保证用户一打开名单应用就能很快被发现。
                // 正在补连 VPN 时也用短间隔：这时候正等着「隧道起来了没有」。
                //
                // 息屏时把间隔拉长到 30 秒，而不是完全停下：完全依赖 SCREEN_ON 广播恢复，
                // 在部分 ROM（实测小米 HyperOS 3 / Android 16 不投递该广播到后台应用）上，
                // 进程若恰好是在息屏时被系统拉起，就会永久卡住不再轮询。
                delay(
                    when {
                        !interactive -> POLL_INTERVAL_SCREEN_OFF_MS
                        isConnecting() -> POLL_INTERVAL_MS
                        entry != null -> POLL_INTERVAL_LISTED_MS
                        else -> POLL_INTERVAL_MS
                    },
                )
            }
        }
    }

    /**
     * 刷新「名单里哪些应用已安装」。
     *
     * 内置名单是死的，只有用户自定义那部分会变，所以名单没变就直接返回，
     * 不会每轮轮询都去问一遍 PackageManager。
     */
    private fun refreshInstalledPackages() {
        val custom = SettingsStore.customApps.value
        if (custom == installedPackagesKey) return
        installedPackagesKey = custom
        MonitorRepository.setInstalledPackages(
            appRepository.installedAmong(VpnAppCatalog.all(custom).map { it.packageName }),
        )
    }

    /**
     * 读取 VPN 状态。系统查询有成本，因此按 [VPN_CHECK_INTERVAL_MS] 降频；
     * 需要立即取值时（[force]）才实时查询。
     */
    private fun currentVpnState(now: Long, force: Boolean = false): Boolean {
        if (!force && now - vpnCheckedAt < VPN_CHECK_INTERVAL_MS) {
            return lastVpnActive
        }
        vpnCheckedAt = now
        val active = detector.isVpnActive()
        if (active != lastVpnActive) {
            Log.d(TAG, "VPN 状态变化：$lastVpnActive -> $active")
        }
        lastVpnActive = active
        MonitorRepository.setVpnActive(lastVpnActive)
        return lastVpnActive
    }

    /** 心跳日志（默认每分钟一条），用来在 logcat 里确认后台轮询有没有被系统掐掉。 */
    private fun logHeartbeat(now: Long, packageName: String?, interactive: Boolean) {
        if (now - lastHeartbeatAt < HEARTBEAT_INTERVAL_MS) return
        lastHeartbeatAt = now
        heartbeatCount++
        Log.d(TAG, "心跳 #$heartbeatCount 前台=${packageName ?: "未知"} 屏幕=${if (interactive) "亮" else "灭"}")
    }

    /**
     * 前台应用发生变化。
     *
     * 命中名单时这里只负责「记一笔」，真正的启停动作交给 [updateConnectTask]：
     * 以**回读到的真实 VPN 状态**为准，连不上就继续补发指令，
     * 而不是「指令发出去了就算成功」。
     */
    private fun handleForegroundChange(
        packageName: String,
        entry: VpnRequiredApp?,
        vpnActive: Boolean,
        now: Long,
    ) {
        if (packageName == lastPackage) return
        lastPackage = packageName

        // 应用名只在前台应用真的变化时才查，并且带缓存
        MonitorRepository.onForegroundChanged(packageName, appRepository.labelOf(packageName))

        if (entry == null) return
        if (now - lastHitAt < MIN_HIT_INTERVAL_MS) return
        lastHitAt = now

        val hitId = MonitorRepository.nextHitId()
        MonitorRepository.addHit(
            DetectionHit(
                id = hitId,
                packageName = packageName,
                displayName = entry.displayName,
                timestamp = now,
                vpnActive = vpnActive,
            ),
        )
        Log.i(TAG, "命中 ${entry.displayName}（$packageName）vpn=$vpnActive")

        if (vpnActive) {
            notifyHit(entry.displayName, HitNotice.VpnConnected)
            return
        }
        if (!SettingsStore.autoStartClash.value) {
            notifyHit(entry.displayName, HitNotice.NotConnected)
            return
        }
        // 没有 VPN：挂一个「补连」任务，由它按真实结果推进这条命中记录
        attachConnectTask(entry, hitId)
    }

    /**
     * 给这条命中记录挂上「补连 VPN」任务。
     *
     * 已经有任务时就把这条记录并进去、共用同一次尝试的结果——用户连着切几个名单应用
     * 是很常见的，没必要每切一次就从头再试一遍；通知里的应用名跟着当前所在的应用走。
     */
    private fun attachConnectTask(entry: VpnRequiredApp, hitId: Long) {
        val existing = connectTask
        if (existing != null) {
            existing.hitIds += hitId
            existing.packageName = entry.packageName
            existing.displayName = entry.displayName
            existing.leftForegroundAt = 0L
        } else {
            connectTask = ConnectTask(entry.packageName, entry.displayName, hitId)
        }
    }

    /**
     * 「名单应用在前台，VPN 就该是连着的」——每轮轮询都跑一遍。
     *
     * 为什么不能只在命中名单时发一条指令了事：那样有三个洞，每个都会把用户留在
     * 「Clash 已拉起 · VPN 未连接」上不动，而且再也出不来。
     *
     * 1. `startActivity` 不抛异常只说明指令递出去了，系统完全可能把它**静默拦掉**。
     *    真机实测（HyperOS 3 / Android 16）：小米的「后台弹出界面」没允许时日志是
     *    `Abort background activity starts from <uid>`，Clash 根本没被拉起，而应用这边
     *    `startActivity` 正常返回。于是「已拉起 Clash」是假结论，只发一次，
     *    用户不切走就永远不会再试（实测：拦截状态下停在名单应用里 60 秒，一条指令都不会再发）。
     * 2. Clash 自己的 `START_CLASH` 在「内核已经在跑、只是隧道没建起来」时是**空操作**
     *    （源码：`if (isClashRunning()) 提示已启动 else startClash()`），连发才有机会连上。
     * 3. VPN 也可能在用户停在名单应用里的时候被系统或别的 VPN 应用顶掉，
     *    而「只在切前台时判断一次」永远等不到下一次机会。
     *
     * 所以这里每轮都拿真实状态推进：连上 → 记「已连接」；没连上 → 按节奏继续补发指令；
     * 用户离开名单应用 → 收尾。
     */
    private fun updateConnectTask(entry: VpnRequiredApp?, vpnActive: Boolean, now: Long) {
        val task = connectTask ?: run {
            // 手上没有任务时也要盯一眼：用户可能就停在这个应用里没动过，
            // 而 VPN 是被系统或别的 VPN 应用顶掉的——「只在切前台时才判断」永远等不到
            // 下一次机会，以前这种情况会一直停在「未连接」。
            // 这种补连没有对应的「命中记录」，所以任务不带 hitIds，只推提醒。
            if (vpnActive || entry == null) return
            if (!SettingsStore.autoStartClash.value) return
            ConnectTask(entry.packageName, entry.displayName).also { connectTask = it }
        }

        // 连上了：不管用户还在不在这个应用里都算成功，隧道是真的起来了
        if (vpnActive) {
            connectTask = null
            applyConnectResult(task, established = true, notice = HitNotice.AutoConnected)
            return
        }

        // 用户已经切走：不再补发，但再多看几秒，免得「刚切走隧道才起来」被误判成失败
        if (entry == null) {
            if (task.leftForegroundAt == 0L) {
                task.leftForegroundAt = now
            } else if (now - task.leftForegroundAt >= CONNECT_LEAVE_GRACE_MS) {
                connectTask = null
                applyConnectResult(task, established = false, notice = null)
            }
            return
        }
        task.leftForegroundAt = 0L

        // 没有指望连上的情况：联动被关掉、或 Clash 不在
        if (!SettingsStore.autoStartClash.value) {
            connectTask = null
            applyConnectResult(task, established = false, notice = null)
            return
        }
        if (!clashController.isInstalled()) {
            connectTask = null
            applyConnectResult(task, established = false, notice = null)
            return
        }

        // 还没连上：按节奏继续补发启动指令
        if (task.attempts > 0 && now - task.lastAttemptAt < retryInterval(task)) return

        task.attempts++
        task.lastAttemptAt = now
        val sent = clashController.start()
        Log.i(TAG, "第 ${task.attempts} 次拉起 Clash（${task.displayName}）sent=$sent，等隧道起来")

        if (task.attempts == 1) {
            for (hitId in task.hitIds) {
                MonitorRepository.updateHit(hitId) {
                    it.copy(clashTriggered = true, clashConnecting = true)
                }
            }
            notifyHit(task.displayName, HitNotice.AutoConnecting)
        }
        // 快速重试阶段用完还没连上：先如实提醒一次，之后降到慢速继续兜
        if (task.attempts >= CONNECT_FAST_ATTEMPTS && !task.warned) {
            task.warned = true
            Log.w(TAG, "${task.displayName} 试了 ${task.attempts} 次仍未连上 VPN，转为慢速重试")
            applyConnectResult(
                task,
                established = false,
                notice = if (isBackgroundStartBlocked()) HitNotice.StartBlocked else HitNotice.ConnectFailed,
            )
        }
    }

    /**
     * 把当前结果写回这条任务带的所有命中记录。
     *
     * 成功以**回读到的 VPN 状态**为准；失败时顺手判断一下是不是系统把「后台拉起 Clash」
     * 拦掉了（[isBackgroundStartBlocked]），界面和通知据此给出「去开启后台弹出界面」这种
     * 能直接照着做的提示，而不是干巴巴一句「未连接」。
     */
    private fun applyConnectResult(task: ConnectTask, established: Boolean, notice: HitNotice?) {
        val blocked = !established && isBackgroundStartBlocked()
        for (hitId in task.hitIds) {
            MonitorRepository.updateHit(hitId) {
                it.copy(
                    clashTriggered = task.attempts > 0,
                    clashConnecting = false,
                    clashVpnEstablished = established,
                    clashBlocked = blocked,
                )
            }
        }
        Log.i(
            TAG,
            "${task.displayName} 补连结果：established=$established attempts=${task.attempts} " +
                "blocked=$blocked hits=${task.hitIds.size}",
        )
        if (notice != null) notifyHit(task.displayName, notice)
    }

    /** 快速重试阶段用短间隔，用完就降到慢速兜底（用户还停在这个应用里时不放弃）。 */
    private fun retryInterval(task: ConnectTask): Long =
        if (task.attempts < CONNECT_FAST_ATTEMPTS) {
            CONNECT_RETRY_INTERVAL_MS
        } else {
            CONNECT_SLOW_RETRY_INTERVAL_MS
        }

    private fun hasPendingConnect(): Boolean = connectTask != null

    /** 是否处于「正在快速补连」的阶段：这期间轮询间隔保持短的，好及时看到隧道起来。 */
    private fun isConnecting(): Boolean {
        val task = connectTask
        return task != null && task.attempts < CONNECT_FAST_ATTEMPTS && task.leftForegroundAt == 0L
    }

    /**
     * 「从后台拉起 Clash」会不会被系统直接拒绝。
     *
     * 这两条都会让 `startActivity` 正常返回、而 Activity 根本不启动，应用侧拿不到任何回调：
     * - 没有「悬浮窗」权限：Android 10 起不允许后台启动 Activity；
     * - 小米的「后台弹出界面」没允许：真机实测日志是 `Abort background activity starts from <uid>`，
     *   置回 allow 之后同一条路径立刻恢复正常。
     */
    private fun isBackgroundStartBlocked(): Boolean =
        !MonitorPermissions.hasOverlayAccess(this) ||
            MonitorPermissions.miuiPermission(this, MonitorPermissions.MIUI_OP_BACKGROUND_POPUP) == false

    /**
     * 一次「把 VPN 连上」的尝试过程。
     *
     * [hitIds] 是这次尝试要交代结果的所有命中记录（用户连着切几个名单应用会合并到一起）；
     * 「VPN 半路被顶掉、用户没切过应用」这种补连没有命中记录，所以是空的。
     * [leftForegroundAt] 记用户离开名单应用的时刻（0 表示还在名单应用里）；
     * [warned] 表示快速阶段失败时已经提醒过用户，慢速兜底阶段不再重复推送。
     */
    private class ConnectTask(
        var packageName: String,
        var displayName: String,
        hitId: Long? = null,
    ) {
        val hitIds: MutableList<Long> = if (hitId != null) mutableListOf(hitId) else mutableListOf()
        var attempts = 0
        var lastAttemptAt = 0L
        var leftForegroundAt = 0L
        var warned = false
    }

    /** 命中名单时要推哪一条提醒。 */
    private enum class HitNotice {
        /** 打开时 VPN 本来就连着。 */
        VpnConnected,

        /** 已经发出启动指令，正在等隧道。 */
        AutoConnecting,

        /** 回读到 VPN 真的连上了。 */
        AutoConnected,

        /** 补发了几次指令还是没连上。 */
        ConnectFailed,

        /** 同上，而且看起来是系统把「后台拉起 Clash」拦掉了。 */
        StartBlocked,

        /** 没开自动联动（用户关掉了开关），就是没有 VPN。 */
        NotConnected,
    }

    private fun notifyHit(displayName: String, notice: HitNotice) {
        val text = when (notice) {
            HitNotice.VpnConnected -> getString(R.string.notification_text_hit_with_vpn, displayName)
            HitNotice.AutoConnecting -> getString(R.string.notification_text_hit_auto_connecting, displayName)
            HitNotice.AutoConnected -> getString(R.string.notification_text_hit_auto_connected, displayName)
            HitNotice.ConnectFailed -> getString(R.string.notification_text_hit_clash_failed, displayName)
            HitNotice.StartBlocked -> getString(R.string.notification_text_hit_clash_blocked, displayName)
            HitNotice.NotConnected -> getString(R.string.notification_text_hit_without_vpn, displayName)
        }
        notifyText(text)
    }

    /**
     * 推送一条一次性提醒（命中名单、补连 VPN 的结果）。
     *
     * 这些提醒用的是独立的通知 id 和独立的通道，所以「隐藏常驻通知」的开关不会把它们一起藏掉。
     */
    private fun notifyText(text: String) {
        // 文案没变就不重复推送，省掉一次 Binder 调用和通知栏刷新
        if (text == lastNotificationText) return
        lastNotificationText = text
        notificationManager?.notify(ALERT_NOTIFICATION_ID, buildAlertNotification(text))
    }

    /** 常驻通知（前台服务那条）：文案固定，只反映「正在监控」这件事。 */
    private fun refreshStatusNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildStatusNotification(),
            foregroundServiceType(),
        )
    }

    private fun buildStatusNotification(): Notification {
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_watching))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return notification
    }

    private fun buildAlertNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return contentIntent
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        // 常驻通知只挂一条通道。
        //
        // 曾经试过再准备一条 IMPORTANCE_NONE 的通道来做「隐藏」：应用把前台通知挂过去，
        // 系统不画出来。真机实测（HyperOS 3 / Android 16）这条路走不通——系统会在往
        // 被屏蔽的通道投递前台服务通知时把通道重要性抬回 LOW（`mOriginalImp=0` 但
        // `mImportance=2`），通知照样显示。所以「隐不隐藏」只能由用户在系统设置里
        // 关掉这条通道，应用侧只负责把状态读出来、把入口给出去。
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            },
        )
        // 老版本建过的那条隐藏通道没人用了，顺手清掉
        manager.deleteNotificationChannel(CHANNEL_STATUS_HIDDEN_LEGACY)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERT,
                getString(R.string.notification_channel_alert_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_alert_description)
                setShowBadge(false)
            },
        )
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        /** 常驻通知的通道。用户可以在系统设置里单独关掉它，「显示常驻通知」开关读的就是它的状态。 */
        const val CHANNEL_STATUS = "monitor_status_v1"

        /** 一次性提醒的通道，和常驻通知分开，不受前者的开关影响。 */
        private const val CHANNEL_ALERT = "monitor_alert_v1"

        /** 老版本用来「隐藏」常驻通知的通道，现在只做清理。 */
        private const val CHANNEL_STATUS_HIDDEN_LEGACY = "monitor_status_hidden_v1"

        /** 常驻通知的 id（前台服务必须持有一条），以及一次性提醒的 id。 */
        private const val NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val TAG = "MonitorService"

        /** 心跳日志间隔。 */
        private const val HEARTBEAT_INTERVAL_MS = 60_000L

        /** 空闲时的轮询间隔：决定「用户刚打开名单应用」多久被发现。 */
        private const val POLL_INTERVAL_MS = 2_000L

        /** 名单应用处于前台时的轮询间隔，只需要及时发现「已经离开」。 */
        private const val POLL_INTERVAL_LISTED_MS = 5_000L

        /** 息屏时的轮询间隔：只是拉长，不停止，保证亮屏后一定能自己恢复。 */
        private const val POLL_INTERVAL_SCREEN_OFF_MS = 30_000L

        /** VPN 状态的读取间隔。 */
        private const val VPN_CHECK_INTERVAL_MS = 5_000L

        private const val MIN_HIT_INTERVAL_MS = 3_000L

        /**
         * 「补连 VPN」的节奏。
         *
         * 快速阶段每 [CONNECT_RETRY_INTERVAL_MS] 补发一次启动指令，共 [CONNECT_FAST_ATTEMPTS] 次
         * （约覆盖前 10 秒：Clash 冷启动、系统忙、指令被吞掉都在这段时间里能救回来）；
         * 之后降到每分钟一次长期兜底——用户还停在名单应用里时不放弃，比如他刚去把
         * 「后台弹出界面」打开，下一分钟就能自己连上。
         */
        private const val CONNECT_RETRY_INTERVAL_MS = 2_000L
        private const val CONNECT_SLOW_RETRY_INTERVAL_MS = 60_000L
        private const val CONNECT_FAST_ATTEMPTS = 4

        /** 用户离开名单应用之后再观察多久，避免「刚切走隧道才起来」被误判成没连上。 */
        private const val CONNECT_LEAVE_GRACE_MS = 6_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MonitorService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
