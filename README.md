# Auto Connect

自动连接助手：检测到需要代理的应用被打开时，后台静默拉起 VPN（Clash Meta / FLClash / Surfboard），
离开名单应用后再把 VPN 断开。

Kotlin + Jetpack Compose + Material 3，UI 全部由 Compose 实现，XML 仅保留构建必需的清单与资源。

> 当前版本 **1.1**：v1.0 只支持 Clash Meta、且只能启动；1.1 适配三个客户端，并新增「离开名单自动断开」。

## 功能

1. **开机自启 + 常驻**
   - `BootReceiver` 监听 `BOOT_COMPLETED`，开机后拉起 `MonitorService`
   - `MonitorService` 是前台服务（`specialUse` 类型），进程不会被系统回收
   - 拿到悬浮窗权限时，开机后会把 `MainActivity` 自动带回前台
   - `MainActivity` 持有 `FLAG_KEEP_SCREEN_ON`，界面常亮

2. **识别需要 VPN 的应用并维护名单**
   - 名单在 `data/VpnAppCatalog.kt`，当前 25 条：ChatGPT / Gemini / Claude / Perplexity、Google 全家桶（Play 商店、GMS、YouTube、Gmail、地图…）、X / Facebook / Instagram / TikTok / Reddit / Discord、Telegram / WhatsApp / Spotify / Dropbox
   - `ForegroundAppDetector` 基于 `UsageStatsManager` 轮询前台应用：空闲 2 秒一次、名单应用在前台时 5 秒一次、息屏时完全暂停（详见「后台占用优化」）
   - 命中名单即写入「命中记录」并推送通知，同时标注当时 VPN 是否已连接
   - 界面上按分类罗列名单，并标注每个应用在本机是否已安装
   - 名单卡片右侧的「＋」可以打开应用选择器，把设备上任意带桌面图标的应用加进名单
     （支持搜索；内置条目不可删，自定义条目可随时「移除」）
   - 自定义名单存在 `SettingsStore.customApps`，落盘到 SharedPreferences；
     监控服务每轮只做一次轻量比对，名单没变就不会重复查 PackageManager

3. **界面**
   - 权限、VPN 联动、监控名单都是可折叠的二级卡片，标题行直接显示状态摘要
     （如「已授权 6/6」「FLClash · 可启停」「25 个内置 · 2 个自定义」），默认收起
   - 权限卡片按机型列出全部项目：通用 4 项 + 小米机型特有的「自启动」「后台弹出界面」，
     每一项都显示真实状态（小米那两项通过 MIUI 自己的 appops 读，见「权限说明」）
   - 折叠状态用 `rememberSaveable` 保存，转屏 / 重建不会丢
   - 配色是**内置的浅色 / 深色两套调色板**（`ui/theme/Color.kt`），不用 Android 12+ 的
     Material You 动态取色：动态取色会跟着每台设备的壁纸变，同一份界面在不同机器上长得不一样；
     写死之后所有机型都是同一套颜色，只跟随系统的深浅色模式切换
   - 二级卡片的展开指示是手绘的描边箭头（不用「▲ / ▼」文字符号，也不引 material-icons），
     展开 / 收起时用旋转动画过渡

4. **VPN 连通性判断**
   - `ConnectivityManager` + `TRANSPORT_VPN` 判断当前是否有 VPN 生效

