# Hospital Patient Pathway — standalone redesign

Independent Camunda 8.9 project. See [Chinese guide](README.zh-CN.md).

## Integrated SD and SR

[Editable piStar TXT](goalModel.txt) · [Single green SVG](istar/Hospital_SD_SR_Version2.svg) · [PNG](istar/Hospital_SD_SR_Version2.png) · [Scope and notation](istar/README.md).

The compact strategic process view is available as [BPMN](strategic-model.bpmn). It follows the course guidance with one pool, a referral-to-outcome happy path, seven responsibility lanes, ten main flow objects and six artifacts. Two subprocesses group funding and treatment details. The file is non-executable; the detailed i* model and deployable operational BPMN remain separate.

The model follows the current BPMN collaboration boundary: 17 actors, including all 7 external participants, 94 intentional elements and 34 task-to-task dependencies. Hospital lanes are represented as internal roles; patient, referrer, scheduling, correspondence, payment, funding and external treatment services remain external. Each actor maps to a BPMN participant, and every source ID is checked against the current BPMN. SP05 availability follows the HTTP JSON Connector; the strategic provider task is an abstraction of the API response. Rebuild the editable TXT and structural report with `python version2/tools/build_istar.py`, then render SVG, PNG and the local viewer with `python version2/tools/render_istar.py`.

## Postman

[`postman/`](postman/README.md) provides **36 Version 2 requests**, a local environment and bilingual guides, covering all 22 messages received by `Process_HPAS`. Import both JSON files, create an instance, publish `HPAS: Referral received`, then complete the referral form in Tasklist. Deploy only the main BPMN and the 37 forms; the nine event subprocesses run inside active instances.

## Project files and checks

```text
hospital-patient-pathway.bpmn   Deployable BPMN with drill-down planes
strategic-model.bpmn            Compact non-executable strategic process
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
