# 测试与验收

## 当前退役专项验收

主机退役清理已完成，标记为 `complete`；原两窗 UUID 和共 23 个 sidecar 标签已实际恢复，现有窗口及今天新增标签保留。任务空闲后正常关闭唯一窗口执行，未强制终止进程；草稿冷备份与恢复启动后的快照 SHA-256 完全一致，包含 654 documents、139 个非空 text parts、0 个附件 blobs。14 字符数据库见证文本仍在，不代表当前可见输入验证。全部 139 个非空输入及附件尚未逐条通过 UI 验收，ADB reverse、防火墙残留缺口仍保留。真实阶段及输入快照见 `RETIREMENT.md`。

本轮 Desktop 纯规划 fixture 15 项、输入备份专项 4 项共 19 项通过，PowerShell/Node 语法与文档检查通过。Debug、Release 两条构建均已执行，但因 `SDK location not found` 失败，未取得本轮新的 Android 单元测试、Lint、APK 或签名验证结果；旧产物不能替代当前质量门槛。

| 场景 | 必需证据 |
|---|---|
| 默认 preview | 不写 Desktop/启动/网络状态，不停止进程；报告资源计划 |
| `-DisableStartupOnly` | 登录项移除、未来入口拒绝启用；当前 Desktop/Worker/Server 和输入保持 |
| 输入一致快照 | SQLite read-only online backup 含 WAL、integrity check、附件 SHA-256/引用与可读 JSON 回读 |
| 当前可见输入 | 与快照精确吻合；不在输出中打印内容，不能仅用 draft/tab metadata 证明 |
| Apply 拒绝条件 | Desktop 打开、未验证输入副本、共享任务活动/未知、owner 未识别、shared tab/draft、状态变更时拒绝 |
| 定点 Desktop 清理 | 当前 tabs/draft IDs/recent/info/window IDs 保留，其他 server 保留；指定 closed session 身份回到 sidecar |
| Host 清理 | 精确 Worker stop request、目标 listener 停止，Tailscale 4096/4097 清理且其他映射保留 |
| 防火墙/设备残留 | 仅清理确认归属的规则和 reverse；没有在线设备时保留未验收缺口 |
| 正常重开 Desktop | 全部未发送输入/附件、当前标签和历史可恢复；local/sidecar 独立运行 |
| 拒绝复活 | 旧安装/启动/迁移/bridge/provisioning/连接入口返回拒绝，无副作用 |

`scripts/backup-desktop-inputs.tests.py` 验证草稿快照和附件/见证拒绝路径。该自动验证不等同于真实 Desktop 重启验收；后者必须单独记录。退役改动不要求运行新 Agent 消耗 provider 请求来证明 UI 保存。

## 1. Required quality gate

每个代码变更完成前：

```powershell
.\scripts\build.cmd
.\scripts\build.cmd -Release
```

期望：

- 15 JVM tests，0 failures/errors
- Android Lint `No issues found.`
- Debug APK assembled and debug-signed
- Release APK assembled, R8-minified, unsigned
- PowerShell and Node scripts parsed by `build.ps1` before Gradle

## 2. Unit tests

`OpenCodeJsonTest` 6 个：

| Test | Contract |
|---|---|
| current session/status shape | summary/model/retry parsing |
| streaming parts/blocking requests | text/tool/permission/question |
| v2 permission + unknown part | aliases and forward compatibility |
| global envelope directory | reply routing preserved |
| current data/location envelope | `session.next.*` parsing |
| connected model + bridge | connected provider and metadata sets |

`OpenCodeApiTest` 9 个：

| Test | Contract |
|---|---|
| Basic + encoded directory | auth and query wire shape |
| async prompt | agent/model/parts body |
| create explicit directory | project routing |
| global SSE | path/envelope/directory |
| SSE 401 | auth error propagation |
| normalize URL | bare host/HTTPS behavior |
| Provider 404 fallback | only missing endpoint falls back to config/providers |
| Provider 401 | auth failure is not hidden by fallback |
| Bridge Bearer Origin | bridge gets Bearer and no server Basic credentials |

