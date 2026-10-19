# VERSION2 SD / SR 整合图

- [可编辑 TXT（piStar 原生 JSON）](../goalModel.txt)
- [单张绿色 SVG](Hospital_SD_SR_Version2.svg)
- [单张绿色 PNG](Hospital_SD_SR_Version2.png)
- [交互查看器](index.html)：下载后用浏览器打开，可缩放、点击角色或依赖高亮。

模型以本目录上级的 `hospital-patient-pathway.bpmn` 为依据，覆盖 P01–P08 与 P03B、P09–P16 的核心职责。每个内部节点及依赖的 `Source BPMN IDs` 可回查新版 BPMN；源文件摘要保存在模型属性中。VERSION1 不变。

## 精简范围

总 Actor 从旧 `goalModel.txt` 的 19 个减为 15 个；BPMN 外部参与者在本视图中由 7 个减为 5 个；跨角色依赖由 33 条减为 26 条。新版保留 82 个内部意图元素。

| 调整 | 新版表达 |
| --- | --- |
| External Scheduling Service | 隐藏独立技术服务 Actor，由预约团队的“Search and handle slot outcome”等任务表达协调责任。实际排班仍由 BPMN 中的外部服务执行。 |
| External Correspondence Service | 隐藏独立技术服务 Actor，由秘书和预约团队的分发任务表达协调责任。实际信函服务仍存在于 BPMN。 |
| Delay-tracking system | 纳入 Pathway coordinators 的监测和报表任务。 |
| Hospital IT / system administration | 按 P16 泳道纳入 Administrative management 的授权、访问、停机和审计任务。 |
| Hospital senior management | 按 P15 泳道纳入 Administrative management 的逐级升级处理任务。 |
| 旧版合并的 Clinical team | 按 VERSION2 分为 Consultants 与 Clinical team，分别表达临床决策/审批和治疗执行/变更职责。 |

保留的五个外部 Actor：Patient、Referring organisation、External clinical services、Insurer / funder、Payment provider。这是战略视图的范围收缩，不修改 BPMN 外部参与者或执行接口，也没有把不同机构合并成一个虚构业务角色。

## VERSION2 业务对齐

- P03/P03B/P14：预约、变更、随访和超时升级；电话联系由 Call handling 执行，排班查询仍是外部服务调用。
- P04/P10：明确同意与拒绝为不同结果；有同意且请求完整时才能创建有效治疗请求。取消/拒绝/未到诊由顾问决定临床响应。
- P05：区分内部资源、外部临时预订和等待释放容量，等待期间避免重复预订。
- P06/P07/P11：保留保险审批、支付状态核查、禁止不明交易自动重复请求，以及退款决策/执行结果核对。新版 BPMN 的紧急临床例外保留在单独任务中，要求记录理由及后续财务跟进。
- P08/P09：临床团队评估适合度、执行安全暂停及验证正式变更；紧急暂停后的追认属于顾问，财务影响属于 Finance。
- P12：Call handling 分类和转交；CNS 判断临床优先级并响应，财务团队处理超出授权信息范围的查询。
- P13/P15/P16：保留七天信函准备时限/非中断提醒、管理升级、监测报表、访问授权及停机恢复。

部分接收或协调任务是对 BPMN 交互的战略抽象，例如患者的同意决策、预约团队接收验证结果、路径团队接收异常。相应 `Applicability` 明确说明推导和责任边界，不代表新增可执行 BPMN 节点。

## 图形与线的意思

| 图形/连线 | 含义 |
| --- | --- |
| 角色圆圈和虚线围栏 | Actor 及其责任范围；围栏本身不是依赖线。 |
| 圆角目标、六边形任务、矩形资源、云形质量 | 分别表示 Goal、Task、Resource、Quality。 |
| 跨角色依赖 | 读取顺序为“依赖方任务 → 所需目标/任务/资源 → 提供方任务”。全部 52 个端点连接具体任务，不连接角色圆圈。小型 D 形方向标记是 piStar 原生依赖符号，没有 D01 等人为编号。 |
| AND 分解 | 子任务共同组成该角色应具备的服务能力；不是执行顺序，也不表示每个病例必须同时执行所有条件分支。 |
| `help` 贡献箭头 | 对质量目标有正向帮助，不表示单凭该任务即可保证质量目标。 |

这张图采用战略能力层级。条件任务的触发条件保存在 `Applicability` 中；具体网关、互斥路由、消息与时序仍以 BPMN 为准。例如内部/外部预订、同意/拒绝、重试/紧急例外并不要求在一次流程实例里同时执行。

## 编辑、重建与验证

在 piStar 使用 Load/导入打开 `goalModel.txt`，可编辑后重新保存。文件使用原生节点、依赖和折点，不需要自定义边界连接插件。

```sh
python version2/tools/build_istar.py
```

此命令从明确的 VERSION2 语义定义重建 TXT 和 `model-validation.json`；SVG/PNG 是该 TXT 的 piStar 渲染快照，修改模型后应重新导出。所有源节点 ID 均存在于新版 BPMN，依赖双方为不同 Actor 内的 Task，依赖方不再被进一步分解。

`native-validation.json` 记录本次原生编辑器导入、保存、重载、端点保持及渲染检查结果：无导入警告、节点重叠、连线穿过无关节点或文本溢出。检查不声称消除了所有连线交叉，也不验证或改变业务执行系统的验收状态。
