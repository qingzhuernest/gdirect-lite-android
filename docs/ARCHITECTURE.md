# 关键代码导航

客户端模块只有 `:app`。公开下载表单在 [activity_transfers.xml](../app/src/main/res/layout/activity_transfers.xml)，任务详情在 [activity_transfer_detail.xml](../app/src/main/res/layout/activity_transfer_detail.xml)，分开对应输入与观察任务。Android 入口留在 `dev.gdirect.lite`，内部代码按职责使用真实 Java 子包；对应测试保持相同分包。0.7.3 的设置页使用 AndroidX Preference；网络 TCP/TLS 流水线见 RouteProbeQueue。

```text
app/src/main/java/dev/gdirect/lite/
├── GoogleNetwork.java        应用初始化、路由策略和切网处理
├── MainActivity.java         公开链接下载表单、启动入口
├── TransferActivity.java     当前/最近一次传输详情与打开文件
├── DriveActivity.java        私人文件浏览与操作界面
├── DownloadService.java      前台上传/下载编排
├── SettingsActivity.java     统一设置
├── ConnectionActivity.java   账户/云盘/下载的实时连接状态
├── LogActivity.java          本机日志查看和导出
├── FilePreviewActivity.java  原生文件预览
├── network/                  选线、TLS/HTTP、地址范围、DNS、重试
├── drive/                    Drive API、会话、目录状态、修改协调
├── auth/                     官方 Google 授权集成及回调解析
├── transfer/                 上传协议、范围/版本规则、完整性及线程关闭
├── diagnostics/              结构化日志、私有存储及安装诊断
├── preview/                  预览格式与大小规则
└── ui/                       状态文案、导航事件隔离、页面复用和文件菜单策略
```

## 网络连接先看这几处

| 职责 | 源码 |
| --- | --- |
| 实时连接证据、过期/切网失效与用户状态文案 | [ConnectionHealth](../app/src/main/java/dev/gdirect/lite/network/ConnectionHealth.java) · [ConnectionText](../app/src/main/java/dev/gdirect/lite/ui/ConnectionText.java) |
| 重启恢复、账户/权限记忆、旧回调和主动断开 | [RememberedAuthorization](../app/src/main/java/dev/gdirect/lite/auth/RememberedAuthorization.java) · [GoogleAuthorization](../app/src/main/java/dev/gdirect/lite/auth/GoogleAuthorization.java) |
| 默认模式、明确同意的中转、范围准备、切网代次与 HTTP 工厂 | [GoogleNetwork](../app/src/main/java/dev/gdirect/lite/GoogleNetwork.java) |
| 缓存、优先中转、有界发现、三种 TLS 模式、备用路线与取消 | [DirectRoutes](../app/src/main/java/dev/gdirect/lite/network/DirectRoutes.java) |
| 有界 TCP → TLS 流水线与网络/VPN 路径变化 | [RouteProbeQueue](../app/src/main/java/dev/gdirect/lite/network/RouteProbeQueue.java) · [NetworkPathTracker](../app/src/main/java/dev/gdirect/lite/network/NetworkPathTracker.java) |
| 固定 IP、g.cn 身份/真实 Host、HTTP 适配、发送前检查、元数据时限及取消保护 | [GoogleHttp](../app/src/main/java/dev/gdirect/lite/network/GoogleHttp.java) |
| Google 专用根与额外证书限制 | [GoogleTrust](../app/src/main/java/dev/gdirect/lite/network/GoogleTrust.java) · [GoogleCertificatePolicy](../app/src/main/java/dev/gdirect/lite/network/GoogleCertificatePolicy.java) |
| IPv4/IPv6 数字解析、官方范围过滤、缓存与有限内置候选 | [IpLiteral](../app/src/main/java/dev/gdirect/lite/network/IpLiteral.java) · [GoogleAddressSpace](../app/src/main/java/dev/gdirect/lite/network/GoogleAddressSpace.java) · [GoogleRangeCatalog](../app/src/main/java/dev/gdirect/lite/network/GoogleRangeCatalog.java) · [GoogleBootstrap](../app/src/main/java/dev/gdirect/lite/network/GoogleBootstrap.java) |
| 加密 DNS 与移动端 TCP 预检 | [GoogleDnsDiscovery](../app/src/main/java/dev/gdirect/lite/network/GoogleDnsDiscovery.java) · [GoogleFrontendScan](../app/src/main/java/dev/gdirect/lite/network/GoogleFrontendScan.java) |
| 可取消的手动检测、幂等读取重试 | [ConnectionCheck](../app/src/main/java/dev/gdirect/lite/network/ConnectionCheck.java) · [ReadRetry](../app/src/main/java/dev/gdirect/lite/network/ReadRetry.java) |

