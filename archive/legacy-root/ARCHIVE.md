# 原根目录历史归档

本目录保存仓库早期根目录的模型、表单、报告、交付文件、生成脚本和测试证据。文件之间的相对结构尽量保持原样；文件名相近或内容相同的副本仍保留，便于追溯。

- 当前集成基线：[version1](../../version1/README.md)。
- 当前重设计版：[version2](../../version2/README.md)。
- 完整路径迁移说明：[目录地图](../../docs/REPOSITORY_STRUCTURE.zh-CN.md)。
- 案例资料：[docs/case-study](../../docs/case-study/)。
- 课程材料：[docs/course-materials](../../docs/course-materials/)。

## 归档内容

| 内容 | 位置 |
| --- | --- |
| 早期流程版本和预览 | 本目录的 `1-16*`、`1-17*`、`hpas.v1*` 等文件 |
| 早期共享表单与映射 | 本目录的 `*.form` 与 `Form_Task_Mapping.csv` |
| 模型评审稿 | `HospitalPatientPathway/` |
| 模型生成、语义检查、预览脚本 | `hpas-build/` |
| 运行、校验与测试记录 | `evidence/` |
| 早期验收计划与贡献表 | `deliverables/` |
| 演示文件 | `presentation/` |
| 早期角色配置 | `security/` |

报告中的日期、通过数、部署 ID 和本机绝对路径属于当时的记录。旧报告里的 `java/` 是历史工作目录说明，当前可用的 Java 项目位于 `version1/java/` 和 `version2/java/`，不在此归档中。

历史脚本的默认输入和输出相对于脚本自身定位。例如，从仓库根目录检查归档模型：

```powershell
python archive/legacy-root/hpas-build/check_semantics.py
```

该命令检查的是历史 `hpas.v1.bpmn`，不是 V2 主模型。不要将整个归档作为 Camunda 部署资源目录。
