# 第三方组件与参考材料

本项目未选择开源许可证，不能替第三方重新授权。构建时从配置的 Maven / Google / Gradle 仓库获取依赖；具体版本以 Gradle 声明和解析结果为准。

主要依赖包括 AndroidX（Compose、Room、WorkManager 等）、Kotlin / kotlinx、OkHttp、Apache Commons CSV、Google ML Kit，以及构建和测试工具 Gradle、AGP、KSP、JUnit、Espresso。相应发布物自带的许可证、版权声明和条款仍适用；发布可安装二进制之前应再次核对该二进制的依赖许可与 NOTICE。特别是 ML Kit 不应被笼统写成“所有依赖均为同一种开源许可证”。

Gradle Wrapper 的脚本与 JAR 为第三方构建引导工具，不是作者创作的软件。它的版本/分发校验在 `gradle/wrapper/gradle-wrapper.properties` 中管理。

产品设计曾参考作者长期使用的记账产品；CSV 格式和支付应用节点标识用于兼容性。公开仓库不包含第三方 APK、反编译目录、商业产品截图或提取资源。代码中保留已有的独立实现说明与参考来源，不把“参考行为”当作其他软件代码的再分发许可。

`PaymentTextParser.kt` 的已有注释说明曾参考开源自动记账项目的门禁思路；本次未引入该项目源码。自动识别规则的第三方代码来源仍应在未来变更时逐项审查。未完成全依赖的正式许可证审计，不以此文档代替法律结论。

文档截图为本应用生产界面配合虚构账目生成。界面图标来自本工程矢量资源及 Compose Material Icons。对系统字体的使用只体现在渲染结果中，仓库不分发字体文件。
