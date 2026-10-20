package io.camunda.demo.hpas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hpas.persistence.HospitalDatabase;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Version 1 business criteria remain assertions, even when version 2 does not implement them.
 * Static guard checks detect absent mapped model contracts; they are not token execution evidence.
 */
class OriginalPlanParityIT {
    private static final String B = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static Document model;

    @BeforeAll
    static void readFrozenModel() throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Path bundle = Boolean.getBoolean("acceptance.candidate")
            ? Path.of("..", "evidence", "acceptance-candidate", "hospital-patient-pathway.bpmn")
            : Path.of("..", "evidence", "acceptance-input", "hospital-patient-pathway.bpmn.keep");
        model = factory.newDocumentBuilder().parse(bundle.toFile());
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = ';', value = {
        "AT-05;requested scheduling window is represented by a date/deadline rule;Process_HPAS;requestedReviewBy|reviewBy|deadline",
        "AT-07;funding denial is a routing input;Process_HPAS;fundingAuthorised|fundingApproved",
        "AT-08;prior charge is checked before a new payment request;Process_HPAS;priorChargeFound|alreadyCharged",
        "AT-09;clinical stop has an explicit route;Process_HPAS;fitnessDecision.*stop|clinicalDecision.*stop",
        "AT-11-identity;rescheduling has an identity guard;SP03B;identityVerified",
        "AT-11-date;rescheduling validates the requested review deadline;SP03B;requestedReviewBy|reviewBy|deadline",
        "AT-11-contact;near-term rescheduling has a telephone-contact decision;SP03B;withinTwoWeeks|14|phone",
        "AT-13-transfer;transfer is separate from refund/no-refund;SP11;refundScope.*transfer|fundTransferAuthorised"
    })
    void originalDecisionContractExists(String at, String criterion, String scope, String regex) {
        String conditions = conditions(scope);
        assertThat(conditions).as(at + ": " + criterion + "; original acceptance criterion is not waived")
            .containsPattern("(?is)(" + regex + ")");
    }

    @Test @DisplayName("AT-10 — charge/funding recheck must precede implementation of a financially affected change")
    void at10_financeRecheckMustPrecedeImplementation() {
        assertThat(reachableWithout("SP09", "SP09_FinanceReview", "SP09_ApplyChange", Set.of()))
            .as("There must be a route from finance recheck to implementation; version 2 currently checks finance after implementation")
            .isTrue();
    }

    @Test @DisplayName("AT-15 — restoration must have a message subscription keyed by incidentReference")
    void at15_restorationUsesIncidentReference() {
        var messages = model.getElementsByTagNameNS(B, "message");
        boolean restoredMessageFound = false;
        for (int i = 0; i < messages.getLength(); i++) {
            Element message = (Element) messages.item(i);
            String name = message.getAttribute("name").toLowerCase(java.util.Locale.ROOT);
            if (!name.contains("restor") && !name.contains("recover")) continue;
            restoredMessageFound = true;
            Element extensions = firstChild(message, "extensionElements");
            Element subscription = firstChild(extensions, "subscription");
            assertThat(subscription).as("restoration message subscription").isNotNull();
            assertThat(subscription.getAttribute("correlationKey").replace(" ", ""))
                .as("restoration must correlate by incidentReference").isEqualTo("=incidentReference");
        }
        assertThat(restoredMessageFound).as("a distinct service-restored message is required").isTrue();
    }

    @Test @DisplayName("AT-13 — refundAuthorised=false cannot create a refund request even with a named approver")
    void at13_unauthorisedRefundMustBeRejected() {
        var executor = executor();
        var job = job(Map.of("referralId", "TEST-UNAUTH-REFUND", "refundDue", true,
            "refundAuthorised", false, "financeApprover", "TEST-FINANCE"));
        assertThatThrownBy(() -> executor.execute("hpas-sp11-send-refund", job))
            .as("Named approver alone does not override an explicit refusal of authorisation")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("AT-10 — real worker refuses an unapproved treatment change")
    void at10_realWorkerRejectsUnapprovedChange() {
        assertThatThrownBy(() -> executor().execute("hpas-sp09-apply-change",
            job(Map.of("referralId", "TEST-CHANGE", "formallyAuthorised", false))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("AT-14 — real worker refuses distribution of an unapproved letter")
    void at14_realWorkerRejectsUnapprovedLetter() {
        assertThatThrownBy(() -> executor().execute("hpas-sp13-distribute",
            job(Map.of("referralId", "TEST-LETTER", "letterApproved", false))))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("AT-15 — real worker records denied access without approving it")
    void at15_realWorkerDeniesAccess() {
        assertThat(executor().execute("hpas-sp16-apply-access",
            job(Map.of("referralId", "TEST-DENIED", "accessAuthorised", false))))
            .containsEntry("accessDecisionRecorded", "denied");
    }

    @Test @DisplayName("AT-08 — payment worker keeps an existing paid charge out of the new request route")
    void at08_realWorkerMarksPriorCharge() {
        assertThat(executor().execute("hpas-sp07-calculate-charge",
            job(Map.of("referralId", "TEST-PRIOR", "chargeAmount", 25, "priorChargeFound", true))))
            .containsEntry("priorChargeFound", true).containsEntry("paymentVerified", true);
    }

    @Test @DisplayName("AT-09 — treatment stop requires a recorded clinical reason")
    void at09_stopWorkerRequiresReason() {
        assertThatThrownBy(() -> executor().execute("hpas-sp08-stop-treatment",
            job(Map.of("referralId", "TEST-STOP", "fitnessDecision", "stop"))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(executor().execute("hpas-sp08-stop-treatment",
            job(Map.of("referralId", "TEST-STOP", "fitnessDecision", "stop", "stopReason", "clinical"))))
            .containsEntry("treatmentStopped", true);
    }

    @Test @DisplayName("AT-10 — financially affected treatment changes need finance recheck")
    void at10_financeGuardRunsInWorker() {
        assertThatThrownBy(() -> executor().execute("hpas-sp09-apply-change",
            job(Map.of("referralId", "TEST-CHANGE", "formallyAuthorised", true,
                "financialImpact", true, "financialReviewComplete", false))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(executor().execute("hpas-sp09-apply-change",
            job(Map.of("referralId", "TEST-CHANGE", "formallyAuthorised", true,
                "financialImpact", true, "financialReviewComplete", true))))
            .containsEntry("treatmentChangeApplied", true);
    }

    @Test @DisplayName("AT-11 — reschedule worker enforces identity, deadline and near-term contact")
    void at11_rescheduleWorkerEnforcesEligibility() {
        Map<String, Object> valid = Map.of("referralId", "TEST-RESCHEDULE", "identityVerified", true,
            "requestedReviewBy", "2026-11-01", "offeredAppointmentDate", "2026-10-25",
            "withinTwoWeeks", true, "phoneContactCompleted", true);
        assertThat(executor().execute("hpas-sp03b-apply-reschedule", job(valid)))
            .containsEntry("appointmentChanged", true);
        Map<String, Object> noPhone = new java.util.HashMap<>(valid);
        noPhone.put("phoneContactCompleted", false);
        assertThatThrownBy(() -> executor().execute("hpas-sp03b-apply-reschedule", job(noPhone)))
            .isInstanceOf(IllegalStateException.class);
        Map<String, Object> wrongIdentity = new java.util.HashMap<>(valid);
        wrongIdentity.put("identityVerified", false);
        assertThatThrownBy(() -> executor().execute("hpas-sp03b-apply-reschedule", job(wrongIdentity)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test @DisplayName("AT-13 — fund transfer is separately authorised from a refund")
    void at13_transferWorkerHasSeparateAuthorisation() {
        assertThatThrownBy(() -> executor().execute("hpas-sp11-transfer-funds",
            job(Map.of("referralId", "TEST-TRANSFER", "refundScope", "transfer",
                "fundTransferAuthorised", false, "financeApprover", "TEST-FINANCE"))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(executor().execute("hpas-sp11-transfer-funds",
            job(Map.of("referralId", "TEST-TRANSFER", "refundScope", "transfer",
                "fundTransferAuthorised", true, "financeApprover", "TEST-FINANCE"))))
            .containsEntry("fundTransferRecorded", true).doesNotContainKey("refundRequestId");
    }

    private static HpasJobExecutor executor() {
        return new HpasJobExecutor(mock(CamundaClient.class), false, mock(HospitalDatabase.class, RETURNS_DEEP_STUBS));
    }
    private static ActivatedJob job(Map<String, Object> vars) {
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getVariablesAsMap()).thenReturn(vars);
        when(job.getProcessInstanceKey()).thenReturn(1L);
        when(job.getElementId()).thenReturn("TEST-ELEMENT");
        return job;
    }
    private static Element scope(String id) {
        var all = model.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (id.equals(e.getAttribute("id"))) return e;
        }
        throw new AssertionError("Missing model scope: " + id);
    }
    private static Element firstChild(Element parent, String local) {
        if (parent == null) return null;
        for (var child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element e && local.equals(e.getLocalName())) return e;
        return null;
    }
    private static String conditions(String id) {
        var nodes = scope(id).getElementsByTagNameNS(B, "conditionExpression");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < nodes.getLength(); i++) text.append(nodes.item(i).getTextContent()).append('\n');
        return text.toString();
    }
    private static boolean reachableWithout(String scopeId, String start, String target, Set<String> excluded) {
        var nodes = scope(scopeId).getElementsByTagNameNS(B, "sequenceFlow");
        var pending = new ArrayDeque<String>();
        var visited = new HashSet<String>();
        pending.add(start);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current) || excluded.contains(current)) continue;
            if (current.equals(target)) return true;
            for (int i = 0; i < nodes.getLength(); i++) {
                Element flow = (Element) nodes.item(i);
                if (current.equals(flow.getAttribute("sourceRef"))) pending.add(flow.getAttribute("targetRef"));
            }
        }
        return false;
    }
}
