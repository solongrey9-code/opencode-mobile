# 开发归档：2026-09-01

本文档保存本轮从空仓库到可运行 Android 客户端的探索、实验、失败、修复和经验。它是历史归档，不替代 `BASELINE.md` 或 `status.cmd`。

敏感信息已脱敏：不包含真实 Basic password、bridge token、Authorization header、session ID、设备序列号、Provider credential 或 Desktop store 原文。

## 1. 原始目标

目标是构建原生 Android OpenCode 客户端：

- 与 Desktop 共用 sessions/messages/history。
- 在手机查看正在运行任务。
- 从手机继续输入、停止任务、处理 permission/question。
- 查看 Todo 和 Diff。
- 尽量保持 OC-2 Desktop 视觉语义。
- 拔掉 USB 后仍可调试/使用。

最初容易产生的误解是“Desktop 和 shared CLI 读同一 DB，所以 live state 也相同”。本轮最关键的纠正是：数据库历史相同，不代表 process-local execution 相同。

## 2. Bootstrap 经验

仓库开始时没有完整 Android 工程和稳定工具链入口。建立了：

- Gradle multi-file project，单 `:app` module。
- Gradle Wrapper 8.11.1 + SHA-256 validation。
- AGP 8.9.2、Kotlin/Compose 2.1.20。
- SDK 35、minSdk 26、Java 17。
- 便携 JDK/SDK/Gradle fallback under `%TEMP%\opencode`。
- 一键 Debug/Release build scripts。

经验：

- Windows 上不要假设 Android Studio 已配置 `JAVA_HOME`/`ANDROID_HOME`。
- Wrapper checksum 必须与 distribution version 一起固定。
- Build script 应运行 tests + lint + assemble，而不是只验证 compile。
- Release 构建必须单独跑 R8，因为 Debug 成功不能证明 shrink/minify contract。

## 3. OpenCode API 逆向与验证

### 3.1 版本固定

CLI 和 Desktop 都确认是 OpenCode `1.18.25`。研究固定到上游 commit `9f69463f...`，避免 default branch 后续变化污染结论。

### 3.2 API 并非单一版本

发现同一服务同时暴露：

- root legacy API
- `/api/*` current API
- `/experimental/*`
- transitional event vocabularies

因此没有采用全局 `protocol=legacy/current` 开关，而采用 endpoint-level fallback。

经验：

- 404/405 表示 endpoint 不存在，适合 fallback。
- 401/403 是 auth failure，不能 fallback/reconnect 无限循环。
- 500/network failure 不应自动切换到另一个语义不同的 endpoint。
- 每个 wire shape 都需要 MockWebServer 或 parser fixture。

### 3.3 Global session 和 directory

`/experimental/session?roots=true&limit=250` 能跨 project 获取 root sessions。Legacy `/session` 是 located instance API，必须用 directory query 正确路由。

Permission/question request 来自 global envelope 时必须保留 directory；否则 reply 可能发到错误 project instance。

### 3.4 SSE envelope

实际需要兼容三类 envelope：

- global `{directory,payload:{type,properties}}`
- located `{type,properties}`
- current `{type,data,location}`

Unknown fields/parts 是版本演进常态，parser 采用宽松读取并保留 raw。

## 4. 最重要的并发探索

### 4.1 初始风险

简单实现常见顺序：

```text
REST snapshot -> start SSE
```

这会丢失两者之间的事件。

另一种错误：

```text
start SSE and update UI -> REST snapshot completes -> overwrite UI
```

这会用旧 snapshot 覆盖新 event。

### 4.2 最终屏障

采用：

```text
start SSE
buffer events
load snapshot
lock
apply snapshot
replay buffered events
continue live
```

Reconnect 同样执行 snapshot barrier，因为 global SSE 没有 replay cursor。

### 4.3 旧连接污染

仅 cancel Job 不足以防止旧 async response 在新连接激活后写入 state。增加：

- `connectionGeneration`：识别 connect attempt。
- `connectionEpoch`：识别 active API lifetime。
- API identity check。
- connection-scoped job cancel + join。
- `stateSync` mutex。

经验：网络状态机需要同时解决“谁被激活”和“哪个异步结果仍有效”。

### 4.4 Cross-project fan-out