5. **命中名单时自动拉起 VPN（后台静默）**
   - 三个客户端任选，走的是它们**自己对外导出**的控制入口（`data/VpnEngine.kt`）：

     | 客户端 | 控制入口 | 启动 / 停止 |
     |---|---|---|
     | Clash Meta | `com.github.kr328.clash.ExternalControlActivity` | `ACTION_START_CLASH` / `ACTION_STOP_CLASH` |
     | FLClash | `com.follow.clash.TempActivity` | `com.follow.clash.action.START` / `…STOP` |
     | Surfboard | `com.getsurfboard.ui.activity.DeeplinkActivity` | `surfboard:///start` / `surfboard:///stop` |

     这三个入口就是各客户端给自己的桌面快捷方式 / 快捷设置图块用的同一套接口（真机核对：点 FLClash、
     Surfboard 的图块，执行的就是这里发出的那条指令）。目标 Activity 都是透明主题 + `noHistory`，
     启动后立即结束，不会盖住用户当前界面；加上 `SYSTEM_ALERT_WINDOW`（悬浮窗）权限，
     Android 10+ 允许本项目从后台启动它。
   - 「VPN 联动」卡片列出设备上已装的客户端，点一下即切换；只装一个时什么都不用配
     （不选就是「自动」，取第一个已安装的）
   - **以「回读到的真实 VPN 状态」为准，而不是发一条指令 + 6 秒后看一眼就完事**：
     只要名单应用还在前台、VPN 还没连上，就每 2 秒补发一次启动指令（最多 4 次，约前 10 秒），
     之后降成每分钟一次继续兜，连上了或用户离开名单应用才收尾。
     命中记录和通知都按真实结果写：「正在自动连接 VPN…」→「已自动建立 VPN」/「没能建立 VPN」
   - 失败时会判断「是不是被系统拦掉了」（没有悬浮窗权限、或小米没开「后台弹出界面」），
     是的话直接写明「后台拉起 VPN 客户端被系统拦截，请开启后台弹出界面」
   - 用户停在名单应用里没动、VPN 被系统或别的 VPN 应用顶掉时也会自己补连回来
     （以前只在「切换前台应用」时判断一次，这种情况永远等不到第二次机会）
   - 可在界面上关闭（「VPN 联动」卡片里的开关），也可点「立即启动 VPN」手动验证

6. **离开名单应用后自动断开 VPN**
   - 离开名单应用先等 8 秒再动手：用户从 ChatGPT 点开一个链接、或只是下拉看一眼通知都会短暂离开
     名单应用，这期间回到任意名单应用就取消，隧道不会被拆掉又立刻重建
   - 断开同样**以回读到的状态收尾**：每 2 秒发一次停止指令，每个客户端最多 4 次，
     回读到「VPN 未连接」才算成功；断不掉就把「断开失败」写进命中记录和通知，不谎报
   - 「谁建的隧道」在部分 ROM 上读不到（实测 HyperOS 3 上 `NetworkCapabilities.getOwnerUid()`
     返回 `-1`，详见「已知限制」），这时按「用户选中的 → 其余已安装的」依次尝试，
     全都试完隧道还在才报失败；失败之后会收手，用户下次打开名单应用时再试，
     不会每十几秒把几个客户端轮流点一遍
   - 可在界面上关闭（「离开名单应用时自动断开 VPN」开关）

7. **常驻通知可以按需隐藏**
   - 「监控服务」卡片里有一个「显示常驻通知」开关，它显示的是**系统里那条通知通道的真实状态**
   - 点开关会跳到系统设置里「Auto Connect 状态」这一条通道的设置页，在那里关掉「允许通知」
     即可：通知栏不再显示「正在监控前台应用」，前台服务照常运行（`startForeground` 照发，
     只是系统不画出来），回到应用开关会自动变成关
   - **只影响这一条通知**：命中名单、自动补连 VPN 的结果是独立的通知 id（`1002`）
     和独立通道（`monitor_alert_v1`），不受影响（实测：通道关掉后命中提醒照常弹出）
   - 为什么要绕到系统设置：应用没法把通知「发出去但谁都不显示」。试过把前台通知挂到一条
     `IMPORTANCE_NONE` 的通道上来隐藏，**真机实测无效**，详见「已知限制」

## 权限说明

| 权限 | 用途 | 授予方式 |
|---|---|---|
| `PACKAGE_USAGE_STATS` | 读取前台应用，**核心权限** | 特殊权限，须在系统设置中手动开启（应用内提供跳转） |
| `SYSTEM_ALERT_WINDOW` | Android 10+ 后台启动 Activity：开机后自动回到前台、后台拉起 VPN 客户端 | 特殊权限，应用内提供跳转 |
| `POST_NOTIFICATIONS` | 命中名单时推送提醒 | Android 13+ 运行时申请 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 安装即授予 |
| `FOREGROUND_SERVICE` / `_SPECIAL_USE` | 常驻前台服务 | 安装即授予 |
| `ACCESS_NETWORK_STATE` | 判断 VPN 是否连接 | 安装即授予 |
| `QUERY_ALL_PACKAGES` | 判断名单中的应用是否已安装 | 安装即授予 |

用 adb 一键授予（调试用）：

```powershell
adb shell appops set com.example.composestarter.debug android:get_usage_stats allow
adb shell appops set com.example.composestarter.debug SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.example.composestarter.debug android.permission.POST_NOTIFICATIONS
```

