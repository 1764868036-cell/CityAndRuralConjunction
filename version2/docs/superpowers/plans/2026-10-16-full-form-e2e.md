# Full form E2E execution plan

Goal: Submit all 37 deployed forms in real Tasklist tasks, complete representative main paths and event branches with synthetic data.
Architecture: local Camunda 8.9 + Java worker + Tasklist UI; REST only for fixture creation, message delivery, state inspection and cleanup. UI submits forms.

- [x] Inventory forms, fields and gateway conditions; prepare scenarios for all 37 forms.
- [x] Deploy explicit BPMN/forms paths, start workers; create isolated TEST instances.
- [x] Complete hospital, insurer, patient-payment exception paths and treatment closure via Tasklist forms.
- [x] Trigger all event subprocess families; fill alternative enquiry, cancellation, finance and access forms.
- [x] Test weekly reminder with a clearly identified accelerated-timer test definition.
- [x] Verify 37/37 form task completions from engine state, capture screenshots and route outcomes, investigate incidents.
- [x] Cancel leftover fixtures and stop only services launched for this test; publish coverage matrix and limitations.

Results: [full E2E report](../../../evidence/FULL_E2E_TEST.zh-CN.md).
