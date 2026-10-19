# 第一次用 Postman：跟着 Version 2 做一遍

[English](README.md) | **简体中文** | [进阶参考](REFERENCE.zh-CN.md)

**Postman 是一个“给程序发请求、查看程序回答”的工具。** 在这个项目里，你用它告诉 Camunda：“新建一位演示患者的流程”“预约成功了”“检验结果到了”，然后观察流程有没有走到下一步。

第一次只走本文的一条路线：资料完整 → 接受转诊 → 预约成功 → 就诊 → 同意治疗 → 院内安排 → 检验 → 关闭。所有数据都是虚构演示数据。

## 先分清：哪个东西做什么？

| 你会用到的东西 | 它的作用 |
| --- | --- |
| Postman 桌面软件 | 你点击 **Send（发送）** 的地方 |
| 本目录的 collection JSON | 已经写好的 36 个请求，导入后变成左侧的请求列表；它不是安装包 |
| 本目录的 environment JSON | 保存服务器地址、当前患者编号等，避免每次重新输入 |
| Camunda | 运行流程图，决定当前该做哪一步 |
| Java Worker | 本项目的 Java 程序，处理流程里的自动任务 |
| Tasklist 网页 | 人工填写、提交表单的地方 |
| Operate 网页 | 查看流程当前停在哪个节点的地方 |

一次操作的关系是：**你点 Send → Postman 发请求 → Camunda 收到消息 → 流程继续 → 遇到自动任务交给 Worker，遇到人工任务等待你填写表单。**

先做到第 3 步，看到第一次成功的回答，再继续后面的完整流程。

## 1. 安装 Postman，准备项目文件

