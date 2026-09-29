package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Appointment;
import io.camunda.demo.hospital.persistence.model.SlotRequest;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.ServiceCatalogue;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 3 "Arrange Initial Consultation and Contact Patient".
 *
 * <ul>
 *   <li>{@code request-consultation-slot} (R3_RequestSchedule) asks the external scheduling
 *       service for a slot and publishes {@code Initial consultation slot availability}.</li>
 *   <li>{@code send-appointment-confirmation} (R3_BookSendOffer) books the slot and publishes
 *       the patient's answer (accept / alternative date / decline).</li>
 *   <li>{@code record-pending-consultation} (R3_Waitlist) counts the retry, notifies the patient
 *       and lets the timer R3_RetryWait run the request again.</li>
 *   <li>{@code forward-appointment-change} (R3_Task_ForwardAlternative) hands a patient-proposed
 *       alternative date to the appointment-change process (process 17).</li>
 *   <li>{@code forward-scheduling-conflict} (R3_Task_ForwardConflict) escalates an urgent or
 *       out-of-window booking to the scheduling-conflict path of process 17.</li>
 * </ul>
 *
 * <p>The first two steps are also what turns the scheduling conversation into records: the service
 * the referral selected is resolved in the hospital's catalogue ({@code treatment_service}), the
 * slot search is written as a {@code slot_request} whose attempt count survives the waits between
 * the jobs, and the answer becomes the {@code appointment} the patient decides on. The waitlist
 * step reads that request back from the record instead of only from the variable the previous job
 * returned, so the number of attempts is the same after a restart as it is during a run.
 */