### 小米机型特有的两项（应用内可读状态）

「自启动 / 后台运行」和「后台弹出界面」不是 Android 权限，而是 MIUI 自己做的一套 appops，
**没有公开名字**（`adb shell appops get <包名> AUTO_START` 一律报 `Unknown operation string`），
只能按 op 编号查。真机实测（HyperOS 3 / Android 16，2026-09-20）确认：

| 界面上的开关 | MIUI appop | 验证方式 |
|---|---|---|
| 自启动 / 后台运行 | `10008` | `10008=allow` 的第三方应用（微信、QQ、欢律、小米运动健康）与「自启动管理」页里「允许」的那几个完全一致 |
| 后台弹出界面 | `10021` | 置成 `ignore` 后后台 `startActivity` 立刻被拦（logcat：`Abort background activity starts from <uid>`）；置回 `allow` 后同一路径恢复正常，客户端被拉起且 VPN 真的连上 |

应用里读这两项走的是反射（`getOpsForPackage` / `checkOpNoThrow(int, …)` 都是隐藏 API，
公开的那几个只认 op 名字），查不到时界面显示「去设置」而不是谎报「未开启」。

adb 模拟这两项没开（调试用）：

```powershell
adb shell appops set com.example.composestarter.debug 10008 ignore   # 关自启动
adb shell appops set com.example.composestarter.debug 10021 ignore   # 关后台弹出界面
```

注意：MIUI 的「自启动管理」页有自己的缓存，用 appops 改完再打开那个页面看到的可能还是旧状态，
但实际生效的是 appops（`10021` 的拦截行为当场就能复现）。

## 已知限制

- 「命中记录」只存在内存里，进程重启后清空（未落库）
- 没有悬浮窗权限时，开机只会拉起服务，不会把界面弹到前台（Android 10 起的后台启动限制）
- 前台检测依赖系统 `UsageStatsManager`，采样间隔空闲 2 秒 / 名单应用在前台 5 秒，
  打开名单应用后最多 2 秒才会被发现；极短时间的切换可能漏记
- 息屏时轮询间隔放宽到 30 秒，所以息屏状态下切换应用最多要 30 秒才会被发现
- **客户端里必须先选中一个配置，自动启动才能建立隧道。** Clash Meta 选 Profile、FLClash 选配置、
  Surfboard 选配置文件；没有配置时客户端会被正常拉起，但不会建立 VPN，
  本项目只能通过「VPN 是否连上」间接发现这一点
- **「启动」指令在客户端的内核已经跑着的时候可能是空操作。** Clash Meta 源码里 `ACTION_START_CLASH`
  只是 `if (isClashRunning()) 提示已启动 else startClash()`，而 `isClashRunning()` 看的是
  「内核服务在不在跑」，不是「隧道建没建起来」；所以「内核活着、隧道没连」这种状态下
  单发一条指令什么都不会发生——这是以前会稳定卡在「已拉起 · VPN 未连接」的原因之一，
  现在靠「没连上就补发」覆盖掉（这条是 Clash Meta 的实现细节，另外两个客户端没有实测到，
  但补发机制对三家一视同仁）
- **后台 `startActivity` 可能被系统静默拦掉，应用侧拿不到任何错误。**
  实测（HyperOS 3 / Android 16，2026-09-21）：把本应用的 MIUI appop `10021`（「后台弹出界面」）
  置成 `ignore` 后，日志里是 `Abort background activity starts from <uid>`、客户端根本没被拉起，
  但 `startActivity` 正常返回——以前据此写下的「已拉起客户端」是假结论，而且只发一次，
  用户不切走就再也不会重试（实测：拦截状态下停在名单应用里 60 秒，一条指令都不会再发）。
  现在补发 4 次仍连不上就会把「被系统拦截」明确写进命中记录和通知；
  用户把权限打开后，慢速重试会自己把 VPN 连上（实测：打开权限后第 5 次重试连上）。
- 首次启用 VPN 时，Android 必定弹出一次系统 VPN 授权框（`VpnService.prepare`），
  授权一次后不再出现，这一步无法绕过
