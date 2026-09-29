package io.camunda.demo.hospital;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.demo.hospital.simulation.ExternalEventService;
import io.camunda.demo.hospital.support.Bundle;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.ProcessInstanceSelectors;
import io.camunda.process.test.api.assertions.UserTaskSelectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the integrated pathway against a real Camunda 8 cluster.
 *
 * <p>The test framework starts in {@code remote} mode and uses the cluster from
 * {@code application.properties} (the local {@code c8run} installation), because the default
 * {@code managed} mode would need Docker. Deploy the model and start the cluster before running
 * these tests:
 *
 * <pre>
 * c8run start                # in camunda8-getting-started-bundle-8.9.0-windows-x86_64/c8run-8.9.0
 * mvn test -Dtest=HospitalPathwayProcessTest
 * </pre>
 *
 * <p>The integrated model starts at its single plain start event, registers the referral
 * ({@code R1_RegisterCheck}) and then waits at the external-trigger router for one of the entry
 * messages. A segment that the router selects runs through its automated steps (the job workers)
 * and its human steps (completed here through the process-test context) to the completed end
 * event. What is verified here: the model deploys with its forms, the entry messages route
 * correctly, the new workers complete the automated steps, and the human steps can be completed
 * with the variables the forms produce.
 */
@SpringBootTest(properties = {
        "camunda.process-test.runtime-mode=remote",
        "camunda.process-test.remote.client.enabled=true",
        "camunda.process-test.remote.client.grpc-address=http://127.0.0.1:26500",
        "camunda.process-test.remote.client.rest-address=http://127.0.0.1:8080",
        "camunda.process-test.remote.client.deployment.enabled=false",
        "hospital.simulation.auto-responder-enabled=false",
        "hospital.simulation.retry-attempts-before-success=0",
        "hospital.simulation.slot-availability=available",
        "hospital.simulation.treatment-services-available=true",
        "hospital.simulation.funding-authorised=true",
        "hospital.simulation.payment-outcome=paid"})
@CamundaSpringProcessTest
@DisplayName("The workers run the integrated pathway on a Camunda 8 cluster")
class HospitalPathwayProcessTest {

    private static final String PROCESS_ID = "Process_Hospital_Integrated";

    @Autowired
    private CamundaClient client;

    @Autowired
    private CamundaProcessTestContext processTestContext;

    @Autowired
    private ExternalEventService externalEventService;

    /** Referral id the started instance carries; every entry message correlates on it. */
    private String referralId;

    /**
     * Deploys the model and its deployment-bound forms together, exactly as they lie in the bundle
     * folder. The forms are part of the deployment of the model, so they have to be sent with it.
     */
    @BeforeEach
    void deployPathway() throws IOException {
        Path model = Bundle.model();
        var deployment = client.newDeployResourceCommand()
                .addResourceBytes(Files.readAllBytes(model), model.getFileName().toString());
        for (Path form : Bundle.forms()) {
            deployment = deployment.addResourceBytes(Files.readAllBytes(form), form.getFileName().toString());
        }
        deployment.send().join();
    }

