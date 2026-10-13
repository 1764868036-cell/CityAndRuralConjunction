# Camunda roles and user-input validation

This configuration targets Camunda **8.9 Tasklist V2**. All 70 executable hospital user tasks
now have a candidate group selected from their BPMN lane. Tasks start unassigned so a real
member of the responsible team can claim them. The 11 external-pool tasks are documentation
of external participants and are not executable hospital work queues.

| Lane / responsibility | Camunda group ID |
| --- | --- |
| Medical Secretaries | `medical-secretaries` |
| Consultants | `consultants` |
| Clinical Nurse Specialists | `clinical-nurse-specialists` |
| Outpatient Bookings | `outpatient-bookings-team` |
| Treatment and Chemotherapy Bookings | `treatment-bookings-team` |
| Finance | `finance-team` |
| Patient Pathway Coordinators | `pathway-coordinators` |
| Call Handling | `call-handlers` |
| Administrative Manager | `administrative-managers` |
| IT Support and System Administration | `system-administrators` |
| Senior Management | `senior-management` |

## Configure the cluster

1. Merge [camunda-roles.yaml](camunda-roles.yaml) into the **cluster's** configuration, retaining
   its authentication provider, administrator account and storage settings. Do not put this file
   into the Java worker application's properties. Restart the cluster with the merged configuration.
2. In Camunda Admin, add real staff accounts (or OIDC mappings) to their approved groups. The
   file creates no users or passwords. Each group inherits its corresponding `hospital-<groupId>`
   role for Tasklist component access and the built-in `task-worker` role for property-based
   `READ`, `CLAIM`, `COMPLETE` task access.
3. Check the resulting roles and authorizations in Admin, including existing installations where
   initialization may leave existing records unchanged. Ordinary staff must not also inherit
   `admin`, wildcard `USER_TASK` grants, or process-wide `READ_USER_TASK`, `UPDATE_USER_TASK`,
   `CLAIM_USER_TASK`, `COMPLETE_USER_TASK` permissions: broader grants override task restrictions.
4. Configure the worker's Camunda credentials for the protected API using your chosen provider.
   Give its service account the job-worker/message permissions required by the existing workers;
   task-worker permissions alone do not authorize job activation or message publishing. Keep
   deployment and process-start privileges on explicitly designated operator accounts.
5. Build the Java workers and deploy `1-17.bpmn` together with all 16 root `.form` files. Run
   the new workers before completing tasks. When deploying via Modeler, keep `java/target/`
   free of old deployable BPMN/form copies; the new build packages form definitions as `.json`.

The `system-administrators` department group can handle process 16 tasks; it does not receive
Camunda platform administrator rights. Process 16 records the authorization decision and audit
trail. An authorized identity administrator applies the approved group membership in Camunda
Admin; submitting `requestedRole`, `approvedRole`, or free-text permissions does not change
the submitter's identity or grant platform permissions. These two form choices use group IDs
from the table, not arbitrary text or usernames.

Candidate groups require protected APIs and enabled authorizations to enforce access. Existing
process instances retain their deployed model version; use new instances for the updated model.
For existing customizations of `task-worker`, check that its grants remain property-based on
`assignee`, `candidateUsers` and `candidateGroups`.

## Input validation

All 16 forms constrain text length, required nonblank text, listed options, 24-hour times, ISO
timestamps and durations, money precision/range, and whole-number cycle/reminder counts. Dates
remain native date fields. The role fields now offer only the configured hospital groups.

`HospitalFormValidator` reads copies of those exact forms packaged into the worker JAR. Every
hospital user task has a `completing` listener (`validate-hospital-user-input`), which validates
both common fields and `taskData_<task element ID>`. The engine's element ID selects the rules;
changing `hospitalFormTask` cannot select a different contract. Inactive sections are ignored,
and the existing conditional clinical-approval fields are required only when visible.

Server checks also reject impossible calendar dates, nonpositive retry durations, missing
documents explanations when documents are incomplete, patient shares exceeding estimated cost,
nonpositive approved refunds, and transfers without a destination booking reference. A rejection
returns field names and correction reasons without echoing patient data. The completion is denied,
so no task output mappings or downstream steps run; correct the inputs and submit again. If the
listener worker is unavailable, completion waits for it rather than bypassing validation.

Rebuild and deploy forms and workers from the same revision. API integrations must supply the
same nested input structure and JSON types as Tasklist (numbers as JSON numbers, checkbox values
as booleans, radio/select values as strings). External message payloads and privileged process
modification operations are separate integration boundaries, not user-task submissions.

## Verification

From `java/`, run the cluster-independent regression checks:

```powershell
.\mvnw.cmd "-Dtest=BpmnModelCoverageTest,HospitalSecurityContractTest,HospitalFormValidatorTest,UserInputValidationWorkerTest,HospitalDatabaseTest,CorridorMessageAuditTest,WorkerPersistenceTest" test
```

Use a disposable Camunda 8.9 cluster for `HospitalPathwayProcessTest`; that existing suite purges
the test cluster. Its successful form submissions now include all required fields. For access
acceptance testing, use two non-admin staff accounts in `medical-secretaries` and `finance-team`:

1. Start a new referral with an operator account. The secretary can see and claim
   `R1_RegisterCheck`; the finance account cannot read, claim or complete that task through
   Tasklist or the V2 API. Repeat with a finance task and the accounts reversed.
2. Submit whitespace required text, an unknown select value, `2026-02-30`, `25:70`, a negative
   amount, a fractional cycle count, or `P0D` through the completion API. The task must stay active.
3. Submit corrected, complete data. The task must complete and advance exactly once.
4. On `P9_Task_ConfirmClinicalApproval`, choose `no`; hidden approval fields may be absent.
   Choose `yes`; all three approval fields must then be supplied.

The local tests cover contracts, form parity, validation and listener commands. Actual access
enforcement and runtime listener behavior must also be checked on the configured cluster.

References: [8.9 user-task authorization](https://docs.camunda.io/docs/8.9/components/tasklist/user-task-authorization/),
[8.9 user-task listeners](https://docs.camunda.io/docs/8.9/components/concepts/user-task-listeners/),
[cluster initialization properties](https://docs.camunda.io/docs/8.9/self-managed/components/orchestration-cluster/core-settings/configuration/properties/).
