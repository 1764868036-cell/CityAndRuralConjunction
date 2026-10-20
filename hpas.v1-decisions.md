# hpas.v1 决策与假设记录（Batch 1 · BPMN 框架）

> 交付物：`hpas.v1.bpmn`（全新从零建模）+ `hpas.v1-preview.svg`
> 日期：2026-10-15 · 引擎：Camunda 8.9（本机 c8run）· 部署 key：2251799813692079（version 1）

## 一、已确认决策（三轮问答）

| # | 决策点 | 结论 |
|---|--------|------|
| 1 | 版本定位 | 全新从零重做，仅以 case study 为唯一依据；现有 1-17 系列仅作对照 |
| 2 | 交付方式 | 三批分层：①BPMN 框架 → ②表单 → ③Java job worker，每批停下确认 |
| 3 | 范围口径 | 16 组子流程全量（旧 P17 调度变更并入 SP03 域） |
| 4 | 技术复用 | 表单与 worker 全部重做；文件 hpas 前缀；纯新增不触碰旧资产 |
| 5 | 编排架构 | **单实例全路径**：一个流程实例承载患者全程，correlation key = referralId |
| 6 | 事件驱动 | **9 个非中断事件子流程**（SP03B、SP09–SP16），可在主链任意阶段触发 |
| 7 | 患者建模 | 患者 = 显式黑池（用户指正的遗漏），双向消息交互（出席/改期/取消/咨询/支付/收信） |
| 8 | P03 电话联系 | 每次尝试记录结果，**3 次未成功升级** Pathway Coordinator 并入监控 |
| 9 | P12 紧急度 | **类型自动定级**：临床=紧急（网关条件固化），财务/行政=常规 |
| 10 | P13 信函 SLA | **混合两层**：7 天逾期=P13 内非中断定时器边界事件（P7D）；1/3 个月升级=SP15 监控扫描 |

### 1.1 图完整性修订（2026-10-15 第二轮，用户三项指令）

| # | 指令 | 落地 |
|---|------|------|
| 11 | 为外部流程添加简单的模拟应答流程 | 7 个外部黑池全部填入应答流程（非可执行）：转诊方 3 链（提交转诊/补件/收结果）、患者 3 链（出席/改期取消/咨询）、排班/信函/资助各 1 链、PSP 2 链（收款/退款）、外部治疗 2 链（可用性/检验）；35 条消息流全部接驳到具体应答节点（消息开始/抛出事件），形成完整请求-应答闭环 |
| 12 | 为子流程添加参与者泳道划分 | 9 个事件子流程补齐内部角色泳道（如 SP10=呼叫中心/顾问/临床/财务；SP12=呼叫中心/财务/CNS；SP15=协调员/行政管理），全部 17 个子流程容器均含泳道；主链泳道名统一为标准团队名 |
| 13 | 先不考虑 Zeebe 可部署，保证图完整产出 | 本版以图的完整性优先；泳道仅视觉元数据（Zeebe 忽略），外部流程为非可执行池（引擎跳过）；已备份上一版可部署基线至 `hpas-build/backup.hpas.v1.deploy-verified.bpmn` |

## 二、10 项默认假设（未被否决即生效）

1. **无可用预约**（SP03/SP14）：不静默外期预约——升级 Pathway Team 决策（沿文档 P14 口径）
2. **支付确认丢失**（SP07）：标记调查 → Finance 核实：已扣款→继续；未扣款→允许重新发起（绝不自动重扣）
3. **紧急暂缓**（SP09）：立即执行 + 事后由责任临床医生补录授权；非正式渠道请求不处理
4. **转介出去**（SP02 redirect）：记录决策与理由 → 通知转诊方 → 实例以"转介"终态结束
5. **取消/爽约**（SP10）：三出口（重新预约 / 临床路径审查 / 告知转诊方并关闭）+ 已付款联动 Finance
6. **沟通偏好**：患者记录含 preferredChannel + 无障碍/翻译/代表标志（Batch 2 表单字段体现）
7. **预付款联动**（SP05→SP07）：需预付的治疗预约保持 provisional 直至支付成功/豁免记录
8. **治疗周期**（SP08）：每周期前血检+临床评估 → 继续/延迟/变更 三出口
9. **流程启动**：none start（demo）→ 转诊消息捕获（与 v1 已验证的 Tasklist 演示模式一致）
10. **三权分立**：用户任务 candidate group 严格按团队划分；关键决策记录理由（Batch 2/3 落实变量）