1. 打开 [Postman 官方下载页](https://www.postman.com/downloads/)，下载与你电脑匹配的 Windows 桌面安装包。
2. 双击下载的安装包，完成安装，打开 **Postman**。浏览器打开下载网页不等于已经打开软件。
3. 确认电脑上有完整的项目文件夹。还没有的话，打开 [GitHub 仓库](https://github.com/1764868036-cell/CityAndRuralConjunction)，点击 **Code → Download ZIP**，下载并解压。
4. 打开解压后的 `version2` 文件夹，应能找到 `hospital-patient-pathway.bpmn`、`forms`、`java`、`postman`。

不登录也能用轻量客户端发送第 3 步的简单请求。第 4 步要使用本项目的请求集合和环境，需要登录或注册 Postman 账号，进入工作区（Workspace）。这是 [Postman 轻量客户端与工作区的区别](https://learning.postman.com/docs/getting-started/basics/using-api-client/)。

## 2. 启动 Camunda：先让接收请求的程序运行

以下按 Windows、Camunda 8.9 本地运行来写。如果 Camunda 已经启动，直接做第 3 步检查。

1. 准备 **JDK 21**。打开 PowerShell，输入 `java -version` 并回车；应能看到 Java 版本。如果提示找不到命令，先完成 JDK 安装并配置 `JAVA_HOME`，再重新打开终端。
2. 没有 Camunda 的话，按 [Camunda 8.9 安装说明](https://docs.camunda.io/docs/8.9/self-managed/quickstart/developer-quickstart/c8run/install-start/) 下载并解压对应的 Camunda 8 Run。
3. 在资源管理器打开包含 `c8run.exe` 的文件夹，在地址栏输入 `powershell` 并回车。
4. 在弹出的窗口输入：

```powershell
.\c8run.exe start
```

等待启动完成，保留这个窗口。下一步会检查它是不是真的能接收请求。

## 3. 发出第一个请求：只检查是否连得上

在 **Postman 软件**中：

1. 点击 **New → HTTP**，新建一个请求。
2. 地址栏左边的方法选择 **GET**，地址填入：

   `http://localhost:8080/v2/topology`

3. 点击右边的 **Send**。
4. 查看下方的回答区域（Response）。

**成功时：** 状态是 **200 OK**，下方有一段 JSON 数据，包含集群信息，例如 `brokers`、`clusterSize`。JSON 就是程序用来交换数据的文本，现在不用逐行看懂。

这次 GET 的意思是：“Camunda，你在运行吗？把集群信息告诉我。”它不会创建患者流程。地址中的 `localhost` 表示你自己的电脑，`8080` 是服务端口。

如果显示 `ECONNREFUSED` 或 `Could not send request`，先检查第 2 步。**这一步成功后，再往下做。** 本文默认本地接口无需认证；若出现 401/403，需按集群配置设置 Authorization（认证）。

## 4. 导入已经写好的请求

1. 登录 Postman，进入一个工作区。
2. 点击 **Import（导入）**，选择本项目 `version2/postman` 中的两个文件：
   - `HPAS_Version2.postman_collection.json`
   - `HPAS_Version2_Local.postman_environment.json`
3. 在左侧 **Collections（集合）** 找到并展开 **Hospital Patient Pathway - Version 2 HPAS - Camunda 8.9**。
4. 在环境选择框中选 **Hospital Pathway - Version 2 HPAS - Local**，不要停留在 **No environment**。
5. 在 **Environments（环境）** 打开这个环境，确认 `baseUrl` 是 `http://localhost:8080`，`processDefinitionId` 是 `Process_HPAS`。首次运行时，其余三项留空。

| 请求页面上的词 | 现在怎么理解 |
| --- | --- |
| GET / POST | 请求的方法；GET 常用于读取，POST 用于提交。这套请求已经选好，不用改 |
| URL 地址栏 | 发给谁、调用哪个接口 |
| Body | 一起发送的数据，已替你填好大部分内容 |
| Send | 发送当前这一条请求 |
| Response | 服务器的回答 |
| `{{baseUrl}}` | 把环境里的服务器地址代入这里 |

在第一个文件夹 **01 - Setup and referral entry (run individually)**，打开 **01 - Check cluster topology**，再点一次 Send，应同样返回 200。

**只逐条点 Send，不要点击整个集合的 Run。** 36 个请求里包含成功、失败、取消等不同路线，不能全发一遍。

## 5. 部署：把流程图和表单交给 Camunda

打开 **02 - Deploy version2 BPMN and all 37 forms**，点击 **Body → form-data**。

这一页需要上传 **1 个 BPMN 文件 + 37 个表单 = 38 个文件**。导入 JSON 不会把电脑里的这些文件自动上传。

如果现有文件路径未找到，按下面重新选择：

1. 清除原 `resources` 行的文件选择，在该行选择 `version2/hospital-patient-pathway.bpmn`。
2. 再添加一行，Key 也填 **resources**，类型选 **File**。
3. 在新行选择文件，进入 `version2/forms`，多选全部 **37 个 .form 文件**。
4. 确认两行均已勾选，然后点 **Send**。不要自己添加 Content-Type 请求头，Postman 会生成上传文件需要的头。

只选这些文件；不要选 `version1`、`source`、`evidence` 中的副本。

**成功时：** 返回 200 和部署结果。接着发送 **03 - Find HPAS definitions**，回答的 `items` 中应有 `processDefinitionId: "Process_HPAS"`。刚部署完可能需要稍等后重查。

## 6. 启动 Java Worker：让自动任务有人处理

用资源管理器打开项目的 `version2/java` 文件夹，在地址栏输入 `powershell` 并回车。执行：

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hpas.simulation.external-responses=false"
```

首次启动会下载依赖，需要等一会儿。看到应用启动完成、没有启动失败信息后，**让这个窗口一直开着**。

这个参数的意思是：预约、就诊、检验这些外部结果由你在 Postman 手动发送。Worker 仍然处理自动任务，也可能自动发起门诊信函任务。

## 7. 创建一条演示流程，再发送转诊

1. 在 Postman 发送 **04 - Create HPAS instance**，保留原 Body。
2. 成功返回 200 后，打开环境：`referralId`、`patientId`、`processInstanceKey` 应有自动填入的值。复制保存 `patientId`，等会儿填表会用到。
3. 再发送 **05 - Referral received**。

**成功时：** 打开 [Tasklist](http://localhost:8080/tasklist)，使用本地 Camunda 配置的账号登录，应出现 **Check referral supporting documents** 任务。创建实例后、发送转诊前没有这项任务，是正常的。

每次填表：找到当前实例的任务 → 打开 → 如需认领，先分配给自己 → 填写 → 点击完成任务的按钮。提交后刷新列表，查看下一项。

任务较多时，用 Postman 的 **04 - Observe current instance → Search user tasks** 查当前实例的任务，再核对 Tasklist。同一文件夹的 **Search variables** 可以查看已保存的表单值。用 [Operate](http://localhost:8080/operate) 找到环境中 `processInstanceKey` 对应的实例，可以查看活动节点。

## 8. 完成资料检查、转诊审核和预约

所有表单的 **Responsible staff ID** 都填虚构编号 `DEMO-STAFF-01`。下表文字是演示内容，不代表实际医疗判断。

| 在哪里操作 | 点击或填写什么 | 成功后看什么 |
| --- | --- | --- |
| Tasklist：**Check referral supporting documents** | 勾选 **All required referral documents received**；**Verified patient ID** 填环境里的 `patientId`；Missing documents 留空；完成任务 | 出现转诊审核任务 |
| Tasklist：**Review referral and record decision** | **Authorised consultant decision** 选 **Accept**；**Clinical reason for decision** 填 `Demo referral accepted`；**Consultant identity** 填 `DEMO-CONSULTANT-01`；不勾 Urgent referral；完成任务 | Operate 活动节点到达 `SP03_Catch_Slot` |
| Postman：第二个文件夹中的 **P03 - Scheduling result (slot found)** | 保留 Body，点 Send | 表示预约成功；流程到达 `SP03_Catch_Visited` |
| Postman：**P03 - Clinic visit completed** | 在上面的就诊等待点活动时点 Send | Tasklist 出现诊断与方案讨论任务 |

第二个文件夹的完整名字是 **02 - External responses (send at the matching wait)**。后面检验结果也在这里。看不到等待点时先检查 Worker 和当前任务，不要反复点 Send。

## 9. 在 Tasklist 填写诊断、同意和治疗申请

按下面顺序完成三个任务。每张表仍需填写 **Responsible staff ID**。

| 任务 | 字段 | 演示填写内容 |
| --- | --- | --- |
| **Assess patient, discuss diagnosis and options** | Diagnosis and clinical findings | `Demo assessment` |
| 同上 | Options and risks explained | `Demo options discussed` |
| 同上 | Patient agrees to proceed with treatment | 勾选 |
| **Record informed consent** | Signed consent record reference | `CONSENT-DEMO-01` |
| 同上 | Consent discussion | `Demo consent recorded` |
| **Create authorised Treatment Booking Request** | Treatment plan ID | `PLAN-DEMO-01` |
| 同上 | Treatment and resources | `Demo hospital treatment` |
| 同上 | Required start date (YYYY-MM-DD) | 填一个未来日期，格式如 `年-月-日`，月份和日期各两位 |
| 同上 | Number of cycles | `1` |
| 同上 | Booking resource | 选 **Hospital** |
| 同上 | Authorising clinician | `DEMO-CONSULTANT-01` |

创建实例时已经采用医院经费（`fundingRoute=hospital`），本例不需要发送付款消息。

**成功时：** Worker 处理院内安排等自动任务，主流程到达检验等待点 `SP08_Catch_Lab`。同时可能出现门诊信函任务，按第 11 步完成即可。

## 10. 发送检验结果，结束本次治疗

1. 确认 Operate 中 `SP08_Catch_Lab` 正在等待。
2. 在 Postman 发送 **P08 - Laboratory result**，保留默认 Body。
3. 在 Tasklist 打开 **Clinical review of fitness to continue**：
   - **Clinical fitness decision** 选择 **Fit to continue**。
   - **Further treatment cycles planned** 不勾选。
   - **Blood test / review reference** 保留消息带入的值；如果空白，填 `LAB-` 加上环境中完整的 `referralId`。
   - **Clinical reasoning** 填 `Demo review completed`。
   - **Responsible staff ID** 填 `DEMO-STAFF-01`。
4. 完成任务。

**成功时：** 自动任务处理后，主流程到达 `Top_WaitClosure`，等待关闭通知。

## 11. 完成门诊信函，再关闭流程

Worker 可能在不止一个阶段发起信函分支。每遇到一组，按下面三步处理；不要再手动发送“Clinic letter required”来重复创建。

| Tasklist 任务 | 演示填写内容 |
| --- | --- |
| **Prepare clinic letter (clinical content)** | **Clinical findings, decisions and follow-up** 填 `Demo clinic letter`；**Intended recipients** 填 `Demo recipient` |
| **Approve clinical content** | 勾选 **Consultant approves clinical content**；Approval notes 可留空 |
| **Administrative checks, confirm recipients** | **Administrative check** 选 **Ready to distribute**；不勾 **Follow-up requested in letter**；**Confirmed recipients** 填 `Demo recipient`；**Approved distribution channel** 填 `Demo internal channel` |

每张表都填写 **Responsible staff ID** 并完成任务。

确认主流程在 `Top_WaitClosure` 后，到 Postman 的 **05 - Authorised closure (choose matching wait)** 文件夹，发送 **Close pathway at Top_WaitClosure**。

然后发送 **04 - Observe current instance → Get current HPAS instance**。**最终成功的标志是实例状态变成 `COMPLETED`**，Operate 也显示已结束。如果仍是 `ACTIVE`，检查是否还有信函或其他分支任务未完成。

200 只是“这次请求被接受”，不是“整个患者流程已结束”。第 3 步的 200 和这里的 COMPLETED 是两件事。

## 12. 再做一次、结束练习

重新做一个独立案例时，在环境中清空 `referralId`、`patientId`、`processInstanceKey`，从第 7 步重新创建。**同一次流程中不要改这些值。** Camunda 未重置、定义未改变时，不用再次部署。

完成练习后，可在 Worker 窗口按 Ctrl+C；在包含 `c8run.exe` 的目录执行 `.\c8run.exe stop` 停止本地 Camunda。停止程序不等于删除流程数据。

## 卡住时，先对照这张表

| 看到的情况 | 先做什么 |
| --- | --- |
| 软件里找不到 Collections 或 Environments | 检查是否仍在未登录的轻量客户端；登录进入工作区 |
| 提示选择 Version 2 环境，或变量标红 | 选择指定环境，检查 baseUrl 和 Process_HPAS |
| 请求发不出去、连接被拒绝 | 确认 Camunda 已启动，地址是本机 8080；本教程使用桌面版 |
| 401 / 403 | 按集群配置设置集合 Authorization；请求继承集合认证 |
| 部署提示文件找不到 | 回到第 5 步，重新绑定本机的 38 个文件 |
| 创建提示没有流程定义 | 重查 Find HPAS definitions，确认部署了本版主 BPMN |
| 创建成功却没有人工任务 | 确认已发送 Referral received，并查看当前实例 |
| 流程停在自动任务 | 检查 Worker 窗口；用 Search incidents 查看错误详情 |
| 消息返回 200，流程没动 | 核对当前等待点、同一 referralId 和消息名称；不要连续重发 |
| 查询暂时为空 | 刚提交的数据可能尚未进入查询索引，稍等后刷新 |
| 表单不能提交 | 检查必填项，尤其 Responsible staff ID；确认任务已分配给自己 |

## 想结合代码看：先看一次“预约成功”

打开 **P03 - Scheduling result (slot found)** 的 **Body**，你会看到：

```json
{
  "name": "HPAS: Scheduling result",
  "correlationKey": "{{referralId}}",
  "timeToLive": 900000,
  "messageId": "{{$guid}}",
  "variables": {
    "slotFound": true,
    "appointmentDate": "{{futureDate}}"
  }
}
```

这里 `name` 表示“预约结果来了”，`correlationKey` 告诉引擎是哪一次转诊，`slotFound: true` 表示找到号源。日期由集合脚本生成，发送时替换 `{{futureDate}}`。

| 去哪个文件找 | 搜索什么 | 和你操作的关系 |
| --- | --- | --- |
| [请求集合](HPAS_Version2.postman_collection.json) | `P03 - Scheduling result (slot found)` | 定义 Postman 发给 Camunda 的地址和 JSON 数据 |
| [流程图](../hospital-patient-pathway.bpmn) | `SP03_Catch_Slot`、`slotFound` | 接收预约结果，并按结果选择下一步 |
| [Java 自动任务](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java) | `hpas-sp03-search-slot`、`reply` | 默认会模拟预约结果；第 6 步的参数关闭这类自动回话，留给你手动发送 |
| [治疗申请表单](../forms/hpas_sp04_createrequest.form) | `treatmentPlanId`、`required` | 定义第 9 步看到的字段和必填规则 |

本集合直接请求 **Camunda 的接口**。Java Worker 从引擎领取自动任务，不是本集合背后的患者增删改查 HTTP 服务。

本文步骤已与集合、BPMN、表单和 Worker 代码核对；完整的 Postman + Tasklist 人工操作路线仍需实际执行验证，不能将文中的“成功时”当作已经跑通的记录。消息关联、付款分支和测试范围见 [进阶参考](REFERENCE.zh-CN.md)。
