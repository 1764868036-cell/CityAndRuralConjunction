# Integrated hospital model runtime

The executable definition is `Process_Hospital_Integrated` in `../1-17.bpmn`.
Start it from Tasklist, complete the referral form, then publish the chosen external
message using the same `referralId`. Publishing an entry message by itself no longer
creates a hospital instance.

## Local validation run

Use a new referral and patient identifier for each run. Start the worker with:

```powershell
mvn -f .\java\pom.xml spring-boot:run "-Dspring-boot.run.arguments=--hospital.simulation.scenario=none --hospital.simulation.auto-responder-enabled=false --hospital.simulation.retry-attempts-before-success=0"
```

This does not deploy BPMN or forms. Deploy the BPMN and all `.form` files together;
the user task form bindings use deployment binding.

The optional automatic responder is intended for one explicitly configured demo
referral and patient. Keep it disabled when manually driving a new test instance.
Request/response job handlers use the identifiers in their activated job, so their
responses return to the correct referral.

## External responses

The worker simulates requests to scheduling, treatment capacity, funders, payment
providers and correspondence. It makes no real bank, email or patient contact calls.
Internal hospital send tasks record the hand-off and allow the sequence flow to
continue; they do not start a second process instance.

The manual driver must provide unsolicited patient preferences, consent/refusal,
laboratory results and any selected entry event. Message names and correlation key
variables are centralized in `support/HospitalMessages.java`. Names must match the
BPMN message **name**, not its XML identifier. All hospital messages have a key and
are buffered briefly until their intermediate catch is active. Missing keys and
publication errors fail a job so it can be retried, rather than completing a task
without delivering its response.

All messages referenced by the integrated hospital process use the name prefix
`Hospital pathway: `. This prevents the local demo from starting historical process
versions which still subscribe to unprefixed message names. Manual drivers must use
the prefixed names, for example `Hospital pathway: Incoming referral package`.
The clinic letter completion message deliberately has zero TTL and only releases
the active reminder subscription for its unique `clinicLetterId`.

Build without running the historical integration tests against the shared cluster:

```powershell
mvn -o -f .\java\pom.xml -DskipTests compile
```

The historical integration tests and old scenario list predate the single-start
model and are not an isolated acceptance suite for a running shared cluster.
