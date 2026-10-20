# Camunda 角色与用户输入校验

本次改动对应两项要求：实现基于 Camunda 的角色权限，以及增加用户输入校验。

## 已实现

- 70 个可执行人工任务全部按医院泳道设置候选组，移除固定的示例用户名。
- 新增 11 个部门组和对应的 `hospital-<组 ID>` 角色配置；通过 Camunda 内置
  `task-worker` 角色提供基于任务属性的读取、认领、完成权限。
- 16 份表单增加文本长度、非空白必填、时间格式、金额范围与精度、整数次数等约束。
  申请角色和批准角色改为固定的部门组下拉选项。
- 所有可执行人工任务增加 `completing` 监听器。服务端根据引擎提供的任务 ID 校验
  公共字段和 `taskData_<任务 ID>`，不会信任用户传入的 `hospitalFormTask`。
- 服务端还检查真实日历日期、正数等待时长、患者分担额不超过预计费用、缺失材料说明、
  退款金额及转移目标。无效提交会被拒绝，任务保留，供用户修正后再次提交。

## 启用步骤

1. 将 [camunda-roles.yaml](camunda-roles.yaml) 合并到 **Camunda 集群**配置；保留原有
   身份认证、管理员账号及存储配置。该文件不是 Java worker 的配置，不含账号密码。
2. 重启集群，在 Camunda Admin 中核对组、角色、授权，再将实际员工或 OIDC 映射加入
   经批准的部门组。旧集群的已有角色可能保留原配置，需要核对实际结果。
3. 使用普通员工账号验收。不要给普通员工同时分配平台 `admin` 或流程级的全量任务权限，
   否则更宽的授权会覆盖候选组限制。候选组只有在 API 保护与授权检查启用后才构成限制。
4. 为 worker 配置受保护 API 所需的服务账号及相应任务执行、消息发布权限。
5. 重新构建 worker，将同一版本的 `1-17.bpmn` 和 16 份 `.form` 一起部署，再启动 worker。
   新建流程实例进行测试；旧实例仍使用原部署版本。worker 未运行时，新增的提交监听器会等待。

流程 16 负责审批记录和审计；获得批准后，由有权限的身份管理员在 Camunda Admin 中调整
实际组成员。表单里的角色或权限文本不会直接赋予登录用户平台权限。
`system-administrators` 是医院 IT 工作组，本配置没有赋予其 Camunda 平台管理员权限。

## 验证

在 `HospitalPatientPathway/java` 目录执行无需集群的测试：

```powershell
.\mvnw.cmd "-Dtest=BpmnModelCoverageTest,HospitalSecurityContractTest,HospitalFormValidatorTest,UserInputValidationWorkerTest,HospitalDatabaseTest,CorridorMessageAuditTest,WorkerPersistenceTest" test
```

另外，用医疗秘书、财务两个非管理员账号分别检查可见、认领和完成范围，并用 V2 完成任务
API 提交非法输入，确认任务未推进，再提交修正数据。完整检查步骤和官方资料见
[英文说明](README.md)。

`HospitalPathwayProcessTest` 需要专用 Camunda 8.9 测试集群，会清理该集群数据。
本次本地环境未运行集群，因此未声称完成真实账号权限或引擎端到端验收。
