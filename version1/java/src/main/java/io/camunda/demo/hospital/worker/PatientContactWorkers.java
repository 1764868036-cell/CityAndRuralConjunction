package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Appointment;
import io.camunda.demo.hospital.persistence.model.ClinicLetter;
import io.camunda.demo.hospital.persistence.model.Referral;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
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
 * The two paths that inform a person outside the hospital and the follow-up booking:
 *
 * <ul>
 *   <li>{@code inform-referring-organisation} (P10_Task_NotifyReferrer) hands the case
 *       information to the referring organisation.</li>
 *   <li>{@code notify-patient} (P14_Task_Notify) sends the follow-up appointment notice.</li>
 *   <li>{@code book-follow-up} (P14_Task_Book) books the follow-up appointment with the external
 *       scheduling service.</li>
 *   <li>{@code request-follow-up-slot} (P14_Task_RequestSlot) asks the scheduling service for a
 *       slot within the clinically required timeframe and publishes the options.</li>
 * </ul>
 *
 * <p>The two P14 steps are backed by records: the follow-up starts from the clinic letter that was
 * distributed for this referral (the model links process 14 to the distribution step, so the
 * letter is on record), and booking the appointment writes it as the next appointment of the
 * referral - the same table the initial consultation lives in. The P10 step reads the referral so
 * that the organisation it informs is the one the hospital has on record.
 */
@Component
public class PatientContactWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(PatientContactWorkers.class);

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;
    private final ServiceCatalogue catalogue;

    public PatientContactWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database, ServiceCatalogue catalogue) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
        this.catalogue = catalogue;
    }

    /** P10_Task_NotifyReferrer - give the referring organisation the case information. */
    @JobWorker(type = "inform-referring-organisation")
    public Map<String, Object> informReferringOrganisation(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        String organisation = database.referrals().findById(referralId)
                .map(Referral::referringOrganisation)
                .orElse("the referring organisation");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("referrerNotified", true);
        result.put("referrerNotification", switch (Variables.text(variables, "caseEventType", "cancellation")) {
            case "no-show" -> "Patient did not attend; case information sent to " + organisation + " (simulated).";
            case "refusal" -> "Patient refused treatment; case information sent to " + organisation + " (simulated).";
            default -> "Appointment cancelled; case information sent to " + organisation + " (simulated).";
        });

        // The referring organisation runs its own process; the message flow is not executed by the
        // engine, so the worker only records that the information left the hospital.
        LOG.info("referral {}: case information handed to {} (job {})", referralId, organisation, job.getKey());
        return result;
    }

    /** P14_Task_Notify - tell the patient the appointment details. */
    @JobWorker(type = "notify-patient")
    public Map<String, Object> notifyPatientAboutAppointment(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientNotified", true);
        result.put("patientNotificationMethod", "SMS and letter (simulated)");
        result.put("patientNotificationDate", LocalDate.now().toString());

        LOG.info("referral {}: follow-up appointment notice sent to the patient (job {})", referralId, job.getKey());
        return result;
    }

    /** P14_Task_Book - book the follow-up appointment with the scheduling service. */
    @JobWorker(type = "book-follow-up")
    public Map<String, Object> bookFollowUpAppointment(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String requestedBy = Variables.text(variables, "requestedReviewBy", LocalDate.now().plusDays(28).toString());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("followUpBookingReference", "FUB-" + referralId);
        result.put("followUpAppointmentDate", requestedBy);
        result.put("followUpSlotAvailable", true);
        result.put("schedulingServiceNote", "Follow-up booked by the external scheduling service (simulated).");

        LOG.info("referral {}: follow-up appointment {} booked for {} (job {})",
                referralId, result.get("followUpBookingReference"), requestedBy, job.getKey());

        recordFollowUpAppointment(referralId, variables, requestedBy);
        return result;
    }

    /** P14_Task_RequestSlot - ask the scheduling service for a follow-up slot. */
    @JobWorker(type = "request-follow-up-slot")
    public Map<String, Object> requestFollowUpSlot(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        boolean available = properties.isTreatmentServicesAvailable();

        // The follow-up starts from the clinic letter the distribution step completed; naming it
        // keeps the appointment traceable to the visit that asked for it.
        List<ClinicLetter> letters = database.clinicLetters().findByReferralId(referralId);
        Optional<ClinicLetter> letter = letters.isEmpty()
                ? Optional.empty()
                : Optional.of(letters.get(letters.size() - 1));

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("slotAvailable", available ? "yes" : "no");
        answer.put("date_of_appointment", LocalDate.now().plusDays(21).toString());
        answer.put("appointmentTime", "09:15");
        answer.put("availability", available ? "available" : "unavailable");
        answer.put("followUpLetterReference", letter.map(ClinicLetter::letterId).orElse(""));
        answer.put("followUpSlotNote", "Follow-up slot options from the scheduling service (simulated).");

        LOG.info("referral {}: follow-up slot options requested for letter {}, available={} (job {})",
                referralId, letter.map(ClinicLetter::letterId).orElse("not on record"), available, job.getKey());

        messenger.publish(HospitalMessages.SCHEDULING_AVAILABILITY, variables, answer);
        return answer;
    }

    /** Writes the follow-up appointment as the next appointment of this referral. */
    private void recordFollowUpAppointment(String referralId, Map<String, Object> variables, String requestedBy) {
        String appointmentId = "APPT-FU-" + referralId;
        LocalDateTime scheduledFor = Variables.date(variables, "requestedReviewBy", LocalDate.now().plusDays(28))
                .atStartOfDay();

        Appointment appointment = new Appointment(
                appointmentId, referralId, patientId(variables), catalogue.codeOf(variables), scheduledFor,
                Variables.text(variables, "treatmentLocation", null),
                Variables.text(variables, "requestingClinician", Variables.text(variables, "clinicianId", null)),
                "booked", null, null);

        Optional<Appointment> onRecord = database.appointments().findById(appointmentId);
        if (onRecord.isEmpty()) {
            database.appointments().insert(appointment);
        } else {
            database.appointments().update(appointment);
        }
        LOG.info("referral {}: follow-up appointment {} written for {}", referralId, appointmentId, requestedBy);
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}
