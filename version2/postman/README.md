# Your first Postman walkthrough with Version 2

**English** | [简体中文](README.zh-CN.md) | [Advanced reference](REFERENCE.md)

**Postman sends requests to a program and shows its answers.** Here, you use it to tell Camunda to create a demonstration patient pathway, report an appointment result and deliver a laboratory result. You then check whether the process advances.

Follow one route first: complete documents → accept referral → book appointment → attend clinic → consent → hospital treatment → laboratory review → closure. Use fictional demonstration data throughout.

## What does each tool do?

| Item | Purpose |
| --- | --- |
| Postman desktop app | Where you click **Send** |
| Collection JSON in this directory | Imports 36 prepared requests into the sidebar; it is not an installer |
| Environment JSON in this directory | Stores the server address and current case identifiers |
| Camunda | Runs the process diagram and decides what happens next |
| Java worker | Executes the process's automated tasks |
| Tasklist | A browser page where you fill in human-task forms |
| Operate | A browser page where you inspect the current process position |

**Click Send → Postman sends a request → Camunda receives it → the process advances → workers handle automated tasks and people complete forms.**

Reach the first successful response in step 3 before continuing with the full pathway.

## 1. Install Postman and get the project files

1. Download the Windows desktop installer for your computer from the [official Postman download page](https://www.postman.com/downloads/).
2. Run the installer and open **Postman**. Opening its download website does not start the desktop app.
3. If you do not have the project, open the [GitHub repository](https://github.com/1764868036-cell/CityAndRuralConjunction), choose **Code → Download ZIP**, and extract it.
4. Open its `version2` directory. You should see `hospital-patient-pathway.bpmn`, `forms`, `java` and `postman`.

The signed-out lightweight client can send the simple request in step 3. To use this collection and environment in step 4, sign in or create a Postman account and enter a workspace. See the [official lightweight-client explanation](https://learning.postman.com/docs/getting-started/basics/using-api-client/).

## 2. Start Camunda

These instructions use Windows and a local Camunda 8.9 installation. If it is already running, go to step 3.

1. Have **JDK 21** installed. Open PowerShell and run `java -version`. If Java is not found, finish installing the JDK and configuring `JAVA_HOME`, then reopen the terminal.
2. Download and extract Camunda 8 Run if needed, following the [Camunda 8.9 installation guide](https://docs.camunda.io/docs/8.9/self-managed/quickstart/developer-quickstart/c8run/install-start/).
3. In File Explorer, open the directory containing `c8run.exe`. Type `powershell` in the address bar and press Enter.
4. Run:

```powershell
.\c8run.exe start
```

Wait for startup and keep the window open. The next step checks that Camunda can actually receive requests.

## 3. Send your first request

In the **Postman app**:

1. Choose **New → HTTP**.
2. Select **GET** beside the address bar and enter `http://localhost:8080/v2/topology`.
3. Click **Send**.
4. Look at the response area below.

**Expected:** **200 OK** and JSON containing cluster information such as `brokers` and `clusterSize`. JSON is text that programs use to exchange data; you do not need to understand every field yet.

This asks Camunda for its cluster information. It does not create a patient pathway. `localhost` means your computer; `8080` is the service port.

For `ECONNREFUSED` or `Could not send request`, check step 2 first. Continue only after this works. This guide assumes an unauthenticated local API. For 401/403, configure Authorization according to your cluster.

## 4. Import the prepared requests

1. Sign in to Postman and enter a workspace.
2. Click **Import** and select these two files from `version2/postman`:
   - `HPAS_Version2.postman_collection.json`
   - `HPAS_Version2_Local.postman_environment.json`
3. Under **Collections**, expand **Hospital Patient Pathway - Version 2 HPAS - Camunda 8.9**.
4. Select **Hospital Pathway - Version 2 HPAS - Local** in the environment selector instead of **No environment**.
5. Under **Environments**, open that environment. Check `baseUrl=http://localhost:8080` and `processDefinitionId=Process_HPAS`. Leave the other three values empty for the first run.

| Interface term | Meaning |
| --- | --- |
| GET / POST | Request method; already selected for each prepared request |
| URL | Which server and endpoint receive the request |
| Body | Data sent with the request; mostly prepared for you |
| Send | Send this one request |
| Response | The server's answer |
| `{{baseUrl}}` | Substitute the server address from the selected environment |

Open **01 - Setup and referral entry (run individually) → 01 - Check cluster topology** and click Send. Expect another 200 response.

**Send requests individually. Do not Run the entire collection.** Its requests include alternative success, failure and cancellation routes.

## 5. Deploy the diagram and forms

Open **02 - Deploy version2 BPMN and all 37 forms → Body → form-data**.

Upload **1 BPMN + 37 forms = 38 files**. Importing JSON does not upload those files. If the imported paths are unresolved:

1. Clear the existing file selection on the `resources` row and select `version2/hospital-patient-pathway.bpmn`.
2. Add another row with the same key, **resources**, and choose type **File**.
3. On this new row, open `version2/forms` and select all **37 .form files**.
4. Ensure both rows are enabled and click Send. Let Postman generate Content-Type for the upload.

Exclude files from `version1`, `source` and `evidence`.

**Expected:** 200 and deployment details. Send **03 - Find HPAS definitions** next. Its `items` should contain `processDefinitionId: "Process_HPAS"`. Search can take a moment to reflect a deployment.

## 6. Start the Java worker

In File Explorer, open the project's `version2/java` directory. Type `powershell` in the address bar, press Enter, and run:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hpas.simulation.external-responses=false"
```

The first run downloads dependencies. Wait for application startup without a startup failure, and **leave this window open**.

The argument makes appointment, attendance and laboratory replies manual so you can send them with Postman. Automated tasks still run, and the worker can still trigger internal clinic-letter tasks.

## 7. Create a case and send the referral

1. Send **04 - Create HPAS instance** with its existing Body.
2. After a 200 response, inspect the environment: `referralId`, `patientId` and `processInstanceKey` should now have values. Copy `patientId` for the form.
3. Send **05 - Referral received**.

**Expected:** Open [Tasklist](http://localhost:8080/tasklist), sign in with your local Camunda account, and find **Check referral supporting documents**. No referral task before sending the referral message is normal.

For each form: open the current case's task, assign it to yourself if required, fill it in, complete the task, then refresh the list.

If there are many tasks, use **04 - Observe current instance → Search user tasks** to identify this instance's tasks. **Search variables** in the same folder shows saved form values. In [Operate](http://localhost:8080/operate), find the instance matching the environment's `processInstanceKey` to inspect its active nodes.

## 8. Check documents, accept the referral and book the appointment

Enter fictional staff ID `DEMO-STAFF-01` in **Responsible staff ID** on every form. All examples below are demonstration text, not actual clinical judgments.

| Where | Action | Expected next state |
| --- | --- | --- |
| Tasklist: **Check referral supporting documents** | Check **All required referral documents received**; set **Verified patient ID** to the environment's patientId; leave missing documents empty; complete | Referral-review task |
| Tasklist: **Review referral and record decision** | Set **Authorised consultant decision** to **Accept**, **Clinical reason for decision** to `Demo referral accepted`, **Consultant identity** to `DEMO-CONSULTANT-01`; leave Urgent referral unchecked; complete | Operate shows `SP03_Catch_Slot` active |
| Postman: **P03 - Scheduling result (slot found)** | Keep Body unchanged and Send | Process reaches `SP03_Catch_Visited` |
| Postman: **P03 - Clinic visit completed** | Send when that attendance wait is active | Assessment task appears |

Both P03 requests are in **02 - External responses (send at the matching wait)**, along with the laboratory request used later. If the expected wait is absent, inspect the worker and current tasks before sending again.

## 9. Complete assessment, consent and the treatment request

Complete these three tasks in order. Remember **Responsible staff ID** on each form.

| Task | Field | Demonstration value |
| --- | --- | --- |
| **Assess patient, discuss diagnosis and options** | Diagnosis and clinical findings | `Demo assessment` |
| Same task | Options and risks explained | `Demo options discussed` |
| Same task | Patient agrees to proceed with treatment | Checked |
| **Record informed consent** | Signed consent record reference | `CONSENT-DEMO-01` |
| Same task | Consent discussion | `Demo consent recorded` |
| **Create authorised Treatment Booking Request** | Treatment plan ID | `PLAN-DEMO-01` |
| Same task | Treatment and resources | `Demo hospital treatment` |
| Same task | Required start date (YYYY-MM-DD) | A future date, with a four-digit year and two-digit month/day |
| Same task | Number of cycles | `1` |
| Same task | Booking resource | **Hospital** |
| Same task | Authorising clinician | `DEMO-CONSULTANT-01` |

The creation request already selected hospital funding (`fundingRoute=hospital`), so this example does not need payment messages.

**Expected:** Automated booking tasks run and the main process reaches `SP08_Catch_Lab`. Clinic-letter tasks may also appear; handle them as described in step 11.

## 10. Send the laboratory result and complete treatment

1. Confirm `SP08_Catch_Lab` is active in Operate.
2. Send **P08 - Laboratory result** in Postman with the default Body.
3. Open **Clinical review of fitness to continue** in Tasklist:
   - **Clinical fitness decision:** **Fit to continue**.
   - **Further treatment cycles planned:** unchecked.
   - **Blood test / review reference:** keep the supplied value; if empty, enter `LAB-` followed by the full environment `referralId`.
   - **Clinical reasoning:** `Demo review completed`.
   - **Responsible staff ID:** `DEMO-STAFF-01`.
4. Complete the task.

**Expected:** After automated processing, the main process waits at `Top_WaitClosure`.

## 11. Finish clinic letters and close the pathway

The worker may trigger letters at multiple stages. Complete each active group of tasks below; do not manually duplicate the Clinic letter required event.

| Tasklist task | Demonstration inputs |
| --- | --- |
| **Prepare clinic letter (clinical content)** | **Clinical findings, decisions and follow-up:** `Demo clinic letter`; **Intended recipients:** `Demo recipient` |
| **Approve clinical content** | Check **Consultant approves clinical content**; Approval notes can be empty |
| **Administrative checks, confirm recipients** | **Administrative check:** **Ready to distribute**; leave **Follow-up requested in letter** unchecked; **Confirmed recipients:** `Demo recipient`; **Approved distribution channel:** `Demo internal channel` |

Enter **Responsible staff ID** and complete each task.

Once the main process is at `Top_WaitClosure`, send **05 - Authorised closure (choose matching wait) → Close pathway at Top_WaitClosure**.

Then send **04 - Observe current instance → Get current HPAS instance**. **Final success means state `COMPLETED`**, also visible in Operate. If it remains `ACTIVE`, check for unfinished letters or other branches.

A 200 response means that request was accepted. It does not prove that the whole pathway completed.

## 12. Repeat or stop

For a new independent case, clear `referralId`, `patientId` and `processInstanceKey` in the environment, then restart at step 7. **Keep these values unchanged during a case.** Redeployment is unnecessary unless the definition changed or Camunda was reset.

After practice, press Ctrl+C in the worker window and run `.\c8run.exe stop` from the Camunda launcher directory. Stopping services does not delete process data.

## If you get stuck

| Symptom | First check |
| --- | --- |
| No Collections or Environments | Sign in and enter a workspace instead of the lightweight client |
| Environment error or unresolved variables | Select the Version 2 environment; check baseUrl and Process_HPAS |
| Connection refused / request cannot be sent | Start Camunda and check local port 8080; this guide uses the desktop app |
| 401 / 403 | Configure collection Authorization for your cluster; requests inherit it |
| Deployment cannot find files | Reselect the 38 local files in step 5 |
| No process definition at creation | Run Find HPAS definitions and verify the current main BPMN deployment |
| No human task after creation | Send Referral received and inspect this instance |
| Stuck on an automated task | Inspect the worker window and Search incidents |
| Message returns 200 but no progress | Check the active wait, matching referralId and message name; avoid repeated Sends |
| Search temporarily empty | Wait briefly for indexing and refresh |
| Form cannot be completed | Check required fields, especially Responsible staff ID, and task assignment |

## Connect one request to the code

Open **P03 - Scheduling result (slot found) → Body**:

```json
{
  "name": "HPAS: Scheduling result",
  "correlationKey": "{{referralId}}",
  "timeToLive": 900000,
  "messageId": "{{$guid}}",
  "variables": {
    "slotFound": true,
    "appointmentDate": "{{futureDate}}"
  }
}
```

The name identifies an appointment result; the correlation key identifies the referral; `slotFound: true` says that a slot was found. The collection generates a date to replace `{{futureDate}}`.

| File | Search for | Connection to the walkthrough |
| --- | --- | --- |
| [Collection](HPAS_Version2.postman_collection.json) | `P03 - Scheduling result (slot found)` | Defines the HTTP address and JSON Postman sends |
| [BPMN](../hospital-patient-pathway.bpmn) | `SP03_Catch_Slot`, `slotFound` | Receives the result and selects the next path |
| [Java executor](../java/src/main/java/io/camunda/demo/hpas/HpasJobExecutor.java) | `hpas-sp03-search-slot`, `reply` | Normally simulates this reply; step 6 disables it so you can send it |
| [Treatment request form](../forms/hpas_sp04_createrequest.form) | `treatmentPlanId`, `required` | Defines the fields and required inputs in step 9 |

These requests call **Camunda's API directly**. The Java worker receives automated jobs from the engine; it is not a patient CRUD HTTP service.

The instructions were checked against the collection, BPMN, forms and worker code. The full manual Postman + Tasklist route still needs execution; expected outcomes above are not a record of a completed run. See the [advanced reference](REFERENCE.md) for message rules, optional payment paths and validation scope.