    @Test
    @DisplayName("a monitoring run entered through the router is carried out by the reporting workers")
    void monitoringRunIsCarriedOutByTheWorkers() {
        long instanceKey = startAtRouter();

        publish(HospitalMessages.MONITORING_REQUEST, Map.of("requestedBy", "performance and quality team"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .isCompleted()
                .hasCompletedElements("P15_Task_Collect", "P15_Task_Generate", "P15_Task_Publish")
                .hasVariable("reportsPublished", true);
    }

    @Test
    @DisplayName("an access request runs through the human steps and the audit worker")
    void accessRequestRunsThroughHumanSteps() {
        long instanceKey = startAtRouter();

        publish(HospitalMessages.ACCESS_REQUEST, Map.of(
                "targetStaffUserId", "STAFF-1",
                "requestType", "role change",
                "requestedRole", "clinic administrator"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P16_Task_Authenticate");

        completeUserTask("P16_Task_Authenticate", instanceKey,
                Map.of("targetStaffUserId", "STAFF-1",
                        "targetStaffName", "Alex Morgan",
                        "requestType", "role change",
                        "requestedRole", "clinic administrator",
                        "verificationMethod", "line manager confirmation",
                        "identityEvidenceReference", "ID-STAFF-1",
                        "accessAuthorised", "yes",
                        "verificationNotes", "verified by the line manager"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P16_Task_Grant");

        completeUserTask("P16_Task_Grant", instanceKey,
                Map.of("approvedRole", "clinic administrator",
                        "grantedPermissions", "view clinic letters",
                        "accessScope", "clinic letters of this referral",
                        "authorisingAdministrator", "service manager"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .isCompleted()
                .hasCompletedElements("P16_Task_Audit");
    }

    @Test
    @DisplayName("denied access is audited without a permission grant")
    void deniedAccessIsAuditedWithoutGrant() {
        long instanceKey = startAtRouter();

        publish(HospitalMessages.ACCESS_REQUEST, Map.of(
                "targetStaffUserId", "STAFF-DENIED-1",
                "requestType", "role change",
                "requestedRole", "clinic administrator"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P16_Task_Authenticate");

        completeUserTask("P16_Task_Authenticate", instanceKey,
                Map.of("targetStaffUserId", "STAFF-DENIED-1",
                        "targetStaffName", "Synthetic Denied User",
                        "requestType", "role change",
                        "requestedRole", "clinic administrator",
                        "verificationMethod", "line manager confirmation",
                        "identityEvidenceReference", "ID-DENIED-1",
                        "accessAuthorised", "no",
                        "verificationNotes", "request denied by manager"));

        // The denial branch bypasses P16_Task_Grant and joins the audit task.
        // If a grant task is created, this instance will remain active instead of completing.
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .isCompleted()
                .hasCompletedElements("P16_Task_Audit")
                .hasVariable("accessAuthorised", "no");
    }

    @Test
    @DisplayName("an unverified appointment change returns to identity correction")
    void unverifiedAppointmentChangeCannotUpdateBooking() {
        long instanceKey = startAtRouter();

        publish(HospitalMessages.APPOINTMENT_CHANGE_REQUEST, Map.of(
                "patientId", "PAT-IDENTITY-TEST",
                "appointmentChangeType", "appointment"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P17_Task_RecordRequest");

        completeUserTask("P17_Task_RecordRequest", instanceKey,
                Map.of("patientId", "PAT-IDENTITY-TEST",
                        "appointmentChangeType", "appointment",
                        "requestChannel", "telephone"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P17_Task_VerifyIdentity");

        completeUserTask("P17_Task_VerifyIdentity", instanceKey,
                Map.of("identityVerified", "no",
                        "identityEvidenceReference", "INSUFFICIENT",
                        "verificationNotes", "caller cannot confirm identity"));

        // The only no branch of P17_Gateway_IdentityVerified returns to recording/correction.
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("P17_Task_RecordRequest")
                .hasVariable("identityVerified", "no");
    }

    @Test
    @DisplayName("missing documents are requested, answered and correlated back into the pathway")
    void missingDocumentsAreRequestedAndCorrelatedBack() {
        String referralId = "REF-MISSING-" + System.nanoTime();
        // The registration step already answered the documents question with "no", so the referral
        // entry message takes the R1_RequestDocs branch of R1_Complete.
        long instanceKey = startAtRouter("no");

        publish(HospitalMessages.INCOMING_REFERRAL, Map.of(HospitalMessages.REFERRAL_ID, referralId));

        // The worker of send-referral-documents-request published the answer of the referring
        // organisation, so the receive task R1_ReceiveDocs is passed and R1_UpdateReferral waits.
        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasCompletedElements("R1_RequestDocs", "R1_ReceiveDocs")
                .hasActiveElements("R1_UpdateReferral");

        completeUserTask("R1_UpdateReferral", instanceKey,
                Map.of("documentsCompleteChoice", "yes", "referralUpdateNotes", "documents complete"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("R2_Review");
    }

    @Test
    @DisplayName("a referral is reviewed, declined and the outcome is sent to the referrer")
    void declinedReferralEndsAfterSendingTheOutcome() {
        String referralId = "REF-DECLINED-" + System.nanoTime();
        // The registration step answered "yes", so the referral entry message goes straight to the
        // review; the registration step itself was already completed by startAtRouter.
        long instanceKey = startAtRouter("yes");

        publish(HospitalMessages.INCOMING_REFERRAL, Map.of(HospitalMessages.REFERRAL_ID, referralId));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .hasActiveElements("R2_Review");

        completeUserTask("R2_Review", instanceKey,
                Map.of("referralSuitableChoice", "no", "referralDecisionReason", "not clinically suitable"));

        CamundaAssert.assertThat(ProcessInstanceSelectors.byKey(instanceKey))
                .isCompleted()
                .hasCompletedElements("R2_Decline")
                .hasVariable("referralOutcome", "declined");
    }

    // --- helpers -------------------------------------------------------------------------

    /**
     * Starts an instance at the plain start event (with a referral id every entry message
     * correlates on) and completes the registration step so the token waits at the
     * external-trigger router for one of the entry messages.
     */
    private long startAtRouter() {
        return startAtRouter("yes");
    }

    /**
     * Starts an instance whose registration step answers the documents question with the given
     * choice: {@code "yes"} sends the pathway straight on to the review, {@code "no"} makes it
     * request the missing documents first.
     *
     * <p>The registration step sits <em>before</em> the external-trigger router, so it is completed
     * exactly once, here - a test must never complete it again after publishing an entry message.
     */
    private long startAtRouter(String documentsCompleteChoice) {
        referralId = "REF-TEST-" + System.nanoTime();
        long instanceKey = client.newCreateInstanceCommand()
                .bpmnProcessId(PROCESS_ID)
                .latestVersion()
                .variables(Map.of(HospitalMessages.REFERRAL_ID, referralId))
                .send()
                .join()
                .getProcessInstanceKey();

        completeUserTask("R1_RegisterCheck", instanceKey,
                Map.of("documentsCompleteChoice", documentsCompleteChoice));
        return instanceKey;
    }

    /**
     * Completes a user task with the variable shape the model reads.
     *
     * <p>Every user task of the model maps its form fields through {@code taskData_<elementId>}:
     * the task's output mapping reads {@code taskData_<elementId>.<field>} and lifts it into the
     * process variable of the same name. Completing a task with the flat field names instead makes
     * that mapping write null into the variables, and the gateway behind the task then takes the
     * wrong branch - see {@code ../WORK_REPORT.md}, section 17.4.
     */
    private void completeUserTask(String elementId, long instanceKey, Map<String, Object> fields) {
        processTestContext.completeUserTask(
                UserTaskSelectors.byElementId(elementId, instanceKey),
                Map.of("taskData_" + elementId, fields));
    }

    /** Publishes an entry message correlated on the referral id of the started instance. */
    private void publish(String messageName, Map<String, Object> variables) {
        java.util.Map<String, Object> merged = new java.util.HashMap<>(variables);
        merged.put(HospitalMessages.REFERRAL_ID, referralId);
        client.newPublishMessageCommand()
                .messageName(messageName)
                .correlationKey(referralId)
                .variables(merged)
                .timeToLive(java.time.Duration.ofMinutes(1))
                .send()
                .join();
    }
}
