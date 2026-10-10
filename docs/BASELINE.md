# 最新基线

文档更新：2026-10-06。Android/Desktop 共享、自启动的主机退役清理已完成，Desktop 使用自身的 local/sidecar；原两窗及标签已恢复，全部输入的逐条 UI 验收仍有缺口。运行状态必须执行 `scripts/status.cmd` 核对。

## 当前退役阶段

- 主机标记为 `complete`；登录启动、精确 Mobile Worker/managed Server、共享默认绑定及指定 server entry 已清理，managed runtime 和凭据已离线归档，旧复活入口拒绝启用。
- Tailscale 4096/4097 映射已撤销，其他 443/8443 映射保留。2026-10-06 15:44 状态显示 4096/4097 均未监听、Worker 未运行、共享连接为 0，ADB ready device 为 0。
- Desktop `1.18.34` 原两窗已恢复，分别有 11 和 12 个 sidecar 标签，共 23 个；在任务空闲后正常关闭唯一窗口执行，未强制终止进程，两原窗口 UUID、现有窗口及今天新增标签保留。全部输入的逐条 UI、设备 reverse 和防火墙残留尚未验收。
- 2026-10-06 恢复前冷备份与恢复启动后的草稿快照哈希完全一致，包含 654 documents、139 个非空 text parts、0 个附件 blobs，14 字符数据库见证文本仍在；2026-10-04 副本中的 4 个附件 blobs 保留。独立副本完整不能代替全部 139 个非空输入的逐条 UI 验收，也不是当前可见输入的验证结果。
- `-Apply` 必须提供已验证输入备份，再确认 Desktop 已正常关闭、共享任务为 0 和资源归属。
- 保留数据库、当前标签、窗口 IDs、真实输入数据库和离线回退材料；不恢复旧日期的整个 Desktop 快照来退役。
- Android 源码和协议兼容实现保留。以下能力及旧共享拓扑是实现/历史参考，不能用于恢复共享部署。

完整流程及验收边界见 `RETIREMENT.md`。上述数量是本次核对快照，不是持续不变的事实。

本轮 Desktop 规划 15 项、输入备份专项 4 项共 19 项通过，PowerShell/Node 语法与文档检查通过。Debug、Release 构建均已执行，但因 `SDK location not found` 失败，本轮没有新的 Android 单元测试、Lint 或 APK 验证结果。

## 产品基线

- Android 包名：`ai.opencode.mobile`
- 版本：`0.1.0`，versionCode `1`
- minSdk `26`，target/compile SDK `35`
- Kotlin `2.1.20`，AGP `8.9.2`，Gradle `8.11.1`
- Java/JVM `17`
- UI：Jetpack Compose + Material 3，自定义 OC-2 主题
- 网络：OkHttp `4.12.0` + `okhttp-sse`
- JSON：`kotlinx-serialization-json` lenient/manual parser

## 保留的实现能力

- 跨项目会话列表、搜索、新建、重命名、删除、分享。
- Legacy 和 current OpenCode 消息/事件渲染。
- Markdown、代码块、Reasoning、工具、错误、附件。
- Agent/模型选择、Prompt、Stop、Revert。
- Todo、Diff、Permission、Question。
- SSE 实时同步、重连、快照屏障、事件缓冲和旧结果隔离。
- 运行会话置顶；后台子 Agent 活动投影到根会话。
- Desktop 共享标签、私有 sidecar 标签和最近标签分开展示。
- Desktop 模型 connected-provider、show/hide、Family、排序 parity。
- Android Keystore 加密 Basic 密码和 bridge token。
- Custom Tab 打开官方完整 Web UI。
- 历史 USB reverse、无线 ADB、Desktop 迁移、只读 bridge 脚本；入口已拒绝启用。
- 历史登录后台运行时、DPAPI 共享密码和 Server/Bridge 自动恢复；未来入口已拒绝启用。
- 历史 Managed CLI 版本对齐；不再自动安装或跟随 Desktop 更新。
- Bridge 提供的 LAN 地址只作为未信任建议；选择地址会清空 Basic password，不会自动重连。
- Desktop bridge 和 migration 对 PRIVATE schema 固定检查已审计的 `1.18.25/1.18.26/1.18.27`。

