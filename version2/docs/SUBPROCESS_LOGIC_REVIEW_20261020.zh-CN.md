# Version 2 子流程逻辑检查（2026-10-20）

## 范围与边界

检查当前 bpmn/hospital-patient-pathway.bpmn 中 9 个事件子流程与 SP13_PrepareScope，并核对相关表单、HpasJobExecutor 和远程新版验收计划。此次仅检查并生成报告，未改 BPMN、表单、Worker 或部署实例。

## 远程状态

已 git fetch origin。当前 HEAD de2e46f，origin/main f5e4751，落后 2 个提交。git pull --ff-only 被本地 RedesignAcceptanceContractTest.java 改动阻止。未覆盖/暂存本地改动；已用 git show 阅读远程验收计划。

## 已执行检查

- check_semantics.py：通过，9 条 event subprocess laneSet 提示（非结构错误）。
- validate_bundle.py：通过，9 个事件子流程、37 个任务表单、55 个 Job 类型、7 个外部响应池。
- 9 个事件子流程的直接图可达性检查：无不可达节点（边界事件计入其附着活动触发路径）。
- P13 当前提醒表达式为 R/P7D，已不是旧计划记录的一次性 P7D；本次未执行时钟运行测试。
- 以上是静态检查，未声称全路径运行验收通过。

## 问题清单

### 1. [P1] SP03B / SP14：预约回复存在并发串线风险

证据：与主流程共用 Msg_SchedulingResult，关联键均为 referralId；多个子流程实例可能同时等待，返回变量却分别是 slotAcceptable / inRequestedPeriod。

建议：仅对子流程使用独立回复消息及每次请求的 requestId，Worker 回传相同 requestId；保留主流程消息契约。

### 2. [P1] SP09：财务复核发生在实施变更之后

证据：SP09_ApplyChange → SP09_NotifyPlanUpdated → SP09_GW_Financial → SP09_FinanceReview；不符合 AT-10 实施前复核要求。

建议：在子流程中先确认影响并完成所需财务复核，再实施并发布更新。

### 3. [P1] SP09：入口及前置判断变量供应不足

证据：hpas-sp08-notify-change 仅发布 changeSource；入口读取 urgentChange，后续读取 financialImpact。financialImpact 的表单位于判断之后；外部调用方完整提供变量时可规避，但内置触发消息不足。

建议：为子流程定义并验证消息输入；将影响评估放到首次判断前；缺失输入进入补录，不能默认临床批准。

### 4. [P1] SP11：退款授权及转移路线不完整

证据：refundScope 含 transfer，但网关仅检查 refundDue；send-refund 仅检查 refundDue 和 financeApprover，不校验 refundAuthorised、金额及 scope；record-refund 不判断退款结果。

建议：子流程增加退款/转移/不调整分流；明确审批状态及金额校验；失败或待定结果进入财务处理，不直接记成功。

### 5. [P1] SP12：授权信息判断位于采集之前

证据：SP12_Record 表单不含 infoAuthorised；财务分支直接读取该变量；它出现在后置 SP12_AnswerFin 表单。未由消息或父作用域提供时无法可靠选路。

建议：在分类表单或独立授权评估中采集，再判断；不把缺失授权等同于授权。

### 6. [P1] SP13：未批准信件缺少正常退回路径

证据：SP13_Approve 无条件进入准备完成；letterApproved 默认 false。adminCheck=ok 时仍到 distribute，Worker 才拒绝，转为 incident。

建议：在子流程内部对批准结果分流；不批准退回草拟/修订；批准后才能进入分发。

### 7. [P1] SP16：恢复前就进入数据核对

证据：RecordDowntime → Reconcile；子流程内无恢复消息等待，也没有 incidentReference 关联订阅。

建议：在核对前等待恢复事件，用每次故障 incidentReference 关联，错误 reference 不释放等待。

### 8. [P2] SP03B：预约改期缺少原验收要求的前置检查

证据：当前检查 changeKind 与 slotAcceptable，没有身份核验、复诊截止日期以及两周内电话联系路由。

建议：只在改期子流程增加身份、截止日期、联系判断；复用主流程同类规则的业务含义。

### 9. [P2] SP10：close 只记录通知，不完成主流程关闭接口

证据：hpas-sp10-notify-referrer 仅输出 referrerNotified；未发布主流程已有的 HPAS: Pathway closure authorised。SP10_End 只结束该子流程。

建议：明确 close 的业务含义；若指关闭整个 pathway，在子流程 Worker 发布已有关闭消息并携带授权依据，保留主流程接收结构。

### 10. [P2] SP14：冲突处理后未回到预约确认

证据：outside period → Highlight → End；pathwayDecision 是自由文本，不能保证重新检索、记录例外预约或明确关闭。

建议：增加结构化处理决定，路由到重新检索、获批例外预约或明确结束结果。

### 11. [P2] SP15 / 全部事件子流程：演示实现与并发可靠性仍有差距

证据：SP15_Scan 读取输入 letterOverdueMonths，未实际扫描业务记录；事件子流程无输入输出映射；publish 每次使用随机 messageId，Worker 重试可能再次触发非中断分支。

建议：区分演示扫描与真实扫描；按业务请求设计本地变量和显式结果；消息以稳定业务事件 ID 去重，增加并发及重复投递测试。

## 其他已确认边界

非中断事件子流程不应为解决单个分支问题整体改成中断，否则会取消主流程与其他活动分支。事件子流程结果应显式定义传播契约：本地新变量默认不向父作用域传播，而父作用域已有变量可能被更新；不能把任务完成等同于其他分支已收到业务结果。

## 参考

- https://docs.camunda.io/docs/8.9/components/modeler/bpmn/event-subprocesses/
- https://docs.camunda.io/docs/8.9/components/concepts/messages/

## 文件指纹

当前模型 SHA-256：`f89e159af2d8cfd65506c9f89878bd4ecadf237a99580f4061603c5eb9af0c7f`。
