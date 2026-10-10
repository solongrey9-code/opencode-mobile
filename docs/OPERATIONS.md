# 运维手册

所有命令默认从仓库根目录执行。

## 当前运维：退役与独立 Desktop

Android/Desktop 共享与自启动的主机清理已完成，标记为 `complete`；精确 Mobile Worker/managed Server、共享默认绑定和指定 Tailscale 映射已清理。旧启动、安装、迁移和连接入口拒绝启用。原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留；任务空闲后正常关闭唯一窗口执行，未强制终止进程。草稿冷备份与恢复启动后的快照哈希一致；全部 139 个非空输入的逐条 UI、ADB reverse、防火墙和 Android SDK 构建缺口仍保留，见 `RETIREMENT.md`。

当前允许的操作：

```powershell
.\scripts\status.cmd
.\scripts\retire-opencode-mobile.cmd
.\scripts\build.cmd
.\scripts\build.cmd -Release
```

默认 retire 只预览；`-DisableStartupOnly` 保持现有进程/输入；完整 `-Apply -InputBackupDirectory` 必须先核验真实输入数据库和附件、完成任务并正常关闭 Desktop。禁止把 tab metadata 备份当作输入保全。完整门槛、精确清理范围和回退见 `RETIREMENT.md`。

## 以下为历史共享运维参考

下面第 1～13 节保留退役前的运维说明。构建和只读检查仍适用；共享安装、启动、迁移、provisioning、网络和 attach 命令均不是当前修复方案，其入口拒绝启用。缺少 4096/4097 listener 不能据此重新安装 runtime。

## 1. 首次检查

```powershell
.\scripts\status.cmd
```

重点确认：

- `4096` shared server 是否已有 listener。
- `4097` bridge 是否已有 listener。
- Desktop 当前 shared/private tab 数量。
- ADB 设备和 reverse 状态。
- 最近构建、测试、Lint 结果。

不要重复启动占用相同端口的第二个 server/bridge。

## 2. 构建

要求：JDK 17、Android SDK 35。脚本也会发现 `%TEMP%\opencode` 下的便携工具。

Debug gate：

```powershell
.\scripts\build.cmd
```

等价任务：

```text
testDebugUnitTest
lintDebug
assembleDebug
```

Release gate：

```powershell
.\scripts\build.cmd -Release
```

等价任务：

```text
testDebugUnitTest
lintDebug
assembleRelease
```

Release 当前 R8-minified 但 unsigned。

## 3. 启动 shared stack

### Recommended persistent runtime

首次安装或 Desktop 更新后执行：

```powershell
.\scripts\install-runtime.cmd -RestartExisting
```

安装器会：

- 确认现有 Shared Server 没有 active session；无法确认时拒绝接管。
- 保存 current-user DPAPI server password。
- 安装与 Desktop 同版本的 managed CLI。
- 写入当前用户登录启动项。
- 启动监督 Worker并验证 health。

Desktop 自动更新后，如果 Managed CLI 或安装副本需要更新，运行中的安装器会要求 `-RestartExisting`。它只在确认 active shared session 为 0 后优雅停止 Worker并更新；无法确认时拒绝操作。

停止当前 Worker但保留登录启动：

```powershell
.\scripts\stop-runtime.cmd
```

默认 runtime 绑定 `127.0.0.1`。手机通过 USB reverse 或无线 ADB reverse 使用这个地址，不需要将 Windows WLAN 或“组织管理”的网络策略改为 Private。

### Temporary loopback / ADB reverse

仅用于临时本机 Desktop + Android 调试：

```powershell
.\scripts\start-opencode-mobile.cmd -UsbOnly
```

脚本询问 Basic password，启动：

```text
OpenCode Server http://127.0.0.1:4096
Desktop Bridge http://127.0.0.1:4097
```

并对所有已授权 ADB device 配置两个 reverse。

启动器先检查端口空闲，然后分别启动并 health-check Bridge 和 OpenCode。Bridge 子进程只继承 Bearer token，OpenCode 子进程只继承 Basic credentials；readiness、Server 退出或 Bridge 退出都会使脚本以非零状态失败并清理另一子进程。

### Trusted LAN

```powershell
.\scripts\start-opencode-mobile.cmd
```

绑定 `0.0.0.0` 并尝试创建：

```text
Firewall rule: OpenCode Mobile LAN
RemoteAddress: LocalSubnet
Ports: 4096,4097
```

创建规则需要管理员权限。失败只警告，process 仍会监听所有接口，因此必须主动检查防火墙。

### Serve mode

默认运行 `opencode web`。只需要 server 时：

```powershell
.\scripts\start-opencode-mobile.cmd -UsbOnly -ServeOnly
```

## 4. Android profile

USB/wireless ADB reverse：

```text
Server URL: http://127.0.0.1:4096
Bridge URL: http://127.0.0.1:4097
Username: opencode
Password: <shared server password>
Directory: blank for all projects, or absolute path
```

