package com.example.composestarter.data

/**
 * 需要借助 VPN / 代理才能在中国大陆正常使用的常见应用名单。
 *
 * 内置条目加在 [builtInApps] 里；用户自己添加的应用存在 [SettingsStore.customApps]，
 * 两者在 [all] / [find] / [grouped] 里合并，界面与检测逻辑共用同一份数据。
 */
object VpnAppCatalog {

    const val CATEGORY_AI = "AI 助手"
    const val CATEGORY_GOOGLE = "Google 服务"
    const val CATEGORY_SOCIAL = "社交与视频"
    const val CATEGORY_OTHER = "其他"
    const val CATEGORY_CUSTOM = "自定义"

    val builtInApps: List<VpnRequiredApp> = listOf(
        VpnRequiredApp("com.openai.chatgpt", "ChatGPT", CATEGORY_AI),
        VpnRequiredApp("com.google.android.apps.bard", "Gemini", CATEGORY_AI),
        VpnRequiredApp("com.anthropic.claude", "Claude", CATEGORY_AI),
        VpnRequiredApp("ai.perplexity.app.android", "Perplexity", CATEGORY_AI),
        VpnRequiredApp("com.microsoft.bing", "Copilot / Bing", CATEGORY_AI),

        VpnRequiredApp("com.google.android.googlequicksearchbox", "Google", CATEGORY_GOOGLE),
        VpnRequiredApp("com.android.vending", "Google Play 商店", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.gms", "Google Play 服务", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.youtube", "YouTube", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.gm", "Gmail", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.apps.maps", "Google 地图", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.apps.photos", "Google 相册", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.apps.docs", "Google 云端硬盘", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.apps.translate", "Google 翻译", CATEGORY_GOOGLE),
        VpnRequiredApp("com.google.android.calendar", "Google 日历", CATEGORY_GOOGLE),

        VpnRequiredApp("com.twitter.android", "X（Twitter）", CATEGORY_SOCIAL),
        VpnRequiredApp("com.facebook.katana", "Facebook", CATEGORY_SOCIAL),
        VpnRequiredApp("com.instagram.android", "Instagram", CATEGORY_SOCIAL),
        VpnRequiredApp("com.zhiliaoapp.musically", "TikTok", CATEGORY_SOCIAL),
        VpnRequiredApp("com.reddit.frontpage", "Reddit", CATEGORY_SOCIAL),
        VpnRequiredApp("com.discord", "Discord", CATEGORY_SOCIAL),

        VpnRequiredApp("org.telegram.messenger", "Telegram", CATEGORY_OTHER),
        VpnRequiredApp("com.whatsapp", "WhatsApp", CATEGORY_OTHER),
        VpnRequiredApp("com.spotify.music", "Spotify", CATEGORY_OTHER),
        VpnRequiredApp("com.dropbox.android", "Dropbox", CATEGORY_OTHER),
    )

    private val builtInByPackage: Map<String, VpnRequiredApp> =
        builtInApps.associateBy { it.packageName }

    /** 内置名单 + 用户自定义名单。 */
    fun all(custom: List<VpnRequiredApp> = SettingsStore.customApps.value): List<VpnRequiredApp> =
        builtInApps + custom

    fun find(packageName: String): VpnRequiredApp? =
        builtInByPackage[packageName]
            ?: SettingsStore.customApps.value.firstOrNull { it.packageName == packageName }

    fun isVpnRequired(packageName: String): Boolean = find(packageName) != null

    /** 是否为内置条目。内置条目不允许在界面上删除。 */
    fun isBuiltIn(packageName: String): Boolean = builtInByPackage.containsKey(packageName)

    /**
     * 按分类分组，顺序与 [builtInApps] 中首次出现的顺序一致，
     * 「[CATEGORY_CUSTOM]」永远排在最后。
     */
    fun grouped(
        custom: List<VpnRequiredApp> = SettingsStore.customApps.value,
    ): Map<String, List<VpnRequiredApp>> {
        val grouped = LinkedHashMap<String, MutableList<VpnRequiredApp>>()
        builtInApps.forEach { app -> grouped.getOrPut(app.category) { mutableListOf() }.add(app) }
        if (custom.isNotEmpty()) grouped[CATEGORY_CUSTOM] = custom.toMutableList()
        return grouped
    }
}
