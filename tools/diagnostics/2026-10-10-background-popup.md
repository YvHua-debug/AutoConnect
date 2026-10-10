# Auto Connect 1.1.5 后台弹窗排查

2026-10-10，Redmi K80，FLClash 0.8.98，UmiVPN 0.7.7。

## 原因与修复

先打开 FLClash 主界面，再让 Auto Connect 在后台检测 ChatGPT。原版发出 START 后，
`QuickActionActivity` 结束，系统前台却停在 FLClash 的 `MainActivity`，问题可稳定复现。
控制 Activity 使用透明主题，但与主界面共享默认任务亲和性。仅使用 NEW_TASK 会复用已有任务，
透明控制页结束后留下其底下的主界面，导致用户被切走。

启动和停止统一改用 `NEW_TASK | MULTIPLE_TASK | EXCLUDE_FROM_RECENTS`。
控制页在独立临时任务内执行，结束后回到先前的应用。没有启动客户端主界面作为兜底。

## UmiVPN

安装包包名 `com5vnetwork.umi`，版本 0.7.7。核对其 APK 清单及官方公开源码：

- `TmVpnService` 为 `exported=false`，并受 `BIND_VPN_SERVICE` 权限保护。
- `MyTileService` 受 `BIND_QUICK_SETTINGS_TILE` 系统权限保护，第三方应用不能模拟图块调用。
- 导出的主 Activity 提供桌面入口和 `vx://add` 导入链接，没有外部启动/停止接口。

当前不能实现无额外特权的后台自动启停。用户确认暂不列入适配名单，因此不提供手动连接选项。
内部保留包名供隧道归属识别；适配列表、安装查询、自动选择和停止兜底均排除不支持控制的客户端。

源码参考：[官方清单](https://github.com/5VNetwork/umivpn/blob/main/android/app/src/main/AndroidManifest.xml)、
[官方主 Activity](https://github.com/5VNetwork/umivpn/blob/main/android/app/src/main/kotlin/com5vnetwork/umi/MainActivity.kt)。

## 验证

- 13 项单元测试通过，包含 5 项客户端选择与停止兜底回归测试。
- 签名 release 构建、lintVital 与 APK 签名校验通过。
- 原版与独立任务指令对照：原版将 FLClash 主界面带到前台；新指令启动和停止后都保持 ChatGPT 前台。
- 最终包覆盖安装为 1.1.5（versionCode 7），保留 FLClash 选择、监控名单和 30 秒断开延迟。
- 最终包打开 ChatGPT：第一次启动指令建立 VPN，`topResumedActivity` 仍为 ChatGPT。
- 新版退出到桌面：按保留的 30 秒延迟发出第一次停止指令并成功断开，桌面保持前台。
- 界面适配列表只显示 Clash Meta、FLClash、Surfboard；未把 UmiVPN 标为支持自动启停。

完整 `lintDebug` 的离线运行因未缓存的 androidTest 依赖未能执行；正式包的 lintVital 通过。
未更改用户的 VPN 配置、节点或账户。