大历史库包含许多 directory。对每个目录并发拉 status/permission/question 若不限制会导致连接风暴。

采用 `Semaphore(6)`。单目录瞬时失败保留旧状态，401/403 则整体断开。

## 5. Running state 问题

### 5.1 症状

用户看到 Desktop 有 Agent 在跑，Android session list 没有“正在运行”。

### 5.2 两个根因

1. Desktop 使用私有 sidecar，Android 使用 shared server：不是同一 process。
2. Server 报告 active 的是 child/subagent session，UI 只列 root sessions。

### 5.3 修复

- UI 明确区分 private/shared Desktop tabs。
- 轮询 `/api/session/active` 和 legacy status。
- 对不可见 active ID 获取 session detail。
- 沿 `parentID` 最多 16 层投影到 root。
- 2 秒 polling 做 eventual correction。

### 5.4 真机验证

创建有可观察持续时间的测试任务，确认：

- root session 进入“正在运行”。
- 显示“执行中”。
- 任务结束后自动退出 running group。
- 临时测试 sessions 删除干净。

经验：不能只用极短任务验证 Busy UI，状态可能在采样前结束。

## 6. Desktop sidecar 调研

### 6.1 观察

Desktop 内置 sidecar：

- loopback only
- random ephemeral port
- random UUID Basic password
- password 不在检查过的 electron-store 中持久化
- renderer 经 private IPC 获取连接信息

对 sidecar 未授权请求得到 401。这不是缺陷，而是安全设计。

### 6.2 被拒绝路径

- 读取 process memory。
- 从 renderer 注入脚本提取 password。
- 绕过 sidecar Basic auth。
- 把 sidecar 端口当稳定 endpoint。

这些路径会破坏 Desktop 安全边界，而且仍无法成为可维护产品方案。

### 6.3 核心结论

历史数据可共享是因为 server processes 读同一 OpenCode DB。当前 execution/event bus/pending requests 是 process-local。因此 Desktop 必须切换到 shared server 才能同步运行态。

## 7. Desktop tab bridge 演进

### 7.1 需求

Android 需要知道哪些 sessions 正在 Desktop 打开，并复刻 Desktop model visibility。

OpenCode Server 不提供 Desktop UI tab metadata，因此需要一个 companion bridge。

### 7.2 最小读取面

反向工程 electron-store 后，只读取：

- settings window IDs/default server
- window tabs/recent
- global model preference
- host LAN addresses

不读取 messages、provider auth、whole server store。

### 7.3 安全复审发现

最初若 bridge 复用 Basic password，会造成 credential scope 扩张：只需要 read-only metadata 的 Origin 获得控制整个 OpenCode server 的能力。

最终改为：

- 32 random bytes bridge token
- Host DPAPI
- Android Keystore
- Bearer timing-safe compare
- explicit bridge URL

### 7.4 Debug provisioning 风险

如果 exported Launcher Activity 接受 URL/token extras，任意普通 App 可调用并注入恶意 bridge Origin。

修复：

- 单独 Debug source-set receiver
- `android.permission.DUMP`
- Release 不包含 receiver
- Launcher 不解析 provisioning extras

经验：开发便利入口也必须做 Android component threat modeling。

## 8. Desktop tab identity bug

### 8.1 症状

Desktop 打开历史 session 时，Android 可能把它误标为共享“Desktop open”，即使 Desktop tab 实际属于 sidecar。

### 8.2 根因

仅用 `sessionID` 做集合，忽略 tab 的 `server` identity。因为多个 server 读相同 DB，session ID 可以相同。

### 8.3 修复

Bridge 输出四个字段：

```text
privateOpenSessionIds
sharedOpenSessionIds
privateRecentSessionId
sharedRecentSessionId
```

UI 用不同 group/badge/warning。

经验：分布式/多进程系统中的 object ID 不包含 authority identity 时，必须额外携带 Origin/server key。

## 9. Desktop migration 探索

### 9.1 Store schema

发现：

- outer files 是 JSON。
- `tabs`/`tabs.recent`/`tabs.info` 是第二层 JSON string。
- dotted keys 是 literal keys。
- server identity 被编码到 tab key。

### 9.2 安全流程

迁移脚本先在完整 Desktop user-data 副本上验证，而不是直接修改真实配置。

验证内容：

