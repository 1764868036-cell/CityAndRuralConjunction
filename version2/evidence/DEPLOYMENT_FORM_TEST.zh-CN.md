# 部署与表单填写验证（早期记录）— 2026-10-16

后续已完成全部表单与全流程验证，完整结果请看 [FULL_E2E_TEST.zh-CN.md](FULL_E2E_TEST.zh-CN.md)。本页只记录早期两张表单的初测。

技能：已从 camunda/skills 安装 camunda-process-mgmt、camunda-forms、camunda-job-workers、camunda-process-test。插件库 Camunda BPMN 查询无匹配插件。

环境：本地 Camunda 8.9.0，显式使用 local profile。部署38个资源成功：1个BPMN、37个Form。部署键2251799813707869，Process_HPAS v5，定义键2251799813707870。

测试实例2251799813708086；仅使用TEST前缀的合成数据。

1. Tasklist领取 Check referral supporting documents（2251799813708117）。空提交被阻止，提示 Responsible staff ID 必填。
2. 勾选资料齐全，填写 TEST-SECRETARY-001，提交成功，进入顾问审核。
3. Tasklist领取 Review referral and record decision（2251799813708351）。下拉选择Accept，填写原因、顾问身份、员工ID，提交成功。
4. Java Worker执行预约与外部模拟应答，进入 Assess patient, discuss diagnosis and options（2251799813708649）。流程ACTIVE、hasIncident=false。

证据：form-review-completed.png。

范围：37个表单部署成功；实际界面填写2张。未声称所有表单、全部条件分支或端到端治疗结案均通过。Maven启动完成编译，但当前项目没有自动化Java测试源码。

测试实例已取消，Worker与本次本地集群在验证后关闭。
