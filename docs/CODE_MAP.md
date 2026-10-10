# 代码地图

当前整套 Android/Desktop 共享、自启动和网络接管正在退役。旧 Host 启用入口 fail closed；当前阶段与输入保全要求见 `RETIREMENT.md`。Android 源码保留，不代表共享部署仍为当前运行方式。

## Android 源码

| 文件 | 主要职责 | 修改风险 |
|---|---|---|
| `OpenCodeApplication.kt` | Application 壳 | 低 |
| `MainActivity.kt` | Compose 入口、Activity/ViewModel 生命周期 | 中 |
| `OpenCodeModels.kt` | 网络和 UI domain model | 高，影响全链路 |
| `OpenCodeJson.kt` | legacy/current/bridge JSON 兼容解析 | 高，shape 变化需测试 |
| `OpenCodeApi.kt` | REST、SSE、Basic/Bridge auth、endpoint fallback | 高，安全和协议边界 |
| `ConnectionStore.kt` | profile、Keystore AES-GCM | 高，凭据不可降级 |
| `AppViewModel.kt` | 连接事务、SSE 屏障、reducer、轮询、操作取消 | 最高风险 |
| `OpenCodeApp.kt` | 全部 Compose UI、Permission/Question/Model/IME | 高，语义和布局 |
| `OpenCodeTheme.kt` | OC-2 palette、typography | 中 |
| `BridgeProvisionReceiver.kt` | Debug-only ADB bridge provisioning | 高，必须 DUMP 保护 |

## Android 资源

| 文件 | 说明 |
|---|---|
| `app/src/main/AndroidManifest.xml` | release permissions、backup/security、Launcher |
| `app/src/debug/AndroidManifest.xml` | debug-only DUMP-protected receiver |
| `network_security_config.xml` | USB/LAN cleartext 与 system CA |
| `data_extraction_rules.xml` | 禁止 backup/transfer |
| `values/strings.xml` | English strings |
| `values-zh-rCN/strings.xml` | 简体中文 parity |
| `themes.xml` | bootstrap window theme |
| `ic_launcher.xml` | vector icon |

## 测试

| 文件 | 覆盖 |
|---|---|
| `OpenCodeJsonTest.kt` | session/status/message/tool、permission/question、SSE envelope、model/bridge |
| `OpenCodeApiTest.kt` | Basic Auth、directory query、prompt/create、SSE、fallback、Bridge Origin |

当前总数：15 tests。

## Host scripts

| 文件 | 主要职责 |
|---|---|
| `build.ps1/.cmd` | 发现工具链，运行 debug/release gate |
| `status.ps1/.cmd` | 无敏感实时状态检查，新对话必跑 |
| `retire-opencode-mobile.ps1/.cmd` | 默认预览；startup-only 禁用未来启动；Apply 校验输入副本、精确清理与离线回退 |
| `retirement-desktop.ps1` | 只读规划当前 Desktop 定点清理，保留当前 tabs/窗口，拒绝 shared draft 自动迁移 |
| `shared-integration-guard.ps1` | 仓库/主机退役标记检查，旧入口拒绝启用 |
| `backup-desktop-inputs.py` | 只读 SQLite online backup，保全 WAL/文本/附件并验证可见输入见证 |
| `backup-desktop-inputs.tests.py` | 输入快照、附件哈希/引用与见证拒绝路径测试 |
| `start-opencode-mobile.ps1/.cmd` | 已拒绝启用；保留历史 server/bridge、token、reverse、防火墙实现 |
| `desktop-bridge.mjs` | 已拒绝启用；历史 Bearer-auth 只读 Desktop metadata API |
| `install-debug.ps1/.cmd` | 已拒绝启用；历史安装、reverse、DUMP provisioning |
| `enable-wireless-adb.ps1/.cmd` | 已拒绝启用；历史 USB → TCP ADB 和 reverse |
| `configure-desktop-shared.ps1/.cmd` | 已拒绝启用；历史 Desktop 迁移、新 window IDs |
| `migrate-desktop-now.ps1` | 已拒绝启用；历史延迟停止/迁移/重启 |
| `configure-tailnet-mobile.ps1/.cmd` | 已拒绝启用；历史 Tailscale Mobile 网络配置 |
| `runtime-worker.ps1` | 未来启动拒绝启用；历史 shared Server/Bridge 监督 |
| `install-runtime.ps1/.cmd` | 已拒绝启用；历史 DPAPI、managed CLI、HKCU Run 接管 |
| `stop-runtime.ps1/.cmd` | stop request 优雅停止 Worker 和子进程 |
| `attach-shared.ps1/.cmd` | 已拒绝启用；历史安全凭据 TUI attach |
| `adb.ps1/.cmd` | 发现便携 Android SDK 后转发 ADB 参数 |
| `verify-debug-signature.ps1/.cmd` | 发现便携 JDK/SDK 并验证 Debug APK v2 签名 |
| `check-docs.mjs` | 验证必需文档、Markdown links 和 backtick 本地路径 |

## Build configuration

| 文件 | 说明 |
|---|---|
| `settings.gradle.kts` | repositories 和 `:app` |
| `build.gradle.kts` | AGP/Kotlin/Compose plugin versions |
| `app/build.gradle.kts` | SDK、build types、dependencies、tests |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 8.11.1 + SHA-256 |
| `gradle.properties` | JVM、UTF-8、AndroidX、non-transitive R |
| `.gitignore` | 工具、产物、Desktop/DB/secret 防入库 |

## 数据流速查

```text
ConnectionStore -> AppViewModel.connect -> OpenCodeApi
                                      |-> SSE buffer
                                      |-> REST snapshot
                                      `-> atomic apply/replay

OpenCodeApi events -> AppViewModel reducer -> StateFlow -> Compose

Desktop stores -> desktop-bridge -> Bearer request -> AppViewModel enrich
```

## 修改前检查

| 修改区域 | 必须阅读 |
|---|---|
| `AppViewModel.kt` | `ARCHITECTURE.md` SSE/连接章节 |
| `OpenCodeApi.kt`/`OpenCodeJson.kt` | `PROTOCOL_COMPATIBILITY.md` |
| Bridge/迁移脚本 | `DESKTOP_INTEGRATION.md`, `SECURITY.md` |
| Composer/模型 UI | `UI_MODEL_PARITY.md` |
| 网络和脚本 | `OPERATIONS.md`, `TROUBLESHOOTING.md` |
