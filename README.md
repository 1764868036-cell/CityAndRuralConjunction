# Hospital Patient Pathway

This repository contains two related Camunda 8 process applications for a hospital referral and treatment pathway. **Version 1** is the integrated baseline. **Version 2** is a separate redesign with a compact main diagram and drill-down event subprocesses.

## Start here

- [Project guide in Chinese](README.zh-CN.md)
- [Project understanding and reading guide](learn.md)
- [Version 1 application](version1/README.md)
- [Version 2 redesign](version2/README.md) · [V2 Chinese guide](version2/README.zh-CN.md)
- [V1/V2 test comparison](version2/TEST_COMPARISON_VERSION1_VERSION2.zh-CN.md)
- [V2 acceptance results and known gaps](version2/TEST_RESULTS_VERSION2.zh-CN.md)

## Versions at a glance

| | Version 1 | Version 2 |
| --- | --- | --- |
| Purpose | Integrated reference process and reusable baseline | Standalone redesign for the pathway |
| BPMN | `version1/1-17.bpmn` · process id `Process_Hospital_Integrated` | `version2/hospital-patient-pathway.bpmn` · process id `Process_HPAS` |
| Forms | 16 shared forms across hospital and external participant tasks | 37 individual forms for the hospital process |
| Workers | Spring Boot Java workers in `version1/java/` | Spring Boot Java workers in `version2/java/`, plus a REST Connector for external availability |
| Detail | See [V1 README](version1/README.md) | See [V2 README](version2/README.md) |

The canonical V2 BPMN is `version2/hospital-patient-pathway.bpmn`. It expands P01–P08 on the main diagram and presents later branches as collapsed event subprocesses that can be opened in Camunda Modeler. The separate `version2/evidence/acceptance-candidate/` bundle is a test candidate; it is **not** the canonical model and should not be treated as the deployment source.

## Architecture and tests

Both applications use BPMN for orchestration, Camunda Forms for human tasks, and Java job workers for automated steps and simulated external responses. V2 also uses a Camunda REST Connector for the external availability request. See [learn.md](learn.md) for how these pieces connect and for the important correlation, deployment, and test boundaries.

Useful checks (run from the repository root):

```powershell
# V2 model bundle and semantic checks
python version2/tools/validate_bundle.py
python version2/tools/check_semantics.py version2/hospital-patient-pathway.bpmn

# V1 worker and process tests
Set-Location version1/java
./mvnw.cmd test

# V2 worker and model contract tests
Set-Location ../../version2/java
./mvnw.cmd test
```

V2’s published acceptance report is based on a frozen snapshot: 74 of 86 assertions passed, with 12 original-plan criteria intentionally retained as failures; this is not full acceptance. Its 401/401 route coverage and API task-completion checks do not establish browser form rendering or acceptance compliance. See the [full result record](version2/TEST_RESULTS_VERSION2.zh-CN.md) before interpreting a pass rate.

The current canonical model and the isolated candidate have different Worker/model contracts. The default V2 model-contract test has known failures against the canonical model; candidate validation must explicitly select its snapshot with `-Dacceptance.candidate=true`. Do not report the candidate run as canonical-model validation.

## Repository map

```text
version1/   integrated baseline: BPMN, forms, Java workers, guides and evidence
version2/   standalone redesign: BPMN, forms, Java workers, tools, Postman and tests
deliverables/ original acceptance test plan
evidence/   V1 test and runtime evidence
java/       legacy duplicate/source archive; use version1/java or version2/java
```

The root `java/` directory is retained as a legacy duplicate/archive. For current work, use the Java project inside the version being changed.
