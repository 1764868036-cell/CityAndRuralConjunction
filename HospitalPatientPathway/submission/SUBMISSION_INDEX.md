# Hospital Patient Pathway - submission index

Submission snapshot: 29 September 2026. The deployable process is `../1-17.bpmn` (`Process_Hospital_Integrated`). This index distinguishes the executable hospital participant from the external collaboration participants, which are represented in the diagram and simulated locally.

| Requirement | Submitted artefact | Verification or boundary |
| --- | --- | --- |
| BPM&EA operational BPMN | `../1-17.bpmn` | Camunda 8.9 Modeler lint: 0 errors, 0 warnings; engine deployment-scope validation: 0 errors, 0 warnings. |
| Deployment configuration | `../README.md`, `../.process-application`, `DEPLOYMENT.md` | Deploy the root BPMN and 16 root `.form` resources together. |
| External workers | `../java/src/main/`, `../java/pom.xml`, `../java/mvnw*`, `../java/README.md` | Model-to-worker mapping in `../java/WORKER_MAP.md`; 50 job types cover 51 automated steps. |
| Worker configuration template | `../java/application-example.properties` | Copy or adapt for the target Camunda endpoint; default code settings remain in `src/main/resources/application.properties`. |
| Editable forms and task bindings | 16 root `.form` files, `../Form_Task_Mapping.csv`, `../1-17.bpmn` | All 81 collaboration user tasks have deployment-bound forms, including 70 in the executable hospital process. |
| Product and sprint backlogs | `PRODUCT_BACKLOG.csv`, `SPRINT_BACKLOGS.md` | Current submission plan, traced to evidence. Historical sprint ceremonies are not asserted. |
| Project plan and evidence | `PROJECT_PLAN_AND_EVIDENCE.md`, `EVIDENCE_CLOSURE_RECORD.md` | Milestones, dependencies, risks and a current dated action record. Historical planning ceremonies are not asserted. |
| Business process test plan | `../deliverables/Hospital_Patient_Pathway_Business_Process_Test_Plan_EN.docx` (primary), `BUSINESS_PROCESS_TEST_PLAN_EN.md` (editable source), `../deliverables/Hospital_Patient_Pathway_Business_Process_Test_Plan_EN.pdf` (reference) | English V1.2 plan with individual AT-01 to AT-16 statuses, evidence index and exit criteria. The earlier Chinese version remains as a reference. |
| Acceptance test plan | `../deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.docx` (primary, supplied Word document), `ACCEPTANCE_TEST_PLAN.md` (r6 text reference), `../deliverables/Hospital_Patient_Pathway_Acceptance_Test_Plan.pdf` (r5 reference) | English detailed cases with preconditions, data, actions, expected outcomes and pass/fail criteria. The supplied Word document is the authoritative r7 copy. |
| Test results and evidence | `TEST_RESULTS.md`, `../evidence/` | Current 28/28 Java tests, 53/53 FEEL cases, runtime instance traces, and an earlier environment-error test for transparency. |
| AISD design justification | `DESIGN_DECISIONS.md` | Process structure, boundaries, allocation, gateways, interactions, exceptions and trade-offs. |
| Readable BPMN PDF | `../deliverables/Hospital_Patient_Pathway_BPMN_Review.pdf` | 17 stage views with a key and source reference; editable source remains the BPMN. |
| Presentation | `../deliverables/Hospital_Patient_Pathway_Test_Review_EN_20260930_v3.pptx` | 22-slide process and test review. |
| Repository version | Git tag `hospital-pathway-final-2026-09-29-r7` | The tag identifies the submission with the supplied acceptance-plan Word document; earlier versions remain available. |

The project is a **local, synthetic-data demonstration**. External services and people are represented by collaboration pools and simulation code; the evidence does not establish live hospital, insurer, payment or correspondence integration. The status of each acceptance scenario is in `TEST_RESULTS.md`.

## Review order

1. Open `../deliverables/Hospital_Patient_Pathway_BPMN_Review.pdf` for a readable stage-by-stage view, then inspect `../1-17.bpmn` in Camunda Modeler for the complete editable collaboration.
2. Read `DESIGN_DECISIONS.md` and the two primary Word test plans; the acceptance-plan Markdown is an earlier text reference.
3. Inspect `TEST_RESULTS.md` and its referenced evidence files before repeating the tests in `DEPLOYMENT.md`.
4. Use `../deliverables/Hospital_Patient_Pathway_Test_Review_EN_20260930_v3.pptx` for the presentation.
