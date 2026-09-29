package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Appointment;
import io.camunda.demo.hospital.persistence.model.Consultation;
import io.camunda.demo.hospital.persistence.model.TreatmentCycle;
import io.camunda.demo.hospital.persistence.model.TreatmentService;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.ServiceCatalogue;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 5 "Arrange Treatment and External Services": the hospital books treatment, laboratory or
 * imaging capacity at an external service.
 *
 * <ul>
 *   <li>{@code request-treatment-service} (R5_RequestService) asks for capacity and publishes
 *       {@code Treatment or external service availability}.</li>
 *   <li>{@code record-pending-treatment-service} (R5_KeepPending) keeps the request pending and
 *       counts the retry that the timer R5_RetryService repeats.</li>
 *   <li>{@code publish-provisional-treatment-booking} (R5_TreatmentBooked) announces the
 *       financially cleared booking to Treatment Bookings.</li>
 * </ul>
 *
 * <p>Phase 5 is also where the pathway records the treatment it was authorised to give: the
 * consultation and the consent decision the booking rests on are written as a {@code consultation}
 * when the capacity is asked for, and the {@code treatment_cycle} the clinical review repeats is
 * planned when the services are provisionally arranged. The service itself is resolved in the
 * hospital's catalogue ({@code treatment_service}), so the appointment, the slot search and every
 * cycle of a referral name the same service.
 */
@Component
public class TreatmentWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(TreatmentWorkers.class);

    private static final String RETRY_COUNTER = "treatmentServiceRetryCount";

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;
    private final ServiceCatalogue catalogue;

    public TreatmentWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database, ServiceCatalogue catalogue) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
        this.catalogue = catalogue;
    }

    /** R5_RequestService - ask the external treatment service for a slot. */
    @JobWorker(type = "request-treatment-service")
    public Map<String, Object> requestTreatmentService(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        int attempt = Variables.number(variables, RETRY_COUNTER, 0);
        boolean available = attempt >= properties.getRetryAttemptsBeforeSuccess()
                && properties.isTreatmentServicesAvailable();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("allServicesAvailable", available);
        answer.put("serviceReservationReferences", available ? "LAB-" + referralId + "; IMG-" + referralId : "");
        answer.put("externalServiceNotes", "Capacity answer of the external service (simulated, attempt " + (attempt + 1) + ").");

        LOG.info("referral {}: external treatment service answered allServicesAvailable={} on attempt {} (job {})",
                referralId, available, attempt + 1, job.getKey());

        recordConsultation(job, variables);

        messenger.publish(HospitalMessages.TREATMENT_SERVICE_AVAILABILITY, variables, answer);
        return answer;
    }

    /** R5_KeepPending - no capacity: keep the request pending and count the retry. */
    @JobWorker(type = "record-pending-treatment-service")
    public Map<String, Object> keepTreatmentServicePending(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        int attempt = Variables.nextAttempt(variables, RETRY_COUNTER);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(RETRY_COUNTER, attempt);
        result.put("treatmentWaitingListReference", "TWL-" + referralId + "-" + attempt);
        result.put("treatmentServiceTeamNotified", true);

        LOG.info("referral {}: no external capacity, request kept pending (attempt {}) (job {})",
                referralId, attempt, job.getKey());
        return result;
    }

    /** R5_TreatmentBooked - announce the provisionally arranged services to Treatment Bookings. */
    @JobWorker(type = "publish-provisional-treatment-booking")
    public Map<String, Object> publishProvisionalTreatmentBooking(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provisionalBookingPublished", true);
        result.put("provisionalBookingReference", "PRV-" + referralId);
        result.put("treatmentBookingsNotified", true);

        LOG.info("referral {}: provisional treatment booking {} announced to Treatment Bookings (job {})",
                referralId, result.get("provisionalBookingReference"), job.getKey());

        recordTreatmentCycle(job, variables);
        return result;
    }

    /**
     * Records the consultation and the consent the treatment arrangement rests on.
     *
     * <p>Phase 4 itself has no worker - the consultant examines the patient and the patient decides
     * in user tasks - so the first automated step that follows a recorded consent, this request for
     * treatment capacity, writes the consultation down. That is what makes the consent auditable
     * and what lets the clinic letter later say which consultation it documents.
     */
    private void recordConsultation(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String consultationId = "CONS-" + referralId;
        boolean consented = Variables.flag(variables, "informedConsent",
                !"refuse".equalsIgnoreCase(properties.getPatientConsentDecision()));
        LocalDate consentDay = Variables.date(variables, "consentDate", null);

        Optional<Appointment> appointment = database.appointments().findById("APPT-" + referralId);

        Consultation consultation = new Consultation(
                consultationId, referralId, appointment.map(Appointment::appointmentId).orElse(null),
                patientId(variables),
                Variables.text(variables, "clinicianId", "CLIN-" + referralId),
                Variables.text(variables, "consultationType", null),
                Variables.date(variables, "assessmentDate", LocalDate.now()).atStartOfDay(),
                Variables.text(variables, "treatmentPlanReference", Variables.text(variables, "diagnosis", null)),
                Variables.text(variables, "clinicalDisposition", Variables.text(variables, "diagnosis", null)),
                consented ? "consented" : "refused",
                Variables.text(variables, "consentRecordedBy", null),
                consentDay == null ? null : consentDay.atStartOfDay());

        Optional<Consultation> onRecord = database.consultations().findById(consultationId);
        if (onRecord.isEmpty()) {
            database.consultations().insert(consultation);
        } else {
            database.consultations().update(consultation);
        }

        LOG.info("referral {}: consultation {} recorded with consent '{}' (job {})",
                referralId, consultationId, consultation.consentStatus(), job.getKey());
    }

    /** Plans the treatment cycle the clinical review repeats, one row per pass of a service. */
    private void recordTreatmentCycle(ActivatedJob job, Map<String, Object> variables) {
        String referralId = referralId(variables);
        String serviceCode = catalogue.codeOf(variables);
        TreatmentService service = catalogue.entry(serviceCode);
        int cycleNumber = Variables.number(variables, "cycleNumber", 1);

        LocalDate plannedStart = Variables.date(variables, "provisionalTreatmentDate",
                Variables.date(variables, "treatmentDate", null));
        LocalDate plannedEnd = Variables.date(variables, "finalTreatmentDate",
                plannedStart == null ? null : plannedStart.plusDays(service.leadTimeDays()));

        Optional<TreatmentCycle> onRecord =
                database.treatmentCycles().findByReferralServiceAndNumber(referralId, serviceCode, cycleNumber);
        TreatmentCycle cycle = new TreatmentCycle(
                onRecord.map(TreatmentCycle::treatmentCycleId).orElse("CYC-" + referralId + "-" + cycleNumber),
                referralId, patientId(variables), serviceCode, cycleNumber,
                plannedStart, plannedEnd,
                Variables.date(variables, "treatmentDate", null),
                "planned", null, null);

        if (onRecord.isEmpty()) {
            database.treatmentCycles().insert(cycle);
        } else {
            database.treatmentCycles().update(cycle);
        }

        LOG.info("referral {}: treatment cycle {} of {} planned from {} to {} (job {})",
                referralId, cycleNumber, serviceCode, plannedStart, plannedEnd, job.getKey());
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}
