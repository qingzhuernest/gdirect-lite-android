<img src="docs/design/icon-preview.png" width="88" height="88" alt="RouteShelf 图标">

# RouteShelf Java

RouteShelf Java 是独立开发的 Google Drive Android 客户端。应用显示名为 RouteShelf（原 GDirect Lite），支持 Android 10 及更新版本。当前为 1.0.1-preview，可浏览、搜索、预览、上传和下载文件。修改文件和共享权限需要用户明确授权。应用可在系统网络之外尝试经 TLS 验证的 Google IPv4 / IPv6 数据端点，见 [双栈连接](docs/IPV6.md)。

## 开始使用

- [Windows 只读开发预览：构建、授权与限制](windows/README.md)
- [安装、更新和 Google 授权](docs/INSTALL.md)
- [Android 构建与测试](docs/DEVELOPMENT.md)
- [文档索引](docs/README.md) · [版本记录](CHANGELOG.md)

当前没有稳定版 Release。固定签名预览版与自行构建的调试版有不同的安装身份。具体状态以对应提交的构建结果和包内清单为准。

## 网络与数据

默认仅尝试 Google 端点。已知第三方中转须由用户启用。候选端点会重新验证 TLS 与服务，连通性因网络而异。Google 登录使用平台的官方授权流程，和文件数据的选路分开。[连接设计](docs/DIRECT_NETWORK.md)

Android 的连接日志保存在本机，用户可以手动复制或导出脱敏文本。账号、文件标识、令牌和正文不进入日志。[日志与反馈](docs/DIAGNOSTICS.md)

## 贡献与许可

从 [贡献指南](CONTRIBUTING.md) 开始。普通问题可以通过 [Issues](https://github.com/qingzhuernest/routeshelf-java/issues) 反馈。涉及越权或泄露的问题请参阅 [安全报告](SECURITY.md)。

项目自有源码采用 [GNU GPL 第 3 版](LICENSE)。分发修改版时，接收者有权按 GPL 获得对应源码。Android 可按单独的许可例外使用 Google Play 服务客户端库。历史版本、证书和其他第三方材料的授权范围见 [许可证与归属](docs/LICENSING.md)。
