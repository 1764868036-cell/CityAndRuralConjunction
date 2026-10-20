# Hospital Patient Pathway — standalone redesign

Independent Camunda 8.9 project. See [Chinese guide](README.zh-CN.md).

## Integrated SD and SR

[Editable piStar TXT](goalModel.txt) · [Single green SVG](istar/Hospital_SD_SR_Version2.svg) · [PNG](istar/Hospital_SD_SR_Version2.png) · [Scope and notation](istar/README.md).

The model adapts the VERSION2 BPMN to the revised hospital boundary: 15 actors, including 4 external actors, 86 intentional elements and 27 task-to-task dependencies. Scheduling and correspondence providers are hidden implementation details in this strategic view; they remain external participants in BPMN. Clinical support services are internal under the revised business scope, while the current BPMN still models the laboratory and treatment provider as external. Consultants and the Clinical Team retain separate responsibilities. Rebuild the TXT with `python version2/tools/build_istar.py` from the repository root.

## Postman

[`postman/`](postman/README.zh-CN.md) contains the SP05 external-availability collection and local Mock configuration. Follow that guide to import `SP05.postman_collection.json` and exercise available, unavailable and upstream-error responses. Deploy the main BPMN and the 37 forms together; the nine event subprocesses run inside active instances.

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

## SP05 REST integration

External availability now uses an HTTP REST Connector. See the [Postman Desktop deployment guide](postman/README.zh-CN.md), [change report](docs/SP05_CHANGE_REPORT.zh-CN.md), [API tests](evidence/SP05_E2E.zh-CN.md), and [browser form verification](evidence/SP05_WEB_E2E.zh-CN.md). Camunda 8.9 uses `SECRET_SP05_AVAILABILITY_URL` (singular prefix). The verified local Mock is `http://localhost:4510/availability`.

The main happy path completed with 12 browser submissions across 9 forms and no incidents. All-branch coverage and role isolation were not established. Inbound Runtime schema errors, cross-letter field reuse and Connector H2 audit limitations remain documented. Historical acceptance results describe a separate frozen snapshot.
