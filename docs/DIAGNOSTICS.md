# 连接日志与反馈

入口：云盘右上角 **⋮ → 设置 → 连接日志与反馈**。默认本机记录，不自动上传。

1. 正常执行一次出现问题的操作，例如首次连接、加载目录或下载。需要隔离旧记录时，可在复现前清空日志。
2. 返回日志页，菜单点“刷新”，然后“复制日志”粘贴反馈，或“保存文本”通过系统文件选择器保存 TXT 后发来。
3. 同时说明现象和大约发生时间，以及 Wi-Fi/移动网络、代理开关等必要背景；无需提供密码、令牌或私人文件链接。

手动重检重新验证缓存路线，保留其他健康路线，不推进网络代次。若反馈日常首次连接/目录性能，请先不进入检测页复现，保留自动选路记录。

## 能看到什么

导出头包含版本、完整构建、包名、Android API、当前连接模式和私有缓存状态。每行 UTC 时间；`op` 是本进程操作编号，重启后可重置；`APP_START` 分隔进程。`ms` 为该阶段或操作累计毫秒；`code` 为 HTTP/Google 状态码，DNS_READY 与 0.7.3 的 SCAN_BEGIN 则为候选数。

| 事件 | 含义 |
| --- | --- |
| PREFERRED_BEGIN / PREFERRED_FALLBACK | 已同意中转的优先验证，以及失败后进入普通发现 |
| RANGE_BEGIN / RANGE_READY / RANGE_BOOTSTRAP | 官方范围准备、可用范围，或改用有限内置子集 |
| RANGE_GOOG_HTTP / RANGE_CLOUD_HTTP | 官方两个列表的响应与耗时 |
| DNS_BEGIN / DNS_READY / DNS_HTTP / DNS_FAILED | DNS 收集、结果数与加密解析状态 |
| SCAN_BEGIN / SCAN_READY | 0.7.3 的 SCAN_BEGIN 记录扩大计划数量，随后 TCP/TLS 同时推进；旧版 SCAN_READY 为 TCP 预筛结果，不表示 TLS 已通过 |
| PROBE_HTTP / PROBE_OK / PROBE_FAILED | 不携带账户凭据的服务探测、TLS 验证结果 |
| CACHE_HIT / SELECT_OK / SELECT_FAILED | 路线复用、选中端点/IP/TLS 或失败 |
| REQUEST_READ_BEGIN / REQUEST_WRITE_BEGIN / REQUEST_HEADERS | 实际请求启动、TLS 后准备发送；无 URL、头和正文 |
| REQUEST_RESPONSE / REQUEST_RETRY / BODY_FAILED | 响应状态/耗时、读重试或流中断 |
| AUTH_RESTORE_RETRY | 官方 SDK 网络错误/超时后的有限静默重试，code 只记录前次 Google 状态码（7/15） |
| AUTH_RESTORE_BEGIN / AUTH_RESTORE_OK / AUTH_RESTORE_REQUIRED / AUTH_RESTORE_FAILED | 重启/会话过期后的官方授权恢复；只记录阶段与数字状态，不含账户选择或令牌 |
| AUTH_BEGIN / AUTH_CALLBACK / AUTH_OK / AUTH_FAILED | 官方授权阶段与数字状态；无账户或令牌 |
| BROWSE_AUTOCONNECT | 自动浏览触发：code 1=账户恢复、2=网络变化、3=一次延迟重试、4=用户线路检测完成后的目录衔接；无私人字段 |
| LIST_BEGIN / LIST_READY / LIST_DISPLAYED | 目录加载开始、网络解析完成、呈现完成；无目录名/ID/查询 |
| UPLOAD_BEGIN / DOWNLOAD_BEGIN / TRANSFER_OK / TRANSFER_FAILED | 传输起止和错误类别；无文件名/ID/内容 |

API 的匿名 PROBE_HTTP 返回 **401 是正常探测预期**，不是登录失败。HTTP 响应事件的 `error=NONE` 只表示收到响应，仍需结合 code 判断结果。错误记录按 TLS、证书、超时、DNS、网络、授权、取消或通用 I/O 分类，不导出异常原文和堆栈。

## 保留和隐私

只记录固定事件、服务类别、公开远端 IPv4/IPv6、TLS 枚举、时长、数字状态和错误类别；动态内容主机统一成类别，避免子域里的内容标识。不会记录设备 IP、SSID、邮箱、文件名/ID、搜索词、完整 URL、请求头、Cookie、资源密钥、令牌、请求/响应正文或 logcat。

最多 512 条/128 KiB，旧记录自动淘汰。写盘在单独线程，约一秒合并；突然结束进程可能丢最后一秒。磁盘写入失败时内存记录仍可导出。正常重启恢复有限私有记录；恢复时重建白名单字段，清空期间的旧文件不能重新填回。清空后新发生的连接仍继续记录。复制/文件导出均由用户操作，已发出或保存的副本不随本机清空一起删除。

当前自动测试覆盖脱敏、限额、并发、重启恢复、清空竞态、慢/故障磁盘和真实 TLS 请求日志。系统剪贴板、文件提供器、旋转与手机布局仍需设备验收，不把 JVM 测试当作真机结果。

## 0.8.1 对目录循环与取消的修复

0.8.0 的界面回调可能在每次 LIST_DISPLAYED 前又发起 LIST_BEGIN，形成循环，并在取消时误淘汰缓存。0.8.1 隔离程序更新导航的回调，取消不再代表路线故障。正常停留列表不应持续请求；用户操作和正常分页仍会产生新请求。元数据整体超时记录 TIMEOUT，不记为 CANCELLED。HTTP 200 与 LIST_READY 表示 API/解析成功；实际文件下载还应查看 DOWNLOAD_BEGIN 和 TRANSFER_OK/TRANSFER_FAILED，不能用目录响应替代。
