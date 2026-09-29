# Evidence closure record - 29 September 2026

This is a **current, dated work record** for strengthening the submission. It does not claim that a historical sprint meeting or stakeholder sign-off occurred. The earlier sprint backlogs in `SPRINT_BACKLOGS.md` remain explicitly reconstructed from artefacts.

## Decisions and completed actions

| ID | Decision or action | Measurable exit | Evidence |
| --- | --- | --- | --- |
| EC-01 | Re-run model business-rule expressions against the exact submission BPMN | 1386 expressions parse; 53/53 mapped branch cases pass; model SHA-256 recorded | `evidence/validation/feel-2026-09-29.json`, `feel-cases-2026-09-29.json`, `feel-run-manifest.json` |
| EC-02 | Add a runtime negative test for access denial | Denial bypasses permission grant and completes the audit branch | `java/src/test/java/io/camunda/demo/hospital/HospitalPathwayProcessTest.java`; current Surefire XML and full test log |
| EC-03 | Add a runtime negative test for appointment-change identity | `identityVerified=no` returns to request correction before updating a booking | Same test class and current Surefire XML/log |
| EC-04 | Re-run the complete Java test suite with Camunda available | 28/28 tests pass, 0 failures/errors/skips | `evidence/tests/maven-evidence-closure-2026-09-29.log` and five Surefire summaries/XML reports |
| EC-05 | Publish a fixed public repository version | Tag resolves to the tested commit; anonymous download of the submission index returns HTTP 200 | Git tag `hospital-pathway-final-2026-09-29-r2` |

## Evidence chain

The local Camunda runtime statuses and traces from 28 September contain instance identifiers and start/end times. The Java test reports record the current 29 September test run. The FEEL manifest binds its 53 cases and result to the current BPMN bytes with SHA-256. Git commits and the version tag identify what the assessor downloads. These records establish that the cited work and tests occurred; they do **not** establish prior planning ceremonies.

## Open acceptance work, with exit conditions

| Priority | Case(s) | Work still needed | Exit evidence |
| --- | --- | --- | --- |
| High | AT-05 to AT-08 | Run unavailable-slot, consent refusal, declined funding/service and duplicate-charge scenarios through Camunda | New instance ID, submitted variables, element trace, final status/incident check for each case |
| High | AT-11 | Run valid and invalid appointment dates plus near/far contact routes end to end | Traces proving no late booking and phone call at the 14-day boundary |
| Medium | AT-14 | Observe suspected-error letter route, timer intervals and two concurrent letters | Correlated letter IDs, timer events, completion status and no cross-letter variable leakage |
| Medium | AT-13 | Run unauthorised refund as a negative engine case | Trace proving no refund worker invocation and a defined alternate outcome |

The FEEL cases cover several of these gateway boundaries at design time. Their results cannot replace the listed Camunda token and message traces.

## Historical planning evidence still needed from the project team

If the assessment expects original sprint ceremonies, attach genuine records such as a dated board export, issue history, meeting minutes or task assignment messages. Record their original dates and owners. Do not backdate this file or present the reconstructed sprint table as an original meeting record.
