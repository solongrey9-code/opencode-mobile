# 安全模型

## 当前退役保护

共享、自启动和 bridge 的主机退役清理已完成，标记为 `complete`；精确 managed 进程、共享绑定和指定 Tailscale 映射已清理，runtime 和凭据已离线归档，旧复活入口 fail closed。原两窗 UUID 和共 23 个 sidecar 标签已恢复，现有窗口及今天新增标签保留；任务空闲后正常关闭执行，未强制终止进程。草稿冷备份与恢复启动后的哈希一致，不能代替全部 139 个非空输入的逐条 UI 验收；设备 reverse、防火墙和 Android SDK 构建缺口仍保留。完整阶段与检查见 `RETIREMENT.md`。

用户未发送输入属于必须保全的资产。SQLite online backup 必须包含 WAL 已提交输入和附件，并对当前可见输入进行精确见证；tab metadata 不是输入副本。输入快照、恢复 JSON、见证文件和 Desktop 状态备份都放在仓库外，不输出内容、不提交。

退役只操作已确认的精确启动、worker 父子进程、目标 server entry 和 Tailscale TCP 映射。未知 owner、无法确认任务空闲、Desktop 仍运行或输入恢复材料未验证时拒绝 Apply。DPAPI 凭据与 managed runtime 移到离线回退目录，不自动复活服务。

以下章节保留 Android 的凭据边界及旧 Host 组件安全实现说明。共享网络和 token 轮换流程仅为历史参考，不能用于绕过退役 guard。

## 1. 资产

- OpenCode Server Basic password
- Bridge Bearer token
- Provider API/OAuth credentials
- OpenCode DB 和 session transcripts
- Desktop state files
- APK signing keys
- 工作区源码和 Diff

## 2. 信任边界

| 边界 | 认证 | 数据 |
|---|---|---|
| Android → OpenCode Server | HTTP Basic | sessions/messages/execution/actions |
| Android → Desktop Bridge | Bearer | read-only UI metadata |
| Desktop → Shared Server | HTTP Basic | full client control |
| ADB shell → Debug app | `android.permission.DUMP` | bridge provisioning only |
| Custom Tab → Web UI | Browser Basic/TLS | full Web UI |

Basic 和 Bearer 绝不复用、绝不派生、绝不跨 Origin 发送。

## 3. Android credential storage

`ConnectionStore.kt`：

- Android Keystore AES key
- AES/GCM/NoPadding
- 每次加密使用 Cipher 随机 IV
- ciphertext = IV + encrypted bytes
- SharedPreferences 只存 Base64 ciphertext
- password 与 bridge token 独立 ciphertext

Decryption failure：返回空字符串。不会 crash，不会返回 ciphertext，不会静默使用旧 secret。

Backup/transfer：全部排除。

## 4. Bridge token

- 256-bit CSPRNG
- 64-character hex
- Windows current-user DPAPI protected
- Host runtime 通过 `OPENCODE_MOBILE_BRIDGE_TOKEN`
- Bearer exact compare + `timingSafeEqual`
- Bridge 不接受 Basic auth
- Bridge 不从 OpenCode password 计算 verifier
- Launcher 启动 Bridge 前移除 Basic 环境变量；启动 OpenCode 前移除 Bridge token。Bridge 自身若检测到继承 Basic 变量会 fail closed。

轮换：

1. 停 bridge launcher。
2. 删除 `%LOCALAPPDATA%\OpenCodeMobile\bridge-token.dat`。
3. 重新启动生成新 token。
4. 重新 provisioning Debug device 或手工更新 Release profile。

## 5. Debug provisioning

Debug manifest 声明：

```text
BridgeProvisionReceiver
exported=true
permission=android.permission.DUMP
```

Launcher Activity 仅处理 MAIN/LAUNCHER，不接受 bridge extras。

