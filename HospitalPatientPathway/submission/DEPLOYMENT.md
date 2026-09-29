# Deployment configuration and reproduction

Target used for the evidence: self-managed Camunda 8.9.19 at `http://localhost:8080` and gRPC `http://localhost:26500`. The Java application targets Java 21. The environment is a local demonstration.

## Resources and boundaries

The sole deployable process definition is `../1-17.bpmn` (`Process_Hospital_Integrated`). The 16 `.form` files in the bundle root use deployment binding and must be uploaded in the **same deployment** as the BPMN. The `.process-application` marker supports Camunda Modeler deployment. Historical `.bpmn.keep` and `.form.keep` files in the local `_analysis` folder are archival material and are excluded from the published repository.

External participant pools show message contracts and responsibilities. Their processes are non-executable; the Java simulation publishes external replies in the local demo. Replace those adapters with approved integrations before real use.

## Run

1. Start a Camunda 8.9 compatible cluster and confirm REST port 8080 and gRPC port 26500.
2. From the bundle root, deploy all resources together. In PowerShell:

   ```powershell
   $forms = Get-ChildItem -File -Filter *.form | ForEach-Object { '-F'; "resources=@$($_.Name)" }
   curl.exe -sS -X POST http://localhost:8080/v2/deployments -F "resources=@1-17.bpmn" @forms
   ```

3. Copy `../java/application-example.properties` to a private location or set equivalent Spring properties. Confirm the Camunda endpoints and simulation settings. Never use the demonstration H2 data directory as a production system of record.
4. Start workers with `cd java` then `./mvnw.cmd spring-boot:run` on Windows or `./mvnw spring-boot:run` on a compatible Unix shell.
5. Start a new instance through the plain start event in Tasklist. Use synthetic `referralId` and `patientId` values. Follow `../MANUAL_TEST_GUIDE.md` for task variables and external message starts.

## Deployment checks

- Response from `/v2/deployments` contains one process definition and 16 forms; confirm all expected form IDs.
- `BpmnModelCoverageTest` sees exactly one `.bpmn` and one copy of each form ID in the project tree.
- Modeler lint and deployment-scope engine validation report zero errors/warnings (`../evidence/validation/`).
- Do not infer live external integration from a completed simulated run. The simulation switches and their defaults are documented in `../java/README.md`.

The process definition version in `../evidence/runtime/deployment-v4-black-style.json` is the version used by the cited runtime acceptance evidence. A fresh deployment creates a new version and key.
