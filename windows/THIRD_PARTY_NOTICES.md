# Windows 适配第三方声明

本目录项目自有 Java / C# / XAML 代码按原仓库 GPL-3.0-only 贡献；既有版本的许可授予范围见 docs/LICENSING.md。原网络/API/链接/校验类直接从 app/src/main/java 编译，未导入 mizorewww/gdirect 的 GPL 实现、种子或资产。历史 ZIP 保持原样和原许可。

生产 Java 依赖：OkHttp 4.12.0、Okio JVM 3.6.0、Kotlin stdlib 1.9.10 + JDK7/JDK8 bridge 1.8.21、JetBrains annotations 13.0、com.vaadin.external.google:android-json:0.0.20131108.vaadin1。上述库为 Apache-2.0，沿用原项目已有依赖体系（Android 提供的 org.json 在 Windows 用原 JVM 测试所用的 android-json 实现）。测试库 JUnit 4.13.2 为 EPL-1.0；Hamcrest 1.3 为 BSD-3-Clause；MockWebServer 和 okhttp-tls 4.12.0 为 Apache-2.0；测试库不放入产品 engine 目录。库坐标和下载哈希见 dependencies.json。

gts-roots.pem 及其 MPL-2.0 许可直接来自原资源，保持原指纹校验。Google bootstrap JSON 保留原归属和报告日期，候选不是可用性保证。

本机交付所附运行时来自已安装的 JetBrains Runtime 25.0.3，保留 jre/release 和完整 jre/legal 目录，其中 OpenJDK 部分适用 GPL-2.0-with-Classpath-exception。运行时源代码项目为 https://github.com/JetBrains/JetBrainsRuntime ，具体版本见随包 release。第三方运行时的 GPL 许可不改为应用源码 Apache 许可。随附 java.lang 等模块的完整许可/例外及源代码获取说明在 jre/legal/java.base/。微软 .NET 框架由本机提供，保留其条款。

完整原项目依赖声明见仓库 THIRD_PARTY_NOTICES.md。Google 品牌和服务不表示背书。
