# Sprint backlogs and progress evidence

These are **reconstructed delivery backlogs** based on the supplied project artefacts and dated validation records. They document the work for assessment, but do not claim that formal sprint planning meetings, estimates or stakeholder sign-off occurred. Dates refer to evidence timestamps; the exact original assignment dates for each item are not known.

| Sprint / review window | Goal | Backlog items | Done evidence | Remaining risk |
| --- | --- | --- | --- | --- |
| S1, model and forms review (through 27 Sep 2026) | Make the hospital pathway deployable and connect user tasks to forms | PB-01 to PB-05 | `1-17.bpmn`, 16 root forms, `Form_Task_Mapping.csv`, `WORK_REPORT.md`; 81-task binding audit | Clinical policy values remain assumptions until stakeholder validation. |
| S2, workers and data (through 28 Sep 2026) | Cover all automated hospital steps and persist synthetic business objects | PB-06, PB-07, PB-08, PB-09, PB-10 | `java/WORKER_MAP.md`, Java source and H2 schema; Maven reports; runtime status files | External replies are local simulations. |
| S3, assessment packaging (29 Sep 2026) | Publish traceable plan, tests, decisions, readable diagram and fixed repository version | PB-11 to PB-13 | This submission directory, PDF exports, presentation, repository commit/tag | Reviewer must confirm the public URL and repeat key scenarios in their own cluster. |

## Definition of done used for this submission

An item is complete when its artefact exists in the published repository, an assessor can locate it from `SUBMISSION_INDEX.md`, and the stated acceptance measure has a linked evidence file. A runtime-sampled item proves only the cited path; it does not imply exhaustive testing of all branch combinations.

## Review points

| Date | Observable event | Evidence |
| --- | --- | --- |
| 27 Sep 2026 | Model repair and Java worker baseline recorded | `WORK_REPORT.md`; Java test reports |
| 28 Sep 2026 | Modeler and engine validation, form audit and full/branch runtime exercises recorded | `evidence/validation/`; `evidence/runtime/`; `_analysis` local narrative copied into `TEST_RESULTS.md` |
| 29 Sep 2026 | Submission packaging and targeted regression tests run | `evidence/tests/`; Git commit and tag |
| 29 Sep 2026, evidence closure | Two negative Camunda tests and 53 design-time FEEL cases run; open scenarios itemised | `EVIDENCE_CLOSURE_RECORD.md`; `evidence/tests/`; `evidence/validation/feel-run-manifest.json`; revision r2 tag |

## Next backlog refinement

PB-14 should be split by integration (scheduler, insurer, payment provider, treatment service, correspondence) after real API contracts, security constraints and operational owners are confirmed. This work is outside the current synthetic-data submission.
