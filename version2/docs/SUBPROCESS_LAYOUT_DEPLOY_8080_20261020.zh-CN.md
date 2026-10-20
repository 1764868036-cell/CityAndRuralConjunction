# 子流程连线整理及 8080 部署记录

## 已部署版本

- 本地环境：`http://localhost:8080`，gRPC `26500`。
- 流程：`Process_HPAS`，部署版本 **4**。
- Definition Key：`2251799813777682`。
- Deployment Key：`2251799813777678`。
- 资源：`hospital-patient-pathway.bpmn` + 42 个表单（41 个任务表单、1 个启动表单）。
- 部署后通过 API 读取 XML，与本地文件进行 canonical XML 比较，一致。
- 当前源码 SHA-256：`04f392005d6278e9044ec5550b2bd038b765588fe5e530da1ecdf90f46fdd525`。

## 连线整理范围与结果

只重排 9 个事件子流程和 P13 内层准备子流程的 DI 图形定义。
本次未修改任何业务节点、条件、任务配置、消息契约或表单内容。主流程 110 条连线、主图、外部参与者流程均保留。
与本轮修改前的模型移除 DI 后进行比较，全部业务内容一致，证据见 `layout-only-proof.json`。

责任泳道改为按列排列，流程主要从上到下推进；同阶段分支并列，返回线从侧边绕行；分支标签移到可用空隙。
所有子流程仍可折叠和下钻。

几何比较（10 张子流程图合计）：

| 指标 | 整理前 | 整理后 |
|---|---:|---:|
| 不共用端点的连线正交交叉点 | 110 | 8 |
| 折点数 | 330 | 252 |
| 穿过非端点任务/节点的连线次数 | 0 | 0 |
| 连线曼哈顿总长度（模型坐标单位） | 64145 | 56546 |

交叉点统计只计算严格相交的水平/竖直线段，不包含共线重叠、共享业务端点或文本覆盖，因此不等同于所有视觉冲突数量。节点文字和分支标签已通过渲染检查。

## Worker 更新

构建：`mvn package`，28 项测试通过。
停止了连接本项目的两个旧医院 Worker JVM：38764、18640；保留 Camunda 引擎和 Connector Runtime。
统一启动新包 `java/target/hpas-redesigned-workers-1.0.0.jar`，实际 JVM PID **30624**。
使用 version2 专属数据库 `java/data/hpas-redesign`。原临时内存测试 Worker 被替换，未删除持久化数据库文件。
确认新增的 `hpas-sp03b-validate-slot`、`hpas-sp11-transfer` Worker 已注册。

后续重启可使用：

```powershell
./tools/start_local_workers.ps1 -ReplaceExisting
```

脚本先验证现有医院 Worker 属于该 version2 项目，再替换；不停止引擎或 Connector Runtime。
SP05 Mock 服务 `4510` 和现有 Connector Runtime 保持运行，Mock 可用性请求返回 200/true。

## 部署后验证

1. bpmn-js 18.6.3 导入、打开 10 张下钻图、保存后重导入：0 警告。
2. 当前模型回归 9 项、资源包与主流程保护检查：通过。
3. 8080 真实 Java Worker 检查 4 项：
   - 改期消息自动回复、日期校验、新预约处理；
   - 独立资金转移指令；
   - 财务拒绝时不实施变更；
   - 错误故障编号不放行，匹配恢复消息后完成核对。
4. 浏览器打开 Tasklist Processes，启动表单指向版本 4 的 Definition Key。
5. 浏览器提交合成启动表单后，产生 `SP01_CheckDocuments`，确认无 incident。

本轮 5 个测试实例已清理，仅取消本轮自己创建的实例。没有迁移、取消或修改用户已有实例；旧实例仍属于其原部署版本。统一 Worker 代码会服务同名 Job 类型，历史实例若走旧子流程仍需遵守 Worker 当前的授权校验。

这是部署及重点冒烟验证，不是网页逐张填表或完整 AT-01—AT-16 验收。资金转移仍是授权指令记录，并非真实账务划转。

## 证据位置

全部证据在 `evidence/subprocess-layout-deploy-20261020/`：

- `deployment.json`、`deployed.bpmn.keep`：部署响应和服务端模型。
- `worker-start.json`、`workers.out.log`：运行包指纹、PID、启动和注册日志。
- `routing-metrics.json`、`layout-pages.json`：布局和连线比较。
- `layout-only-proof.json`、`main-process-preservation.json`：内容保护对比。
- `modeler-check.json`、`BPMNDiagram_*.png`：下钻检查和预览。
- `smoke-results.json`：4 个实际 Worker 场景及清理记录。
- `browser-start-result.json`、`tasklist-first-task.png`：网页启动验证。

执行前已 fetch 远程，最新为 `f5e4751`。`pull --ff-only` 因本地既有测试文件改动被阻止，未覆盖用户改动。本轮未提交或推送。