- **Clash Meta 的停止入口冷启动时经常失效，这是它自己的实现问题。** 停止方向它也用官方导出的
  `com.github.kr328.clash.ExternalControlActivity`（桌面长按 Clash 图标时那个「停止 Clash」
  快捷方式用的也是它，`dumpsys shortcut` 验证过），但它内部走的是**自己进程里的私有广播**
  （`ACTION_CLASH_REQUEST_STOP`，动态注册、带 `com.github.metacubex.clash.meta.permission.RECEIVE_BROADCASTS`
  权限）：冷启动（`force-stop` 之后）实测单发 0/3、连发 6 次也无效，
  `BroadcastQueue` 显示广播 1ms 就投递了（不是 MIUI 延迟），只有它的界面被打开过一次
  （进程活跃）之后才收得到。2026-09-28 在 1.1 上复测：冷启动时按
  「Clash Meta → FLClash → Surfboard」全试一遍，隧道照样在；把 Clash Meta 的界面打开一次再试，
  第 1 次就断开了。**FLClash 和 Surfboard 没有这个问题**（真机冷启动 3/3 一次成功），
  想要稳定的自动断开，建议把「VPN 客户端」选成它们。
- **系统那枚 VPN 图块（控制中心）没法被应用「点一下」。** 用户提过的思路是用控制中心的 VPN
  按钮来断开：那枚图块由 `com.android.systemui` 的 `VpnTileService` 提供，第三方应用既没有
  「替用户点图块」的 API，`cmd statusbar click-tile <组件>` 也只认自己声明的 `TileService`。
  所以最终走的是各客户端自己导出的控制入口（见「功能」第 5 条）——不依赖系统 UI，也不需要任何
  特殊权限。`am kill` / `killBackgroundProcesses` 也验证过不行：它们是前台服务级别，
  系统不允许第三方应用清理
- **应用进程读不到 VPN 的隧道网卡。** 曾经试过用「`NetworkInterface` 里有没有 `tun0`」
  来判断 VPN 是否连接，结果在真机上必然误报成「未连接」（tun0 明明存在，应用侧遍历不到），
  所以 VPN 状态只能走 `ConnectivityManager`，别做这种「优化」。
- **隧道归属（谁建的隧道）在部分 ROM 上读不到。** `dumpsys connectivity` 里能看到
  `OwnerUid: 10393`，但应用侧拿到的 `NetworkCapabilities.getOwnerUid()` 是 **-1**
  （实测 HyperOS 3 / Android 17，2026-09-28：这个字段只对特权调用方填）。自动断开因此
  不依赖它：归属排在最前，认不出来就按「用户选中的 → 其余已安装的」依次尝试，
  真实依据始终是「回读到的 VPN 状态」
- **应用自己关不掉前台服务的通知，只能由用户在系统设置里关。**
  前台服务必须持有一条通知，而应用没法把它「发出去但谁都不显示」：曾经准备了一条
  `IMPORTANCE_NONE` 的通道，把前台通知挂过去，看起来能隐藏——但那是**假象**。
  实测（HyperOS 3 / Android 16，2026-09-20）：往被屏蔽的通道投递前台服务通知时，
  系统会把这条通道的重要性抬回 `LOW`（`dumpsys` 里 `mOriginalImp=0` 但 `mImportance=2`），
  通知过一会儿就自己冒出来了（第一次投递的瞬间确实不显示，很容易误判成成功）。
  唯一有效的做法是用户在系统通知设置里关掉这条通道：实测关掉后通道 `mImportance=0`、
  通知记录直接消失，而同一应用的 `monitor_alert_v1`（提醒）通道不受影响。
  所以应用里那个开关只做两件事：读系统的真实状态、把用户送到那条通道的设置页
  （`ACTION_CHANNEL_NOTIFICATION_SETTINGS`，真机确认能直接打开对应通道）。
- **在最近任务里划掉卡片会连进程一起杀掉**，服务收不到 `onDestroy`，`START_STICKY`
  也不会重拉，而且 ActivityManager 自己的重启排期带指数退避（实测最长排到 68 分钟）。
  服务改成靠 `onTaskRemoved()` + 1 秒闹钟自愈（实测约 2 秒恢复），
  15 分钟一次的例行看门狗是最后兜底。细节见「小米 / HyperOS」的第三个坑。

## 后台占用优化

监控需要一直挂着，所以「每秒都在做、但其实没必要每秒做」的事情都省掉了：

