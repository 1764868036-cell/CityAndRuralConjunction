package io.camunda.demo.hpas;

import static io.camunda.process.test.api.CamundaAssert.assertThat;
import static io.camunda.process.test.api.assertions.ProcessInstanceSelectors.byKey;
import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.DeploymentEvent;
import io.camunda.process.test.api.CamundaAssert;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import io.camunda.process.test.api.assertions.UserTaskSelectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.TestInfo;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.w3c.dom.Element;

/**
 * Reuses the version 1 acceptance plan's business scenarios against the exact version 2 bundle.
 * Java is used for parameterized business tables, two-instance correlation and segment assertions.
 * Workers are controlled routing stubs; worker business logic is tested independently.
 */
@SpringBootTest(classes = Version2AcceptanceIT.TestApplication.class, properties = {
    "camunda.process-test.runtime-mode=remote",
    "camunda.process-test.remote.client.enabled=true",
    "camunda.process-test.remote.client.grpc-address=${acceptance.grpc:http://127.0.0.1:27500}",
    "camunda.process-test.remote.client.rest-address=${acceptance.rest:http://127.0.0.1:8180}",
    "camunda.process-test.remote.camunda-monitoring-api-address=${acceptance.monitoring:http://127.0.0.1:19600}",
    "camunda.process-test.remote.client.deployment.enabled=false"})
@CamundaSpringProcessTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Version2AcceptanceIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}

    @Autowired CamundaClient client;
    @Autowired CamundaProcessTestContext context;
    private final List<Long> created = new ArrayList<>();
    private final Map<Long, String> referrals = new HashMap<>();
    private DeploymentEvent deployment;
    private long definitionKey;
    private String caseName;
    private final String runId = UUID.randomUUID().toString();
    private static final Path BUNDLE = Path.of("..").toAbsolutePath().normalize();
    private static final Path INPUT = BUNDLE.resolve("evidence/acceptance-input");
    private static final String B = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String Z = "http://camunda.org/schema/zeebe/1.0";

    @BeforeEach
    void deployExactBundleAndStubExternalWork(TestInfo testInfo) throws Exception {
        caseName = testInfo.getDisplayName();
        CamundaAssert.setAssertionTimeout(Duration.ofSeconds(12));
        // CPT removes deployments owned by the preceding test during cleanup.
        deployment = null;
        if (deployment == null) {
            var command = client.newDeployResourceCommand().addResourceBytes(
                Files.readAllBytes(INPUT.resolve("hospital-patient-pathway.bpmn.keep")), "hospital-patient-pathway.bpmn");
            try (var paths = Files.list(INPUT.resolve("forms"))) {
                for (Path form : paths.filter(p -> p.toString().endsWith(".form.keep")).sorted().toList())
                    command = command.addResourceBytes(Files.readAllBytes(form), form.getFileName().toString().replace(".keep", ""));
            }
            deployment = command.send().join();
            definitionKey = deployment.getProcesses().get(0).getProcessDefinitionKey();
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var model = factory.newDocumentBuilder().parse(INPUT.resolve("hospital-patient-pathway.bpmn.keep").toFile());
        var definitions = model.getElementsByTagNameNS(Z, "taskDefinition");
        var types = new LinkedHashSet<String>();
        for (int i = 0; i < definitions.getLength(); i++) types.add(((Element) definitions.item(i)).getAttribute("type"));
        for (String type : types) context.mockJobWorker(type).thenComplete();
    }

    @AfterEach
    void cancelOnlyTestCreatedResidualRoots() throws Exception {
        for (long key : created) {
            var pi = client.newProcessInstanceGetRequest(key).send().join();
            Files.writeString(BUNDLE.resolve("evidence/acceptance-instance-traces.jsonl"),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "runId", runId, "case", caseName, "instanceKey", Long.toString(key),
                    "processDefinitionKey", Long.toString(definitionKey),
                    "stateBeforeCleanup", pi.getState().name(), "hasIncident", pi.getHasIncident())) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if ("ACTIVE".equals(pi.getState().name())) client.newCancelInstanceCommand(key).send().join();
        }
        created.clear();
        referrals.clear();
    }

    @Test @DisplayName("AT-01 — exact version 2 BPMN and all 37 forms deploy together")
    void at01_bundleDeploys() {
        assertThat(deployment.getProcesses()).hasSize(1);
        assertThat(deployment.getProcesses().get(0).getBpmnProcessId()).isEqualTo("Process_HPAS");
        assertThat(deployment.getForm()).hasSize(37);
    }

    @Test @DisplayName("AT-02 — complete suitable referral traverses the whole main pathway and closes")
    void at02_completeSuitableReferral() {
        long pi = openReferral();
        complete(pi, "SP01_CheckDocuments", Map.of("documentsComplete", true));
        complete(pi, "SP02_ClinicalReview", Map.of("reviewDecision", "accept"));
        publish(pi, "Scheduling result", Map.of("slotFound", true));
        publish(pi, "Clinic visit completed", Map.of("sp03Outcome", "attended"));
        complete(pi, "SP04_Assess", Map.of("consentGiven", true));
        complete(pi, "SP04_RecordConsent", Map.of());
        complete(pi, "SP04_CreateRequest", Map.of("requestValid", true));
        publish(pi, "Lab result", Map.of());
        complete(pi, "SP08_Review", Map.of("fitnessDecision", "fit", "moreCycles", false));
        assertThat(byKey(pi)).hasActiveElements("Top_WaitClosure");
        close(pi);
        assertThat(byKey(pi)).isCompleted().hasCompletedElements("SP03_ConfirmBooking", "SP05_BookInternal",
            "SP08_Administer", "Top_End_Closed");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-03 — missing documents correlate only to the intended referral and resume review")
    void missingDocumentsAreRequestedAndCorrelatedBack() {
        long pi = openReferral();
        long other = openReferral();
        complete(pi, "SP01_CheckDocuments", Map.of("documentsComplete", false));
        complete(other, "SP01_CheckDocuments", Map.of("documentsComplete", false));
        assertThat(byKey(pi)).hasActiveElements("SP01_Catch_Supplementary");
        publish(pi, "Supplementary information received", Map.of("supplementaryReceived", true));
        complete(pi, "SP01_CheckDocuments", Map.of("documentsComplete", true));
        assertThat(byKey(pi)).hasActiveElements("SP02_ClinicalReview")
            .hasCompletedElement("SP01_RequestMissing", 1).hasNoActiveElements("SP01_Catch_Supplementary");
        assertThat(byKey(other)).hasActiveElements("SP01_Catch_Supplementary");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-04 — unsuitable referral notifies the referrer and ends without consultation")
    void declinedReferralEndsAfterSendingTheOutcome() {
        long pi = openReferral();
        complete(pi, "SP01_CheckDocuments", Map.of("documentsComplete", true));
        complete(pi, "SP02_ClinicalReview", Map.of("reviewDecision", "reject"));
        assertThat(byKey(pi)).isCompleted().hasCompletedElements("SP02_NotifyRejected", "Top_End_ReferralClosed")
            .hasNotActivatedElements("SP03_RequestSlot", "SP05_BookInternal", "SP08_Administer");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-05 slotFound={0}, urgent={1}")
    @CsvSource({"true,false", "false,false", "false,true"})
    void at05_schedulingAvailabilityAndHumanEscalation(boolean found, boolean urgent) {
        long pi = start("SP03_GW_Urgent", Map.of("urgentReferral", urgent));
        publish(pi, "Scheduling result", Map.of("slotFound", found));
        if (!found) {
            assertThat(byKey(pi)).hasActiveElements("SP03_NoSlotReview");
            complete(pi, "SP03_NoSlotReview", Map.of("schedulingExceptionReason", "TEST recorded exception"));
        }
        assertThat(byKey(pi)).isWaitingForMessage("HPAS: Clinic visit completed", referrals.get(pi)).hasCompletedElements("SP03_ConfirmBooking");
        publish(pi, "Clinic visit completed", Map.of("sp03Outcome", "attended"));
        assertThat(byKey(pi)).hasActiveElements("SP04_Assess");
        if (urgent) assertThat(byKey(pi)).hasCompletedElements("SP03_FastTrack");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-05 — failed near-term phone contact escalates after three attempts")
    void at05_phoneEscalation() {
        long pi = start("SP03_GW_Urgent", Map.of("withinTwoWeeks", true));
        publish(pi, "Scheduling result", Map.of("slotFound", true));
        for (int attempt = 1; attempt <= 3; attempt++)
            complete(pi, "SP03_PhoneContact", Map.of("phoneAttempts", attempt, "contactOutcome", "unreachable"));
        complete(pi, "SP03_EscalateContact", Map.of());
        assertThat(byKey(pi)).hasCompletedElement("SP03_PhoneContact", 3)
            .isWaitingForMessage("HPAS: Clinic visit completed", referrals.get(pi)).hasCompletedElements("SP03_EscalateContact");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-06 — consent refusal prevents all treatment booking and administration")
    void at06_consentRefusal() {
        long pi = start("SP04_Assess", Map.of());
        complete(pi, "SP04_Assess", Map.of("consentGiven", false));
        assertThat(byKey(pi)).hasActiveElements("Top_WaitClosure");
        close(pi);
        assertThat(byKey(pi)).isCompleted().hasNotActivatedElements("SP04_RecordConsent", "SP05_BookInternal",
            "SP05_BookExternal", "SP07_SendPaymentRequest", "SP08_Administer");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-07 — unavailable external service remains pending until availability is restored")
    void at07_serviceUnavailable() {
        long pi = start("SP05_RequestAvailability", Map.of("resourceType", "external"));
        assertThat(byKey(pi)).isWaitingForMessage("HPAS: External availability result", referrals.get(pi));
        publish(pi, "External availability result", Map.of("externalAvailable", false));
        assertThat(byKey(pi)).hasActiveElements("SP05_Catch_Released").hasNotActivatedElements("SP05_BookExternal", "SP08_Administer");
        assertThat(byKey(pi)).isWaitingForMessage("HPAS: External slot released", referrals.get(pi));
        publish(pi, "External slot released", Map.of("externalAvailable", true));
        assertThat(byKey(pi)).hasCompletedElements("SP05_BookExternal").hasActiveElements("SP08_Catch_Lab");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-08 paymentStatus={0}")
    @ValueSource(strings = {"completed", "confirmation-lost", "failed"})
    void at08_paymentRoutes(String status) {
        long pi = start("SP07_CalculateCharge", Map.of("fundingRoute", "patient-pay"));
        publish(pi, "Payment status", Map.of("paymentStatus", status));
        if (status.equals("confirmation-lost"))
            complete(pi, "SP07_Investigate", Map.of("investigationOutcome", "taken"));
        if (status.equals("failed")) {
            complete(pi, "SP07_DecideRetry", Map.of("retryDecision", "urgent-override"));
            complete(pi, "SP07_RecordOverride", Map.of("urgentOverrideReason", "TEST clinician decision", "overrideClinician", "TEST-CONSULTANT"));
        }
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_Lab").hasCompletedElement("SP07_SendPaymentRequest", 1);
        if (!status.equals("failed")) assertThat(byKey(pi)).hasCompletedElements("SP07_RecordTransaction", "SP07_ConfirmBooking");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-09 — a fit patient continues only when the next cycle is due")
    void at09_continueTreatment() {
        long pi = start("SP08_Review", Map.of());
        complete(pi, "SP08_Review", Map.of("fitnessDecision", "fit", "moreCycles", true));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_NextDue").hasCompletedElement("SP08_Administer", 1);
        publish(pi, "Next cycle due", Map.of());
        publish(pi, "Lab result", Map.of());
        complete(pi, "SP08_Review", Map.of("fitnessDecision", "fit", "moreCycles", false));
        assertThat(byKey(pi)).hasActiveElements("Top_WaitClosure").hasCompletedElement("SP08_Administer", 2);
        close(pi);
        assertThat(byKey(pi)).isCompleted();
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-09 — delayed treatment waits for the next clinical review without administration")
    void at09_delayTreatment() {
        long pi = start("SP08_Review", Map.of());
        complete(pi, "SP08_Review", Map.of("fitnessDecision", "delay"));
        complete(pi, "SP08_DelayHandling", Map.of("delayReason", "TEST clinical decision"));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_NextDue").hasNotActivatedElements("SP08_Administer");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-14 follow-up within requested period={0}") @ValueSource(booleans = {true, false})
    void at14_followUpPeriod(boolean inPeriod) {
        long pi = start(null, Map.of());
        publish(pi, "Follow-up instruction authorised", Map.of());
        publish(pi, "Scheduling result", Map.of("inRequestedPeriod", inPeriod));
        if (!inPeriod) complete(pi, "SP14_Highlight", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP14_End");
        if (inPeriod) assertThat(byKey(pi)).hasCompletedElements("SP14_Book", "SP14_Inform");
        else assertThat(byKey(pi)).hasNotActivatedElements("SP14_Book");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-01 monitoring overdue months={0}") @ValueSource(ints = {0, 1, 3})
    void monitoringRunIsCarriedOutByTheWorkers(int months) {
        long pi = start(null, Map.of("letterOverdueMonths", months));
        publish(pi, "Monitoring run due", Map.of());
        if (months >= 3) complete(pi, "SP15_EscalateHigher", Map.of());
        else if (months >= 1) complete(pi, "SP15_EscalateManager", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP15_End", "SP15_Generate");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-15 — current downtime recording route reconciles and audits")
    void at15_downtimeCurrentRoute() {
        long pi = start(null, Map.of("eventKind", "interruption"));
        publish(pi, "Access or interruption event", Map.of());
        complete(pi, "SP16_RecordDowntime", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP16_Reconcile", "SP16_Audit", "SP16_End");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-04 — additional clinical information can be received before redirection")
    void at04_informationThenRedirect() {
        long pi = openReferral();
        complete(pi, "SP01_CheckDocuments", Map.of("documentsComplete", true));
        complete(pi, "SP02_ClinicalReview", Map.of("reviewDecision", "information"));
        publish(pi, "Supplementary information received", Map.of());
        complete(pi, "SP02_ClinicalReview", Map.of("reviewDecision", "redirect"));
        assertThat(byKey(pi)).isCompleted().hasCompletedElements("SP02_RequestInfo", "SP02_NotifyRedirected");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-05 — reached patient can receive authorised closure before attending")
    void at05_phoneReachedThenClosure() {
        long pi = start("SP03_ConfirmBooking", Map.of("withinTwoWeeks", true));
        complete(pi, "SP03_PhoneContact", Map.of("phoneAttempts", 1, "contactOutcome", "reached"));
        assertThat(byKey(pi)).isWaitingForMessage("HPAS: Pathway closure authorised", referrals.get(pi));
        publish(pi, "Pathway closure authorised", Map.of("sp03Outcome", "closed"));
        assertThat(byKey(pi)).isCompleted().hasCompletedElements("SP03_Catch_Close", "Top_End_Closed");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-07 — invalid request returns for correction before an external booking")
    void at07_correctInvalidRequest() {
        long pi = start("SP04_CreateRequest", Map.of("requestValid", false));
        complete(pi, "SP04_CreateRequest", Map.of("requestValid", false, "resourceType", "external"));
        assertThat(byKey(pi)).hasCompletedElement("SP04_CreateRequest", 1).hasActiveElements("SP04_CreateRequest");
        complete(pi, "SP04_CreateRequest", Map.of("requestValid", true, "resourceType", "external"));
        publish(pi, "External availability result", Map.of("externalAvailable", true));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_Lab").hasCompletedElement("SP04_CreateRequest", 2);
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-07 fundingRoute={0}")
    @ValueSource(strings = {"exempt", "insurer", "patient-pay"})
    void at07_supportedFundingRoutes(String route) {
        long pi = start("SP06_Determine", Map.of("fundingRoute", route, "exemptionAuthorised", true));
        if (route.equals("insurer")) {
            publish(pi, "Funding approval received", Map.of("fundingAuthorised", true));
            complete(pi, "SP06_RecordApproval", Map.of());
        }
        if (route.equals("patient-pay")) publish(pi, "Payment status", Map.of("paymentStatus", "completed"));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_Lab");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-08 retry after={0}")
    @ValueSource(strings = {"failed", "confirmation-lost"})
    void at08_authorisedRetryAfterNegativeResult(String status) {
        long pi = start("SP07_SendPaymentRequest", Map.of());
        publish(pi, "Payment status", Map.of("paymentStatus", status));
        if (status.equals("failed")) complete(pi, "SP07_DecideRetry", Map.of("retryDecision", "retry"));
        else complete(pi, "SP07_Investigate", Map.of("investigationOutcome", "not-taken"));
        assertThat(byKey(pi)).hasCompletedElement("SP07_SendPaymentRequest", 2).hasActiveElements("SP07_Catch_Status");
        publish(pi, "Payment status", Map.of("paymentStatus", "completed"));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_Lab");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-09 — a treatment-change decision waits for plan update before closure")
    void at09_waitForChangedPlan() {
        long pi = start("SP08_Review", Map.of());
        complete(pi, "SP08_Review", Map.of("fitnessDecision", "change", "moreCycles", false));
        assertThat(byKey(pi)).hasActiveElements("SP08_Catch_PlanUpdated").hasNotActivatedElements("SP08_Administer");
        publish(pi, "Treatment plan updated", Map.of("moreCycles", false));
        assertThat(byKey(pi)).hasActiveElements("Top_WaitClosure");
        close(pi);
        assertThat(byKey(pi)).isCompleted();
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-11 — non-rescheduling requests go to the pathway team")
    void at11_otherAppointmentChange() {
        long pi = start(null, Map.of("changeKind", "other"));
        publish(pi, "Appointment change requested", Map.of());
        complete(pi, "SP03B_Review", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP03B_End").hasNotActivatedElements("SP03B_Rebook");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-12 — paid cancellation refers the financial decision to finance")
    void at12_paidCancellation() {
        long pi = start(null, Map.of());
        publish(pi, "Cancellation reported", Map.of());
        complete(pi, "SP10_Record", Map.of("appointmentPaid", true, "attendanceEvent", "cancelled"));
        complete(pi, "SP10_Decide", Map.of("pathwayResponse", "close"));
        assertThat(byKey(pi)).hasCompletedElements("SP10_NotifyRefund", "SP10_End");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-10 — finance can request a refund after an approved change")
    void at10_financialChangeRequestsRefund() {
        long pi = start(null, Map.of("urgentChange", false, "formallyAuthorised", true, "financialImpact", true));
        publish(pi, "Treatment change requested", Map.of());
        complete(pi, "SP09_FinanceReview", Map.of("refundRequired", true));
        assertThat(byKey(pi)).hasCompletedElements("SP09_NotifyRefund", "SP09_End");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-14 — a routine clinical enquiry is not escalated as urgent")
    void at14_routineClinicalEnquiry() {
        long pi = start(null, Map.of());
        publish(pi, "Patient enquiry received", Map.of());
        complete(pi, "SP12_Record", Map.of("enquiryClass", "clinical"));
        complete(pi, "SP12_AssessPriority", Map.of("urgentConcern", false));
        complete(pi, "SP12_ReferCNS", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP12_End").hasNotActivatedElements("SP12_FlagUrgent");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-14 — an approved letter can issue an authorised follow-up instruction")
    void at14_letterFollowUpInstruction() {
        long pi = start(null, Map.of());
        publish(pi, "Clinic letter required", Map.of());
        complete(pi, "SP13_Draft", Map.of());
        complete(pi, "SP13_Approve", Map.of("letterApproved", true));
        complete(pi, "SP13_AdminProcess", Map.of("adminCheck", "ok", "followUpInstructed", true));
        assertThat(byKey(pi)).hasCompletedElements("SP13_NotifyFollowUp", "SP13_End");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-10 urgent={0}, authorised={1}, financialImpact={2}")
    @CsvSource({"false,true,false", "false,true,true", "false,false,false", "true,true,false", "true,false,false"})
    void at10_formalTreatmentChange(boolean urgent, boolean authorised, boolean financial) {
        long pi = start(null, Map.of("urgentChange", urgent, "formallyAuthorised", authorised, "financialImpact", financial));
        publish(pi, "Treatment change requested", Map.of());
        if (urgent) complete(pi, "SP09_Retrospective", Map.of("formallyAuthorised", authorised));
        if (authorised && financial) complete(pi, "SP09_FinanceReview", Map.of("refundRequired", false));
        assertThat(byKey(pi)).hasCompletedElements(authorised ? "SP09_End" : "SP09_End_Rejected");
        if (authorised) assertThat(byKey(pi)).hasCompletedElements("SP09_ApplyChange");
        else assertThat(byKey(pi)).hasNotActivatedElements("SP09_ApplyChange");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-11 slotAcceptable={0}") @ValueSource(booleans = {true, false})
    void at11_appointmentChangeCurrentRoutes(boolean acceptable) {
        long pi = start(null, Map.of("changeKind", "reschedule"));
        publish(pi, "Appointment change requested", Map.of());
        publish(pi, "Scheduling result", Map.of("slotAcceptable", acceptable));
        if (!acceptable) complete(pi, "SP03B_Review", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP03B_End");
        if (acceptable) assertThat(byKey(pi)).hasCompletedElements("SP03B_Rebook", "SP03B_Notify");
        else assertThat(byKey(pi)).hasNotActivatedElements("SP03B_Rebook");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-12 response={0}")
    @ValueSource(strings = {"re-offer", "clinical-review", "close"})
    void at12_cancellationOutcomes(String response) {
        long pi = start(null, Map.of());
        publish(pi, "Cancellation reported", Map.of());
        complete(pi, "SP10_Record", Map.of("appointmentPaid", false, "attendanceEvent",
            response.equals("close") ? "no-show" : response.equals("clinical-review") ? "declined" : "cancelled"));
        complete(pi, "SP10_Decide", Map.of("pathwayResponse", response));
        if (response.equals("clinical-review")) complete(pi, "SP10_ClinicalReview", Map.of());
        assertThat(byKey(pi)).hasCompletedElements("SP10_End");
        assertThat(byKey(pi)).hasCompletedElements(switch (response) {
            case "re-offer" -> "SP10_NotifyRebook";
            case "clinical-review" -> "SP10_ClinicalReview";
            default -> "SP10_NotifyReferrer";
        });
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-13 refundDue={0}") @ValueSource(booleans = {true, false})
    void at13_refundCurrentRoutes(boolean due) {
        long pi = start(null, Map.of());
        publish(pi, "Refund case opened", Map.of());
        complete(pi, "SP11_Determine", Map.of("refundDue", due, "financeApprover", "TEST-FINANCE"));
        if (due) publish(pi, "Refund result", Map.of("refundStatus", "completed"));
        assertThat(byKey(pi)).hasCompletedElements("SP11_End", due ? "SP11_Record" : "SP11_RecordNone");
        if (!due) assertThat(byKey(pi)).hasNotActivatedElements("SP11_SendRefund");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-14 enquiry={0}, authorised={1}")
    @CsvSource({"admin-simple,true", "admin-transfer,true", "financial,true", "financial,false", "clinical,true"})
    void at14_enquiryClassification(String category, boolean authorised) {
        long pi = start(null, Map.of());
        publish(pi, "Patient enquiry received", Map.of());
        complete(pi, "SP12_Record", Map.of("enquiryClass", category, "infoAuthorised", authorised));
        switch (category) {
            case "admin-simple" -> complete(pi, "SP12_Answer", Map.of());
            case "admin-transfer" -> complete(pi, "SP12_Transfer", Map.of());
            case "financial" -> complete(pi, authorised ? "SP12_AnswerFin" : "SP12_ReferFinance", Map.of());
            case "clinical" -> {
                complete(pi, "SP12_AssessPriority", Map.of("urgentConcern", true));
                complete(pi, "SP12_ReferCNS", Map.of());
            }
        }
        assertThat(byKey(pi)).hasCompletedElements("SP12_End", "SP12_Close");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-14 suspectedClinicalError={0}") @ValueSource(booleans = {true, false})
    void at14_letterClinicalErrorControl(boolean suspectedError) {
        long pi = start(null, Map.of());
        publish(pi, "Clinic letter required", Map.of());
        complete(pi, "SP13_Draft", Map.of());
        complete(pi, "SP13_Approve", Map.of("letterApproved", true));
        complete(pi, "SP13_AdminProcess", Map.of("adminCheck", suspectedError ? "clinical-error" : "ok", "followUpInstructed", false));
        if (suspectedError) {
            assertThat(byKey(pi)).hasActiveElements("SP13_Draft").hasNotActivatedElements("SP13_Distribute");
            complete(pi, "SP13_Draft", Map.of());
            complete(pi, "SP13_Approve", Map.of("letterApproved", true));
            complete(pi, "SP13_AdminProcess", Map.of("adminCheck", "ok", "followUpInstructed", false));
        }
        assertThat(byKey(pi)).hasCompletedElements("SP13_End", "SP13_Distribute");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-14 — seven-day reminder repeats weekly until preparation completes")
    void at14_originalTimerReminder() {
        long pi = start(null, Map.of());
        publish(pi, "Clinic letter required", Map.of());
        assertThat(byKey(pi)).hasActiveElements("SP13_Draft");
        try {
            context.increaseTime(Duration.ofDays(8));
        } catch (RuntimeException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "BLOCKED: remote Camunda clock is immutable; original P7D timer is not shortened. " + unavailable.getMessage());
        }
        complete(pi, "SP13_Reminder", Map.of());
        assertThat(byKey(pi)).hasActiveElements("SP13_Draft").hasCompletedElements("SP13_End_Reminder");
        context.increaseTime(Duration.ofDays(7));
        complete(pi, "SP13_Reminder", Map.of());
        assertThat(byKey(pi)).hasCompletedElement("SP13_Reminder", 2).hasActiveElements("SP13_Draft");
        assertNoIncident(pi);
    }

    @Test @DisplayName("AT-14 — completing a letter cancels its future reminder subscriptions")
    void at14_completedLetterCancelsReminders() {
        long pi = start(null, Map.of());
        publish(pi, "Clinic letter required", Map.of());
        complete(pi, "SP13_Draft", Map.of());
        complete(pi, "SP13_Approve", Map.of("letterApproved", true));
        complete(pi, "SP13_AdminProcess", Map.of("adminCheck", "ok", "followUpInstructed", false));
        assertThat(byKey(pi)).hasCompletedElements("SP13_End");
        try {
            context.increaseTime(Duration.ofDays(8));
        } catch (RuntimeException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "BLOCKED: reminder cancellation requires a controllable test clock. " + unavailable.getMessage());
        }
        assertThat(byKey(pi)).hasNotActivatedElements("SP13_Reminder");
        assertNoIncident(pi);
    }

    @ParameterizedTest(name = "AT-15 accessAuthorised={0}") @ValueSource(booleans = {true, false})
    void at15_accessDecisionRouting(boolean authorised) {
        long pi = start(null, Map.of("eventKind", "access"));
        publish(pi, "Access or interruption event", Map.of());
        complete(pi, "SP16_VerifyAuth", Map.of("accessAuthorised", authorised));
        assertThat(byKey(pi)).hasCompletedElements("SP16_End", "SP16_Audit");
        assertNoIncident(pi);
    }

    private long openReferral() {
        long pi = start(null, Map.of());
        publish(pi, "Referral received", Map.of());
        assertThat(byKey(pi)).hasActiveElements("SP01_CheckDocuments");
        return pi;
    }

    private long start(String element, Map<String, Object> overrides) {
        String referral = "TEST-AT-" + UUID.randomUUID();
        Map<String, Object> variables = new HashMap<>(Map.of(
            "referralId", referral, "patientId", "TEST-PATIENT", "urgentReferral", false,
            "withinTwoWeeks", false, "requestValid", true, "resourceType", "internal",
            "fundingRoute", "hospital", "moreCycles", false, "appointmentPaid", false, "consentGiven", true));
        variables.putAll(overrides);
        var command = client.newCreateInstanceCommand().processDefinitionKey(definitionKey).variables(variables);
        if (element != null) command = command.startBeforeElement(element);
        long pi = command.send().join().getProcessInstanceKey();
        created.add(pi);
        referrals.put(pi, referral);
        return pi;
    }

    private void complete(long pi, String task, Map<String, Object> variables) {
        Map<String, Object> submission = new HashMap<>();
        try {
            var form = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                INPUT.resolve("forms/hpas_" + task.toLowerCase(java.util.Locale.ROOT) + ".form.keep").toFile());
            sampleFields(form.path("components"), submission);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        submission.putAll(variables);
        if (submission.containsKey("referralId")) submission.put("referralId", referrals.get(pi));
        context.completeUserTask(UserTaskSelectors.byElementId(task, pi), submission);
    }
    private void sampleFields(com.fasterxml.jackson.databind.JsonNode fields, Map<String, Object> values) {
        for (var field : fields) {
            if (field.has("key")) {
                Object value = switch (field.path("type").asText()) {
                    case "checkbox" -> field.path("defaultValue").asBoolean();
                    case "number" -> Math.max(1, field.path("validate").path("min").asInt());
                    case "select", "radio" -> field.path("values").get(0).path("value").asText();
                    case "datetime" -> "2026-10-16";
                    default -> "TEST synthetic acceptance input";
                };
                values.put(field.path("key").asText(), value);
            }
            if (field.path("components").isArray()) sampleFields(field.path("components"), values);
        }
    }
    private void publish(long pi, String message, Map<String, Object> variables) {
        client.newPublishMessageCommand().messageName("HPAS: " + message).correlationKey(referrals.get(pi))
            .messageId(UUID.randomUUID().toString()).timeToLive(Duration.ofMinutes(1)).variables(variables).send().join();
    }
    private void close(long pi) { publish(pi, "Pathway closure authorised", Map.of()); }
    private void assertNoIncident(long pi) {
        assertThat(client.newProcessInstanceGetRequest(pi).send().join().getHasIncident()).isFalse();
    }
}