- settings default URL
- server list entry
- sidecar tab rewrite
- recent key rewrite
- info key rewrite
- timestamp backup
- original files 保留

### 9.3 Chromium route 恢复问题

只改 tab store 不够。Desktop 可能按旧 window ID 从 Chromium Local Storage 恢复 `last-active-url`，再次进入 sidecar route。

修复：迁移后生成新 window IDs，使新窗口先从 Home 启动再加载迁移后的 tabs。

### 9.4 Process detection bug

最初用 `Get-Process -Name OpenCode` 判断 Desktop 是否运行，可能把其他名为 OpenCode 的 process 或 server 混入。

修复：用 `Win32_Process.ExecutablePath` 精确匹配 Desktop executable。

### 9.5 延迟自动迁移

为了让当前回复送达后关闭 Desktop，建立一次性延迟 helper：

- Basic password 写入 DPAPI-protected temporary file。
- background PowerShell 不在 command line 带 plaintext password。
- 等待后精确停止 Desktop process。
- 调用 backup-first migration。
- 重启 Desktop。
- finally 删除 temp password file、zero BSTR。

迁移日志确认 stop → migrate → restart，temporary password file 已删除。

### 9.6 迁移后复核

迁移立即完成时，10 个旧 sidecar session tabs 全部变为 shared，default server 变为 shared。

之后用户/当前对话重新打开了 2 个 sidecar tabs，形成 mixed state：default shared、10 shared tabs、2 private tabs，recent 在 private。因此文档基线明确区分 default server 和每个 tab identity。

经验：迁移是时点操作，不是永久禁止 sidecar；实时 status 必须重新检查。

## 10. Model parity 探索

### 10.1 初始问题

Android 模型列表与 Desktop 不一致：

- 展示未连接 Provider。
- 展示过多历史 family models。
- 出现伪“默认”选项。
- 当前模型/Agent model 语义不清楚。

### 10.2 上游规则

研究 Desktop source 得到：

- Provider 必须 connected。
- persisted `hide`/`show` override 优先。
- 无效/缺失 release date 显示。
- 约六个月窗口内按 provider/family 只保留最新 release。
- popular providers 有固定顺序。
- recent model 最多 5。
- `(latest)` 是 badge/display suffix，不控制可见性。

### 10.3 修复

- Server catalog + bridge preferences 合并。
- Popup 按 Provider 分组。
- 当前 model 显示 checkmark。
- 移除伪 Default row。
- Agent 有 model 时选择 Agent 同步选择。

经验：所谓 parity 不能只复制显示名称，必须复制 availability、visibility、ordering 和 selection precedence。

## 11. IME 空白探索

### 11.1 症状

OnePlus Android 16 上，软键盘和 Composer 之间出现大块空白。

### 11.2 根因

Activity edge-to-edge + Scaffold safeDrawing/resize 已处理 IME，Composer 又叠加 `imePadding()` 和 `navigationBarsPadding()`，形成重复 inset。

### 11.3 修复与测量

- 移除 Composer 额外 IME/navigation padding。
- 保留 Scaffold `WindowInsets.safeDrawing`。
- UIAutomator 坐标测量 Composer card 和 IME top，剩约 28px 设计间距。

经验：Inset bug 必须在真机和真实 IME 上测量，preview 不足以证明。

## 12. Session list FAB 问题

旧浮动新建按钮遮挡底部 session row。修复为 top app bar action。

经验：移动 session list 是高密度监督界面，floating controls 容易覆盖长列表和系统 navigation area。

## 13. OnePlus 安装故障

### 13.1 症状

标准 `adb install -r` 流式安装在 OnePlus 返回：

```text
Failure [-99]
```

APK 本身可构建和验证，问题属于 vendor streaming installer path。

### 13.2 修复

使用：

```text
adb install --no-streaming -r
```

并固定到 `install-debug.ps1`。

经验：厂商系统的 install failure code 不一定说明 APK manifest/signature 错；用非流式路径隔离 transport 层问题。

## 14. 拔线连接探索

### 14.1 Direct LAN

默认 launcher 可绑定 `0.0.0.0` 并创建 LocalSubnet firewall rule。非管理员创建 firewall rule 曾得到 Windows Error 5 / Access denied。

