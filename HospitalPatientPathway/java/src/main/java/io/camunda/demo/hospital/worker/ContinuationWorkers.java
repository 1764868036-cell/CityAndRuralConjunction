package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Enquiry;
import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.ServiceCatalogue;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process 8 "Evaluate Whether to Continue Treatment": the automatic hand-offs of the continuation
 * review. The clinical decision itself is made by a person; these steps only carry the outcome to
 * the team that acts on it, so none of them publishes a message the pathway waits for.
 *
 * <ul>
 *   <li>{@code reply-to-clinical-enquiry} (P8_Task_SendClinicalReply) returns the clinical answer
 *       to Call Handling.</li>
 *   <li>{@code request-next-treatment-cycle} (P8_Task_SendNextCycle) sends the Consultant-authorised
 *       next-cycle request to Treatment Bookings.</li>
 *   <li>{@code send-treatment-plan-change} (P8_Task_SendPlanChange) sends the clinically authorised
 *       plan change.</li>
 *   <li>{@code return-clinical-priority-decision} (P8_Task_SendPriorityDecision) returns the
 *       priority decision to the pathway team.</li>
 *   <li>{@code refer-treatment-stop-to-finance} (P8_Task_SendRefundCase) refers a treatment stop to
 *       Finance for a refund review.</li>
 * </ul>
 *
 * <p>Two of them work on the records: the clinical answer closes the enquiry process 12 opened, and
 * the next-cycle request counts the passes that are already on record for this referral and
 * service, so the number of the next cycle is the number the treatment cycles themselves hold.
 */