| 优化项 | 改动前 | 改动后 |
|---|---|---|
| 轮询间隔 | 固定 1 秒 | 空闲 2 秒；名单应用在前台时 5 秒；补连 VPN / 正在断开期间 2 秒 |
| 前台应用查询窗口 | 每轮都回看 30 分钟内的全部事件 | 首次回看 30 分钟，之后只查「距上次查询 1 秒」的增量窗口，与上一次结果合并 |
| 息屏期间 | 照常每秒轮询 | 间隔放宽到 30 秒（只拉长、不停止） |
| VPN 状态查询 | 每轮都问 `ConnectivityManager` | 5 秒一次；名单应用在前台、正在补连或正在断开时立即回读 |
| 应用名查询 | 每轮都查 `PackageManager` + `loadLabel` | 只在前台应用变化时查，且带缓存 |
| 已安装应用枚举 | 每次启动枚举全机应用（几百个 `PackageInfo`） | 只查名单里的包（内置 25 + 自定义），且只在名单变化时重查 |
| VPN 客户端安装状态 | 每轮都查 `PackageManager` | 60 秒一次；用户在界面上改了选择则立即重查 |
| 自定义名单比对 | — | 每轮只做一次列表相等判断（几个元素的 `List.equals`），不碰 `PackageManager` |
| 通知刷新 | 文案相同时也会 `notify()` | 文案没变就不推送 |

实测（模拟器 `Pixel_API35`，App 退到后台，取 `/proc/<pid>/stat` 的 60 秒 CPU 时间增量）：

| 场景 | 改动前 | 改动后 |
|---|---|---|
| 亮屏 · 后台 | 9 jiffies ≈ 0.15% | 5 jiffies ≈ 0.083% |
| 息屏 · 后台 | 10 jiffies ≈ 0.167% | 2 jiffies ≈ 0.033% |
| 后台 PSS | 79.9 MB | 76.0 MB |

息屏这一项收益最大：用户不看屏幕的时候，监控一次系统查询都不做。

> 为什么息屏只是「拉长」而不是「挂起」：最初的实现是息屏时完全停下、等 `ACTION_SCREEN_ON`
> 广播再恢复。实测小米 HyperOS 3 / Android 16 不会把这个广播投递给后台应用，
> 一旦进程恰好是在息屏时被系统拉起来，就会永久卡在挂起状态、再也不轮询。
> 现在改成每轮直接读 `PowerManager.isInteractive` 来决定间隔，不依赖任何广播。

## 小米 / HyperOS（Redmi、POCO）必须做的设置

实测机型：**Redmi K80（HyperOS 3.0 / Android 16）**。

HyperOS 的 PowerKeeper 会把没加白的应用在息屏 / 后台一段时间后**直接冻结**。
被冻结时前台服务还在、通知也还在，但进程里的代码一行都不会执行，
表现就是「只有把应用挂在后台小窗里才检测得到」。

关键日志（`adb logcat` 可见）：

```
D/powerkeeper.dfsanalyze: screen is off
D/ActionExecute: delayFreeze true reason=screen off uid=<应用 uid>
D/MiSensorServiceImpl: PowerKeeperSensor stop iUid=<uid> but app has Controlled
```

需要手动完成下面三项（缺一不可）：

| 设置项 | 路径 | 作用 |
|---|---|---|
| 省电策略 → **无限制** | 设置 → 应用设置 → 应用管理 → Auto Connect → 省电策略 | 阻止 PowerKeeper 冻结进程，**最关键** |
| **自启动** → 打开 | 设置 → 应用设置 → 应用管理 → Auto Connect → 自启动 | 允许开机 / 被系统拉起 |
| **后台弹出界面** → 允许 | 应用信息 → 权限管理 → 后台弹出界面 | 命中名单时才能静默拉起 VPN 客户端 |

另外建议：

- 最近任务里下拉应用卡片 → 点**锁**图标（锁定后 MIUI 不会清理它）
- 应用内「后台省电白名单」点「去授权」，把应用加入 Android 的电池优化白名单
- 关闭系统「省电模式」（它会进一步限制后台）

验证方法：应用内「实时状态 → 后台轮询」会显示**最长间隔**，
被系统冻结过就会标红并显示分钟级的空档；正常应该是「X 秒」级别。

### 第二个坑：「后台弹出界面」没开会静默失败