## 三、架构说明（框架要点）

- **主链**（协调泳道）：Start → 转诊消息捕获 → SP01→SP02→SP03→SP04→SP05→SP06→SP07→SP08
  → **驻留点** `Top_WaitClosure`（捕获"路径关闭授权"消息）→ 通知转诊方 → End。
  驻留点保证治疗完成后实例继续存活，事件子流程订阅持续在线。
- **9 个事件子流程**（虚线边框，非中断、`isInterrupting="false"`）：SP03B 预约变更（SP03 域）、
  SP09 治疗变更、SP10 取消爽约、SP11 退款、SP12 咨询路由、SP13 门诊信函、SP14 随访预约、
  SP15 监控报告、SP16 访问审计停机。同一消息可多次/并发触发多个子实例（已实证）。
- **内部衔接**：SP04/SP08 触发信函、SP08↔SP09 计划更新、SP09/SP10 开退款案等，
  均为 sendTask（jobType 语义 = Batch 3 worker 发布对应消息）。
- **外部黑池 7 个**：患者、转诊方、外部排班、外部信函、外部 PSP、保险/资助、外部治疗·检验·影像。
  **每个池内均有简单模拟应答流程**（接收消息 → 处理 → 回发结果），35 条消息流全部与应答节点接驳。
- **泳道**：主池 = 患者路径协调泳道；**全部 17 个子流程容器**（8 嵌入 + 9 事件 + SP13 嵌套准备域）
  均含内部角色泳道（医务秘书/顾问/预约组/呼叫中心/CNS/治疗排程组/协调员/财务/行政管理/临床团队）。
  事件子流程内 laneSet 仅作视觉分组（Zeebe 侧可忽略，不影响执行语义）。
- **命名规范**：消息 `HPAS: <语义>`；jobType `hpas-<域>-<动作>`（占位，Batch 3 实现）；
  候选组 medical-secretary / consultant / outpatient-bookings / call-handling / cns /
  treatment-bookings（任务名体现）/ pathway-coordinator / finance / admin-manager / clinical-team。

## 四、验证结果（真实集群）

1. 语义自检脚本：**通过**（262 节点 / 272 顺序流 / 35 消息流 / 33 消息定义 / 0 错误）
2. 图完整性增强版 c8run 8.9 部署：**成功**（deploymentKey 2251799813703649，v2，
   processDefinitionKey 2251799813703650）——外部应答流程（非可执行池）与事件子流程泳道
   均未被引擎拒绝（用户"暂不考虑可部署"的要求之下仍达标）
3. v2 运行冒烟：启动实例 → 发布"HPAS: Referral received" → 主链推进至
   `SP01_CheckDocuments`（用户任务活动）✓
4. **关键架构实证**（v1 基线时点）：并发两条"HPAS: Patient enquiry received" → 产生
   **2 个并行 SP12 事件子流程实例**，主链不受影响 ✓（非中断事件子流程多触发语义验证通过）
5. 冒烟实例均已取消，集群状态干净
6. 上一版可部署基线备份：`hpas-build/backup.hpas.v1.deploy-verified.bpmn`

## 五、后续批次衔接点

- **Batch 2（表单）**：为全部 userTask 设计 .form；表单输出映射到网关条件变量
  （documentsComplete、reviewDecision、consentGiven、fundingRoute、paymentStatus、
  retryDecision、investigationOutcome、fitnessDecision、moreCycles、pathwayResponse、
  refundDue、enquiryClass、infoAuthorised、adminCheck、followUpInstructed、
  slotFound、withinTwoWeeks、contactOutcome、phoneAttempts、changeKind、slotAcceptable、
  externalAvailable、resourceType、urgentChange、formallyAuthorised、financialImpact、
  refundRequired、appointmentPaid、inRequestedPeriod、letterOverdueMonths、eventKind 等）
- **Batch 3（Java worker）**：实现全部 `hpas-*` jobType；内部衔接 worker 发布对应 HPAS: 消息
  （信函触发、退款案、计划更新、预约变更、随访指令）；验收测试套件（主链 + 事件分支场景）
- 演示口径：Tasklist 启动（none start）→ REST/worker 发布消息驱动，与 v1 演示模式一致
