package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Local demo implementations for the integrated hospital's cross-team hand-offs.
 * Internal sequence flows carry the token; workers record hand-offs without creating another
 * instance. External scheduling and correspondence answers are simulated as BPMN messages.
 * These workers never contact real patients, banks or clinical systems.
 */
@Component
public class IntegratedPathwayWorkers {
    private static final Logger LOG = LoggerFactory.getLogger(IntegratedPathwayWorkers.class);
    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;

    public IntegratedPathwayWorkers(ExternalPartyMessenger messenger, SimulationProperties properties) {
        this.messenger = messenger;
        this.properties = properties;
    }

    @JobWorker(type = "forward-scheduling-conflict")
    public Map<String, Object> forwardConflict(ActivatedJob job) {
        return record(job, "schedulingConflictForwarded", Map.of("appointmentChangeType", "appointment"));
    }

    @JobWorker(type = "forward-appointment-change")
    public Map<String, Object> forwardAlternative(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        return record(job, "appointmentChangeForwarded", Map.of(
                "appointmentChangeType", "appointment",
                "requestedAppointmentDate", Variables.text(values, "alternativeAppointmentDate", "")));
    }

    @JobWorker(type = "publish-provisional-treatment-booking")
    public Map<String, Object> publishProvisionalBooking(ActivatedJob job) {
        return record(job, "provisionalBookingSentToFinance", Map.of());
    }

    @JobWorker(type = "reply-to-finance-enquiry")
    public Map<String, Object> replyFinance(ActivatedJob job) {
        return record(job, "financeReplyReturned", Map.of());
    }

    @JobWorker(type = "send-funding-clearance")
    public Map<String, Object> sendFundingClearance(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        return record(job, "fundingClearanceSent", Map.of(
                "clearanceReference", Variables.text(values, "fundingReference", "CLR-" + referral(values))));
    }

    @JobWorker(type = "request-advance-payment")
    public Map<String, Object> requestAdvancePayment(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        return record(job, "advancePaymentRequested", Map.of(
                "chargeAmount", Variables.amount(values, "patientShare", "0.00")));
    }

    @JobWorker(type = "send-payment-confirmation-to-bookings")
    public Map<String, Object> sendPaymentConfirmation(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        if (!"paid".equals(Variables.text(values, "paymentStatus"))
                && !Variables.flag(values, "providerConfirmsPaid", false)) {
            throw new IllegalStateException("Payment clearance requires a confirmed paid result");
        }
        return record(job, "paymentClearanceSent", Map.of(
                "clearanceReference", Variables.text(values, "paymentReference", ""),
                "paymentReceived", "yes", "paymentStatus", "paid"));
    }

    @JobWorker(type = "return-clinical-priority-decision")
    public Map<String, Object> returnPriorityDecision(ActivatedJob job) {
        return record(job, "clinicalPriorityDecisionReturned", Map.of());
    }

    @JobWorker(type = "reply-to-clinical-enquiry")
    public Map<String, Object> replyClinical(ActivatedJob job) {
        return record(job, "clinicalReplyReturned", Map.of());
    }

    @JobWorker(type = "request-next-treatment-cycle")
    public Map<String, Object> requestNextCycle(ActivatedJob job) {
        return record(job, "nextCycleRequestSent", Map.of());
    }

    @JobWorker(type = "send-treatment-plan-change")
    public Map<String, Object> sendPlanChange(ActivatedJob job) {
        return record(job, "clinicalPlanChangeSent", Map.of());
    }

    @JobWorker(type = "refer-treatment-stop-to-finance")
    public Map<String, Object> referStopToFinance(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        return record(job, "treatmentStopReferredToFinance", Map.of(
                "refundCaseSource", "Consultant stopped treatment",
                "originalPaymentReference", Variables.text(values, "paymentReference", "")));
    }

    @JobWorker(type = "send-approved-letter-to-correspondence")
    public Map<String, Object> sendLetterToCorrespondence(ActivatedJob job) {
        Map<String, Object> values = Variables.copyOf(job);
        Map<String, Object> answer = Map.of(
                "correspondenceEntryReference", "CORR-" + referral(values) + "-" + job.getKey(),
                "correspondenceEntryReceivedDate", LocalDate.now().toString());
        messenger.publish(HospitalMessages.LETTER_ENTRY, values, answer);
        return record(job, "approvedLetterSentToCorrespondence", answer);
    }

    @JobWorker(type = "request-follow-up-slot")
    public Map<String, Object> requestFollowUpSlot(ActivatedJob job) {
        return schedulingAnswer(job, true);
    }

    @JobWorker(type = "record-pathway-update")
    public Map<String, Object> recordPathwayUpdate(ActivatedJob job) {
        return record(job, "monitoringRecordUpdated", Map.of());
    }

    @JobWorker(type = "refer-treatment-plan-change")
    public Map<String, Object> referPlanChange(ActivatedJob job) {
        return record(job, "planChangeReferredForClinicalApproval", Map.of());
    }

    @JobWorker(type = "refer-funding-change")
    public Map<String, Object> referFundingChange(ActivatedJob job) {
        return record(job, "fundingChangeReferred", Map.of());
    }

    @JobWorker(type = "request-appointment-change-options")
    public Map<String, Object> requestChangeOptions(ActivatedJob job) {
        return schedulingAnswer(job, false);
    }

    @JobWorker(type = "notify-changed-appointment")
    public Map<String, Object> notifyChangedAppointment(ActivatedJob job) {
        return record(job, "patientNotified", Map.of(
                "patientNotificationMethod", "Written appointment notice (local simulation)",
                "patientNotificationDate", LocalDate.now().toString()));
    }

    @JobWorker(type = "request-clinical-priority-review")
    public Map<String, Object> requestPriorityReview(ActivatedJob job) {
        return record(job, "clinicalPriorityReviewRequested", Map.of());
    }

    private Map<String, Object> schedulingAnswer(ActivatedJob job, boolean followUp) {
        Map<String, Object> values = Variables.copyOf(job);
        LocalDate candidate = LocalDate.now().plusDays(7);
        String reviewBy = Variables.text(values, "requestedReviewBy");
        if (reviewBy != null) {
            LocalDate deadline = LocalDate.parse(reviewBy);
            if (deadline.isBefore(candidate)) candidate = deadline;
        }
        boolean available = !candidate.isBefore(LocalDate.now())
                && "available".equalsIgnoreCase(properties.getSlotAvailability());
        Map<String, Object> answer = new LinkedHashMap<>();
        // The follow-up form models yes/no, whereas initial consultation uses a boolean.
        answer.put("slotAvailable", followUp ? (available ? "yes" : "no") : available);
        answer.put("availability", available ? "available" : "unavailable");
        answer.put("date_of_appointment", candidate.toString());
        answer.put("appointmentTime", "10:30");
        answer.put("schedulingServiceNote", "Local simulated scheduling response");
        messenger.publish(HospitalMessages.SCHEDULING_AVAILABILITY, values, answer);
        return answer;
    }

    private Map<String, Object> record(ActivatedJob job, String flag, Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.put(flag, true);
        result.put(flag + "At", LocalDate.now().toString());
        LOG.info("Local simulation completed {} for referral {} (job {})",
                job.getType(), referral(Variables.copyOf(job)), job.getKey());
        return result;
    }

    private String referral(Map<String, Object> values) {
        return Variables.text(values, HospitalMessages.REFERRAL_ID, "unknown-referral");
    }
}
