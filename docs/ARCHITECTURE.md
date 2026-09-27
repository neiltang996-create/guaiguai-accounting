# 架构说明

依据公开工程源码整理；版本基线为 0.3.6，Room schema v3。

## 目录与依赖

| 位置 | 作用 |
|---|---|
| `app/src/main/java/com/family/ledger/ui/` | Compose 页面、Navigation、暗色主题和表单 |
| `data/db/` | Room 实体、DAO、迁移 1→2→3 |
| `data/repo/` | 人物/账本初始化、交易与资产业务规则、退款统计投影 |
| `data/csv/` | CSV 编解码、预览、映射、导入导出与表格导出 |
| `data/sync/` | 事务内变更日志、实体序列化 |
| `sync/` | LWW 合并、NAS HTTP / WebDAV 传输、同步调度 |
| `auto/`、`auto/rules/` | 通知/无障碍信号、页面识别、去重、OCR、待确认界面 |
| `attachments/` | SHA-256 引用、JPEG 私有附件存储 |
| `demo/` | 独立构建使用的虚构账本 |
| `app/schemas/` | Room schema 快照，不是用户数据库 |
| `server/` | Python 标准库服务、备份、Docker 和升级工具 |

构建：AGP 8.9.3、Kotlin 2.0.21、Gradle 8.11.1、JDK 17，minSdk 26 / targetSdk 35 / compileSdk 35。Compose BOM 2024.09.00，Room 2.6.1，WorkManager 2.9.1，OkHttp 4.12.0，ML Kit 中文/拉丁文字识别 16.0.1。版本以 `app/build.gradle.kts` 为准。

`AppContainer` 手动连接仓库和数据库，没有 Hilt/Koin。Compose 观察 Flow；关键写入集中在 Repository。

## 数据模型

`FamilyEntity`、`FamilyMemberEntity`、`DeviceEntity` 区分家庭、业务人物和设备。两位业务人物使用通用稳定 ID，设备有独立随机 ID。`BookEntity`、`CategoryEntity`、`AssetEntity` 由业务 ID 关联，种子采用可重复生成的 ID，让两个新客户端初始化后仍能指向相同账本和共享账户。

交易 `TxnEntity` 保存类型、整数分金额、发生时间、资产、分类、三种人物角色、关联账单、退款/报销种类和图片引用。校准单独使用 `BalanceAnchorEntity`，不会简单覆盖资产的当前余额。

`PendingBillEntity`、`AutoBillLogEntity` 是本地识别与排查数据。同步白名单不包含这两张表；`EntityCodec.SYNCED_TABLES` 包含设备、家庭、成员、账本、资产、锚点、分类、标签、交易和商户规则。

## 一笔账如何保存与同步

1. UI / CSV / 自动识别确认生成草稿。
2. `LedgerRepository` 验证金额、人物、转出转入账户和关联退回上限。
3. 同一 Room 事务写交易和 `SyncJournal`；日志失败则交易回滚。
4. 本地操作拥有 deviceId、seq、opId 和逻辑版本时间。
5. 客户端拉取远端日志，由 `SyncEngine` 预校验整批后应用；游标只在成功后推进。
6. 冲突采用 LWW：先比 hlc，再比 deviceId，最后比 seq；远端应用不重新写成本地操作，防止回环。
7. 共享资产余额从合并后的流水与校准锚点推导。

LWW 不是逐字段合并。双方同时修改同一笔账时，某一版本可能胜出；当前没有完整的交互式冲突解决器。

## 自动识别与附件

通知监听和无障碍节点转换成 `PaySignal`。规则模块使用 `NodeSnapshot` / matcher / extractor，先判定受支持单笔页面，再抽取金额、商户、时间、账户线索等。`ReceiptVisitGate` 和 `ReceiptSettler` 避免页面碎片、重复事件与返回页面反复触发。

`AutoBillPipeline` 查询规则、比对历史及待确认记录，交给完整确认界面或通知/浮窗。用户确认后走同一个交易仓库。高级自动提交选项存在，但默认关闭。

节点不足时，显式启用的 OCR 可在支持系统上读取截图，通过本地 ML Kit 识别。截图前后再核对当前页面和交易，降低将另一页面附到本单的风险。JPEG 存在 app 私有目录，用内容哈希引用；NAS 同步单独传输附件并验证大小、格式和哈希。未实现附件端到端加密与完整自动清理策略。

## 自托管服务

Python `ThreadingHTTPServer` 提供 health、family、devices、ops/count 和 attachments 路径。令牌通过 `X-Sync-Token` 或 Bearer 头验证，家庭 ID 作为数据命名空间。服务端操作日志为每设备 JSONL，含幂等检查、冲突拒绝、写入锁、临时文件替换和备份。

它是单个可信家庭的服务，不是面向不可信租户的公共 SaaS。写入校验失败会拒绝整批；当前落盘对每个日志文件使用原子替换，并不宣称跨多个文件具备完整数据库事务语义。

公开 Docker 示例只映射本机回环端口，令牌不能为空。外部连接应使用自建 HTTPS 反向代理或私有网络；客户端不自动携带令牌跟随重定向。旧私人部署的中继拓扑不写入公开默认配置。

## Demo 与私人运行隔离

`.demo` 构建与普通 `.dev` 开发包、原私人包不同。Demo 使用内置示例身份和数据，覆盖掉任何本地编译配置中的端点/令牌；设置层强制关闭 NAS、WebDAV、通知采集与 OCR。初始化只允许向空账本写样例，重复启动不会重复入账。
