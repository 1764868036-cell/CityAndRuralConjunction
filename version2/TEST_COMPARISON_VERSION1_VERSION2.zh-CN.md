# Version 1 / Version 2 测试差异对比

- version1：`HospitalPatientPathway/1-17.bpmn`，`Process_Hospital_Integrated`。
- version2：`HospitalPatientPathway-redesign/hospital-patient-pathway.bpmn`，`Process_HPAS`。
- 原版验收计划已复制到 `docs/source/`，新版映射见 [验收计划](docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)。

| 比较项 | version1 归档证据 | version2 本轮执行 |
| --- | --- | --- |
| 测试计数 | 归档 Maven 28/28；不代表重新运行当前 version1 源码 | 86 项：74 通过、12 失败、0 错误、0 跳过 |
| 基础回归 | 7 项模型契约，7 项数据库，6 项持久化 Worker，2 项消息审计，6 项流程集成 | 6 项静态模型契约、7 项数据库、1 项 Worker 审计，全部通过 |
| 引擎流程测试 | 6 个归档集成场景，另有历史完整流程样例 | 58 个 CPT 场景：57 通过；每周重复提醒 1 项失败 |
| 原版标准对齐 | 原计划仍有 planned/sampled 项，不能视为全部验收通过 | 14 项决策/真实 Worker 拒绝契约：3 通过，11 项保留失败 |
| BPMN 路由覆盖 | 53/53 FEEL 是规则评估，不是引擎路由覆盖率 | Process_HPAS 401/401 元素和流，100% |
| 表单资源与用户任务 | 16 个共享表单，覆盖 81 个协作任务（70 医院、11 外部） | 37 个独立医院表单，37/37 用户任务经过引擎 API 完成 |
| 浏览器渲染 | 有原版归档 form-js 81 项审计 | 旧版 redesign 有 37 张历史截图；本轮 API 提交不替代当前浏览器审计 |
| 业务持久化 | 原版多个领域 Worker 已接入仓储 | 已复用 H2/JDBC 17 表仓储，当前运行写入主要是 Worker 审计 |
| 验收状态 | 原计划区分 reproducible、sampled、planned | 尚未通过原计划，失败标准未删除 |

本轮明确保留的缺口包括：排班日期窗口、资金拒绝阻断、既有扣款检查、停止优先级、费用复核先后顺序、改约身份/日期/电话规则、退款转移及显式退款授权、每周重复提醒、按 incidentReference 恢复后对账。

原模型有其他任务并行修改，本轮使用 SHA-256 固定快照并仅清理空 DI 图层以便部署。结果不能替代并行修改后的主图验证。快照另有 21 个 c8ctl lint 错误及 35 个警告，涉及 DI 和汇合分流规则。表单中 0/37 显式声明 bindingType=deployment，原版部署绑定标准仍待处理。

完整结果与逐 AT 状态见 [TEST_RESULTS_VERSION2.zh-CN.md](TEST_RESULTS_VERSION2.zh-CN.md)，机器可读结果见 [acceptance-results.json](evidence/acceptance-results.json)。
