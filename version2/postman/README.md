# Version 2 Postman toolkit

**English** | [简体中文](README.zh-CN.md)

This collection contains **36 requests** for the current `version2/hospital-patient-pathway.bpmn`, process `Process_HPAS`: deployment, instance creation, referral entry, external responses, all nine event subprocesses, observation and authorised closure. It covers all **22 distinct messages** received by the executable process. Requests call Camunda 8.9 directly; the Java project provides job workers, not a patient CRUD HTTP server.

## Setup

1. Import `HPAS_Version2.postman_collection.json` and `HPAS_Version2_Local.postman_environment.json` into Postman.
2. Select **Hospital Pathway - Version 2 HPAS - Local**. `baseUrl` defaults to `http://localhost:8080`, without a trailing slash or `/v2`. The collection checks `processDefinitionId=Process_HPAS` to catch an incorrect environment.
3. Start Camunda 8.9. The collection defaults to No Auth; configure the cluster's required authentication on the collection if enabled. Requests inherit it. Keep credentials local.
4. Set the file working directory to `version2`. The deployment request references **38 files**: `hospital-patient-pathway.bpmn` and all 37 `forms/*.form`. Reselect them in **Body → form-data → resources** if imported paths do not resolve. Exclude `source/`, `evidence/`, version1 and frozen acceptance snapshots. Let Postman generate the multipart Content-Type/boundary.
5. From `version2/java`, start the worker with JDK 21+ (PowerShell):

   ```powershell
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hpas.simulation.external-responses=false"
   ```

This disables simulated external replies so Postman can provide them. Workers still execute automated tasks and publish internal handoffs such as clinic-letter, refund and treatment-change events. Version1's `hospital.simulation.*` settings do not apply. Use Postman Desktop or a local agent with access to your files and cluster; a cloud runner's localhost is not your computer.

## Walkthrough

1. Send topology, deployment and definition search from **01 - Setup and referral entry**.
2. Send **04 - Create HPAS instance**. Empty referral/patient IDs are generated uniquely; the returned instance key is saved. The instance waits at `Top_Catch_Referral`.
3. Send **05 - Referral received**, then complete `SP01_CheckDocuments` in [Tasklist](http://localhost:8080/tasklist). Use the environment's patient ID, supply a fictional staff ID and mark documents complete.
4. Complete `SP02_ClinicalReview` with `reviewDecision=accept` and required fields. Send the main P03 scheduling result at `SP03_Catch_Slot`, then Clinic visit completed at `SP03_Catch_Visited`.
5. Complete assessment, consent and treatment-request forms. `SP04_CreateRequest` requires `treatmentPlanId`, `authorisingClinician`, `requiredStartDate`, `resourceType` and the other form fields. Missing required worker inputs return the request for correction. The sample uses internal resources and hospital funding; human authorisation and clinical decisions remain explicit form inputs.
6. Send Laboratory result at `SP08_Catch_Lab`. In `SP08_Review`, the example uses `fitnessDecision=fit` and `moreCycles=false` to finish treatment and reach the closure wait.
7. Complete any active clinic-letter tasks that the worker triggered: draft, approval and administrative processing. Send **Close pathway at Top_WaitClosure** only at that wait. Other active event branches must also finish before the root instance completes.

Use **04 - Observe current instance** and [Operate](http://localhost:8080/operate) to inspect state, tasks, variables and incidents. This manual walkthrough was not executed against a running cluster during this change.

**Do not run the whole collection sequentially.** Requests include alternative outcomes and separate event branches. Choose the request matching the current wait; this is not an automated acceptance suite.

## Version-specific behavior

- Start order: **create instance → publish `HPAS: Referral received` → complete referral form**. The version1 form-before-entry-message order does not apply.
- SP03B and SP09–SP16 are nine non-interrupting event subprocesses within an active instance. They do not create new root instances or select an exclusive entry route.
- Every message uses the `HPAS: ` prefix and correlates on **`referralId`**, including laboratory results. `patientId` is business data.
- `HPAS: Scheduling result` is shared by the main consultation (`slotFound`), appointment change (`slotAcceptable`) and follow-up (`inRequestedPeriod`). The collection offers three payloads, but request labels do not route messages. If several branches wait for that name/key simultaneously, the current model cannot target a specific receiver. Test these separately or ensure only the intended wait is active.
- Workers also trigger internal event branches. Inspect active nodes before manually duplicating a clinic-letter, refund or treatment-plan update event.
- SP16 uses `eventKind=access` or `interruption`. The current model has no service-restored message catch.
- Normal closure at `Top_WaitClosure` uses no extra variables. Pre-attendance closure at `SP03_Catch_Close` requires `sp03Outcome=closed`. Neither is a generic cancel operation.
- Creation selects the latest active `Process_HPAS` definition. Frozen acceptance snapshots reuse this ID. Avoid mixed deployments during testing; verify the version and redeploy the current BPMN when needed.

## Variables and optional paths

| Variable | Purpose |
| --- | --- |
| `baseUrl` | Camunda server |
| `processDefinitionId` | `Process_HPAS` |
| `referralId` | Message correlation key; generated as `REF-V2-PM-...` when empty |
| `patientId` | Patient identifier; generated as `PAT-V2-PM-...` when empty |
| `processInstanceKey` | Saved after creation; can be set manually for an existing instance |
| `futureDate` | Request-local UTC date 21 days from now |

Clear referral/patient IDs or supply new unique values before each independent run. Keep them stable for the current instance. For an existing instance, enter its actual identifiers and key.

For patient-pay testing, set `fundingRoute=patient-pay` and a numeric fictional `chargeAmount` in the creation body. An `appointmentId` is also required; normal booking workers generate it. Select one payment result: completed, failed or confirmation-lost. For refunds, record `refundDue` and `financeApprover` in `SP11_Determine`, then send the result at `SP11_Catch_Result`. All samples are for fictional demonstrations.

Message TTL is **900000 milliseconds (15 minutes)**. Each Send generates a fresh `messageId`; use a fixed ID when testing deduplication. HTTP 200 confirms publication, not correlation or business completion. Search indexes are eventually consistent; retry after recent changes. Searches return 100 records per page (20 for definitions); use returned cursors for more results.

## Validation and references

Requests include status, returned-key and search-array assertions, plus environment/correlation guards. Validation covered the Postman v2.1 schema, request JSON, 38 deployment files, all 22 message names/keys against the BPMN and script execution. Local Camunda port 8080 was unavailable, so live API and end-to-end business validation were not performed.

- [Version2 overview](../README.md)
- [Acceptance plan and variable mapping (Chinese)](../docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)
- [Actual worker simulation behavior](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java)
- [Camunda 8.9 instance creation](https://docs.camunda.io/docs/8.9/apis-tools/orchestration-cluster-api-rest/specifications/create-process-instance/)
- [Camunda 8.9 messages and correlation](https://docs.camunda.io/docs/8.9/components/concepts/messages/)
