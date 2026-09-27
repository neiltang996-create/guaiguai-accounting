# Codex 工作指南

## 项目与结构

乖乖记账为同一可信家庭中的固定两人设计。Android Kotlin + Compose / Material 3，Room v3、Coroutines / Flow、WorkManager、OkHttp、ML Kit；服务端是 Python 标准库。先读 README、docs/ARCHITECTURE.md、docs/PRIVACY.md，再改相应模块。

- `app/src/main/java/com/family/ledger/`：UI、Repository、自动识别、同步、附件、Demo。
- `app/src/main/assets/sample_seed.json`：虚构默认种子。
- `app/src/test/`：JVM 测试；`app/src/androidTest/`：隔离 Android 验收。
- `app/src/sharedTest/`：两种测试共用的手工合成输入。
- `app/schemas/`：Room 历史 schema；不能为通过测试任意修改旧版本。
- `server/`：服务、Docker、备份、升级与测试。
- `docs/`：技术、隐私、开发经历与经过检查的图片。

## 命令

JDK 17 / SDK 35；使用 Wrapper，不写个人绝对路径。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDemo
./gradlew assembleDebugAndroidTest
ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest
python3 -m unittest discover -s server -p 'test_*.py'
python3 -m compileall -q server
python3 scripts/privacy_scan.py --history
gitleaks dir . --redact
gitleaks git . --redact --log-opts=--all
```

Java/Kotlin 变更运行相关单测、lint 和构建；数据/同步变更还要跑对应隔离设备测试。别把 skip 计作 pass。没有要求不改编译工具链版本；改 schema 必须写迁移测试。

## 禁止触碰和提交的内容

未经明确任务授权，不读写手机真实账本、不操作线上 NAS、不改私人签名、不修改上级私人工作区。`config.local.*`、`.env*`（example 除外）、keystore、数据库、真实 CSV/XLSX、日志、APK、缓存、截图回执和 SSH 私钥不得提交。不要通过 `git add -f` 绕过忽略规则。

公开示例使用用户 A / 用户 B、明显虚构商户与账户、文档示例域名。无论代码、注释、测试、截图、commit 作者、提交消息还是历史，都不得含真实姓名、银行卡尾号、真实消费或个人路径。图片只放 `docs/images/`，入库前检查可见内容及元数据。安全报告记录类别与相对位置，绝不贴密钥原值。

私有配置通过本地文件/环境变量注入；公共 CI 不需要任何 NAS secret。编译配置能从 APK 提取，不能称作加密。默认同步关闭、Demo 永不启用同步和采集。禁止伪装系统服务、绕过支付保护或未经授权采集页面。

## 核心产品不变量

1. 人物和设备分开；同一个人可以有多台设备。记账人、付款人、消费人分开，家庭共同消费仅是消费维度。
2. 双方平等维护账目。共享账户必须指向同一资产 ID，余额按流水和锚点推导，不直接 LWW 覆盖余额。30 + 50 元的两笔消费，重复同步后只减少 80 元。
3. 流水和操作日志写在同一事务；失败一起回滚。远端操作不能重新发出成本机操作；损坏批次不能推进游标。
4. 退款、报销在原支出内操作，指定金额、到账账户和时间。合计不超过原实付；不算收入，统计扣回原支出月份；余额仍按实际到账变化。
5. 校准不出现在日常账单或收支统计中；内部锚点/审计记录保留。成功保存返回，失败保留表单内容。
6. 自动识别只接受支持的单笔详情/支付结果，总览不弹；重复账单提示，不重复入账；未确认内容不能悄悄记账。

## UI 与文档

深色记账页面、足够对比度和触控面积；类别与资产选合适图标，不复制其他产品界面或资源。成功操作给反馈并关闭表单，失败不丢输入。不要把内部实现参数塞进普通使用流程。

新增功能同步更新 FEATURES、CHANGELOG 与 Roadmap；未完成标 Experimental / WIP / Planning。开发故事要区分作者自述和日志证据，不伪造旧 commits、用量、测试结果或“行业独有”宣称。

本项目暂未选择开源许可证。不要擅自加 MIT/GPL，也不要自动公开建仓、push、发布 APK 或上传素材。先完成可审阅成果和安全清单，再取得发布确认。