## 构建质量基线

当前期望：

- 15 个 JVM 单元测试通过。
- Android Lint：`No issues found.`
- Debug APK 生成并使用 Android Debug 证书 v2 签名。
- Release APK 生成、R8 minify，未签名。
- Node 和 PowerShell 脚本语法通过。

产物：

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

## 历史共享拓扑（已退役入口）

```text
OpenCode DB
    |
shared opencode server :4096  <-- session/message/execution source of truth
    |-- Desktop HTTP server connection
    |-- Android REST + SSE
    `-- optional attach/Web

Desktop electron-store
    |
read-only metadata bridge :4097
    `-- Android Bearer polling every 2 seconds
```

Bridge 不是 session source，只返回：

- private/shared open session IDs
- private/shared recent session IDs
- Desktop model visibility/MRU 偏好
- 可选 LAN 地址

## 2026-09-02 连接故障修复后快照

该部分是核对时事实，不保证持续不变：

- Shared server：`127.0.0.1:4096`，OpenCode `1.18.27`。
- Bridge：`127.0.0.1:4097`。
- 数据库查询到 107 个 root session、18 个目录、0 个 active session。
- Desktop 默认服务器为 `http://127.0.0.1:4096`。
- Desktop 单窗口存在 11 个 session tab；shared/private 数会随用户切换标签实时变化，最终以 `status.cmd` 为准。
- Bridge 报告默认服务器已 shared；private tabs 仍作为独立上下文显示，不代表默认服务器失效。
- Desktop 最终核对时已运行；磁盘 tab state 和 bridge state保持一致。
- 手机的无线 ADB 能力已实测，但核对时 ADB device 为 0。
- Debug/Release 门槛已在 15 tests 基线上重新运行成功。
- Desktop、Managed CLI 和 Bridge schema gate 当前均为 `1.18.27`。
- 文档自检覆盖 23 份 Markdown 并通过。
- Launcher 在隔离端口验证了成功启动/清理、端口占用失败、Bridge 运行时退出清理和非零错误路径。
- 登录自启动已安装在当前用户 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。
- Worker 正在监督 `4096/4097`；隔离端口已验证 Server 自动恢复和 stop request 完整清理。
- Desktop 已建立到 Shared Server 的连接；REST、CORS、SSE 首帧与页面 OCR 都已验证。
- 本次“无法连接”根因：Desktop 于 13:19 启动，Shared Server 到 13:25 才启动。持久运行时消除这个启动顺序窗口。
- 最终状态仍可能显示 `desktop-before-server`，因为修复安装时对 Shared Server 做过受控重启；关键验收是登录启动已安装、Worker在线且 `Desktop shared transport: connected`。
- USB 与无线 ADB reverse 已在当前 Wi-Fi 上恢复；手机继续使用 loopback URL，避免修改受管理的网络策略或开放 LAN listener。

以上全部是退役前的历史快照。当前不应通过安装 runtime 或迁移 Desktop 恢复该拓扑；应按 `RETIREMENT.md` 检查和清理。

## 当前已知边界

- 前台 SSE 已实现；没有 FCM/APNs 式可靠后台推送。
- 直连 HTTP 只适合 USB reverse 或可信局域网。
- Release APK 尚未配置正式签名。
- Desktop bridge 依赖私有 electron-store schema，必须版本门控和容错。
- Desktop 的 HTTP server entry 会在其用户目录 JSON 中存储 Basic 凭据；依赖 OS 用户账户保护。
- 无线 ADB 不是持久连接，手机/电脑重启、切 Wi-Fi 或 adbd 重启后需重新建立。
- 15-test gate 尚未自动覆盖 ViewModel 竞态调度、Keystore instrumentation、Compose UI、Desktop migration 和 host process lifecycle；对应改动必须执行 `TESTING.md` 的专项验收。
