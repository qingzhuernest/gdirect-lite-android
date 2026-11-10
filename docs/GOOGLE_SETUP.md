# Android 的 Google Drive 授权

同一包名和签名的 Android 测试版更新可继续使用已有 Google Cloud 配置。

以下仅供尚未配置的新开发者使用。官方 Android AuthorizationClient 负责授权，应用不收集密码、不需要 Desktop/Web 客户端密钥，也不保存刷新令牌。

## 当前可交付预览身份

- 应用：RouteShelf（原 GDirect Lite 测试版）。改名不改变已注册的包名或签名。
- 包名：`dev.gdirect.lite.preview`。
- 签名 SHA-1：`87:3F:43:45:0A:44:55:5C:74:35:0F:9F:6B:D4:44:F4:9D:53:C9:B4`。
- 规范值：[config/preview-signing.json](../config/preview-signing.json)。APK-IDENTITY.json 应与实际 APK 相符。

主包 dev.gdirect.lite 是不同身份，旧主包签名不能用当前预览签名覆盖；无需删除它。APK 的公开签名指纹不是私钥，不能据此重新签署兼容升级包。密钥保管/打包见 [开发说明](DEVELOPMENT.md)。

## 重启后恢复连接

从旧版升级后连接一次，应用会在私有偏好中记住上次账户选择和只读/管理选择。之后重新打开时，以同一账户调用官方 AuthorizationClient.authorize() 获取访问令牌；已有授权可复用时不打开授权页面。访问令牌不写入本机文件，SDK 的账户/授权缓存由 Google 管理。已有账户在 45 分钟时提前续期，先清除旧令牌的 SDK 缓存；同账户且权限匹配的续期保持任务代次和资源密钥，已有下载正文不会因本地 50 分钟凭据期限中断。新请求在期限后等待官方续期，最长 31 秒；临时失败不会把已接收的正文误判为换账户。登出、切换账户、需要重新确认授权和当前令牌的 API 401 仍停止旧账户任务。

若 Google 要求再次确认，应用显示连接入口，由用户继续；离线时等待网络恢复再申请。SDK 网络错误（7）或超时（15）保留账户记忆，同一网络等待 5 秒后最多追加一次静默恢复；切换网络后可开始新一轮有界恢复。其他错误等待用户处理，不重复弹窗，也不更改 OAuth 配置。每次自动恢复有 30 秒上限，屏幕旋转不重置重试次数。Google SDK 获取令牌仍使用系统网络。

主动断开或撤销会忘记本机连接，之后打开不自动恢复。需要换账户时先断开，再连接；旧回调不会把已断开的账户重新连上。只读选择保持本地只读，即使 SDK 返回了历史较宽权限。

## 新开发者的 Google Cloud 项目

1. 在自己拥有的项目启用 Google Drive API，不要求自动启用付费结算。
2. 在 Google Auth Platform 配置真实 Branding/联系信息；外部应用开发测试先保持 Testing，加入实际测试账号。
3. 配置默认只读 `https://www.googleapis.com/auth/drive.readonly`，以及可选管理 `https://www.googleapis.com/auth/drive`。二者涉及 restricted scope；应用只有在明确启用管理时才请求更宽权限。drive.file 无法管理全部既有私人文件。
4. 创建 **Android** OAuth 客户端，填写准备安装 APK 的准确包名和证书 SHA-1。不要把 Desktop、Web 密钥或客户端 JSON 塞入应用。
5. 在带 Google Play 服务的 Android 设备安装经验证 APK，点击连接。先验证只读，再按需要启用管理权限。

自行签名的其他开发者拥有自己的 Android 客户端，不应冒用维护者身份。普通同包名/同密钥的版本更新不需要新增客户端。公开发布前按 Google 当时政策处理权限验证、隐私政策等要求；本项目没有自动公开 OAuth 应用。

## 失败时先看实际错误

在“设置 → 应用与授权诊断”打开诊断，记录实际版本、构建、包名、签名和非敏感的 Google 状态码。不要只根据下载链接、APK 文件名或旧版版本号判断安装身份。

- Android 提示签名不同：属于安装签名校验，改 OAuth 不会解决；保留旧主版，使用固定预览身份更新。
- SDK 状态 10：优先核对包名/签名/Android 客户端等配置。
- SDK 状态 7/15：优先检查系统网络与 Google Play 服务连通性。
- 状态 8 或无可解析回调：原因尚不能唯一确定，保留原始数字与步骤，不断言用户取消或 Drive API 未启用。
- Drive HTTP 401/403：区分令牌、文件权限、API、scope 和配额，不能把每个 403 当作需要重新建客户端。

授权和重新获取令牌仍走 Google Play 服务系统网络；自动数据连接、g.cn 和 GTS 根库不修改它的内部网络。切换数据连接模式不能保证初次授权可达。

## 离线检查器与历史

inspector 工件是独立、无 INTERNET 权限的旧主包身份检查器，固定检查 dev.gdirect.lite。它不是登录客户端，不用于下载，也不需要 Google OAuth 注册。当前预览包已有自身诊断页面。

历史临时 debug 密钥、旧回调和早期排障记录保存在 [历史文档](history/GOOGLE_SETUP_LEGACY.md)，不是现有测试版的升级步骤。不要向 Issue、聊天或仓库提供密码、令牌、刷新凭据或签名私钥。