@Component
public class ConsultationWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ConsultationWorkers.class);

    private static final String RETRY_COUNTER = "consultationRetryCount";

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;
    private final ServiceCatalogue catalogue;

    public ConsultationWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database, ServiceCatalogue catalogue) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
        this.catalogue = catalogue;
    }

    /** R3_RequestSchedule - ask the scheduling service for an initial consultation slot. */
    @JobWorker(type = "request-consultation-slot")
    public Map<String, Object> requestConsultationSlot(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        int attempt = Variables.number(variables, RETRY_COUNTER, 0);
        boolean available = retryMaySucceed(attempt) && "available".equalsIgnoreCase(properties.getSlotAvailability());

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("availability", available ? "available" : "unavailable");
        answer.put("date_of_appointment", LocalDate.now().plusDays(14).toString());
        answer.put("appointmentTime", "10:30");
        answer.put("clinicianId", Variables.text(variables, "clinicianId", "CLIN-" + referralId));
        answer.put("clinic_notes", "Slot answer from the external scheduling service (simulated, attempt " + (attempt + 1) + ").");

        LOG.info("referral {}: scheduling service answered '{}' on attempt {} (job {})",
                referralId, answer.get("availability"), attempt + 1, job.getKey());

        recordSlotRequest(variables, answer, attempt);

        messenger.publish(HospitalMessages.SCHEDULING_AVAILABILITY, variables, answer);
        return answer;
    }

    /** R3_BookSendOffer - book the slot and send the appointment details to the patient. */
    @JobWorker(type = "send-appointment-confirmation")
    public Map<String, Object> bookSlotAndSendOffer(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String alternativeDate = LocalDate.now().plusDays(21).toString();

        Map<String, Object> confirmation = new LinkedHashMap<>();
        confirmation.put("bookingReference", "BKG-" + referralId);
        confirmation.put("appointmentDate", Variables.text(variables, "date_of_appointment", LocalDate.now().plusDays(14).toString()));
        confirmation.put("appointmentTime", Variables.text(variables, "appointmentTime", "10:30"));
        confirmation.put("appointmentOfferSent", true);

        String message = switch (properties.getPatientDecision()) {
            case ACCEPT -> HospitalMessages.PATIENT_APPOINTMENT_RESPONSE;
            case ALTERNATIVE -> HospitalMessages.PATIENT_ALTERNATIVE_DATE;
            case DECLINE -> HospitalMessages.PATIENT_DECLINED;
        };
        Map<String, Object> patientAnswer = new LinkedHashMap<>();
        patientAnswer.put("patientAnswer", properties.getPatientDecision().name().toLowerCase());
        patientAnswer.put("alternativeAppointmentDate", alternativeDate);
        patientAnswer.put("patientDeclineReason", "Simulated patient decision");

        LOG.info("referral {}: appointment {} offered, simulating patient answer '{}' (job {})",
                referralId, confirmation.get("bookingReference"), properties.getPatientDecision(), job.getKey());

        recordAppointment(variables, alternativeDate);

        messenger.publish(message, variables, patientAnswer);
        return confirmation;
    }

    /** R3_Waitlist - no free slot: keep the referral pending, notify the patient, count the retry. */
    @JobWorker(type = "record-pending-consultation")
    public Map<String, Object> keepConsultationPending(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        int attempt = Variables.nextAttempt(variables, RETRY_COUNTER);

        SlotRequest open = database.slotRequests().findOpenByReferralId(referralId)
                .orElseThrow(() -> new IllegalStateException("referral " + referralId + " is kept pending although "
                        + "no slot request is on record: the waitlist step follows the slot search"));

        database.slotRequests().update(new SlotRequest(open.slotRequestId(), open.referralId(), open.serviceCode(),
                open.earliestDate(), open.latestDate(), "unavailable", open.attemptCount(), open.status(),
                open.requestedOn(), open.resolvedAppointmentId()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(RETRY_COUNTER, attempt);
        result.put("waitlistEntryReference", "WL-" + referralId + "-" + open.attemptCount());
        result.put("patientNotified", true);
        result.put("waitlistNotifiedTeam", "Bookings team");

        LOG.info("referral {}: no slot, kept pending (attempt {}, reference {}) (job {})",
                referralId, attempt, result.get("waitlistEntryReference"), job.getKey());
        return result;
    }

    private boolean retryMaySucceed(int attempt) {
        return attempt >= properties.getRetryAttemptsBeforeSuccess();
    }

    /** R3_Task_ForwardAlternative - hand the patient-proposed alternative date to process 17. */
    @JobWorker(type = "forward-appointment-change")
    public Map<String, Object> forwardAppointmentChange(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String alternativeDate = Variables.text(variables, "alternativeAppointmentDate",
                LocalDate.now().plusDays(21).toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointmentChangeForwarded", true);
        result.put("alternativeAppointmentDate", alternativeDate);
        result.put("appointmentChangeReference", "CHG-" + referralId);
        result.put("appointmentChangeType", "date");

        // The model hands the case straight to the appointment-change merge of process 17, so no
        // message is published; the worker only records the hand-off.
        LOG.info("referral {}: alternative date {} forwarded to the appointment-change process (job {})",
                referralId, alternativeDate, job.getKey());
        return result;
    }

    /** R3_Task_ForwardConflict - escalate an urgent or out-of-window booking for clinical priority. */
    @JobWorker(type = "forward-scheduling-conflict")
    public Map<String, Object> forwardSchedulingConflict(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schedulingConflictEscalated", true);
        result.put("schedulingConflictReference", "CONF-" + referralId);
        result.put("escalationReason", "Urgent or out-of-window booking escalated by the bookings team (simulated).");

        LOG.info("referral {}: scheduling conflict escalated as {} (job {})",
                referralId, result.get("schedulingConflictReference"), job.getKey());
        return result;
    }

    /**
     * Records the slot search of this referral - opened on the first ask, updated on every retry.
     *
     * <p>One row per referral ({@code SLOT-<referralId>}) carries the current search: it stays
     * {@code open} while the model retries, which is what the waitlist step reads, and the booking
     * step resolves it to {@code booked} once a slot is accepted.
     */
    private void recordSlotRequest(Map<String, Object> variables, Map<String, Object> answer, int attempt) {
        String referralId = referralId(variables);
        String serviceCode = catalogue.codeOf(variables);
        LocalDate offered = Variables.date(answer, "date_of_appointment", LocalDate.now());
        String slotRequestId = "SLOT-" + referralId;

        // Insert or update is decided on the primary key, never on the status: a referral that is
        // worked a second time (a re-opened pathway, a repeated instance) already has its row, and a
        // status-filtered lookup would miss the booked one and collide with it. The row tracks the
        // current search, so this ask re-opens it and clears the appointment it resolved.
        Optional<SlotRequest> onRecord = database.slotRequests().findById(slotRequestId);
        SlotRequest request = new SlotRequest(
                slotRequestId,
                referralId, serviceCode,
                onRecord.map(SlotRequest::earliestDate).orElse(LocalDate.now()),
                offered,
                String.valueOf(answer.get("availability")),
                attempt + 1,
                "open",
                onRecord.map(SlotRequest::requestedOn).orElse(LocalDateTime.now()),
                null);

        if (onRecord.isEmpty()) {
            database.slotRequests().insert(request);
        } else {
            database.slotRequests().update(request);
        }
    }

    /** Records the appointment the patient is asked to decide on, and resolves the slot request. */
    private void recordAppointment(Map<String, Object> variables, String alternativeDate) {
        String referralId = referralId(variables);
        String appointmentId = "APPT-" + referralId;
        boolean accepted = properties.getPatientDecision() == SimulationProperties.PatientDecision.ACCEPT;

        Appointment appointment = new Appointment(
                appointmentId, referralId, patientId(variables), catalogue.codeOf(variables),
                Variables.moment(variables, "date_of_appointment", "appointmentTime", LocalDateTime.now()),
                Variables.text(variables, "appointmentLocation", null),
                Variables.text(variables, "clinicianId", "CLIN-" + referralId),
                accepted ? "booked" : "offered",
                properties.getPatientDecision().name().toLowerCase(Locale.ROOT),
                properties.getPatientDecision() == SimulationProperties.PatientDecision.ALTERNATIVE
                        ? LocalDate.parse(alternativeDate).atStartOfDay()
                        : null);

        Optional<Appointment> onRecord = database.appointments().findById(appointmentId);
        if (onRecord.isEmpty()) {
            database.appointments().insert(appointment);
        } else {
            database.appointments().update(appointment);
        }

        database.slotRequests().findOpenByReferralId(referralId).ifPresent(open ->
                database.slotRequests().update(new SlotRequest(open.slotRequestId(), open.referralId(),
                        open.serviceCode(), open.earliestDate(), open.latestDate(), open.availability(),
                        open.attemptCount(), "booked", open.requestedOn(), appointmentId)));
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}
