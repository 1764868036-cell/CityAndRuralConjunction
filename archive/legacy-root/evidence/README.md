# Test evidence directory

The project uses synthetic identifiers and a local Camunda 8.9.19 cluster. The files below are copied, fixed evidence for this submission.

| Folder | Contents | How to read |
| --- | --- | --- |
| `tests/` | Current full and targeted Maven logs, five Surefire text summaries and five method-level XML reports; earlier baseline and unavailable-cluster logs | The current clean suite exited 0 and passed 28/28. The earlier connection error is historical, not a current failure. |
| `validation/` | Modeler, engine, form mapping, form-js browser and FEEL results, plus a SHA-256 FEEL manifest | FEEL passed 53/53 design-time cases against the tagged BPMN; it does not prove Camunda token execution. Distinguish executable hospital deployment-scope findings from non-executable external-pool warnings. |
| `runtime/` | Deployment response and 13 groups of status, event-log and element-trace files | Check an instance's status, then inspect its matching `.jsonl` and `-trace.json` to confirm the route. |

Inspect each runtime status, event log and element trace together. A `COMPLETED` instance alone does not prove every path or live external integration.
