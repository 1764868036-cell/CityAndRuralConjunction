# SP05 主流程网页表单端到端测试（2026-10-19）

结果：通过。实例 `2251799813707964` 为 COMPLETED，整个实例没有 incident。

本轮使用 Edge 中的真实 Tasklist 页面，逐一领取任务、填写表单、点击 Complete Task。全部 12 次提交由浏览器完成；启动实例、转诊消息及路径关闭消息通过 REST API 发送。测试数据均为合成数据。

| 节点 | 网页任务键 | 状态 |
| --- | --- | --- |
| SP01_CheckDocuments | 2251799813708003 | COMPLETED |
| SP02_ClinicalReview | 2251799813708175 | COMPLETED |
| SP04_Assess | 2251799813708454 | COMPLETED |
| SP04_RecordConsent | 2251799813708539 | COMPLETED |
| SP04_CreateRequest | 2251799813708765 | COMPLETED |
| SP13_Draft | 2251799813709027 | COMPLETED |
| SP08_Review | 2251799813709085 | COMPLETED |
| SP13_Approve | 2251799813709234 | COMPLETED |
| SP13_Draft | 2251799813709568 | COMPLETED |
| SP13_AdminProcess | 2251799813709823 | COMPLETED |
| SP13_Approve | 2251799813710392 | COMPLETED |
| SP13_AdminProcess | 2251799813710599 | COMPLETED |

共 12 次网页提交，涉及 9 种表单。信件编写、审批、行政核对各运行两次。必填字段、下拉选项、复选框、数字和日期文本输入均实际操作。

SP05 证据：resourceType=external，externalAvailable=true，SP05_RequestAvailability 和 SP05_BookExternal 均为 COMPLETED，预订编号为 `TRT-WEB-E2E-SP05-20261019`。后续临床检查、治疗记录、两轮信件和路径关闭全部完成。

[原始引擎证据](sp05-web-e2e-results.json) · [网页填写截图](sp05-web-form.png)

覆盖范围是一条含 SP05 的完整成功主流程，不等于全部 37 种表单及所有异常分支覆盖。本轮使用同一 demo 账号领取不同候选组任务，未验证角色隔离。外部服务由本地 Postman Mock 和现有 Worker 模拟应答，不是实际医院系统。
已观察到第二轮信件会预填第一轮的审批和行政字段；本轮核对后再次点击提交。该共享变量行为不代表独立信件审批数据隔离已通过验证。