Bridge token由 Debug provisioning 自动写入，不应粘贴到 shell history 或文档。

## 5. Debug 安装

```powershell
.\scripts\install-debug.cmd
```

脚本：

1. 选择首个 USB device，否则首个 ready device。
2. `adb install --no-streaming -r`。
3. reverse `4096/4097`。
4. 从 DPAPI token 文件解密 bridge token。
5. 用 DUMP-protected receiver provision Debug app。
6. force-stop 并 launch。

为什么 `--no-streaming`：OnePlus/OxygenOS 实测流式 install 返回 `Failure [-99]`，非流式成功。

## 6. USB reverse

检查：

```powershell
.\scripts\adb.cmd devices -l
.\scripts\adb.cmd reverse --list
```

期望两个映射：

```text
tcp:4096 -> tcp:4096
tcp:4097 -> tcp:4097
```

刷新：

```powershell
.\scripts\adb.cmd reverse tcp:4096 tcp:4096
.\scripts\adb.cmd reverse tcp:4097 tcp:4097
```

删除：

```powershell
.\scripts\adb.cmd reverse --remove tcp:4096
.\scripts\adb.cmd reverse --remove tcp:4097
```

## 7. 无线 ADB

前提：电脑和手机在同一可信 Wi-Fi；初次转换时只有一个已授权 USB device。

```powershell
.\scripts\enable-wireless-adb.cmd
```

脚本：

1. 读取 phone `wlan0` IPv4。
2. `adb tcpip 5555`。
3. `adb connect PHONE_IP:5555`。
4. 对 wireless transport 建立 `4096/4097` reverse。

如果 wireless device 已在线，脚本只刷新两个 reverse。

无线 ADB 会因以下情况消失：

- 手机重启
- 电脑/ADB server 重启
- `adbd` 重启
- Wi-Fi 切换或 DHCP IP 改变
- 厂商系统关闭 TCP debugging

同 Wi-Fi 但手机直接填写电脑 WLAN IP 失败时，先不要尝试修改“由组织管理”的网络设置。默认 runtime 仅绑定 loopback；插 USB 后运行：

```powershell
.\scripts\enable-wireless-adb.cmd
```

它会让无线 transport 保持 `4096/4097` reverse，手机仍填写 `http://127.0.0.1:4096`。

出现后重新插 USB 并运行脚本。不要把历史 IP 写死到自动化。

关闭 TCP ADB：

```powershell
$serial = & ".\scripts\adb.cmd" devices | Select-String '^\d+\.\d+\.\d+\.\d+:5555\s+device' | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1
if ($serial) { .\scripts\adb.cmd -s $serial usb }
```

## 8. Desktop 迁移

前提：Agent 已停止、Desktop Home、Desktop 已关闭。

```powershell
.\scripts\configure-desktop-shared.cmd -ConfirmHome
```

输入与 shared server 相同的 Basic password。脚本会 health/auth 验证、备份、写 server entry、迁移 sidecar session tabs、创建新 window IDs。

迁移后手动启动 Desktop，再运行：

```powershell
.\scripts\status.cmd
```

检查 shared/private 数量。用户后续仍可能重新打开 sidecar tab；默认服务器为 shared 不等于所有 tab 都 shared。

## 9. Migration rollback

1. 关闭 Desktop。
2. 找到最新：

```text
%APPDATA%\ai.opencode.desktop\mobile-shared-backup-<timestamp>
```

3. 将 `opencode.settings`、`opencode.global.dat`、`opencode.window.*.dat` 复制回 user-data。
4. 启动 Desktop。

不要删除 OpenCode DB；migration 不修改它。

## 10. Bridge token rotation

1. 正常停止 shared launcher。
2. 删除 Host DPAPI token file：

```text
%LOCALAPPDATA%\OpenCodeMobile\bridge-token.dat
```

3. 再次启动 shared launcher，生成新 token。
4. 连接 Debug device 并运行 `install-debug.cmd` 或 launcher provisioning。

旧 Android token 会立即失效，只影响 Desktop metadata，不影响 OpenCode REST/SSE。

## 11. Normal shutdown

在 launcher terminal 按 `Ctrl+C`。`finally` 会：

- stop bridge child
- restore prior environment variables
- clear in-process secret references

不会：

- 删除 ADB reverse
- 关闭 wireless ADB
- 删除 firewall rule
- 删除 DPAPI token

若直接杀 terminal，child process 可能 orphan。之后用 `status.cmd` 和 listener owner 验证，再只停止确认过的旧进程。

## 12. Attach and Web

TUI：

```powershell
.\scripts\attach-shared.cmd
```

Android 的完整 Web UI 使用系统 Custom Tab。浏览器可能再次询问同一组 Basic credentials，这是预期 Origin auth 行为。

## 13. WAN 演进

优先级：

1. Tailscale/WireGuard 私有 VPN。
2. HTTPS reverse proxy + 有效证书 + access control。
3. 需要可靠后台通知时增加 relay + FCM。

禁止直接公网暴露明文 `4096/4097`。
