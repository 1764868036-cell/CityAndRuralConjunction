# Version 2 Postman advanced reference

[Back to the walkthrough](README.md) | [简体中文](REFERENCE.zh-CN.md)

The collection has 36 requests covering all 22 received message names in the current main BPMN, with deployment of one BPMN and 37 forms.

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

Requests include status, returned-key and search-array assertions, plus environment/correlation guards. Validation covered the Postman v2.1 schema, request JSON, 38 deployment files, all 22 message names/keys against the BPMN and script execution. These were static and script checks when the collection was added; they do not establish that the manual Postman + Tasklist walkthrough has passed end to end.

- [Version2 overview](../README.md)
- [Acceptance plan and variable mapping (Chinese)](../docs/ACCEPTANCE_TEST_PLAN_VERSION2.zh-CN.md)
- [Actual worker simulation behavior](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java)
- [Camunda 8.9 instance creation](https://docs.camunda.io/docs/8.9/apis-tools/orchestration-cluster-api-rest/specifications/create-process-instance/)
- [Camunda 8.9 messages and correlation](https://docs.camunda.io/docs/8.9/components/concepts/messages/)
