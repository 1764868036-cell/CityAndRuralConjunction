# 医院患者转诊与治疗路径

本仓库包含两个相关的 Camunda 8 流程应用：**Version 1** 是集成基线，**Version 2** 是独立重设计版，主图更紧凑，分支可通过折叠事件子流程下钻查看。

## 从这里开始

- [项目理解与阅读路线](docs/PROJECT_GUIDE.zh-CN.md)
- [目录结构与文件位置](docs/REPOSITORY_STRUCTURE.zh-CN.md)
- [Version 1 项目说明](version1/README.md)
- [Version 2 项目说明](version2/README.zh-CN.md) · [英文版](version2/README.md)
- [V1 / V2 测试差异对比](version2/TEST_COMPARISON_VERSION1_VERSION2.zh-CN.md)
- [V2 验收结果与已知缺口](version2/TEST_RESULTS_VERSION2.zh-CN.md)
- [English README](README.md)

## 两个版本

| | Version 1 | Version 2 |
| --- | --- | --- |
| 定位 | 集成流程基线，可复用原流程与测试 | 独立的患者路径重设计版 |
| BPMN | `version1/1-17.bpmn` · 流程 ID `Process_Hospital_Integrated` | `version2/hospital-patient-pathway.bpmn` · 流程 ID `Process_HPAS` |
| 表单 | 16 个共享表单，覆盖院内及外部参与者任务 | 37 个独立院内用户任务表单 |
| 自动化 | `version1/java/` 中的 Spring Boot Java Worker | `version2/java/` 中的 Spring Boot Java Worker，另有外部资源可用性 REST Connector |
| 详细说明 | [V1 README](version1/README.md) | [V2 README](version2/README.zh-CN.md) |

V2 当前 canonical BPMN 是 `version2/hospital-patient-pathway.bpmn`：P01–P08 的任务直接展开在主图，后续分支以可下钻的折叠事件子流程呈现。`version2/evidence/acceptance-candidate/` 是独立的测试候选包，**不是 canonical 主模型，也不应当作为部署源**。

## 架构和测试

两个版本均以 BPMN 编排流程，以 Camunda Form 承载人工任务，以 Java Job Worker 执行自动化步骤并模拟外部参与者应答。V2 另用 Camunda REST Connector 请求外部资源可用性。关联键、部署边界和测试结果解读见[项目阅读指南](docs/PROJECT_GUIDE.zh-CN.md)。

从仓库根目录运行常用检查：

```powershell
# V2 模型包与语义检查
python version2/tools/validate_bundle.py
python version2/tools/check_semantics.py version2/hospital-patient-pathway.bpmn

# V1 Worker 与流程测试
Set-Location version1/java
./mvnw.cmd test

# V2 Worker 与模型契约测试
Set-Location ../../version2/java
./mvnw.cmd test
```

V2 已发布的验收结果来自固定快照：86 项中 74 项通过、12 项原验收标准仍失败，因此不能称为完整验收通过。401/401 路由覆盖和通过引擎 API 完成任务，不等同于浏览器表单渲染覆盖或验收计划全部通过。详情见[结果报告](version2/TEST_RESULTS_VERSION2.zh-CN.md)。

当前 canonical 模型与独立候选包的 Worker/模型契约不同；V2 默认模型契约测试对 canonical 模型仍有已知失败。验证候选包时必须显式使用 `-Dacceptance.candidate=true` 选择其快照，不能把候选包测试结果写成 canonical 模型已通过。

## 仓库目录

```text
version1/             集成基线：BPMN、表单、Java Worker 和证据
version2/             重设计版：BPMN、表单、Java Worker、Postman 和测试
docs/                 阅读指南、目录地图、案例和课程资料
archive/legacy-root/  原根目录的历史模型、表单、交付文件、工具和证据
```

Java 开发请进入 `version1/java/` 或 `version2/java/`。原根目录文件完整保存在[历史归档](archive/legacy-root/ARCHIVE.md)中，不作为当前部署源。
