# Project plan and planning evidence

## Objective and scope

Deliver an assessable Camunda 8 hospital patient pathway with one executable hospital process, human forms, local external worker simulation, traceable acceptance tests and a published repository version. The BPMN also describes external parties as non-executable collaboration pools. It does not constitute a live clinical or payment service.

## Milestones and dependency chain

| Milestone | Exit criterion | Dependency | Artefact/evidence |
| --- | --- | --- | --- |
| M1 - model structure | Entry, participant/lane boundaries, decisions and exceptions are explicit; validator passes | Case study interpretation | `1-17.bpmn`; `evidence/validation/modeler-runtime-final.json`; `evidence/validation/engine-runtime-final.json` |
| M2 - forms | All 81 collaboration user tasks, including 70 hospital tasks, have deployment-bound editable forms | M1 task IDs | Root `.form` files; `Form_Task_Mapping.csv`; `evidence/validation/form-mapping-audit.json` |
| M3 - workers | Each executable automated model step has a matching job type | M1 service/send tasks | `java/src/main`; `java/WORKER_MAP.md`; `evidence/tests/BpmnModelCoverageTest.txt` |
| M4 - run and test | Main route and selected alternatives reach expected states with no incidents | M1-M3 and Camunda 8.9 cluster | `evidence/runtime/` statuses; `TEST_RESULTS.md` |
| M5 - submission | Plans, decisions, PDFs, slides and tagged public repository are accessible | M1-M4 | `SUBMISSION_INDEX.md`; deliverables; Git tag |

## Roles and responsibility assumptions

No named project team or stakeholder approvals were supplied. For planning purposes, the submitter owns model/code/documents and executes tests; the assessor reviews the repository. Operational BPMN lanes assign domain activities to the medical secretaries, consultants, specialist nurses, bookings, finance, pathway coordinators, call handlers, administrators, IT support and senior management. Those lane assignments are design assumptions and need hospital confirmation before production.

## Risks, mitigations and open decisions

| ID | Risk / decision | Response in this submission | Owner before real deployment |
| --- | --- | --- | --- |
| R1 | Clinical urgency and eligibility rules are not fully specified by the supplied material | Capture decisions and route to clinician rather than inventing a clinical threshold | Clinical lead |
| R2 | External parties are simulated | Keep message contracts explicit; label runtime results as simulation; plan live adapter replacement | Integration lead |
| R3 | A whole-process canvas is hard to read in print | Supply 17 stage views as a PDF and retain one editable master BPMN | Process owner |
| R4 | Timers, concurrent letters and every exception combination are not covered by current runtime evidence | Separate executed results from planned acceptance cases; include pending gates | Test lead |
| R5 | Local H2 and synthetic IDs do not meet patient-data governance | Keep repository free of the local DB and use only synthetic records | Data owner |
| R6 | Historical `.bpmn`/`.form` backup files can break Modeler process-application deployment | Store backup extensions as `.keep` locally and publish only one deployable BPMN and 16 forms | Deployer |

## Planning evidence register

The file timestamps and reports substantiate delivered work. They do not prove formal meetings or stakeholder approval.

| Evidence | What it establishes |
| --- | --- |
| `WORK_REPORT.md` | Dated repair decisions, worker implementation and early test history. |
| `evidence/validation/modeler-runtime-final.json` | Camunda Modeler 8.9 lint result with 0 errors/warnings. |
| `evidence/validation/engine-runtime-final.json` | Schema validity and 0 deployment-scope errors/warnings; non-executable participant findings are separately identified. |
| `evidence/validation/form-mapping-audit.json` | Task/form path audit. |
| `evidence/runtime/deployment-v4-black-style.json` | One deployed hospital definition plus forms in the evidence environment. |
| `evidence/runtime/*-status.json` | Final state, active tasks and incidents for specific simulated instances. |
| `evidence/tests/*.txt` | Test command results and Maven summary. |
| `evidence/tests/TEST-*.xml` and `maven-evidence-closure-2026-09-29.log` | Current method-level test results and the complete 28/28 run. |
| `evidence/validation/feel-run-manifest.json` | Reproducible link between the BPMN SHA-256, 53 rule cases and their actual result. |
| `EVIDENCE_CLOSURE_RECORD.md` | Dated decisions, actions, exit measures and remaining evidence needed; a current record, not a backdated planning meeting. |
| `submission/PRODUCT_BACKLOG.csv` and `SPRINT_BACKLOGS.md` | Traceable current backlog and reconstructed sprint view. |

## Submission control

The repository tag `hospital-pathway-final-2026-09-29-r2` freezes this revised submission; the earlier `hospital-pathway-final-2026-09-29` tag remains an audit baseline. If files change afterward, the new commit is outside r2 until a new tag is issued. The linked public repository should remain readable without login.
