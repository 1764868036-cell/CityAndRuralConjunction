# Isolated acceptance runtime

`Version2AcceptanceIT` uses REST `8180`, gRPC `27500`, and monitoring `19600` by default.
Keep the demo Worker and Connector Runtime on the demo cluster (`8080` / `26500`).
Do not attach automatic external responders to the acceptance cluster: the test owns
the availability and slot-release messages and mocks the service jobs itself.

From `version2/java`, start the dedicated local Camunda 8.9 runtime:

```powershell
..\tools\start_acceptance_runtime.ps1
```

The script locates the adjacent Camunda distribution by default; use `-CamundaHome`
to select another `camunda-zeebe-8.9.0` directory. Logs, PID, and isolated database
files are stored in `%LOCALAPPDATA%/HPAS/acceptance-runtime`. Wait for startup before
running tests. An already running acceptance runtime can be reused.

Run the screenshot's failing scenario:

```powershell
.\mvnw.cmd '-Dtest=Version2AcceptanceIT#at07_serviceUnavailable' test
```

In IntelliJ IDEA, run the same class/method with the working directory set to
`version2/java`; remove old overrides targeting the demo cluster. Custom dedicated
ports require all three overrides: `acceptance.rest`, `acceptance.grpc`, and
`acceptance.monitoring`.

The runtime starts with `zeebe-clock-controlled=true`. The clock reset 403 means
the management endpoint was reached but its clock was immutable; it is not an
external availability API failure. CPT also cleans runtime data between tests,
so this runtime must remain dedicated to tests.

AT-07 now waits for each message subscription before publishing its corresponding
test message. It must observe the pending state before releasing the external slot.
