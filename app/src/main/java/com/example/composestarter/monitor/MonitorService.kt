package com.example.composestarter.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
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
import com.example.composestarter.data.VpnEngine
import com.example.composestarter.data.VpnRequiredApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
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
    private val keyguardManager: KeyguardManager? by lazy {
        getSystemService(KeyguardManager::class.java)
    }
    private var controlsAvailable = true

    /** 轮询循环。持有一份引用，万一它异常退出还能重新拉起来。 */
    private var monitorJob: Job? = null

    private lateinit var detector: ForegroundAppDetector
    private lateinit var appRepository: AppRepository
    private lateinit var vpnController: VpnController

    private var lastPackage: String? = null
    private var lastHitAt = 0L

    /**
     * 「把 VPN 连上」这件事的进行时状态，由轮询协程推进（见 [updateConnectTask]）。
     * 只在这一个协程里读写，所以不需要加锁。
     */
    private var connectTask: ConnectTask? = null

    /**
     * 「把 VPN 断开」这件事的进行时状态（见 [updateAutoStop]）。
     * 和 [connectTask] 一样只在轮询协程里读写。
     */
    private var stopTask: StopTask? = null

    /** 当前生效的 VPN 客户端，以及它是不是刚从设置里改过。 */
    private var engine: VpnEngine? = null
    private var engineStateKey: String? = null
    private var engineCheckedAt = 0L

    /**
     * 自动断开的两段状态：
     * [listedArmed] = 这一次会话里出现过名单应用（没出现过就绝不去动用户的 VPN）；
     * [leftListedAt] = 刚离开名单应用的时刻，0 表示「此刻就在名单应用里」。
     */
    private var listedArmed = false
    private var leftListedAt = 0L

    /** 最近一个处于前台的名单应用，用来在断开之后交代「是哪个应用用完了」。 */
    private var lastListedPackage: String? = null
    private var lastListedName: String? = null

    /** 最近一次读到的 VPN 状态与读取时间，避免每轮都去问系统。 */
    private var lastVpnActive = false
    private var vpnCheckedAt = 0L
    private var lastVpnOwner: VpnEngine? = null

    /** 上次统计「已安装应用」时的自定义名单，名单没变就不用重新查 PackageManager。 */
    private var installedPackagesKey: List<VpnRequiredApp>? = null

    /** 最近一次推送到通知栏的文案，内容没变就不重复推送。 */
    private var lastNotificationText: String? = null

    /** 心跳日志节流用。 */
    private var lastHeartbeatAt = 0L
    private var heartbeatCount = 0L

    /** 上一轮轮询的时间，用来统计「被系统冻结」造成的空档。 */
    private var previousPollAt = 0L

    private val pollWakeups = Channel<Unit>(Channel.CONFLATED)
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            pollWakeups.trySend(Unit)
        }
    }
    private val sessionPrefs by lazy { getSharedPreferences("vpn_session", Context.MODE_PRIVATE) }

    private fun disarmSession() {
        listedArmed = false
        leftListedAt = 0L
        if (sessionPrefs.contains("package")) sessionPrefs.edit().clear().commit()
    }

    private fun rememberSession(entry: VpnRequiredApp) {
        listedArmed = true
        lastListedPackage = entry.packageName
        lastListedName = entry.displayName
        if (sessionPrefs.getString("package", null) != entry.packageName) {
            // 只在名单应用切换时同步落盘，避免进程被杀后遗失待断开的会话。
            sessionPrefs.edit().putString("package", entry.packageName)
                .putString("name", entry.displayName).commit()
        }
    }

    override fun onCreate() {
        super.onCreate()
        SettingsStore.attach(applicationContext)
        detector = ForegroundAppDetector(this)
        appRepository = AppRepository(this)
        vpnController = VpnController(this)
        lastListedPackage = sessionPrefs.getString("package", null)
        lastListedName = sessionPrefs.getString("name", null)
        listedArmed = lastListedPackage != null
        if (listedArmed) Log.i(TAG, "恢复待断开会话：$lastListedPackage")
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

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
        unregisterReceiver(screenReceiver)
        scope.cancel()
        MonitorRepository.setServiceRunning(false)
        super.onDestroy()
    }

    private fun startMonitoring() {
        if (monitorJob?.isActive == true) return

        monitorJob = scope.launch(CoroutineExceptionHandler { _, error ->
            Log.e(TAG, "轮询循环异常退出，等待下一次启动请求自愈", error)
        }) {
            // 这几件事都要走 PackageManager，放到后台线程做，别拖慢服务启动。
            refreshInstalledPackages()
            refreshEngines(now = 0L)

            while (isActive) {
                val now = System.currentTimeMillis()
                val packageName = detector.currentForegroundPackage()
                val interactive = powerManager?.isInteractive ?: true
                controlsAvailable = interactive && keyguardManager?.isKeyguardLocked != true
                val entry = if (controlsAvailable) packageName?.let { VpnAppCatalog.find(it) } else null

                val pollGap = if (previousPollAt == 0L) 0L else now - previousPollAt
                previousPollAt = now
                MonitorRepository.onPoll(now, pollGap)
                logHeartbeat(now, packageName, interactive)

                // 用户刚在界面里加了 / 删了自定义应用时，重新确认一次哪些已安装
                refreshInstalledPackages()
                // 用户可能在界面里换了 VPN 客户端，或刚好装了新的，跟着确认一次
                refreshEngines(now)

                // VPN 状态是「有没有连上」的唯一依据，所以名单应用在前台、或正在补连时
                // 都要拿实时值，不能走那 5 秒的缓存。
                val vpnActive = currentVpnState(
                    now,
                    force = entry != null || hasPendingConnect() || hasPendingStop(),
                )

                if (packageName != null) {
                    handleForegroundChange(packageName, entry, vpnActive, now)
                } else if (lastPackage != null) {
                    lastPackage = null
                    MonitorRepository.onForegroundChanged(null, null)
                }
                updateConnectTask(entry, vpnActive, now)
                updateAutoStop(entry, vpnActive, now)

                // 名单应用在前台时只需要关心「什么时候离开」，间隔可以放宽；
                // 空闲时用短间隔，保证用户一打开名单应用就能很快被发现。
                // 正在补连 VPN 时也用短间隔：这时候正等着「隧道起来了没有」。
                //
                // 息屏时把间隔拉长到 30 秒，而不是完全停下：完全依赖 SCREEN_ON 广播恢复，
                // 在部分 ROM（实测小米 HyperOS 3 / Android 16 不投递该广播到后台应用）上，
                // 进程若恰好是在息屏时被系统拉起，就会永久卡住不再轮询。
                val interval = when {
                    !controlsAvailable -> POLL_INTERVAL_SCREEN_OFF_MS
                    isConnecting() || hasPendingStop() || leftListedAt != 0L -> POLL_INTERVAL_MS
                    entry != null -> POLL_INTERVAL_LISTED_MS
                    else -> POLL_INTERVAL_MS
                }
                withTimeoutOrNull(interval) { pollWakeups.receive() }
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
     * 解析「现在该联动哪个 VPN 客户端」。
     *
     * 选中项来自设置，客户端装没装要走 PackageManager，所以按 [ENGINE_RECHECK_INTERVAL_MS]
     * 降频；用户改了选择则立刻重查。解出来的结果同时用于启动和停止，方向不会打架。
     */
    private fun refreshEngines(now: Long) {
        val selectedId = SettingsStore.vpnEngine.value
        if (now == 0L || selectedId != engineStateKey || now - engineCheckedAt >= ENGINE_RECHECK_INTERVAL_MS) {
            engineStateKey = selectedId
            engineCheckedAt = now
            val installed = vpnController.installedEngines()
            engine = VpnEngine.effective(selectedId, installed)
            MonitorRepository.setEngines(installed, engine)
        }
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
            // 归属决定自动断开时该给谁发停止指令，换了一条隧道就要重新认一次
            lastVpnOwner = if (active) vpnController.activeVpnEngine() else null
            if (active) Log.i(TAG, "当前隧道归属：${lastVpnOwner?.displayName ?: "未知"}")
        }
        // 隧道刚起来的那一瞬间，系统可能还没把「谁建的隧道」填进去（activeNetwork 短暂地仍是底层网络），
        // 所以认不出来就每轮补认一次，认到为止——否则自动断开只能退回用户选中的客户端，
        // 用户手动开的另一个客户端就收不掉了。
        if (active && lastVpnOwner == null) {
            lastVpnOwner = vpnController.activeVpnEngine()
            if (lastVpnOwner != null) Log.i(TAG, "当前隧道归属（补认）：${lastVpnOwner?.displayName}")
        }
        lastVpnActive = active
        MonitorRepository.setVpnActive(lastVpnActive)
        MonitorRepository.setEngineOwner(if (active) lastVpnOwner else null)
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
        // 离开名单应用时（updateAutoStop）要交代「是哪个应用用完了」，所以这里先记下来
        lastListedPackage = packageName
        lastListedName = entry.displayName
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
        if (!SettingsStore.autoStartVpn.value) {
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
            // 历史只保留最近 50 条，失败重试时不无限累积已经淘汰的记录 id。
            if (existing.hitIds.size > MonitorRepository.MAX_HITS) {
                existing.hitIds.subList(0, existing.hitIds.size - MonitorRepository.MAX_HITS).clear()
            }
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
     * 「已拉起客户端 · VPN 未连接」上不动，而且再也出不来。
     *
     * 1. `startActivity` 不抛异常只说明指令递出去了，系统完全可能把它**静默拦掉**。
     *    真机实测（HyperOS 3 / Android 16）：小米的「后台弹出界面」没允许时日志是
     *    `Abort background activity starts from <uid>`，客户端根本没被拉起，而应用这边
     *    `startActivity` 正常返回。于是「已拉起客户端」是假结论，只发一次，
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
        if (engine?.supportsAutoControl == false) {
            connectTask?.let { applyConnectResult(it, established = false, notice = null) }
            connectTask = null
            return
        }
        // 控制入口是 Activity，锁屏时保留补连任务，解锁后再试。
        if (!controlsAvailable && !vpnActive) return
        val task = connectTask ?: run {
            // 手上没有任务时也要盯一眼：用户可能就停在这个应用里没动过，
            // 而 VPN 是被系统或别的 VPN 应用顶掉的——「只在切前台时才判断」永远等不到
            // 下一次机会，以前这种情况会一直停在「未连接」。
            // 这种补连没有对应的「命中记录」，所以任务不带 hitIds，只推提醒。
            if (vpnActive || entry == null) return
            if (!SettingsStore.autoStartVpn.value) return
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

        // 没有指望连上的情况：联动被关掉、或客户端不在
        if (!SettingsStore.autoStartVpn.value) {
            connectTask = null
            applyConnectResult(task, established = false, notice = null)
            return
        }
        val currentEngine = engine
        if (currentEngine == null || !vpnController.isInstalled(currentEngine)) {
            connectTask = null
            applyConnectResult(task, established = false, notice = null)
            return
        }

        // 还没连上：按节奏继续补发启动指令
        if (task.attempts > 0 && now - task.lastAttemptAt < retryInterval(task)) return

        task.attempts++
        task.lastAttemptAt = now
        val sent = vpnController.start(currentEngine)
        Log.i(
            TAG,
            "第 ${task.attempts} 次拉起 ${currentEngine.displayName}（${task.displayName}）" +
                "sent=$sent，等隧道起来",
        )

        if (task.attempts == 1) {
            for (hitId in task.hitIds) {
                MonitorRepository.updateHit(hitId) {
                    it.copy(vpnTriggered = true, vpnConnecting = true)
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
     * 成功以**回读到的 VPN 状态**为准；失败时顺手判断一下是不是系统把「后台拉起客户端」
     * 拦掉了（[isBackgroundStartBlocked]），界面和通知据此给出「去开启后台弹出界面」这种
     * 能直接照着做的提示，而不是干巴巴一句「未连接」。
     */
    private fun applyConnectResult(task: ConnectTask, established: Boolean, notice: HitNotice?) {
        val blocked = !established && isBackgroundStartBlocked()
        for (hitId in task.hitIds) {
            MonitorRepository.updateHit(hitId) {
                it.copy(
                    vpnTriggered = task.attempts > 0,
                    vpnConnecting = false,
                    vpnEstablished = established,
                    vpnBlocked = blocked,
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

    private fun hasPendingStop(): Boolean = stopTask != null

    /** 是否处于「正在快速补连」的阶段：这期间轮询间隔保持短的，好及时看到隧道起来。 */
    private fun isConnecting(): Boolean {
        val task = connectTask
        return task != null && task.attempts < CONNECT_FAST_ATTEMPTS && task.leftForegroundAt == 0L
    }

    /**
     * 「名单应用退到后台 → 把 VPN 断开」——每轮轮询都跑一遍。
     *
     * 只对「刚刚离开的名单应用」动手，而且分两步：
     *
     * 1. 按用户设置等待，这期间用户切回任何一个名单应用都会取消。
     *    用户从 ChatGPT 点开一个链接、或者只是下拉看一眼通知，都不该把隧道拆掉；
     * 2. 到点后按 [STOP_RETRY_INTERVAL_MS] 发停止指令，直到**回读到的 VPN 状态**真的变成未连接为止。
     *
     * 第 2 步是这次重做的关键：以前那版是「发一条停止指令就认为断开了」，而各客户端的停止入口
     * 都只是「递一条指令」——被系统拦掉、进程状态不对时都会静默失效，于是界面上写着已断开、隧道
     * 其实还挂着。现在一律以回读到的状态收尾，断不掉就如实报失败（见 README「已知限制」）。
     */
    private fun updateAutoStop(entry: VpnRequiredApp?, vpnActive: Boolean, now: Long) {
        if (!SettingsStore.autoStopVpn.value || engine?.supportsAutoControl == false ||
            lastVpnOwner?.supportsAutoControl == false) {
            stopTask = null
            disarmSession()
            return
        }
        // 锁屏会挡住客户端的透明 Activity；不能在此时消耗重试次数或轮流拉起其它客户端。
        if (!controlsAvailable) return
        val task = stopTask

        // 1) 正在断开：以回读到的真实状态推进
        if (task != null) {
            if (entry != null) {
                // 用户又回到名单应用里了：隧道留着，别断
                stopTask = null
                Log.i(TAG, "用户回到 ${entry.displayName}，取消停止 VPN")
                return
            }
            if (!vpnActive) {
                stopTask = null
                finishStop(task, stopped = true, notice = HitNotice.AutoStopped)
                return
            }
            if (now - task.lastAttemptAt < STOP_RETRY_INTERVAL_MS) return
            if (task.attempts >= STOP_MAX_ATTEMPTS) {
                // 这个客户端停不掉：还有别的客户端在跑就换一个再试，全试完才报失败
                if (task.advance()) {
                    Log.i(
                        TAG,
                        "${task.engines[task.engineIndex - 1].displayName} 没停掉，改试 ${task.engine.displayName}",
                    )
                } else {
                    stopTask = null
                    finishStop(task, stopped = false, notice = HitNotice.StopFailed)
                    return
                }
            }
            attemptStop(task, now)
            return
        }

        // 2) 没在断开：判断这一次该不该开始
        if (entry != null) {
            rememberSession(entry)
            leftListedAt = 0L
            return
        }
        // 从来没进过名单应用（刚开机、或者用户压根没开过）：不去动用户的 VPN
        if (!listedArmed) return
        if (!vpnActive) {
            // 反正已经没有 VPN 了，收工
            disarmSession()
            return
        }
        if (leftListedAt == 0L) {
            leftListedAt = now
            Log.i(TAG, "已离开名单应用，${SettingsStore.autoStopDelaySeconds.value} 秒后自动断开 VPN")
        }
        // 等待中修改设置也立即生效，0 秒不额外等待一轮。
        if (now - leftListedAt < SettingsStore.autoStopDelaySeconds.value.toLong() * 1_000L) return

        // 先停「真正在建隧道的那一个」：用户手动开的另一个客户端也能被正确收掉。
        // 但隧道归属不一定读得到（实测 HyperOS 3 上 ownerUid 一律是 -1，见 VpnController），
        // 所以还要留后手：这个客户端停不掉就依次换其它已安装的客户端再试，全试完隧道还在才报失败。
        // 多试一个客户端，总比停在「断开失败」上强。
        val preferred = vpnController.activeVpnEngine() ?: engine ?: return
        val candidates = VpnEngine.stopCandidates(preferred, vpnController.installedEngines())
        if (candidates.isEmpty()) {
            disarmSession()
            return
        }
        val started = StopTask(candidates, lastListedPackage, lastListedName).also { stopTask = it }
        if (candidates.size > 1) {
            Log.i(TAG, "停止顺序：${candidates.joinToString(" → ") { it.displayName }}")
        }
        attemptStop(started, now)
    }

    /** 发一次停止指令。 */
    private fun attemptStop(task: StopTask, now: Long) {
        task.attempts++
        task.lastAttemptAt = now
        val sent = vpnController.stop(task.engine)
        Log.i(TAG, "第 ${task.attempts} 次停止 ${task.engine.displayName} sent=$sent，等隧道断开")
    }

    /** 收尾：把结果写回命中记录、推一条提醒。 */
    private fun finishStop(task: StopTask, stopped: Boolean, notice: HitNotice?) {
        task.appPackage?.let { MonitorRepository.markLatestHitStopped(it, stopped) }
        disarmSession()
        // 失败也收手：下次进入名单应用才重新武装，避免不断轮流拉起控制页面。
        Log.i(
            TAG,
            "停止结果：stopped=$stopped 试到第 ${task.engineIndex + 1}/${task.engines.size} 个客户端" +
                "（${task.engine.displayName}）attempts=${task.attempts} app=${task.appName ?: "未知"}",
        )
        // 提醒里报「最该负责的那个」（隧道归属 / 用户选中的那一个），而不是最后试的那个
        val name = task.appName
        if (notice != null && name != null) notifyHit(name, notice, task.engines.first().displayName)
    }

    /**
     * 后台拉起 VPN 客户端会不会被系统直接拒绝。
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

    /**
     * 一次「把 VPN 断开」的尝试过程。
     *
     * [engines] 是依次尝试的客户端（隧道归属排最前，认不出归属就是用户选中的那个）：
     * 前一个停不掉就换下一个，全都试完隧道还在才报失败。
     * [appPackage] / [appName] 是用户刚离开的那个名单应用：结果要写回它的命中记录，
     * 通知里也要说清楚「是哪个应用用完了才断的」。
     */
    private class StopTask(
        val engines: List<VpnEngine>,
        val appPackage: String?,
        val appName: String?,
    ) {
        var engineIndex = 0
        var attempts = 0
        var lastAttemptAt = 0L

        val engine: VpnEngine get() = engines[engineIndex]

        /** 换下一个客户端重试（次数清零）；没有下一个了返回 false。 */
        fun advance(): Boolean {
            if (engineIndex >= engines.lastIndex) return false
            engineIndex++
            attempts = 0
            return true
        }
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

        /** 同上，而且看起来是系统把「后台拉起客户端」拦掉了。 */
        StartBlocked,

        /** 没开自动联动（用户关掉了开关），就是没有 VPN。 */
        NotConnected,

        /** 离开名单应用后，回读到 VPN 真的断开了。 */
        AutoStopped,

        /** 发了几次停止指令，回读到的 VPN 还是连着的。 */
        StopFailed,
    }

    private fun notifyHit(displayName: String, notice: HitNotice, engineName: String? = null) {
        val text = when (notice) {
            HitNotice.VpnConnected -> getString(R.string.notification_text_hit_with_vpn, displayName)
            HitNotice.AutoConnecting -> getString(R.string.notification_text_hit_auto_connecting, displayName)
            HitNotice.AutoConnected -> getString(R.string.notification_text_hit_auto_connected, displayName)
            HitNotice.ConnectFailed -> getString(R.string.notification_text_hit_connect_failed, displayName)
            HitNotice.StartBlocked -> getString(R.string.notification_text_hit_start_blocked, displayName)
            HitNotice.NotConnected -> getString(R.string.notification_text_hit_without_vpn, displayName)
            HitNotice.AutoStopped -> getString(R.string.notification_text_vpn_stopped, displayName)
            HitNotice.StopFailed -> getString(
                R.string.notification_text_vpn_stop_failed,
                displayName,
                engineName.orEmpty(),
            )
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
         * （约覆盖前 10 秒：客户端冷启动、系统忙、指令被吞掉都在这段时间里能救回来）；
         * 之后降到每分钟一次长期兜底——用户还停在名单应用里时不放弃，比如他刚去把
         * 「后台弹出界面」打开，下一分钟就能自己连上。
         */
        private const val CONNECT_RETRY_INTERVAL_MS = 2_000L
        private const val CONNECT_SLOW_RETRY_INTERVAL_MS = 60_000L
        private const val CONNECT_FAST_ATTEMPTS = 4

        /** 用户离开名单应用之后再观察多久，避免「刚切走隧道才起来」被误判成没连上。 */
        private const val CONNECT_LEAVE_GRACE_MS = 6_000L

        /**
         * 断开的补发节奏与上限：以回读到的状态收尾，没断就再发；一个客户端发满
         * [STOP_MAX_ATTEMPTS] 次还没断掉，就换下一个已安装的客户端继续试。
         */
        private const val STOP_RETRY_INTERVAL_MS = 2_000L
        private const val STOP_MAX_ATTEMPTS = 4

        /** VPN 客户端安装状态的复查间隔（用户可能刚装了 / 卸了一个）。 */
        private const val ENGINE_RECHECK_INTERVAL_MS = 60_000L

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
