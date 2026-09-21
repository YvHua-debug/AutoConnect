package com.example.composestarter.data

/**
 * 一条「需要 VPN 才能正常使用」的应用记录。
 *
 * @param packageName 应用包名，用于前台检测时精确匹配
 * @param displayName 展示名称
 * @param category 归类，用于界面上分组
 */
data class VpnRequiredApp(
    val packageName: String,
    val displayName: String,
    val category: String,
)
