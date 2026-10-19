# SP05 外部资源查询：部署与 Postman 桌面端指南

本目录是本轮新增的 SP05 专用实现。此前删除的旧 HPAS_Version2 集合不再使用。

## 工作机制

流程选择 external 资源后，SP05 REST Connector 发出 GET `/availability`，携带 referralId、treatmentPlanId 查询参数。响应中的 JSON 布尔值 externalAvailable 映射到同名流程变量：true 进入外部预订，false 进入待处理并等待资源释放消息。HTTP 503 属于调用失败，重试耗尽产生 incident，不创建预订。

原 SP05_RequestAvailability 发送任务及 SP05_Catch_Availability 捕获事件合并为同步请求/响应。SP05_Catch_Released 仍是独立异步资源释放通知。Java 原查询 Worker 保留，供历史部署版本使用。

## Postman 桌面端

1. Import 导入 `SP05.postman_collection.json`。它包含三个保存的 Example：available、unavailable、upstream-error。
2. Postman 12 可通过 New → Mock 创建本地代码 Mock。已保存的实现位于 `postman/mocks/sp05-availability/default.js`，配置为同目录 config.yaml，端口 4510。可在 Mock 编辑器载入这段代码并启动；本机现有名称是 SP05 Availability Mock。
3. 将集合变量 mockBaseUrl 设置为 `http://localhost:4510` 并保存。请求使用 `{{mockBaseUrl}}/availability?referralId=DEMO-001&treatmentPlanId=PLAN-001`。
4. 请求头 x-mock-response-name 设置为 available、unavailable 或 upstream-error，点击 Send，分别观察 200/true、200/false、503。
5. 本地 Mock 必须保持运行。Postman 关闭后，不应假定 localhost:4510 仍可响应。

集合 Example 是保存的响应样本；本轮使用的代码 Mock 根据请求头返回相同契约，修改 Example 不会自动改写代码 Mock。请同步修改代码和集合。嵌套 `postman/` 目录来自桌面端导出的本地文件布局，其中 request.yaml 的 examples 引用需要连同隐藏的 `.resources` 目录保留。JSON v2.1 集合是独立可导入入口。

云端操作入口为 New → Mock Server → Existing mock，选择该 Mock 后部署。云端托管可能要求账户套餐权限；本机已创建云端服务，但 HTTPS 调用曾发生握手失败，因此以下步骤使用已验证的本地 Mock。不要把部署成功当成调用验证通过。

## 启动 Camunda、Connectors 与业务 Worker

在启动本地 Camunda 8.9 Windows 包的同一 PowerShell 窗口，先设置：

```powershell
$env:SECRET_SP05_AVAILABILITY_URL = 'http://localhost:4510/availability'
Set-Location '<Camunda启动包目录>\c8run-8.9.0'
.\c8run.exe start
```

该 Windows 包自带 Connector Runtime，无需额外 Docker。8.9 默认前缀为 **SECRET_（单数）**，SECRETS_ 无法按默认配置解析。BPMN 中引用的是 `{{secrets.SP05_AVAILABILITY_URL}}`。变量设置后必须重启 Runtime；仅在已经运行的终端中修改变量不会影响旧进程。

本机专用脚本 `../tools/restart_sp05_connector_local.ps1` 可重启该包的 Connector Runtime。它包含本机绝对路径及 Java 路径，换机器应先修改并核对；不能把它当通用安装器。脚本会停止匹配到的 Runtime，集群和 Java Worker 不受其直接控制。

另开终端，在 version2/java 运行：

```powershell
.\mvnw.cmd spring-boot:run
```

## 部署、触发与检查

在 version2 目录向已核对的本地 profile 部署：

```powershell
c8ctl which profile
c8ctl deploy .\hospital-patient-pathway.bpmn .\forms --profile=local
```

部署有变化的资源后启动新版本实例；旧实例不会自动迁移。实例先等待 `HPAS: Referral received` 消息，关联键为 referralId。随后在 Tasklist 依次完成转诊检查、临床审核、治疗评估、知情同意、治疗预订请求。在预订请求中填写 treatmentPlanId、authorisingClinician、requiredStartDate 等必填字段，资源选择 external。实例变量 sp05MockExample 可以选择上述三种响应；成功演示建议显式设置 available。

在 Operate 核对 SP05_RequestAvailability 完成、externalAvailable=true、SP05_BookExternal 完成和 provisionalBookingId。对照 Postman 的请求日志和该实例的 referralId，确认确为流程发出的请求。

Java 的 hpas.simulation.external-responses=true 时，待处理通知 Worker 会自动模拟资源释放，因此 false 路径可能很快继续预订。关闭该开关会同时关闭其他分支模拟应答，不能只靠切换响应就推断流程应永久等待。

## 故障与审计

- Connector 不执行：检查 Runtime 是否启动、是否连接同一集群，以及 Job 类型 io.camunda:http-json:1。
- secret 找不到：核对 SECRET_ 前缀，确认是 Runtime 进程继承了环境变量。
- HTTP 出错：连接超时 10 秒、读取超时 20 秒，3 次 Job 尝试、30 秒 Job 退避；HTTP 客户端自身可能另外重试。
- 响应字段错误：externalAvailable 必须是 JSON 布尔值，字符串不会按 true 使用。错误类型映射为 null，网关选路失败形成 incident。
- SP05 Connector 不经过 Java Worker 的 H2 event_log；执行证据在 Camunda 和 Runtime 中。

Runtime 的入站扫描存在 BPMN Schema 初始化错误，本轮出站 REST 已成功执行，该入站问题仍未修复。

## 验证证据与范围

- [三种 API 场景](../evidence/SP05_E2E.zh-CN.md)：成功、不可用后释放、HTTP 503。
- [真实网页表单主流程](../evidence/SP05_WEB_E2E.zh-CN.md)：12 次网页提交、9 种表单，实例 COMPLETED，0 incident。
- [改动报告](../docs/SP05_CHANGE_REPORT.zh-CN.md)。

网页测试是一条完整成功路径，不代表全部 37 种表单、全部事件分支或角色权限隔离已测试。verify_sp05_live.py 使用本轮实例/部署键，集群重置后需调整，不能直接在其他集群复用。

参考：[Camunda 8.9 Connector 配置](https://docs.camunda.io/docs/8.9/self-managed/components/connectors/connectors-configuration/)。
