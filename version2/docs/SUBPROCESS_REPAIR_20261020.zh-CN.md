# Version 2 子流程修复结果

## 修复范围

当前部署模型：`bpmn/hospital-patient-pathway.bpmn`。

本次修改限于 9 个事件子流程、P13 内层准备子流程、关联子流程表单、子流程 Java Worker 和验证工具。
P01–P08 主流程节点、110 条主流程连线、条件、主图 DI、协作消息线、原有消息定义和外部参与者流程均保留。
主流程已有 Worker 分支与共享消息发布方法也进行了前后内容对比，保持一致；REST URL/secret 配置未调整。

`evidence/subprocess-repair-20261020/main-process-preservation.json` 保存了受保护内容的比较结果。
修复前模型和 Worker 副本保存在同目录的 `.keep` 文件中，不在 Modeler 部署目录内。

## 具体调整

| 子流程 | 已实施的调整 |
|---|---|
| P03B 预约改期 | 先核验身份、记录复诊截止日期；检查拟约日期；两周内预约增加电话联系记录；回复使用分支专属消息和每次请求的唯一关联键。 |
| P09 治疗变更 | 新增顾问评估任务；正常变更先判断临床授权；涉及财务时先复核、批准后实施；财务拒绝正常结束该变更请求；紧急暂停保留事后授权路线。 |
| P10 取消与未到诊 | close 路线发布主流程已经存在的 `HPAS: Pathway closure authorised` 消息并携带原因，不新增主流程节点或中断事件。关闭何时被接收仍遵从原主流程订阅。 |
| P11 退款与转移 | 明确校验财务授权、审批人、正数金额；退款与转移独立路由；转移不得调用退款 Worker；退款结果按请求唯一键关联；失败/待定结果回到财务判断，只有 completed 才记录退款成功。 |
| P12 患者问询 | 在分类表单中采集 `infoAuthorised`，再判断财务问题可直接答复还是转交财务。 |
| P13 临床信件 | 不批准时在准备子流程内退回草拟；批准后才能进入行政检查和分发；保留原 `R/P7D` 提醒。 |
| P14 随访 | 记录获授权的随访期限；冲突后可重查、获批准例外预约、或记录不预约结果；未授权例外回到人工处理；使用专属回复和请求唯一键。 |
| P15 监控 | 信件逾期从该 referral 的已存信件记录读取，不再直接信任调用者传入的逾期月数；显式模拟输入仅在演示模式生效。 |
| P16 权限与停机 | 拒绝授权时绕过受保护的访问变更任务，仅记审计；停机记录后等待 `incidentReference` 匹配的恢复通知，再核对。 |

子流程表单字段和交互结果通过本地输入映射隔离。子流程发送消息使用由 Job Key 派生的稳定 messageId；同一 Job 重试保持相同标识，新的分支执行有独立标识。此措施不等同于无限期的业务幂等账本，长期/跨 Job 重复事件仍应由业务事件 ID 和外部适配器去重。

子流程保持可折叠、可下钻以及各自泳道。新增任务归属和候选组已核对。子流程连线重排时避开任务和节点标签；主图布局保持原样。

## 子流程消息契约

以下仅用于子流程，原有主流程消息定义不变：

| 消息名 | correlationKey | 关键返回字段 |
|---|---|---|
| `HPAS: SP03B scheduling result` | `schedulingRequestId` | `slotAcceptable`、`appointmentDate` |
| `HPAS: SP14 scheduling result` | `schedulingRequestId` | `inRequestedPeriod`、`appointmentDate` |
| `HPAS: Refund result` 的子流程专用定义 | `refundRequestId` | `refundStatus`、`refundReference` |
| `HPAS: Service restored` | `incidentReference` | 恢复通知；仅匹配的故障记录继续 |

改期/随访请求 Worker 返回的 `schedulingRequestId`、退款请求 Worker 返回的 `refundRequestId` 必须由真实外部回复端原样回传。演示自动答复已经同步调整。

## 验证与证据

- `python tools/test_subprocess_repairs.py`：9 个当前模型回归检查通过。
- `python tools/verify_subprocess_scope.py`：主流程、主图、原有共享契约和主流程 Worker 内容对比通过。
- `python tools/validate_bundle.py`：9 个事件子流程、41 个任务表单、57 种自动任务类型（包含 REST Connector）、7 个外部响应池。
- `python tools/check_semantics.py bpmn/hospital-patient-pathway.bpmn`：结构检查通过。9 条 laneSet 提示是该检查器对事件子流程泳道的提示，不是 schema 或引擎部署错误。
- `mvn test`：28 项通过，覆盖 11 个子流程 Worker 检查、9 个资源契约检查、7 个数据库检查和 1 个审计持久化检查。
- bpmn-js 18.6.3：主导入、10 张子流程下钻图打开、保存后重导入均无警告；每张图的截图与 `modeler-check.json` 在证据目录。
- 独立 8180 验收环境：部署重命名的测试流程，11 个引擎场景通过，测试实例无 incident，运行后仅取消本轮创建的实例。测试 Job 类型带本轮前缀，与其他 Worker 隔离。

11 个运行场景：财务拒绝、财务批准后实施、信件退回再批准、转移独立路由、未授权退款阻断、退款失败回财务、恢复通知正确/错误关联、访问拒绝、财务问询转交、改期与随访并发回复隔离、随访冲突后重新检索。

引擎运行测试使用 API 提交合成表单变量和受控 Job 完成；真实 Java 校验由独立 Worker 测试验证。本次没有执行网页逐张填表，也没有重跑旧冻结快照的全部 AT-01—AT-16，不能将上述结果表述为原验收计划全部通过。

## 部署说明与实际边界

本次未向 8080 交互环境发布新版本，未重启用户正在使用的 Worker。

部署时应在 Modeler 中部署整个 `bpmn/` Process Application：当前为 1 个 BPMN + 42 个表单（41 个任务表单及 1 个启动表单）。
Java Worker 需要使用本次源码构建并重启，才能注册新增的日期校验和资金转移任务。仅替换 BPMN 而保留旧 Worker 会导致新增任务等待。
已有流程实例继续使用原部署版本；本次不迁移或修改它们。

资金转移 Worker 当前只生成并审计 `instruction-recorded` 指令，不执行真实账务划转；退款 PSP 结果、预约、信函等真实外部系统仍须接入相应适配器。
P15 的这次实际数据扫描限于已持久化的信件；其他业务域的全量待办汇总、自动升级规则和真实监控报表不属于这次完成结果。未写入业务表的信件也不会凭空出现在扫描结果中。

## 远程仓库

修复前已 fetch。`pull --ff-only` 被本地既有测试文件改动阻止；未覆盖或暂存用户改动。已对照 `origin/main` 的新版验收要求处理相关子流程规则。
本次未提交或推送。