`省电策略 = 无限制` 只解决「检测不到」。命中之后还要在后台拉起 VPN 客户端的控制页，
如果「后台弹出界面」是关闭的，MIUI 会直接拒绝这次启动，而且**不会抛异常**：

```
D/ActivityStarterImpl: MIUILOG- Permission Denied Activity : Intent { act=...action.START_CLASH ... }
E/ActivityTaskManager: Abort background activity starts from <uid>
```

表现：通知栏停在「打开 ChatGPT · 客户端已拉起但 VPN 未建立」，客户端进程根本没起来。
在 MIUI 上，Android 的「悬浮窗权限」已经授权也**不能**替代这个开关，必须单独允许。

开启路径：应用信息 → 权限管理 → **其他权限** → 后台弹出界面 → **始终允许**
（权限页里它在「显示悬浮窗」下面）。应用内「权限」卡片提供了一键跳转。

### 第三个坑：划掉最近任务卡片会连进程一起杀掉

在最近任务里上滑把 Auto Connect 的卡片划掉，MIUI 走的是 `SwipeUpClean` 这条路，
它会**直接杀进程**，而不是只移除任务：

```
I ProcessSceneCleaner: SwipeUpClean: kill procName=com.example.composestarter.debug
I ActivityManager: Killing 15099:com.example.composestarter.debug/u0a372 (adj 200): SwipeUpClean
```

后果有三个，全都容易被误判成「应用还活着」：

- 服务收不到 `onDestroy`，`START_STICKY` 实测也不会把进程拉回来
- ActivityManager 自己的「服务崩溃重启」带**指数退避**：连续被划掉时排期按
  1s → 4s → 16s → 64s → 256s … 翻倍，实测出现过 `in 4096000ms`（约 68 分钟）的排期。
  这段时间里打开名单内的应用不会有任何反应，VPN 也不会被拉起
- 应用内「实时状态 → 后台轮询」只是停在最后一次刷新，界面看不出异常；
  系统「使用情况访问记录」里的自启动记录也可能是上一次手动打开应用留下的，不能证明服务在跑

现在的做法：`MonitorService.onTaskRemoved()` 在卡片被划掉的**当下**排一个 1 秒后触发的
一次性闹钟（`Watchdog.scheduleRestartSoon`），到点由 `WatchdogReceiver` 调
`MonitorService.start()` 把服务接回来：

```
W ActivityManager: Scheduling restart of crashed service ... in 64000ms for start-requested
I am_proc_start: [0,20784,10372,...  ,broadcast,{com.example.composestarter.debug/...WatchdogReceiver}]
D MonitorService: 看门狗触发，检查监控服务
```

这里用 `setAndAllowWhileIdle` 而不是精确闹钟：不精确闹钟的投递窗口是按「距离触发还有多久」
算的，1 秒的闹钟几秒内就会到，因此不需要用户额外授予 `SCHEDULE_EXACT_ALARM`。
万一 `onTaskRemoved` 也没被投递，15 分钟一次的例行看门狗（`Watchdog.INTERVAL_MS`）
是最后兜底，最坏情况 15 分钟内自动恢复。

实测（2026-09-20，Redmi K80 / HyperOS 3）：划掉卡片后约 **2 秒**服务恢复
（中途有一次 AM 退避到 64 秒，进程仍由 `WatchdogReceiver` 拉起）；紧接着打开名单内的 X，
日志出现 `命中 X（Twitter）vpn=false`、`第 1 次拉起 Clash（X）sent=true`，
随后 `VPN 状态变化：false -> true`，隧道网卡建立。

## 应用图标

图标由提供的原图（1254×1254 的 PNG）生成，生成脚本见 `tools/gen_icons.ps1`，
只用 Windows 自带的 .NET `System.Drawing`，不需要额外安装任何东西。

- 自适应图标：底层是原图背景色，前景是从原图裁出的「蓝色开关 + 闪电」，
  按 108dp 画布（含 72dp 安全区）缩放居中；另附一版按亮度生成的单色图层，供 Android 13+ 主题图标使用
- 传统图标：`mipmap-{mdpi..xxxhdpi}/ic_launcher.png` 为原图整图缩放，`ic_launcher_round.png` 为圆形裁剪版
- 通知栏小图标：`drawable/ic_notification.xml`（闪电剪影）