## 3. Script checks

```powershell
node --check .\scripts\desktop-bridge.mjs
node .\scripts\check-docs.mjs
```

PowerShell syntax command见 `TROUBLESHOOTING.md`。

```powershell
git diff --check
```

## 4. APK checks

```powershell
Test-Path .\app\build\outputs\apk\debug\app-debug.apk
Test-Path .\app\build\outputs\apk\release\app-release-unsigned.apk
```

Debug signature：

```powershell
.\scripts\verify-debug-signature.cmd
```

确认 v2 verified 和 Android Debug certificate。

Release 应为 unsigned；不要把 Debug certificate 当正式发布签名。

## 5. Protocol change gate

以下变化必须新增或修改 test：

- endpoint/path/query/body/header
- event envelope/field aliases
- permission/question shape
- model/provider shape
- unknown part/event handling
- origin-bound auth
- 404/405 fallback

Parser shape → `OpenCodeJsonTest`。

Wire contract → `OpenCodeApiTest` + MockWebServer。

## 6. Historical shared / Android manual integration matrix

共享 Desktop、bridge 和网络运输项是退役前覆盖记录；当前不得用它们重新启动旧机制。Android 状态同步实现的验收仍可参考对应场景。

| 场景 | 验证 |
|---|---|
| All projects connect | sessions across multiple directories appear |
| Single directory | only exact project sessions appear |
| Busy root | moves to Running now, shows Executing |
| Busy child | root gets projected Busy within ~2 seconds |
| Retry | Retry badge/message visible |
| Prompt | user message admitted, assistant stream appears |
| Stop | shared server execution interrupts from Android |
| Permission | once/always/reject reaches correct directory |
| Question | nested answers/reject reaches correct request |
| Todo | snapshot and event update |
| Diff | file stats and unified diff render |
| Reconnect | snapshot reconciles without losing new event |
| Shared Desktop tab | blue shared label |
| Sidecar tab | yellow private label and warning |
| Model popup | only connected/visible models, current checkmark, Latest badge |
| Keyboard | no large gap above IME |
| Custom Tab | official Web UI opens; browser auth expected |

## 7. Historical device transport matrix

| Transport | Test |
|---|---|
| USB reverse | both ports listed, app Live |
| Wireless ADB reverse | unplug USB, wireless device remains, both mappings persist |
| LAN direct | trusted network only, host listener/firewall verified |
| Device reboot | expect wireless ADB/reverse loss; re-establish |

## 8. Historical verified device

OnePlus PJZ110, Android 16：

- Debug APK installed successfully with `--no-streaming`.
- Streaming install returned vendor-specific `Failure [-99]`.
- USB reverse verified.
- Wireless ADB reverse verified after USB unplug.
- Busy session grouping, Desktop labels, model popup and IME layout verified.

设备 IP/serial 不属于稳定基线，不能写进脚本。

## 9. Regression priorities

最高优先：

1. Auth Origin leakage
2. SSE/snapshot lost update
3. stale connection result pollution
4. private/shared identity merge
5. wrong-directory permission/question reply
6. Keystore decryption behavior
7. Release manifest accidentally exporting provisioning receiver

## 10. Automated coverage gaps

Required gate 当前不会自动模拟：

- ViewModel generation/epoch/SSE barrier 的精确竞态调度
- Android Keystore key loss/decryption instrumentation
- Compose screenshot/interaction 和 IME 真机布局
- Desktop electron-store migration/rollback
- Host launcher Ctrl+C、terminal hard-kill 和 orphan lifecycle
- 完整退役的真实 Desktop quit/restart、全部未发送输入及附件恢复
- Active child parent-chain projection 的独立 unit test

触及这些区域时必须执行本文件的 manual integration matrix，并在 PR/交付说明中明确实际运行的专项验证。不得把 15 tests 描述为覆盖所有核心不变量。