@Component
public class ContinuationWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ContinuationWorkers.class);

    private final HospitalDatabase database;
    private final SimulationProperties properties;
    private final ServiceCatalogue catalogue;

    public ContinuationWorkers(HospitalDatabase database, SimulationProperties properties,
            ServiceCatalogue catalogue) {
        this.database = database;
        this.properties = properties;
        this.catalogue = catalogue;
    }

    /** P8_Task_SendClinicalReply - return the clinical answer to Call Handling. */
    @JobWorker(type = "reply-to-clinical-enquiry")
    public Map<String, Object> replyToClinicalEnquiry(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clinicalResponseSent", true);
        result.put("clinicalResponseReference", "CLIN-RES-" + referralId);
        result.put("clinicalResponseDate", LocalDate.now().toString());
        result.put("clinicalResponseSummary", "Clinical enquiry answered by the clinical team (simulated).");

        LOG.info("referral {}: clinical response {} returned to Call Handling (job {})",
                referralId, result.get("clinicalResponseReference"), job.getKey());

        answerClinicalEnquiry(job, variables);
        return result;
    }

    /** P8_Task_SendNextCycle - send the Consultant-authorised next-cycle request. */
    @JobWorker(type = "request-next-treatment-cycle")
    public Map<String, Object> requestNextTreatmentCycle(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String serviceCode = catalogue.codeOf(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nextCycleRequested", true);
        result.put("clinicalDecision", "continue");
        result.put("nextCycleRequestReference", "CYC-" + referralId);
        result.put("nextCycleRequestDate", LocalDate.now().toString());

        // The continuation review repeats the treatment, so the number of the pass that is asked for
        // is the one after the passes the record already holds for this referral and service; how
        // many passes the service normally needs comes from the hospital's catalogue.
        int nextCycle = database.treatmentCycles().highestCycleNumber(referralId, serviceCode) + 1;
        int serviceCycles = catalogue.entry(serviceCode).defaultCycles();
        result.put("nextCycleNumber", nextCycle);
        result.put("nextCycleServiceCode", serviceCode);
        result.put("serviceDefaultCycles", serviceCycles);

        recordReviewOutcome(variables, "continue");

        LOG.info("referral {}: next treatment cycle {} of {} requested as {} (the service needs {} pass(es) by "
                        + "default) (job {})",
                referralId, nextCycle, serviceCode, result.get("nextCycleRequestReference"), serviceCycles,
                job.getKey());
        return result;
    }

    /** P8_Task_SendPlanChange - send the clinically authorised treatment-plan change. */
    @JobWorker(type = "send-treatment-plan-change")
    public Map<String, Object> sendTreatmentPlanChange(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("treatmentPlanChangeSent", true);
        result.put("action", "change");
        result.put("planChangeReference", "PLN-" + referralId);
        result.put("planChangeDate", LocalDate.now().toString());

        LOG.info("referral {}: treatment-plan change {} sent for authorisation (job {})",
                referralId, result.get("planChangeReference"), job.getKey());
        return result;
    }

    /** P8_Task_SendPriorityDecision - return the clinical priority decision. */
    @JobWorker(type = "return-clinical-priority-decision")
    public Map<String, Object> returnClinicalPriorityDecision(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("priorityDecisionReturned", true);
        result.put("priorityDecisionReference", "PRI-" + referralId);
        result.put("priorityDecisionDate", LocalDate.now().toString());
        result.put("priorityDecision", "routine");

        LOG.info("referral {}: clinical priority decision {} returned to the pathway team (job {})",
                referralId, result.get("priorityDecisionReference"), job.getKey());
        return result;
    }

    /** P8_Task_SendRefundCase - refer a treatment stop to Finance for a refund review. */
    @JobWorker(type = "refer-treatment-stop-to-finance")
    public Map<String, Object> referTreatmentStopToFinance(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("refundCaseReferred", true);
        result.put("clinicalDecision", "stop");
        result.put("action", "stop");
        result.put("refundCaseReference", "RFD-CASE-" + referralId);
        result.put("refundCaseReferredDate", LocalDate.now().toString());

        LOG.info("referral {}: treatment stop referred to Finance as {} (job {})",
                referralId, result.get("refundCaseReference"), job.getKey());

        recordReviewOutcome(variables, "stop");
        return result;
    }

    /**
     * Records the clinical review on the treatment cycle it was about.
     *
     * <p>The model repeats a treatment cycle while the test results say the patient is fit to
     * continue, so the review's result belongs on the pass that was reviewed: without it the
     * pathway's own decision (fit or unfit, continue or stop) would live in the process variables
     * only and be gone once the instance ended. The pass on record is the newest one of the
     * referral; a review that no planned cycle is on record for - the continuation review can also
     * be entered from a cancellation report - records nothing.
     */
    private void recordReviewOutcome(Map<String, Object> variables, String defaultOutcome) {
        String referralId = referralId(variables);
        String outcome = Variables.text(variables, "clinicalDecision", defaultOutcome);
        boolean fitToContinue = Variables.flag(variables, "fitToContinue",
                properties.isTestResultsFitToContinue());

        currentCycle(referralId).ifPresent(cycle -> {
            database.treatmentCycles().update(new TreatmentCycle(cycle.treatmentCycleId(), cycle.referralId(),
                    cycle.patientId(), cycle.serviceCode(), cycle.cycleNumber(), cycle.plannedStart(),
                    cycle.plannedEnd(), cycle.startedOn(), cycle.status(), fitToContinue, outcome));
            LOG.info("referral {}: review of treatment cycle {} recorded as '{}' (fit to continue: {})",
                    referralId, cycle.cycleNumber(), outcome, fitToContinue);
        });
    }

    /** The pass of this referral that is on record most recently, i.e. the one being reviewed. */
    private Optional<TreatmentCycle> currentCycle(String referralId) {
        List<TreatmentCycle> cycles = database.treatmentCycles().findByReferralId(referralId);
        return cycles.isEmpty() ? Optional.empty() : Optional.of(cycles.get(cycles.size() - 1));
    }

    /** Closes the clinical enquiry of this referral with the answer of the clinical team. */
    private void answerClinicalEnquiry(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String answer = Variables.text(variables, "clinicalEnquiryResponse",
                Variables.text(variables, "clinicalResponseSummary", "Answered by the clinical team"));

        Optional<Enquiry> onRecord = database.enquiries().findById("ENQ-CLIN-" + referralId);
        // Only an open enquiry is answered: a question that was already closed keeps the answer it
        // was given, whatever a second instance of the referral carries.
        onRecord.filter(enquiry -> "open".equals(enquiry.status()))
                .ifPresent(enquiry -> database.enquiries().update(new Enquiry(enquiry.enquiryId(),
                        enquiry.patientId(), enquiry.referralId(), enquiry.channel(), enquiry.raisedBy(),
                        enquiry.contactDetails(), enquiry.question(), answer, "answered", enquiry.receivedOn(),
                        LocalDateTime.now())));

        LOG.info("referral {}: clinical enquiry {} closed with the clinical answer (job {})",
                referralId, onRecord.map(Enquiry::enquiryId).orElse("not on record"), job.getKey());
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }
}
