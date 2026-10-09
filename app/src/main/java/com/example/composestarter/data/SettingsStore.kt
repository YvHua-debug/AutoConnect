package com.example.composestarter.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 轻量设置存储。进程内共享，落盘到 SharedPreferences。
 */
object SettingsStore {

    private const val PREFS_NAME = "monitor_settings"
    private const val KEY_AUTO_START_VPN = "auto_start_vpn"
    /** v1.0 用的旧键名，只读一次做迁移，免得升级后把用户关掉的开关又打开。 */
    private const val KEY_AUTO_START_VPN_LEGACY = "auto_start_clash"
    private const val KEY_AUTO_STOP_VPN = "auto_stop_vpn"
    private const val KEY_AUTO_STOP_DELAY_SECONDS = "auto_stop_delay_seconds"
    const val DEFAULT_AUTO_STOP_DELAY_SECONDS = 8
    const val MAX_AUTO_STOP_DELAY_SECONDS = 86_400
    private const val KEY_VPN_ENGINE = "vpn_engine"
    private const val KEY_SERVICE_ENABLED = "service_enabled"
    private const val KEY_CUSTOM_APPS = "custom_apps"

    /** 自定义名单的序列化分隔符：一行一条 `包名|显示名`（包名里不可能出现 `|`）。 */
    private const val RECORD_SEPARATOR = "\n"
    private const val FIELD_SEPARATOR = '|'

    private var prefs: SharedPreferences? = null

    private val _autoStartVpn = MutableStateFlow(true)
    private val _autoStopVpn = MutableStateFlow(true)
    private val _autoStopDelaySeconds = MutableStateFlow(DEFAULT_AUTO_STOP_DELAY_SECONDS)
    private val _vpnEngine = MutableStateFlow("")
    private val _serviceEnabled = MutableStateFlow(true)
    private val _customApps = MutableStateFlow<List<VpnRequiredApp>>(emptyList())

    /** 命中名单时是否自动拉起 VPN。 */
    val autoStartVpn: StateFlow<Boolean> = _autoStartVpn.asStateFlow()

    /** 离开名单应用后是否自动断开 VPN。 */
    val autoStopVpn: StateFlow<Boolean> = _autoStopVpn.asStateFlow()
    val autoStopDelaySeconds: StateFlow<Int> = _autoStopDelaySeconds.asStateFlow()

    /** 用户选择的 VPN 客户端 id；空串表示「自动」（取第一个已安装的）。 */
    val vpnEngine: StateFlow<String> = _vpnEngine.asStateFlow()

    /** 用户是否希望监控服务常驻。为 false 时看门狗不会把服务拉回来。 */
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    /** 用户自行加进监控名单的应用。 */
    val customApps: StateFlow<List<VpnRequiredApp>> = _customApps.asStateFlow()

    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _autoStartVpn.value = store.getBoolean(
            KEY_AUTO_START_VPN,
            store.getBoolean(KEY_AUTO_START_VPN_LEGACY, true),
        )
        _autoStopVpn.value = store.getBoolean(KEY_AUTO_STOP_VPN, true)
        _autoStopDelaySeconds.value = store.getInt(KEY_AUTO_STOP_DELAY_SECONDS, DEFAULT_AUTO_STOP_DELAY_SECONDS)
            .coerceIn(0, MAX_AUTO_STOP_DELAY_SECONDS)
        _vpnEngine.value = store.getString(KEY_VPN_ENGINE, "").orEmpty()
        _serviceEnabled.value = store.getBoolean(KEY_SERVICE_ENABLED, true)
        _customApps.value = decodeCustomApps(store.getString(KEY_CUSTOM_APPS, null))
    }

    fun setAutoStartVpn(enabled: Boolean) {
        _autoStartVpn.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_START_VPN, enabled)?.apply()
    }

    fun setAutoStopVpn(enabled: Boolean) {
        _autoStopVpn.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_STOP_VPN, enabled)?.apply()
    }

    fun setAutoStopDelaySeconds(seconds: Int) {
        require(seconds in 0..MAX_AUTO_STOP_DELAY_SECONDS)
        _autoStopDelaySeconds.value = seconds
        prefs?.edit()?.putInt(KEY_AUTO_STOP_DELAY_SECONDS, seconds)?.apply()
    }

    fun setVpnEngine(id: String) {
        val normalized = VpnEngine.byId(id)?.id.orEmpty()
        _vpnEngine.value = normalized
        prefs?.edit()?.putString(KEY_VPN_ENGINE, normalized)?.apply()
    }

    fun setServiceEnabled(enabled: Boolean) {
        _serviceEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SERVICE_ENABLED, enabled)?.apply()
    }

    /** 把某个已安装应用加进监控名单。内置条目与重复项都会被忽略。 */
    fun addCustomApp(packageName: String, displayName: String) {
        if (packageName.isBlank()) return
        if (VpnAppCatalog.isBuiltIn(packageName)) return
        if (_customApps.value.any { it.packageName == packageName }) return
        val app = VpnRequiredApp(
            packageName = packageName,
            displayName = displayName.ifBlank { packageName },
            category = VpnAppCatalog.CATEGORY_CUSTOM,
        )
        persistCustomApps(_customApps.value + app)
    }

    fun removeCustomApp(packageName: String) {
        persistCustomApps(_customApps.value.filterNot { it.packageName == packageName })
    }

    private fun persistCustomApps(apps: List<VpnRequiredApp>) {
        _customApps.value = apps
        prefs?.edit()?.putString(KEY_CUSTOM_APPS, encodeCustomApps(apps))?.apply()
    }

    private fun encodeCustomApps(apps: List<VpnRequiredApp>): String = apps.joinToString(RECORD_SEPARATOR) {
        "${it.packageName}$FIELD_SEPARATOR${it.displayName}"
    }

    private fun decodeCustomApps(raw: String?): List<VpnRequiredApp> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD_SEPARATOR).mapNotNull { line ->
            val separator = line.indexOf(FIELD_SEPARATOR)
            if (separator <= 0) return@mapNotNull null
            val packageName = line.substring(0, separator)
            val displayName = line.substring(separator + 1).ifBlank { packageName }
            VpnRequiredApp(packageName, displayName, VpnAppCatalog.CATEGORY_CUSTOM)
        }.distinctBy { it.packageName }
    }
}
