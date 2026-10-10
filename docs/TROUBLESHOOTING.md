# 故障排查

第一步永远是：

```powershell
.\scripts\status.cmd
```

## 当前退役诊断

主机退役清理已完成，标记为 `complete`；2026-10-06 15:44 状态显示 4096/4097 均未监听、Worker 未运行、共享默认绑定为空、共享连接为 0。旧启用入口拒绝执行属于预期行为。原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留；任务空闲后正常关闭唯一窗口执行，未强制终止进程。全部 139 个非空输入的逐条 UI、ADB reverse、防火墙和 Android SDK 构建缺口仍保留。完整阶段见 `RETIREMENT.md`。

| 症状 | 当前处理 |
|---|---|
| 退役后 4096/4097 不再监听 | 预期结果，使用 Desktop 自身的 local/sidecar，不安装 runtime |
| Desktop 对旧 4096 自动重试 | 先保全并验证真实输入，再在任务安全结束/正常关闭后执行 retire Apply；不通过共享自启动补救 |
| retire 提示 Desktop 仍打开 | 不强制关闭；先核验所有未发送文本/附件及当前任务 |
| Apply 缺少/拒绝 input backup | 重新创建外部一致草稿快照并精确验证当前可见输入；tab metadata 不能替代 |
| server active 或响应未知 | 保持原进程，确认任务状态；不能迁移执行内存态 |
| shared tab/draft 或未知 PRIVATE shape | 保持当前文件，逐项核对；不自动删草稿或套用旧版本迁移 |
| Mobile 网络归属不明 | 保留映射，核对精确 owner/handler；不全局 reset Tailscale、kill Node 或清空 ADB reverse |
| 旧安装/迁移/bridge 入口拒绝 | 退役保护生效，不删除 marker 绕过 |

原两窗和标签已恢复，当前剩余的是明确保留的验收缺口。独立副本完整、恢复前后草稿哈希一致和数据库见证文本仍在，均不能代替全部 139 个非空输入及附件的逐条 UI 验收；不要强制关闭仍在执行任务的 Desktop。

## 以下为历史共享故障参考

下面的快速矩阵和第 1～6 节保留退役前排错记录。涉及重新安装 runtime、启动 stack、迁移、bridge provisioning 或无线 reverse 的“修复”已失效，旧入口拒绝启用；不得用于当前退役。SSE/Android 源码和脚本语法检查仍可参考相应章节。

## 快速矩阵

| 症状 | 典型根因 | 修复 |
|---|---|---|
| `Port 4096: not listening` | persistent runtime 未安装或未运行 | 运行 `install-runtime.cmd`，再检查 `status.cmd` |
| `Port 4097: not listening` | bridge 未启动、Node error、端口冲突 | 检查 launcher 和 4097 owner，重新启动 stack |
| Android 401/403 | Basic username/password 不匹配 | 重新输入 shared server credentials |
| Desktop “无法连接/正在自动重试” | Desktop 比 Shared Server 先启动，renderer 保留首次 transport failure | 检查 `Startup order` 和 `Desktop shared transport`；安装 persistent runtime |
| Server 正常但 Desktop 元数据为空 | 4097 reverse 缺失、token mismatch、bridge 退出 | 检查 listener/reverse，重新 provisioning |
| Desktop 显示私有 | 当前/最近 tab server 是 `sidecar` | 在 shared server 下打开对应 session，关闭私有 tab |
| 历史相同但运行态不同步 | Desktop/Android 不在同一 process | 将 Desktop 连接 shared server；运行任务不能迁移 |
| 新会话在私有 sidecar 出现 | Desktop 仍选择 local server | 切换 server selector/default，检查 `status.cmd` |
| Android 一直 reconnect | SSE path/network 中断 | 检查 `/global/event`、reverse、Wi-Fi、server process |
| 初次连接约 60 秒超时 | 未收到 connected 或 snapshot 失败 | 检查 auth、SSE content type 和 sessions endpoint |
| 无线 ADB 突然消失 | 手机/电脑重启、切网、adbd 重启 | 插 USB，重跑 `enable-wireless-adb.cmd` |
| `device not found` | 使用了历史 Wi-Fi IP | `.\scripts\adb.cmd devices -l` 重新发现，不要写死 IP |
| 同 Wi-Fi 直接连接失败 | runtime 仅监听 `127.0.0.1`，不是手机或密码问题 | 插 USB 后运行 `enable-wireless-adb.cmd`，手机继续使用 loopback URL |
| `adb` 未找到 | SDK/临时工具被清理或路径变化 | 安装 `Google.PlatformTools`；脚本支持 Winget 官方 Platform-Tools 路径 |
| `Failure [-99]` 安装 | OnePlus streaming install 兼容问题 | 使用 `install-debug.cmd` 的 `--no-streaming` |
| 键盘上方大空白 | 双重 IME/navigation inset | Composer 不加 ime/navigation padding；只由 scaffold 处理 |
| 运行 child 不在列表 | active ID 是 subagent | 检查 parent chain projection 和 2 秒 poll |
| 模型数量多于 Desktop | 未应用 connected/show-hide/family 筛选 | 检查 bridge model preferences 和 release date 规则 |
| 模型弹窗出现伪默认项 | 把空 model 当 option | 不创建“Default” model；当前模型通过选中态表达 |
| Browser 再次问密码 | Custom Tab 独立浏览器 Origin | 输入 shared Basic credentials，属预期行为 |

## 1. Port collision / orphan

