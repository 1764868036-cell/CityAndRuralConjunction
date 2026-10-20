# 仓库目录与文件位置

当前运行和开发从 `version1/` 或 `version2/` 进入。两套应用的 BPMN、表单、Java 项目、测试快照与工具仍在各自版本目录中。

```text
CityAndRuralConjunction/
├── README.md
├── README.zh-CN.md
├── version1/                         集成基线
│   ├── 1-17.bpmn
│   ├── *.form                       16 个表单
│   ├── java/                        Worker、数据库与测试
│   ├── istar/                       SD/SR 模型与导出图
│   ├── deliverables/                V1 验收计划
│   ├── evidence/                    V1 证据
│   └── security/                    权限配置
├── version2/                         当前重设计版
│   ├── hospital-patient-pathway.bpmn
│   ├── forms/                       37 个表单
│   ├── java/                        Worker、数据库与测试
│   ├── goalModel.txt                可编辑战略模型
│   ├── istar/                       SD/SR 图与模型校验
│   ├── postman/                     SP05 集合、Mock 与操作说明
│   ├── docs/                        验收计划、变更报告与实施记录
│   ├── evidence/                    测试结果、冻结输入与截图
│   ├── tools/                       生成、检查与验证脚本
│   ├── source/                      生成器使用的基线
│   ├── previews/                    流程预览
│   ├── Agile Workshop.docx
│   └── Contribution(1).xlsx
├── docs/
│   ├── PROJECT_GUIDE.zh-CN.md       项目理解与阅读路线
│   ├── REPOSITORY_STRUCTURE.zh-CN.md
│   ├── case-study/                 原始案例及提取文本
│   └── course-materials/           BPMN 与业务流程建模课程材料
└── archive/
    └── legacy-root/                原根目录的历史文件
        ├── ARCHIVE.md
        ├── *.bpmn / *.form / *.svg  早期模型、表单与预览
        ├── HospitalPatientPathway/  早期评审稿
        ├── hpas-build/              历史模型生成和检查脚本
        ├── deliverables/            早期验收计划与贡献表
        ├── presentation/           早期演示文稿
        ├── evidence/               历史运行与测试证据
        ├── security/               历史权限配置
        └── 其他历史报告和交付文件
```

## 常用入口

| 要找的内容 | 位置 |
| --- | --- |
| V2 运行步骤 | [中文版说明](../version2/README.zh-CN.md) |
| V2 BPMN | [主流程](../version2/hospital-patient-pathway.bpmn) |
| V2 Java Worker | [Java 项目](../version2/java/) |
| V2 SD/SR 与战略模型 | [模型说明](../version2/istar/README.md) |
| Postman 与 SP05 Mock | [操作指南](../version2/postman/README.zh-CN.md) |
| V2 Agile Workshop | [Word 文档](../version2/Agile%20Workshop.docx) |
| V2 贡献表 | [Excel 文件](../version2/Contribution%281%29.xlsx) |
| V1 基线 | [版本说明](../version1/README.md) |
| 案例文本 | [case.txt](case-study/case.txt) |
| 历史文件 | [归档说明](../archive/legacy-root/ARCHIVE.md) |

## 本次路径调整

| 原路径 | 新路径 |
| --- | --- |
| `learn.md` | `docs/PROJECT_GUIDE.zh-CN.md` |
| 根目录的三个 `Introduction to ... .docx` | `docs/course-materials/` |
| `case.txt`、`case_doc_fresh.txt`、`case-study-extracted.txt` | `docs/case-study/` |
| 根目录的其他早期模型、表单、报告、Word、Excel 和 ZIP | `archive/legacy-root/` 中的同名文件 |
| 根目录的 `HospitalPatientPathway/`、`hpas-build/`、`deliverables/`、`presentation/`、`evidence/`、`security/` | `archive/legacy-root/` 下的同名目录 |
| `version1/`、`version2/` | 保持原路径 |

历史文件完整保留，包括与 V1 内容相同的副本。归档内部保留原有相对结构，便于追溯旧报告中的文件和证据。提交和部署时使用相应版本目录；归档中的 Workshop、贡献表及模型属于历史材料。

`version2/postman/postman/` 是已有的 Postman 项目结构，包含集合资源与 Mock 配置，本次保持原布局。`version2/evidence/acceptance-input/` 等冻结证据目录也保持原路径和内容。
