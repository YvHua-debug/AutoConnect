# Auto Connect 1.1.6 小窗真机回归

2026-10-11，已连接的 Redmi K80，Android API 37，FLClash。

## 修复

原逻辑只用最后获得焦点的应用决定 VPN 需求，其它小窗获得焦点后，即使名单应用仍可见也会开始断开。
Android 10+ 另行跟踪包名 / Activity 类名的可见集合：resumed 加入，paused 保留，stopped 移除，
设备重启事件清空旧集合。自动连接及自动断开使用同一可见名单判断，界面仍显示当前焦点应用。
Android 7～9 沿用原单前台逻辑。

## 验证

- 24 项单元测试通过，包括新增的 11 项可见窗口回归用例。
- release 构建、lintVitalRelease、APK v2 签名校验通过。
- 正式包覆盖安装为 1.1.6（versionCode 8），没有清除应用数据或修改开关、名单、VPN 客户端配置。
- 打开 ChatGPT 后第一次 START 指令建立 FLClash VPN，ChatGPT 保持前台。
- ChatGPT 主窗口上启动计算器小窗并点击数字：计算器为 freeform 且获得焦点，ChatGPT 为 fullscreen 且可见；
  超过 40 秒观察期后 VPN 网络仍为 CONNECTED，监控日志没有发出 STOP。心跳已识别前台为计算器。
- 返回桌面后 ChatGPT 收到 ACTIVITY_STOPPED，窗口变为不可见；计算器小窗仍可见。
  21:35:56 检测到离开名单应用，按手机当前保留的 8 秒延迟，21:36:04 第一次 STOP，
  21:36:06 回读到 VPN 已断开。没有因名单外小窗仍可见而保留 VPN。
- 反向测试：ChatGPT 为可见 freeform 小窗，设置为 fullscreen 主窗口并获得焦点；
  监控心跳识别前台为设置，超过 20 秒后实际 VPN 网络仍为 CONNECTED，没有误断开。
- 测试结束恢复 ChatGPT 普通窗口并返回桌面，关闭测试计算器小窗，移除设备上的临时 UI 层级文件。

## 限制

可见状态依赖系统完整上报 Activity 生命周期。公开 UsageEvents API 不提供 Activity 实例 ID，
同一 Activity 类的多个实例同时开窗无法分别追踪。本次验证的是上述两种实际小窗场景；
未穷举其它 ROM、分屏和多个相同 Activity 实例场景。