Direct LAN 可用但依赖管理员、防火墙和可信网络；HTTP 没有传输加密。

### 14.2 Wireless ADB reverse

建立流程：

- USB authorization
- 获取 `wlan0` IP
- `adb tcpip 5555`
- `adb connect`
- wireless serial 上 reverse 4096/4097
- 拔 USB

真机验证：USB serial 消失，只剩 wireless transport，app process 保持，UI 显示 Live。

### 14.3 非持久性

后续 Desktop 重启/开发过程中 wireless ADB device 最终不再出现在 `adb devices`。这不是 App/server 失败；TCP ADB 和 reverse 本来就会因设备/网络/ADB 生命周期消失。

因此增加 `status.cmd`，不再把历史 phone IP 当当前事实。

经验：把“能力已验证”和“当前 transport 在线”分开记录。

## 15. Build/test 演进

最终门槛：

- 15 unit tests pass（归档阶段新增 Provider fallback 和 Bridge Origin 合同测试后）。
- Lint no issues。
- Debug assemble + debug v2 signature。
- Release R8 assemble，unsigned。
- PowerShell parser no errors。
- Node `--check` pass。
- `git diff --check` pass。

测试覆盖的重点不是 UI snapshot，而是最易回归的 wire contracts 和 parser aliases。

## 16. 文档系统设计经验

历史总结若把 live process state 和稳定架构混在一起，新对话会误用旧 IP、旧标签数和旧 listener。

最终分层：

```text
START_HERE     最短入口
BASELINE       稳定事实 + 明确标记的核对快照
ARCHITECTURE   不变量和数据流
INDEX          专题导航
Reference      protocol/desktop/security/ops/testing/UI
DECISIONS      为什么这样做
ARCHIVE        开发探索和失败经验
STATE_SNAPSHOT 历史状态证据
status.cmd     当前真实状态
```

## 17. 子 Agent 断流经验

三项大范围调研首次交给子 Agent 时都因输出过长发生 stream disconnect。处理方式：

- 使用返回的 task/session ID 续跑，而不是重新扫描。
- 将任务拆成代码地图、运维安全、协议参考三份。
- 明确限制输出行数。
- 要求 documentation-ready table/notes，省略泛化解释。

续跑后全部成功，结果交叉写入本项目文档。

经验：大范围归档任务应按知识域并行，但每个 Agent 输出必须限界；断流后优先 resume 保留已探索上下文。

## 18. 尚未解决或不应误报完成

- 没有可靠后台 push；前台 SSE 为主。
- 没有正式 release signing。
- 没有自动 VPN/TLS/WAN deployment。
- Desktop bridge 依赖 PRIVATE schema，OpenCode 升级必须重新核对。
- Desktop 可以继续打开 sidecar tabs；迁移不是强制 policy。
- 无线 ADB 不是产品连接方式，也不持久。
- Current durable per-session SSE 未用于全局 replay。
- `migrate-desktop-now.ps1` 是内部一次性 helper，会 force-stop Desktop；日常推荐显式关闭后运行正常 migration script。

## 19. 新对话最应保留的经验

1. 先运行 `status.cmd`，不要相信历史进程/IP/tab 数。
2. Server 是唯一 session truth，Bridge 永远不是。
3. 同 DB 不等于同 live process。
4. SSE 必须 subscribe-buffer-snapshot-replay。
5. 连接切换必须 generation + epoch + cancellation。
6. OpenCode 1.18.25 必须 endpoint-level compatibility。
7. Session ID 必须和 server identity 一起理解。
8. Basic 与 Bridge token 必须独立和 Origin-bound。
9. Debug convenience component 也要做 threat model。
10. Desktop PRIVATE store 写入必须 backup-first，迁移需新 window IDs。
11. 模型 parity 包括 availability、visibility、ordering、selection。
12. IME/ADB/vendor install 必须真机验证。
13. 大输出子 Agent 要限界；断流时 resume，不重新扫描。
14. 历史证据和最新基线必须分层。

## 20. 归档阶段独立审查带来的修复

文档完成后没有直接宣告结束，而是让独立 Agent 将文档与源码逐项对照。审查发现并修复：