典型读取：`DriveActivity → GoogleAuthorization → DriveTransport → GoogleNetwork → DirectRoutes → GoogleHttp`。有效缓存先用；明确允许时先验证中转，失败才进入范围/DNS/扫描。证书和账户/网络发送边界不能为性能而移除。完整行为及验证边界见 [连接设计](DIRECT_NETWORK.md)。

## 文件、授权、传输与诊断

| 职责 | 源码 |
| --- | --- |
| 官方授权 SDK、数字回调、凭据运输工厂 | [GoogleAuthorization](../app/src/main/java/dev/gdirect/lite/auth/GoogleAuthorization.java) · [AuthorizationCallback](../app/src/main/java/dev/gdirect/lite/auth/AuthorizationCallback.java) |
| Drive 元数据与文件操作、受控请求和内容跳转 | [DriveApi](../app/src/main/java/dev/gdirect/lite/drive/DriveApi.java) · [DriveTransport](../app/src/main/java/dev/gdirect/lite/drive/DriveTransport.java) |
| 浏览器自动恢复、切网与有界失败重试 | [BrowseReconnect](../app/src/main/java/dev/gdirect/lite/drive/BrowseReconnect.java) · [DriveActivity](../app/src/main/java/dev/gdirect/lite/DriveActivity.java) |
| 同账户续期与账户代次、跨旋转的批量选择 | [DriveSession](../app/src/main/java/dev/gdirect/lite/drive/DriveSession.java) · [SelectionRetention](../app/src/main/java/dev/gdirect/lite/drive/SelectionRetention.java) · DriveActivity.SelectionModel |
| 内存会话/权限、目录分页、写操作协调 | [DriveSession](../app/src/main/java/dev/gdirect/lite/drive/DriveSession.java) · [DriveListing](../app/src/main/java/dev/gdirect/lite/drive/DriveListing.java) · [MutationCoordinator](../app/src/main/java/dev/gdirect/lite/drive/MutationCoordinator.java) |
| 上传/下载任务、Range/ETag、偏移确认与完整性 | [DownloadService](../app/src/main/java/dev/gdirect/lite/DownloadService.java) · [TransferRules](../app/src/main/java/dev/gdirect/lite/transfer/TransferRules.java) · [UploadTransfer](../app/src/main/java/dev/gdirect/lite/transfer/UploadTransfer.java) · [FileIntegrity](../app/src/main/java/dev/gdirect/lite/transfer/FileIntegrity.java) |
| 安全日志与异步私有缓存 | [DiagnosticLog](../app/src/main/java/dev/gdirect/lite/diagnostics/DiagnosticLog.java) · [DiagnosticLogStore](../app/src/main/java/dev/gdirect/lite/diagnostics/DiagnosticLogStore.java) |
| 预览格式和限额 | [PreviewPolicy](../app/src/main/java/dev/gdirect/lite/preview/PreviewPolicy.java) |

## 测试和辅助文件

- [app/src/test/java/dev/gdirect/lite](../app/src/test/java/dev/gdirect/lite/)：对应六个实现包，覆盖协议、真实回环 TLS、会话、写入和日志边界。
- [tools/check.sh](../tools/check.sh)：唯一完整验证入口；[tools/build-preview-apk.sh](../tools/build-preview-apk.sh)：使用保留私钥打包并核验 APK。
- [tools/checks](../tools/checks/)：独立 Java 检查与合成连接基准源码。
- [tools/legacy-inspector](../tools/legacy-inspector/)：旧主包的离线安装身份工具，Gradle 名称 `:inspector`；不是客户端。
- [docs/history/prototype](history/prototype/)：原 Java 原型 ZIP 及原许可证，字节保留，不参与构建。
- [docs/design](design/)：图标设计预览与来源；实际打包资源在 app/src/main/res。

Android applicationId、入口组件名、固定签名、偏好键、日志文件和缓存资源位置保持兼容。跨包协作使用显式 Java API，Android 导出权限仍以 Manifest 为准。未来新增代码应放入对应职责包，不再堆回入口目录。
