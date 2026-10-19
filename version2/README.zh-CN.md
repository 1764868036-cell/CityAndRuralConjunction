# HPAS BPMN 分层图

## SD and SR 整合图

[可编辑 TXT](goalModel.txt) · [单张绿色 SVG](istar/Hospital_SD_SR_Version2.svg) · [PNG](istar/Hospital_SD_SR_Version2.png) · [角色精简、连线含义与验证说明](istar/README.md)。

以 VERSION2 为基础，按修订后的院内业务边界建模：15 个 Actor（其中 4 个外部 Actor）、86 个内部意图元素、27 条连接到具体任务的跨角色依赖。Clinical support services 归为院内服务，院内资源预订任务依赖其可用性。当前 BPMN 仍将相关检验和治疗服务定义为外部参与者，此处是明确记录的业务边界修订，尚未改动可执行 BPMN。排班和信函服务在战略图中隐藏为实现细节；顾问与临床执行团队按新版职责分开。VERSION1 保持不变。

## BPMN 结构
## SP05 REST Connector

外部资源可用性查询现已使用 REST Connector。部署前需要配置 Connector Runtime 和模拟 API，详细步骤见 [SP05 Postman 桌面端指南](postman/README.zh-CN.md)。当前模型使用 54 种 Java Worker 类型和 1 种 REST Connector 类型；原查询 Worker 保留供历史部署版本使用。下方验证记录为改造前的历史记录。

主图保留 P01–P08 的具体任务。9 个事件分支在主图中显示为带“+”标记的折叠子流程；每个子流程的任务和泳道都放在独立 BPMN 图层中，可在 Camunda Modeler 中下钻查看。

| 子流程 | 名称 | 下钻图层中的泳道 |
| --- | --- | --- |
| SP03B | P03B Appointment changes | Outpatient Bookings Team |
| SP09 | P09 Manage treatment changes | Clinical Team、Consultants、Finance Team |
| SP10 | P10 Cancellation, refusal and no-shows | Call Handling Team、Finance Team、Consultants、Clinical Team |
| SP11 | P11 Refunds and fund transfers | Finance Team |
| SP12 | P12 Patient enquiries | Call Handling Team、Finance Team、Clinical Nurse Specialist Team |
| SP13 | P13 Clinic letters | Consultants、Patient Pathway Coordinators、Medical Secretaries、Consultants - letter preparation |
| SP14 | P14 Follow-up appointments | Outpatient Bookings Team、Patient Pathway Coordinators |
| SP15 | P15 Monitoring and reports | Patient Pathway Coordinators、Administrative Management Team |
| SP16 | P16 Access, audit and downtime | Administrative Management Team |

打开 [hospital-patient-pathway.bpmn](hospital-patient-pathway.bpmn)，在主图点击折叠子流程的“+”或下钻入口查看内部任务和泳道，再沿面包屑返回主图。事件触发、非中断行为、表单绑定和 Java Job Worker 类型保持在同一流程定义中。

## 文件

- `hospital-patient-pathway.bpmn`：主图及 9 个可下钻的子流程图层。
- `previews/hospital-patient-pathway-preview.svg`、`previews/overview.png`：折叠后的主图预览。
- `forms/`：37 个 Camunda Form。
- `java/`：Spring Boot Java Job Worker 项目。
- `build_redesign.py`、`validate_bundle.py`：生成 BPMN 并检查 BPMN、泳道、表单、Worker 和图层的对应关系。

P01–P08 的人工作业仍引用原来的 37 个表单；54 种 Java Worker 类型加 1 种 REST Connector 类型与当前 BPMN 对应；原查询 Worker 另保留供历史版本使用。分支内部流程、触发事件和泳道也保留在原事件子流程中。

## Postman 接口调试

[`postman/`](postman/README.zh-CN.md) 提供 Version 2 专用的 **36 个请求、本地环境及中英文说明**，覆盖 `Process_HPAS` 接收的全部 22 种消息。导入两个 JSON 文件后，先创建实例、发送 `HPAS: Referral received`，再在 Tasklist 完成转诊表单。部署请求仅包含主 BPMN 与 `forms/` 中的 37 个表单；9 个事件子流程在活动实例内按需触发。

## 重建和校验

在项目根目录运行：

```powershell
python tools\build_redesign.py
python tools\validate_bundle.py
python tools\check_semantics.py hospital-patient-pathway.bpmn
python tools\render_preview.py hospital-patient-pathway.bpmn previews/hospital-patient-pathway-preview.svg
```

## 验证记录

- BPMN lint：0 个错误；32 条 `fake-join` 警告来自多个合法流入的合流节点建议。
- Camunda 8.9 部署成功（`Process_HPAS` v4）。
- 对活动实例发送预约变更消息后，SP03B 事件子流程创建了 `hpas-sp03b-search-slot` Job；无 incident。验证实例已取消，测试集群已停止。

主图采用纵向增加泳道高度的紧凑布局，宽度由约 12800 缩至 4550；事件分支和外部参与者按三列排列。build_redesign.py 自动调用 compact_layout.py。

进一步按实际节点占用压缩空白，并以障碍检测选择折线路径。当前测量节点穿越从179处降至49处；仍有部分密集区连线需要进一步人工整理。

已移除6个仅用于汇合的排他网关，替代路线直接汇入原决策网关，减少相邻菱形。

## Java Worker 数据库

`java/` 使用与 version1 相同的 Spring JDBC + H2 repository/schema 实现，数据文件独立保存在 `java/data/hpas-redesign`，不会复用 version1 的数据库文件。初始化建表与种子脚本可重复执行。当前已接入的业务写入是 Worker 完成审计：只保存 BPMN 节点、job type、流程实例键、合成 referral/patient ID 与生成结果字段，不保存用户提交的表单正文。

基础测试使用独立内存 H2（`java/src/test/resources/application.properties`），不访问运行数据文件。运行 `cd java; .\mvnw.cmd test` 可执行 14 项数据库、Worker 审计与模型契约检查，其中包含新增的 Camunda BPMN Schema 校验。

测试差异与移植范围见 [TEST_COMPARISON_VERSION1_VERSION2.zh-CN.md](TEST_COMPARISON_VERSION1_VERSION2.zh-CN.md)。

## Version 2 验收计划

已按 version1 的 AT-01—AT-16 移植验收计划与可执行测试。见 [计划与变量映射](docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)、[本轮结果](TEST_RESULTS_VERSION2.zh-CN.md) 和 [机器可读结果](evidence/acceptance-results.json)。

在可重置的 Camunda 8.9 测试集群启动后，在 `java/` 运行 `.\mvnw.cmd -Pacceptance test`。测试使用 `evidence/acceptance-input/` 的固定快照；原计划未满足的业务标准保留为失败。流程覆盖率、JUnit 通过率、浏览器表单审计和历史运行证据分别登记。

## 本轮改动与验证

[SP05 改动报告](docs/SP05_CHANGE_REPORT.zh-CN.md) · [部署指南](postman/README.zh-CN.md) · [真实网页流程测试](evidence/SP05_WEB_E2E.zh-CN.md)。本轮 12 次网页提交、9 种表单，实例 COMPLETED、0 incident；不代表全分支和权限隔离覆盖。