```powershell
$listeners = Get-NetTCPConnection -State Listen -LocalPort 4096,4097
$listeners
Get-CimInstance Win32_Process | Where-Object {
    $_.ProcessId -in $listeners.OwningProcess
} | Select-Object ProcessId,ExecutablePath,CommandLine
```

只停止已经确认是旧 `opencode serve/web` 或 `desktop-bridge.mjs` 的 process。不要批量 kill 所有 Node/OpenCode；Desktop 和当前 Agent 可能同时运行。

## 1.1 Desktop starts before server

运行 `status.cmd`。若看到：

```text
Startup order: desktop-before-server
Login startup: False
```

执行：

```powershell
.\scripts\install-runtime.cmd -RestartExisting
```

安装器无法验证 active sessions 时会 fail closed，不会停止未知或正在执行的 Server。

若安装后仍看到 `Startup order: desktop-before-server`，但同时看到：

```text
Login startup: True
Runtime worker: running=True
Desktop shared transport: connected
```

表示这是本次受控重启后的历史时间顺序，当前 transport 已恢复。下一次用户登录时，HKCU Run会在 Desktop 重连窗口内持续提供并监督 Server。

## 2. ADB authorization

```powershell
.\scripts\adb.cmd devices -l
```

状态：

- `device`：可用
- `unauthorized`：解锁手机并接受 RSA prompt
- `offline`：重插，或依次运行 `.\scripts\adb.cmd kill-server` 和 `.\scripts\adb.cmd start-server`
- 无设备：USB cable/driver/debugging 或 wireless transport 丢失

若设备显示 `offline`，依次运行：

```powershell
.\scripts\adb.cmd kill-server
.\scripts\adb.cmd start-server
.\scripts\adb.cmd devices -l
```

手机重新显示 `device` 后，再重新建立两个 reverse。

Windows PowerShell 5.1 不支持 `&&`；顺序命令用分号或 `if ($?)`。

## 3. Reverse exists but app cannot connect

逐层检查：

1. Host listener：`127.0.0.1:4096/4097`。
2. ADB device 是 `device`。
3. `.\scripts\adb.cmd reverse --list` 有两个 mapping。
4. Android profile URL 是 `127.0.0.1`，不是旧 LAN IP。
5. Basic auth 正确。
6. bridge token 已 provision。

Reverse 属于具体 ADB transport。USB transport 的 mapping 不会自动迁移到 wireless transport，必须在 wireless serial 上重新创建。

## 4. Bridge 401

Bridge 401 表示 token mismatch，不是 Basic password 错误。

修复：

1. 确认 Host token file 存在。
2. 重新运行 `install-debug.cmd` 或 shared launcher provisioning。
3. 若轮换 token，所有 Debug device 都需重新 provision。

不要用 OpenCode password 替代 token。

## 5. Desktop migration refusal

错误：

```text
Open Desktop Home first, then close OpenCode Desktop
```

原因：精确 Desktop executable 仍在运行。

处理：完成/停止 Agent、Home、关闭 Desktop，再运行 `-ConfirmHome`。

若 health/auth 失败，migration 在写文件前停止。

## 6. Partial migration / mixed tabs

Default server shared 但还有 private tabs 是允许的 mixed state，不一定是迁移失败。

诊断：

```powershell
.\scripts\status.cmd
```

若旧 sidecar tabs 在迁移后立即恢复：

- 确认新 `windowIds` 已写入。
- 检查 Chromium `last-active-url` 是否仍绑定旧 window ID。
- 确认启动的是迁移后的 Desktop user-data。

若状态文件损坏，关闭 Desktop 并恢复 timestamped backup。

## 7. SSE race symptoms

症状：

- 刚连接时 Busy 短暂消失
- 新消息被旧列表覆盖
- reconnect 后 Todo/permission 回退

检查是否破坏：

- subscribe before snapshot
- buffer during snapshot
- `stateSync` atomic apply/replay
- `connectionGeneration`
- `connectionEpoch`
- old job cancel/join

不要通过增加任意 delay 修复；这是顺序/隔离问题。

## 8. Cross-project partial failures

某个 project permission/status endpoint 暂时失败时，只应保留该目录旧状态，不能清空所有目录。401/403 必须整体断开。

检查 `loadSnapshot` 的 `Semaphore(6)` 和 per-directory merge。

## 9. UIAutomator / screenshot

真机 UI 验证：

```powershell
.\scripts\adb.cmd shell uiautomator dump /sdcard/window.xml
.\scripts\adb.cmd pull /sdcard/window.xml "$env:TEMP\opencode\window.xml"
$adb = @("$env:ANDROID_HOME\platform-tools\adb.exe", "$env:ANDROID_SDK_ROOT\platform-tools\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", "$env:TEMP\opencode\android-sdk\platform-tools\adb.exe") | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
$process = Start-Process -FilePath $adb -ArgumentList @("exec-out", "screencap", "-p") -RedirectStandardOutput "$env:TEMP\opencode\screen.png" -NoNewWindow -Wait -PassThru
if ($process.ExitCode -ne 0) { throw "Screenshot failed." }
```

若命令永远 `waiting for device`，先取消并重新检查 serial；不要在已离线的历史 IP 上等待。

## 10. Script syntax

PowerShell：

```powershell
$failed = $false
Get-ChildItem .\scripts -Filter *.ps1 | ForEach-Object {
  $tokens = $null
  $errors = $null
  [System.Management.Automation.Language.Parser]::ParseFile(
    $_.FullName,
    [ref]$tokens,
    [ref]$errors
  ) | Out-Null
  if ($errors.Count) { $errors; $failed = $true }
}
if ($failed) { exit 1 }
```

Node：

```powershell
node --check .\scripts\desktop-bridge.mjs
```
