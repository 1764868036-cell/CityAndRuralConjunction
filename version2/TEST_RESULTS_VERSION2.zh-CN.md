# Version 2 验收测试结果

验收基准为 version1 的 Acceptance Test Plan（AT-01—AT-16），本轮固定输入及哈希见 `evidence/acceptance-input/manifest.json`。

**整体尚未通过原验收计划。以下区分路由测试、原标准缺口与基础回归。**

| 套件 | 运行 | 通过 | 失败 | 错误 | 阻塞/跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| RedesignAcceptanceContractTest | 6 | 6 | 0 | 0 | 0 |
| HospitalDatabaseTest | 7 | 7 | 0 | 0 | 0 |
| HpasJobAuditPersistenceTest | 1 | 1 | 0 | 0 | 0 |
| Version2AcceptanceIT | 58 | 57 | 1 | 0 | 0 |
| OriginalPlanParityIT | 14 | 3 | 11 | 0 | 0 |

合计：86 项，74 通过、12 失败、0 错误、0 阻塞/跳过。

Process_HPAS 路由覆盖：**401/401（100.0%）**。

用户任务完成覆盖：37/37。这是引擎 API 完成覆盖，不等同于浏览器表单渲染覆盖。

表单绑定声明：0/37 显式声明 bindingType=deployment；同批部署成功不代替原计划的部署绑定检查。

另行执行的 c8ctl lint：21 个错误、35 个警告（含 DI 与汇合分流规则）；引擎部署/Schema 通过并不会清除这些 lint 问题。

纯覆盖贪心子集为 48 段；13 个场景存在覆盖重叠，保留原验收计划要求的不同数据/负面断言。重复测试名称 0 个。

| AT | 范围 | 本轮结论 |
| --- | --- | --- |
| AT-01 | 部署与资源完整性 | 快照部署通过；主文件并行修改及 lint 需单独复核 |
| AT-02 | 完整适宜转诊 | 路由预期已验证 |
| AT-03 | 缺资料关联往返 | 路由预期已验证 |
| AT-04 | 不适宜转诊 | 路由预期已验证 |
| AT-05 | 排班、窗口与升级 | 缺口/未满足原标准 |
| AT-06 | 知情同意拒绝 | 路由预期已验证 |
| AT-07 | 服务与资金阻断 | 缺口/未满足原标准 |
| AT-08 | 支付与重复收费 | 缺口/未满足原标准 |
| AT-09 | 治疗继续、停止与变更 | 缺口/未满足原标准 |
| AT-10 | 正式治疗变更与费用复核 | 缺口/未满足原标准 |
| AT-11 | 预约变更身份、日期和联系 | 缺口/未满足原标准 |
| AT-12 | 取消、临床复核和未到诊 | 路由预期已验证 |
| AT-13 | 授权退款和资金转移 | 缺口/未满足原标准 |
| AT-14 | 问询、信件与计时器 | 缺口/未满足原标准 |
| AT-15 | 权限与停机恢复 | 缺口/未满足原标准 |
| AT-16 | 表单渲染和 Worker 契约 | 静态契约/API 完成已验证；部署绑定声明与浏览器渲染仍需复核 |

## 未满足的原标准

- AT-14 `at14_originalTimerReminder`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-13 `at13_unauthorisedRefundMustBeRejected`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-10 `at10_financeRecheckMustPrecedeImplementation`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-15 `at15_restorationUsesIncidentReference`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-05 `originalDecisionContractExists(String, String, String, String)[1]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-07 `originalDecisionContractExists(String, String, String, String)[2]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-08 `originalDecisionContractExists(String, String, String, String)[3]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-09 `originalDecisionContractExists(String, String, String, String)[4]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-11 `originalDecisionContractExists(String, String, String, String)[5]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-11 `originalDecisionContractExists(String, String, String, String)[6]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-11 `originalDecisionContractExists(String, String, String, String)[7]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。
- AT-13 `originalDecisionContractExists(String, String, String, String)[8]`：FAIL。详见 Surefire XML 和 `evidence/acceptance-results.json`。

## 使用限制与复现

- 本轮使用固定快照；源主图由其他任务并行修改。快照只显式清理空 DI 图层，所有执行语义均保留。
- `OriginalPlanParityIT` 的静态决策契约检查与真实 Worker 拒绝测试均保留失败；没有把缺少的标准删掉。
- 数据库测试验证仓储可用，不证明全部 BPMN 业务 Worker 都已写入对应领域表。
- 已归档覆盖报告：[report.html](evidence/acceptance-final/coverage/report.html)。原始 Surefire XML/TXT：[junit](evidence/acceptance-final/junit/)。测试实例记录：[acceptance-instance-traces.jsonl](evidence/acceptance-instance-traces.jsonl)。
- 命令：在 `java/` 运行 `.\mvnw.cmd -Pacceptance test`，再在项目根目录运行 `python tools\report_acceptance_results.py`。