为什么不能在 Launcher 接受 extras：任意普通应用可启动 exported launcher 并伪造 bridge URL。即使 Bridge 请求本身只带 Bearer，也会把用户引向攻击者控制的元数据 Origin。

Bridge 提供的 LAN URL 只是未信任建议。选择建议地址只填入 URL并清空 Basic password；应用不会自动重连，用户必须核对 Origin 并重新输入凭据。

## 6. Origin rules

OpenCode Basic：只由 `OpenCodeApi.requestBuilder` 添加到 configured server origin。

Bridge：使用单独 explicit URL 构建单独 `Request.Builder`，只加 Bearer。

Bridge 返回的 LAN URLs 不会持久化为 server fallback，也不会触发自动 connect。用户选择后 Basic password 先清空。

Custom Tab：应用不处理 HTTP Auth callback，不注入 Basic header，由系统浏览器按完整 Origin 管理。

## 7. Network

USB reverse：

- Host server 可以只监听 loopback。
- Android `127.0.0.1:4096` 经 ADB 转发。
- USB 拔出后 mapping 失效，除非无线 ADB transport 仍在线。

LAN：

- HTTP Basic/Bearer 无传输机密性。
- 只允许可信 LAN、`LocalSubnet` firewall scope。
- 不能直接转发公网。

WAN：

- 推荐 Tailscale/WireGuard 私网。
- 或有效证书 HTTPS reverse proxy + access control。
- 不要将 `0.0.0.0:4096/4097` 直接映射公网。

## 8. Desktop storage

Desktop shared HTTP server entry 会在 `opencode.global.dat` 中包含 username/password。该文件依赖 Windows 用户 ACL，而不是项目仓库加密。

Bridge 只读 select keys，绝不能返回 whole `opencode.global.dat`。

Migration backup 同样含 server credentials，必须留在 Desktop user-data，不能提交。

Bridge 和 migration 只支持已审计的 Desktop `1.18.25/1.18.26/1.18.27`。版本不匹配时拒绝读取/写入 PRIVATE schema。

Persistent runtime将 shared password 作为 current-user DPAPI 文件保存，Managed CLI 与 Desktop 版本对齐；Server 和 Bridge 仍使用分离环境变量。

## 9. 禁止入库

- Desktop `opencode.*` 状态文件
- `opencode.db*`
- `auth.json`
- DPAPI token/password files
- migration backups/logs containing credentials
- Authorization header
- Provider credentials
- signing key/keystore/P12/PFX/PEM
- Android extracted SharedPreferences
- screenshots/transcripts containing secrets
- `.env*`

## 10. 威胁矩阵

| 威胁 | 控制 |
|---|---|
| 偷读 Desktop sidecar | loopback + random password + 不绕过 |
| Bridge 偷 Basic password | 独立 token + explicit Origin |
| 子进程继承不需要的凭据 | 启动前最小环境 + Bridge runtime guard |
| 普通 App 注入 bridge | DUMP-protected debug receiver |
| Snapshot 覆盖新 event | SSE buffer/snapshot barrier |
| 旧连接结果污染新连接 | generation + epoch + job cancellation |
| 私有标签冒充共享 | identity sets 分离 |
| Keystore 丢失 | 空 secret + 重新输入/配对 |
| Browser Auth 泄露 | Custom Tab，不用 WebView callback |
| LAN 抓包 | 仅可信 LAN；WAN 要 VPN/HTTPS |
| Backup 泄露 | Android data extraction 全排除 |

## 11. Security review checklist

- 是否新增了跨 Origin 请求？
- 是否给 bridge 加了 Basic header？
- 是否把 token 与 password 关联/派生？
- 是否新增 exported component？需要什么 permission？
- Release manifest 是否意外包含 Debug receiver？
- 是否输出/日志记录 plaintext secret？
- 是否扩大了 firewall scope？
- 是否允许未知 TLS cert？
- 是否修改了 backup/transfer policy？
- 是否将 private/shared identity 合并？