重新生成：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\gen_icons.ps1
```

## 技术栈

| 项 | 版本 |
|---|---|
| Gradle | 8.14.3（wrapper 指向腾讯镜像） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| Compose BOM | 2026.03.01 |
| compileSdk / targetSdk | 35 |
| minSdk | 24 |
| JDK | 21 |

## 项目结构

```
app/src/main/
├── AndroidManifest.xml          权限、Activity、前台服务、开机广播接收器
├── java/com/example/composestarter/
│   ├── MainActivity.kt          入口 Activity，负责权限刷新与常亮
│   ├── data/
│   │   ├── SettingsStore.kt     轻量设置存储（SharedPreferences + StateFlow）
│   │   ├── VpnRequiredApp.kt    名单条目模型
│   │   ├── VpnAppCatalog.kt     需要 VPN 的应用名单（扩展点）
│   │   ├── VpnEngine.kt       三个 VPN 客户端的外部控制入口（扩展点）
│   │   ├── AppRepository.kt     查询已安装应用与名称
│   │   └── InstalledApps.kt     枚举带桌面图标的应用（供「＋」选择器使用）
│   ├── monitor/
│   │   ├── ForegroundAppDetector.kt  前台应用 / VPN 状态检测
│   │   ├── MonitorService.kt         常驻前台服务与轮询逻辑
│   │   ├── VpnController.kt          按客户端启停 VPN（入口定义见 data/VpnEngine.kt）
│   │   ├── BootReceiver.kt           开机自启
│   │   ├── MonitorPermissions.kt     权限检查与设置页跳转
│   │   └── MonitorRepository.kt      进程内共享状态（StateFlow）
│   ├── ui/MonitorScreen.kt      监控主界面
│   ├── ui/AppPickerDialog.kt    「＋」添加监控应用的选择器
│   └── ui/theme/                Material 3 主题
│       ├── Color.kt             固定配色（浅色 / 深色两套调色板）
│       ├── Theme.kt             MaterialTheme（只跟随系统深浅色模式）
│       └── Type.kt              字体排版
└── res/
    ├── values/                  字符串、颜色、宿主主题
    ├── drawable/                通知栏小图标（Vector）
    ├── drawable-xxxhdpi/        自适应图标前景与单色图层（由原图生成）
    ├── mipmap-*/                启动图标（各密度传统图标）
    ├── mipmap-anydpi-v26/       自适应图标定义
    └── xml/                     备份规则
```

## 环境依赖

- JDK 21（AGP 8.13 / Gradle 8.14 要求 17 以上）
- Android SDK（`local.properties` 里的 `sdk.dir` 指向本机 SDK，该文件不入库）
- 模拟器：AVD `Pixel_API35`（Android 15 / API 35，x86_64）
- Gradle 用户目录：**建议放在纯 ASCII 路径下**，原因见下一节

`org.gradle.java.home` 这类机器相关的配置不要提交进仓库，放到本机的
`<GRADLE_USER_HOME>/gradle.properties`（它的优先级高于项目根目录的同名文件）：

```properties
org.gradle.java.home=C:/path/to/jdk-21
```

不设置时会回退到环境变量 `JAVA_HOME` 或 `PATH` 上的 JDK。

## 重要：Windows 中文用户名导致的 Gradle Worker 报错

Windows 用户名含中文时（`C:\Users\<中文用户名>`），用默认的 Gradle 用户目录会踩到这个坑：
Gradle 会把编译 / 测试 worker 的 classpath 写进一个参数文件，而路径里带着中文用户名：

```
@C:\Users\<中文用户名>\.gradle\.tmp\gradle-worker-classpath<随机>.txt
```

JVM 启动器按系统 ANSI 编码解析该文件，中文路径乱码，构建直接失败：

```
错误: 找不到或无法加载主类 worker.org.gradle.process.internal.worker.GradleWorkerMain
ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain
```

**解决办法**：把 Gradle 用户目录放到纯 ASCII 路径下（下面以 `D:\gradle-home` 为例）。

```powershell
setx GRADLE_USER_HOME "D:\gradle-home"
```

（设置后需重开终端；换目录后第一次构建要重新下载一份依赖缓存。）

Android Studio 里对应设置项是
Settings → Build, Execution, Deployment → Build Tools → Gradle → **Gradle user home**，
填同一个路径。

另外，**项目自身的路径也不要含中文**：Windows 上 AGP 会直接拒绝非 ASCII 的项目路径
（`Your project path contains non-ASCII characters`），把项目放在纯英文路径下最省事。

## 常用命令

```powershell
# 构建 debug APK
.\gradlew.bat :app:assembleDebug

