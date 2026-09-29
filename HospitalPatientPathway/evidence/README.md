# Test evidence directory

The project uses synthetic identifiers and a local Camunda 8.9.19 cluster. The files below are copied, fixed evidence for this submission.

| Folder | Contents | How to read |
| --- | --- | --- |
| `tests/` | Maven clean-test console log and five Surefire text summaries; an earlier log shows the unavailable-cluster attempt | The successful command exited 0 and the current suite totals 26/26. |
| `validation/` | Modeler, engine, form mapping and form-js browser check JSON | Distinguish executable hospital deployment-scope findings from warnings about non-executable external pools. |
| `runtime/` | Deployment response and 13 groups of status, event-log and element-trace files | Check an instance's status, then inspect its matching `.jsonl` and `-trace.json` to confirm the route. |

The master interpretation and acceptance-status mapping are in `../submission/TEST_RESULTS.md`. Do not infer that a `COMPLETED` instance alone proves every path or live external integration.
