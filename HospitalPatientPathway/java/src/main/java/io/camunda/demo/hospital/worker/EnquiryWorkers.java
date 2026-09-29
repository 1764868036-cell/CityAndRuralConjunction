package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Enquiry;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Process 12 "Patient Enquiries": hand an enquiry over to the team that can answer it.
 *
 * <p>Both steps are internal hand-overs, so no BPMN message is published; the worker records the
 * reference of the receiving team and completes the job.
 *
 * <p>Each step also opens the enquiry in the system of record, because the answer is written later
 * by another segment of the pathway - process 6 replies to the finance enquiry and phase 8 replies
 * to the clinical one - and those steps need the question on record to close it. An enquiry that
 * is already open for this referral and this team is updated instead of opened twice.
 */
@Component
public class EnquiryWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(EnquiryWorkers.class);

    private final HospitalDatabase database;
    private final SimulationProperties properties;

    public EnquiryWorkers(HospitalDatabase database, SimulationProperties properties) {
        this.database = database;
        this.properties = properties;
    }

    /** P12_Activity_1cxqg1o - refer a clinical enquiry to the clinical team. */
    @JobWorker(type = "refer-clinical-enquiry")
    public Map<String, Object> referClinicalEnquiry(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("clinicalEnquiryReference", "CLIN-ENQ-" + referralId);
        result.put("clinicalEnquiryReferredDate", LocalDate.now().toString());
        result.put("clinicalEnquiryTeam", Variables.text(variables, "clinicalContactTeam", "Duty clinical team"));

        LOG.info("referral {}: clinical enquiry handed to '{}' (job {})",
                referralId, result.get("clinicalEnquiryTeam"), job.getKey());

        recordEnquiry(job, "ENQ-CLIN-" + referralId, variables, "clinical",
                Variables.text(variables, "enquirySummary", "Clinical question about the treatment plan"));
        return result;
    }

    /** P12_Activity_0iv7m7o - refer a payment or funding enquiry to Finance. */
    @JobWorker(type = "refer-finance-enquiry")
    public Map<String, Object> referFinanceEnquiry(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("financeEnquiryReference", "FIN-ENQ-" + referralId);
        result.put("financeEnquiryDate", LocalDate.now().toString());
        result.put("financeEnquiryTeam", "Finance team");

        LOG.info("referral {}: payment or funding enquiry handed to the Finance team (job {})", referralId, job.getKey());

        recordEnquiry(job, "ENQ-FIN-" + referralId, variables, "finance",
                Variables.text(variables, "financeEnquirySummary",
                        Variables.text(variables, "enquirySummary", "Question about the cost of the treatment")));
        return result;
    }

    /**
     * Opens the enquiry for the team that has to answer it.
     *
     * <p>The row is keyed by referral and team ({@code ENQ-CLIN-<referralId>} /
     * {@code ENQ-FIN-<referralId>}), so the step updates what is on record instead of inserting a
     * second enquiry - and an enquiry that was already answered keeps its answer: a referral that is
     * worked a second time must not turn a finished question back into an open one.
     */
    private void recordEnquiry(ActivatedJob job, String enquiryId, Map<String, Object> variables, String category,
            String question) {
        String referralId = referralId(variables);
        Optional<Enquiry> onRecord = database.enquiries().findById(enquiryId);
        Optional<Enquiry> answered = onRecord.filter(enquiry -> !"open".equals(enquiry.status()));
        LocalDate receivedOn = Variables.date(variables, "enquiryReceivedAt", LocalDate.now());

        Enquiry enquiry = new Enquiry(
                enquiryId, patientId(variables), referralId,
                Variables.text(variables, "contactChannel", Variables.text(variables, "requestChannel", "telephone")),
                Variables.text(variables, "raisedBy", Variables.text(variables, "patient_name",
                        properties.getDefaultPatientName())),
                Variables.text(variables, "patientContact", null),
                question,
                answered.map(Enquiry::response).orElse(null),
                answered.map(Enquiry::status).orElse("open"),
                onRecord.map(Enquiry::receivedOn).orElse(receivedOn.atStartOfDay()),
                answered.map(Enquiry::answeredOn).orElse(null));

        if (onRecord.isEmpty()) {
            database.enquiries().insert(enquiry);
        } else {
            database.enquiries().update(enquiry);
        }
        LOG.info("referral {}: {} enquiry {} recorded as '{}' for the team that answers it (job {})",
                referralId, category, enquiryId, enquiry.status(), job.getKey());
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}