# 安装到已连接的设备/模拟器
.\gradlew.bat :app:installDebug

# 单元测试
.\gradlew.bat :app:testDebugUnitTest

# 启动模拟器
emulator -avd Pixel_API35

# 直接拉起 App
adb shell am start -n com.example.composestarter.debug/com.example.composestarter.MainActivity

# 授予特殊权限（详见「权限说明」）
adb shell appops set com.example.composestarter.debug android:get_usage_stats allow
adb shell appops set com.example.composestarter.debug SYSTEM_ALERT_WINDOW allow

# 手动启动 VPN（等价于点界面上的「立即启动 VPN」，换成当前选中的那个客户端）
adb shell am start -a com.github.metacubex.clash.meta.action.START_CLASH `
  -n com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity
adb shell am start -a com.follow.clash.action.START -n com.follow.clash/com.follow.clash.TempActivity
adb shell am start -a android.intent.action.VIEW -d "surfboard:///start" `
  -n com.getsurfboard/.ui.activity.DeeplinkActivity

# 手动停止 VPN（START_CLASH → STOP_CLASH、action.START → action.STOP、start → stop）
adb shell am start -a com.github.metacubex.clash.meta.action.STOP_CLASH `
  -n com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity
adb shell am start -a com.follow.clash.action.STOP -n com.follow.clash/com.follow.clash.TempActivity
adb shell am start -a android.intent.action.VIEW -d "surfboard:///stop" `
  -n com.getsurfboard/.ui.activity.DeeplinkActivity

# 查看 VPN 是否真的建立（Transports: VPN 即已生效）
adb shell dumpsys connectivity | findstr "VPN"
# 看隧道归属：dumpsys 里能看到 OwnerUid，应用侧读不到（见「已知限制」）
adb shell dumpsys connectivity | findstr "OwnerUid"

```

Debug 构建带 `.debug` 后缀（`applicationIdSuffix`），可与正式包共存。

### 手动验证某个客户端（Surfboard 需要一个配置文件）

客户端里要先选中一个配置，`/start` 才能真的建隧道。Surfboard 上还没有配置时，可以用
`tools/surfboard-test-profile.conf`：**规则只有一条 DIRECT，不连任何服务器，不碰你的机场订阅**。

```powershell
# 仓库里带一份直连配置；起个本地 HTTP 服务再用 deeplink 导入（手机端用 adb reverse 指回来）
python -m http.server 8731 --directory tools
adb reverse tcp:8731 tcp:8731
adb shell am start -a android.intent.action.VIEW \
  -d "surfboard:///install-config?url=http%3A%2F%2F127.0.0.1%3A8731%2Fsurfboard-test-profile.conf" \
  -n com.getsurfboard/.ui.activity.DeeplinkActivity
```

导入后它在 Surfboard 里叫 `surfboard-test-profile`（名字取自文件名）：选中它就能验证「启动 / 停止」整条链路，
但不代理任何流量（规则全是 DIRECT）；验证完直接删掉这个配置即可。

## 网络说明

`maven.google.com` 与 `services.gradle.org` 在本机网络下不可直连，因此：

- `settings.gradle.kts` 中配置了阿里云镜像（google / public / gradle-plugin）优先，官方仓库兜底
- Gradle wrapper 的 `distributionUrl` 指向腾讯云镜像
- `gradle.properties` 中设置了 HTTP 连接/读取超时，避免个别依赖下载卡死时无限等待

更细的 Windows 环境搭建步骤（含依赖镜像、AVD 创建）见 `SETUP.md`。

## 后续扩展建议

- 把命中记录落库（Room），支持按天统计与导出
- 命中时提供悬浮窗提醒，或联动客户端自动切换节点 / 切换配置
- 断开前给一个「还要再用 5 分钟」的延后按钮，而不是到点就断
- 支持更多客户端（v2rayNG、NekoBox 等）：`data/VpnEngine.kt` 里加一条枚举即可
- 加入 `navigation-compose` 做页面路由
- 按 feature 分模块（`:feature:xxx` / `:core:xxx`）拆分多模块结构
- 引入 Hilt 做依赖注入
- 补充 `androidTest` 的 Compose UI 测试

## 许可证

[MIT](LICENSE)
