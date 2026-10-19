# SP05 本地端到端验证（2026-10-19）

结果：3 个场景均达到预期。使用 Camunda 8.9.0、本地 Postman Mock（localhost:4510）、真实 Connector Runtime 和真实 Java Worker。人工任务通过 REST API 完成，未使用 Worker stub，也未验证浏览器表单。

部署：Process_HPAS 第 2 版，定义键 `2251799813701644`。源 BPMN SHA-256：`14893b0053705c972ec56d40ae9614c394a264317eb5e2f11c222eb0c269f42e`。

| 场景 | 实例键 | 实际结果 | 最终状态 |
| --- | --- | --- | --- |
| available | `2251799813701827` | 从流程开始完成前序任务；REST 返回 true，进入 SP05_BookExternal，完成治疗、信件分支和路径关闭。原 secret incident 已解决。 | COMPLETED |
| unavailable | `2251799813705620` | 从 SP05 查询节点启动；进入 SP05_NotifyPending，真实 Worker 自动模拟资源释放，SP05_Catch_Released 完成并转入预订。 | TERMINATED |
| upstream-error | `2251799813705809` | 从 SP05 查询节点启动；HTTP 503 经重试形成 JOB_NO_RETRIES incident，未执行 SP05_BookExternal。 | TERMINATED |

两个分段测试实例在证据采集后取消，避免遗留测试任务；故障场景的 incident 为预期验证结果。原始状态和变量均保留在 [JSON 证据](sp05-e2e-results.json) 中。

## 修复与限制

- 首次联调失败源于本实现给出的环境变量前缀错误。Camunda 8.9 使用 `SECRET_`，现已修正启动脚本和 README；此前错误说明中的 `SECRETS_` 不适用于默认配置。
- 恢复原实例前分别重设 Job retries 并 resolve incident，最终完整实例为 COMPLETED。
- Connector Runtime 的入站流程扫描仍报告 BPMN Schema 初始化错误，本轮出站 HTTP 调用不受其阻断；此问题仍需单独诊断，不能用本轮结果证明所有 Connector 功能正常。
- SP05 不经过 Java Worker 的 H2 event_log，本轮证据来自引擎执行历史、变量和 Connector Runtime。
- 云端 Mock 的 TLS 连通性未在本轮验证；本轮使用本地 Mock，Postman Mock 需保持运行。
- `verify_sp05_live.py` 绑定了本轮部署/恢复实例键，仅适用于当前本地集群；集群重置后需重新指定。

参考：[Camunda 8.9 Connector secret 配置](https://docs.camunda.io/docs/8.9/self-managed/components/connectors/connectors-configuration/#secrets)。
