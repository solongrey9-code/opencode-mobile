# 状态快照：2026-09-01

这是一次脱敏历史快照，不保证当前仍成立。运行 `scripts/status.cmd` 获取实时状态。

## Repository

- Workspace：Windows 本地 Git repository。
- Branch：`master`。
- 核对时尚无 commit，项目文件均为 untracked/worktree 内容。
- 未执行 commit、push 或 PR。

## Versions

- OpenCode CLI/Desktop：`1.18.25`。
- Android app：`0.1.0`。
- Device class：OnePlus PJZ110，Android 16。
- Gradle `8.11.1`，AGP `8.9.2`，Kotlin `2.1.20`，SDK 35，Java 17。

## Shared services

- Shared OpenCode Server 正在 loopback `4096` 监听。
- Desktop metadata bridge 正在 loopback `4097` 监听。
- `/global/health` 返回 healthy，version `1.18.25`。
- `/experimental/session` 核对到 107 root sessions、18 directories。
- `/api/session/active` 核对时为 0 active sessions。

不记录 Basic password 或 bridge token。

## Desktop

- Desktop 正在运行。
- `defaultServerUrl` 是 shared loopback URL。
- 1 个 window store。
- 12 个 session tabs：10 shared、2 sidecar。
- 最近 tab 属于 sidecar，因此 bridge 核对时报告 `desktopUsesSharedServer=false`。
- Migration temporary password file 已删除。
- Migration log 显示 delayed stop、migration complete、Desktop restart requested。
- Timestamped Desktop backup 保留在 Desktop user-data，不在 repository。

## Android connectivity

本轮较早阶段已经真机验证：

- USB install/reverse。
- Wireless ADB transport。
- 拔 USB 后 4096/4097 reverse 和 App Live。

本快照最终核对时 `adb devices` 为空，说明 wireless ADB transport 已离线。Shared server/Desktop 本身仍运行。这证明无线 ADB 在线状态不能写成稳定基线。

## Build artifacts

- Debug APK 存在，约 18.7 MB。
- Release unsigned APK 存在，约 1.9 MB。
- 当时为 12 tests，0 failures，0 errors；归档安全复审后基线增至 15。
- Lint：`No issues found.`

## Verified UI behavior

- Running sessions group + executing badge。
- Child Agent active → root projection。
- Shared/private Desktop tab labels。
- Connected-provider model popup and current checkmark。
- No fake Default model row。
- Composer/IME gap reduced to design spacing。
- Top-bar create action，不遮挡 session list。
- Wi-Fi ADB unplugged path 曾验证成功。

## Important interpretation

- Desktop 默认 shared 不表示所有 tabs shared。
- Shared history 不表示 sidecar execution shared。
- ADB disconnected 不表示 shared server failed。
- 该快照不应覆盖未来 `status.cmd` 输出。

## 2026-09-02 归档后补记

- 独立审查后修复 Bridge LAN credential Origin 风险和 Provider fallback error policy。
- Bridge/migration 增加 Desktop 1.18.25 版本门控。
- Unit test 基线增加到 15。
- Debug/Release/R8、Lint、PowerShell/Node syntax、APK v2 signature 重新验证通过。
- 最终核对时 Desktop 已关闭、ADB 设备为 0；shared server 与新版 bridge 仍在线。
- 最终 Bridge 报告 Desktop version `1.18.25`、default shared；10 个 shared tabs 和 2 个 private tabs 保持分离。
- 文档检查器验证 23 份 Markdown；Launcher 隔离端口成功/失败生命周期测试完成。
- 最后一次实时状态检查时 Desktop 已重新运行，default shared，仍有 10 shared + 2 private tabs；ADB device 为 0。
- 现有 `4096` process 早于 launcher credential-isolation hardening，按“不迁移运行中 process”原则留到本轮结束后的正常重启。

## 2026-09-02 Connection incident follow-up

- Desktop 已更新到 `1.18.26`，Shared CLI 原为 `1.18.25`。
- “无法连接/自动重试”的直接原因是 Desktop 在 Shared Server 启动前约 5 分钟开始连接。
- 安装 persistent runtime 后，Managed CLI、Server、Desktop 和 Bridge 均为 `1.18.26`。
- 当前用户登录启动已安装，Worker监督 `4096/4097` 并通过隔离端口自动恢复测试。
- 页面 OCR 不再包含连接错误，Desktop 有 6 条到 `4096` 的已建立连接。
- 最终核对阶段 tab identity 持续随用户切换，`status.cmd` 曾观察到 6 shared + 5 private；ADB 当前仍无在线设备。

## 2026-09-04 Connectivity follow-up

- Desktop 自动更新至 `1.18.27`，Managed runtime/CLI/Bridge 同步升级至 `1.18.27`。
- 持久 runtime 默认仅监听 loopback；同 Wi-Fi 手机直连 host WLAN IP 因此失败是预期安全边界。
- 重新安装官方 Winget Android Platform-Tools 后，USB ADB 从 `offline` 恢复为 `device`。
- `4096/4097` 已在 USB 和无线 ADB `:5555` transport 上建立 reverse。
- 手机 App 前台运行，两个 phone-loopback TCP port 可达；无需解除“由组织管理”的网络策略。