- Bridge LAN URL 原先可能自动触发携带现有 Basic password 的重连。现在 URL 仅是未信任建议，选择后清空密码并要求用户确认。
- `/provider` 原先任何失败都会 fallback，可能隐藏 401/5xx/cancellation。现在只在 404/405 fallback。
- PRIVATE Desktop schema 原先只有文档要求版本门控，脚本未执行。现在 bridge/migration 明确拒绝非 1.18.25。
- `status.ps1` 初版有 PowerShell `$artifact:` 语法错误和 tab array 计数错误，均已修复并实跑。
- Build gate 原先不包含脚本语法。现在每次 Debug/Release build 先解析全部 PowerShell 和 Node bridge。
- TUI 文档原先把 password 放在命令行。现在使用 `attach-shared.cmd` 的 SecureString prompt + process environment。
- Portable ADB/apksigner 文档原先假设 PATH。现在增加 `adb.cmd` 和 `verify-debug-signature.cmd`。
- Model Latest flag 原先被解析但未渲染，Retry attempt/message 也未展示；均已补 UI。
- Bootstrap model selection 现按 Agent model → configured default → Desktop recent → catalog fallback。
- 新增 3 个 wire-contract tests，总数从 12 增至 15。
- Launcher 进一步改为 Bridge/OpenCode 最小环境隔离，并为两子进程增加 readiness/非零退出传播。
- Build script 增加 Gradle `$LASTEXITCODE` 检查，避免失败构建误报成功。
- Migration 对真实默认 user-data 的关闭/Home 守卫不再能被显式 `-UserData` 绕过。
- 用隔离端口验证完整 launcher 成功路径，并对端口占用、无效 JDK、Bridge 继承 Basic、Desktop 运行中迁移做负向测试。
- Launcher 运行期间主动终止 Bridge，确认另一 Server 子进程和两个隔离端口都会被清理。
- 新增 `check-docs.mjs`，验证必需入口、Markdown links 和文档中的本地路径；当前 23 份 Markdown 全部通过。
- 最终 Bridge `/health` 报告 `desktopVersion=1.18.25`；默认服务器语义为 shared，private recent 作为独立上下文提示。

经验：归档不是只写文档。将文档中的安全承诺反向审计实现，往往能发现真正的代码缺口。

## 21. 2026-09-02 Shared connection failure

现象：Desktop 显示“无法连接到 OpenCode Mobile Shared，正在自动重试”。

已验证根因：

- Desktop `1.18.26` 于 13:19:46 启动。
- Shared Server `1.18.25` 直到 13:25:18 才启动。
- Desktop renderer 在服务缺席时记录 `ClientError: Transport / Failed to fetch`。
- 密码、CORS preflight、REST 和 `/global/event` SSE 首帧均正常，不是凭据或 API shape 故障。
- Server 启动后 Desktop 建立多条 TCP 连接，OCR 确认错误页消失。

永久修复：

- 新增登录自启动并自恢复的 `runtime-worker.ps1`。
- Shared password 从 Desktop 配置导入后用 current-user DPAPI 保存。
- Managed CLI 自动与 Desktop 对齐到 `1.18.26`。
- Worker 先启动 Server、再启动 Bridge，并持续 health/restart。
- `install-runtime.ps1` 只在确认 active session 为 0 后接管已验证旧进程；验证失败时拒绝重启。
- `status.cmd` 增加 Desktop/CLI 版本、进程启动时间、启动顺序、登录启动、Worker 和 Desktop TCP transport。
- 隔离端口验证 Worker 启动、Server 自动重启和 stop request 完整清理。

## 22. 2026-09-04 Phone LAN follow-up

手机同 Wi-Fi 直连 host WLAN IP 失败的原因不是密码或手机策略：persistent runtime 设计为只监听 `127.0.0.1`，避免在 Public WLAN 暴露明文 Basic/Bearer 服务。

恢复路径：

- 安装官方 Winget Android Platform-Tools，修复临时 SDK 清理后的 ADB 缺失。
- ADB `offline` 通过 `kill-server`/`start-server` 恢复为 `device`。
- 在 USB 和无线 ADB transport 都建立 `4096/4097` reverse。
- 手机继续使用 `127.0.0.1`，不需要修改“由组织管理”的 Wi-Fi 设置。
- Desktop 自动更新到 `1.18.27` 后，隔离验证 bridge schema，再升级 managed CLI/Worker/Bridge 到相同版本。
