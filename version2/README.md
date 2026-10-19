# Hospital Patient Pathway — standalone redesign

Independent Camunda 8.9 project. See [Chinese guide](README.zh-CN.md).

## Postman

[`postman/`](postman/README.md) provides **36 Version 2 requests**, a local environment and bilingual guides, covering all 22 messages received by `Process_HPAS`. Import both JSON files, create an instance, publish `HPAS: Referral received`, then complete the referral form in Tasklist. Deploy only the main BPMN and the 37 forms; the nine event subprocesses run inside active instances.

## Project files and checks

```text
hospital-patient-pathway.bpmn   Deployable BPMN with drill-down planes
forms/                         Camunda Forms
java/                          Java workers and Maven wrapper
tools/                         Build, layout, rendering and validation
source/                        Bundled baseline used by the generator
previews/                      SVG and PNG overview
requirements.txt               Python dependencies
```

Run from this directory:

```powershell
python -m pip install -r requirements.txt
python tools/validate_bundle.py
python tools/check_semantics.py hospital-patient-pathway.bpmn
cd java
./mvnw.cmd test
```

Acceptance testing reuses version 1 AT-01 through AT-16. See [adapted plan](docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md) and [results](TEST_RESULTS_VERSION2.zh-CN.md). Run `mvnw.cmd -Pacceptance test` from `java/` against a resettable Camunda 8.9 test cluster; original unmet business criteria intentionally remain failing assertions. The acceptance profile uses the hashed, frozen snapshot in `evidence/acceptance-input/`.
