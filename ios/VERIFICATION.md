# iOS 验证记录 — 2026-10-29

## 源码与环境

- Android 基线：`f4eea97f9bacbd5b18a25fa0ce0186e3106c720e`，原树 `3ac2942367f12088c68f415c2156b6708c95b489`。
- 基线 149 个文件均按 Git blob SHA-1 逐一校验，本地树与原树一致；包含历史 ZIP、图片、Gradle Wrapper 和许可声明。新增变更仅在 `ios/`。
- 开发分支：`codex/ios-native`；工程固定目录：`~/Developer/GDirectLite/ios/`。
- Xcode 27.1（27A9275），iPhoneOS 27.1 SDK，应用最低 iOS 16；核心 Mac XCTest 目标最低 macOS 14。
- 没有新增第三方 Swift 包；核心为本地 Swift Package，只使用 Apple 框架。

## 已实际执行并通过

- `swift test`：33 项 XCTest，0 失败。
- Xcode `GDirectCoreTests` Scheme，Mac arm64：33 项 XCTest，0 失败，`TEST SUCCEEDED`。
- Xcode `GDirectLite` Scheme，Debug / generic iOS / arm64 / `CODE_SIGNING_ALLOWED=NO`：`BUILD SUCCEEDED`，包含 SwiftUI 应用、网络核心和图标资源。
- 一键入口：`bash scripts/verify.sh`，依次执行 Xcode 核心测试及完整 iPhone 应用构建。

测试覆盖：

1. 公开链接、资源密钥、重复参数、伪装域名、协议/端口/用户凭据边界与文件名安全。
2. 确认表单的同文件身份、资源密钥保留、未知字段、目标限制与大小限制。
3. HTTP 响应按单字节等多种边界输入；Content-Length、EOF、chunked 与 trailer；截断、冲突长度、超长协议行、非法压缩、额外正文和磁盘写入失败。
4. 强 ETag、Range 状态/偏移/总长校验、缓存恢复、SHA-256 损坏检测、远端验证器变化清缓存、拒绝不完整分块、同名文件不覆盖。
5. 服务端忽略 Range 时保存完整新文件；缺少强 ETag 时单连接重下；错误跳转/权限/配额/服务器错误不发布文件；1/4/8 连接下多个 4 MiB 分块拼接。
6. 严格 SecTrust SSL 策略：测试信任链 + 正确域名通过、同一信任链 + 错误域名拒绝、系统不信任的测试证书拒绝。测试根仅存在于测试创建的 SecTrust，未写入系统信任或产品代码。
7. 真实本地 TCP 回环的握手期间取消、空闲超时，以及取消前启动和无法创建输出文件时的清理。

## 未验证与未实现

- 未完成 Apple Personal Team 真实签名、iPhone 安装、真机布局/触控/VoiceOver/系统分享与 QuickLook 验收。
- 本机未安装 iOS Simulator 运行时，因此没有模拟器 UI 自动测试或界面截图。核心测试在 Mac 执行，应用为真实 iOS 构建；两者不等价于真机测试。
- 没有收到可使用的公开测试文件链接，未将本地协议测试当作真实 Google Drive 公网下载成功证据；运营商网络、指定 Google IP、复杂确认页及下载限额仍需真机测试。
- 取消/超时是实际回环 TCP 测试；TLS 主机名/证书拒绝是实际 SecTrust 离线测试。没有声称做过完整回环 TLS 服务器握手或对真实 Google 证书进行错误域名攻击测试。
- Android 的 Google 登录、私人云盘浏览/管理、上传、文档导出、自动 IP 发现、g.cn/无 SNI 路由与可选中转尚未移植。当前可用范围以 README 和应用界面说明为准。
- 进入后台主动暂停，不承诺后台常驻；无跨进程自动任务恢复。完整分块可在重新粘贴相同链接后、有相同强验证器时复用。

Xcode 内自定义 Codex 代理已经实际读取工程并执行过构建，后因账户额度中断。其配置未被冒充为功能测试结果；之后在同一固定工程中继续完成实现和上述检查。
