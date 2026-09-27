# 功能与状态

状态含义：**Implemented** 表示源码已有实现和相应验证；**Experimental** 表示可用范围有限；**WIP** 表示仍在补齐；**Planning** 表示尚未交付。Implemented 不等于覆盖所有设备或达到商业质量。

| 能力 | 状态 | 实现与边界 |
|---|---|---|
| 固定双人身份、设备登记与切换 | Implemented | `FixedPeople` / `FamilyRepository`；公共示例使用用户 A/B，切换不改历史归属 |
| 记账人、付款人、消费人 | Implemented | `TxnDraft` / `TxnEntity`；家庭共同消费只用于消费维度 |
| 双方维护家庭账目 | Implemented | 没有按原记账人限制修改；两位使用者处于同一信任边界 |
| 支出、收入、转账、还款 | Implemented | `LedgerRepository`；金额以整数分存储 |
| 家庭共享资产 | Implemented | 同一资产 ID + 余额锚点 + 流水推导，避免直接同步一份可覆盖的当前余额 |
| 银行卡、信用卡、支付宝、微信、充值卡分组 | Implemented | `AssetGroups` / `LedgerSymbols`；部分分组依据类型和名称识别 |
| 余额校准 | Implemented | 创建校准锚点与内部审计流水；校准流水隐藏于日常账单并排除统计，非“完全不留记录” |
| 收入和支出统计 | Implemented | `StatsFlow` / `StatsDateRange`；月、年、自选日期及人物等筛选 |
| 退款、报销 | Implemented | 入口在原支出；金额、到账账户和日期可选，累计不能超过原实付；支持分次与撤销 |
| 跨月退回口径 | Implemented | `ExpenseRecoveries.forStatistics` 只创建统计投影，不修改真实到账时间；扣减原月支出 |
| CSV 导入导出 | Implemented | 钱迹兼容 18 列 CSV，导入预览、人物映射、重复 externalId 处理；不是通用 XLSX 导入 |
| 自托管 NAS 同步 | Implemented | Python HTTP 服务 + 操作日志；公共构建无端点无令牌，默认关闭 |
| WebDAV | Experimental | 独立传输与同步路径；第三方服务差异需自行验证，截图同步覆盖以 NAS 路径为准 |
| 通知、无障碍自动记账 | Experimental | 支持支付应用的部分通知和单笔支付/账单页面；总览页拦截，确认窗口不代表自动扣款 |
| 重复识别控制 | Implemented | 页面访问门禁、来源指纹、订单/时间/金额等比对；已记录账单提示重复 |
| 商户分类学习 | Implemented | 修改确认信息后记录规则；私人优先规则改为 `merchant.charging` 本地配置 |
| 本机 OCR 和截图附件 | Experimental | ML Kit；需显式打开，系统/机型可能不给节点或截图，受保护页面可能失败 |
| 银行明细识别 | Experimental | 已有工行明细规则；不声称覆盖全部银行、币种或页面版本 |
| 暗色记账窗口、图标细分 | Implemented | Compose / Material 3，自有图标和账户/类别符号映射 |
| 独立 Demo 构建 | Implemented | `.demo` 包、虚构数据标识、同步和自动采集关闭；只对空演示账本播种 |
| 预算、日历、周期账单 | Planning | 未提供完整用户流程 |
| 通用 Excel 导入 | Planning | 历史个人 XLSX 整理是开发过程中的一次性迁移工作，不包装为 App 功能 |
| 所有机型后台可靠性、银行附件预览 | WIP | 仍需要持续实测；一部手机成功不代表全平台支持 |

## 两个应保持不变的例子

**共享资产**：用户 A 花 30 元，用户 B 花 50 元，重复同步后共同资产只减少 80 元。

**跨月退回**：4 月支出 100 元，5 月收到退款 20 元、报销 30 元。4 月净支出变为 50 元；5 月不增加收入，资金账户在实际到账日增加。上述数值均为演示例子。
