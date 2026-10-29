# GDirect Lite — 原生 iOS

iOS 16+ 的 SwiftUI 应用，工程入口为 `GDirectLite.xcodeproj`。Android 项目及历史 ZIP 保留原样，iOS 改动全部位于本目录。默认不登录 Google，不需要付费 Apple Developer Program 或额外的服务器。

## 在自己的 iPhone 上安装

1. 用 Xcode 打开 `GDirectLite.xcodeproj`，选择 **GDirectLite** Scheme。
2. 在 **Xcode → Settings → Apple Accounts** 登录自己的 Apple Account。
3. 选择项目的 **GDirectLite** Target → **Signing & Capabilities**，保持 **Automatically manage signing**，Team 选择自己的 **Personal Team**。若标识符已被占用，把 `dev.gdirect.lite.ios` 改为自己唯一的 Bundle Identifier。
4. 用线连接、解锁 iPhone，按设备提示信任这台 Mac；根据 Xcode 提示开启 iPhone 的开发者模式。选择自己的 iPhone 为运行目标。
5. 点击 Run。账户授权、设备信任、开发者模式及可能出现的条款需要由设备/账户本人完成。

本项目只有普通应用和在 Mac 上运行的单元测试目标，不使用推送、App Groups、iCloud、Network Extension、VPN 或付费团队能力。Mac 测试目标无需指定 iPhone 的 Personal Team。

Apple 免费 Personal Team 的开发描述文件有效期为 7 天，到期需重新构建和安装；它用于自己的设备测试，不用于 App Store 分发。参见 [Apple 开发账户说明](https://developer.apple.com/help/account/basics/about-your-developer-account)。实际设备签名仍以 Xcode 和 Apple 账户返回结果为准。

## 已实现

- 中文 SwiftUI 下载、文件、设置三个页面，使用语义颜色、动态字号、VoiceOver 标签和系统控件；复用本仓库自有应用图标。
- 粘贴公开 Google Drive 单文件链接，保留 `resourcekey`；不需要 Google 登录。支持有限的、非执行式 GET 下载确认表单。
- HTTPS 重定向逐跳检查 Google 目标、协议和端口；拒绝用户凭据、片段、歧义参数和不允许的目标。不会发送 Cookie、OAuth 或账户凭据。
- **Network.framework** 负责系统 DNS 或手动 IPv4/IPv6 的 TCP/TLS 连接。指定 IP 只改变 TCP 目标，HTTP Host、SNI 和证书主机名仍使用 URL 中的真实域名。使用 TLS 1.2+、Apple 系统信任链、SSL 主机名策略及 `SecTrustEvaluateWithError`，没有 trust-all、第三方中转或放宽验证的回退。
- 流式 HTTP/1.1 落盘：固定长度、chunked 和关闭连接定界；响应头、协议行、trailer、确认页和文件大小均有界限。检查截断、重复/冲突长度、非法 Range 和磁盘错误。
- 支持 1/4/8 连接、4 MiB 分块。只有服务器持续提供强 ETag 和正确 206/Content-Range 才使用可恢复分块；否则使用新的单连接下载。校验每块长度和本地 SHA-256 后才复用，远端 ETag 或大小变化会丢弃旧缓存，合并完成后原子发布文件。同名文件不覆盖。
- 进度、取消、超时、错误说明和 HTTPS 检测。进入后台主动取消活动连接，保持已经验证的完整分块供前台重试；下载时阻止自动锁屏。
- 本地文件搜索、QuickLook 预览，以及系统分享/“存储到文件”。本地删除需确认。Documents/Downloads 排除自动备份，续传文件保存在系统可清理的 Caches 中。

## 当前边界

- 这是公开文件下载阶段。Android 0.7.2 的 Google 登录、私人云盘浏览/管理、星标/共享云盘、上传、Google 文档导出、自动候选 IP 发现、固定 g.cn/无 SNI 模式及可选中转尚未移植。界面不提供虚假的可用入口。
- 不支持文件夹、需要登录的文件或复杂脚本/验证码/Cookie 下载确认页；Google 权限、限额和确认页格式变化可能导致失败。建议先确认文件对“知道链接的任何人”开放下载。
- 不提供后台持续传输、任务队列、文件同步或后台自动重试。进入后台、锁屏、系统终止进程及网络变化均可能停止下载；前台重试可在条件满足时恢复完整分块。链接和资源密钥不持久化，重启后需重新粘贴。
- 单文件上限为 50 GiB，最大传输时限 24 小时，网络无数据 30 秒超时。并行缓存与最终合并可能需要约两倍文件大小的空间。系统可能清理缓存。
- 强制 IP 对每个经过允许的下载主机都重新做对应域名 TLS 验证。某个 IP 的检测成功不证明所有下载域名可达；失败可清空 IP 使用系统网络。没有可用性或绕过网络限制的保证。
- SHA-256 用于防止本地缓存损坏，强 ETag 用于避免混合远端版本；这不是与 Google 提供的全文件校验值比对。预览格式受系统 QuickLook 支持范围影响。

## 构建与测试

从 `ios/` 执行：

```sh
# 一次运行 Xcode 核心单元测试，再构建完整 iPhone 应用（不签名）。
bash scripts/verify.sh

# 也可独立执行共用核心的 Swift Package 测试。
swift test
```

在 Xcode 中选择 **GDirectCoreTests → My Mac** 后按 Command-U，可执行相同的核心测试；选择 **GDirectLite → 自己的 iPhone** 进行签名安装。应用构建脚本只对当前命令使用 `CODE_SIGNING_ALLOWED=NO`，不会关闭工程的正常设备签名。

协议与下载测试使用本地数据和模拟服务器响应；超时/取消测试使用真实本地回环 TCP。证书链/主机名测试使用代码内公开测试证书与仅测试进程内的测试根，测试私钥不会进入仓库或应用，也不会向系统安装根证书。生产 TLS 使用系统信任，不提供测试根注入入口。

本次验证证据见 [VERIFICATION.md](VERIFICATION.md)。

## 代码与许可

- `GDirectLite/`：SwiftUI 界面、应用生命周期、QuickLook 与系统分享。
- `Sources/GDirectCore/`：链接/确认页、HTTP 流解析、TLS 传输、下载协调、验证分块缓存。
- `Tests/GDirectCoreTests/`：协议、安全边界、续传完整性、TLS 及传输失败测试。
- `GDirectLite.xcodeproj/`：共享应用方案与原生 Mac 核心测试方案。

自有 Swift 代码和复用的应用图标遵循仓库根目录 [Apache-2.0](../LICENSE)。iOS 只依赖 Apple 系统框架，没有增加外部 Swift Package；Android 原有依赖与许可维持不变。
