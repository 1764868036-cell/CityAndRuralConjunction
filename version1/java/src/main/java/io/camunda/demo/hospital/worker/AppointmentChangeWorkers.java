package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.Appointment;
import io.camunda.demo.hospital.support.ExternalPartyMessenger;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.Variables;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <ul>
 * Process 17 "Handle Appointment Change Requests and Escalate Scheduling Conflicts": the automatic
 *
 * steps of the appointment-change path.
 *   <li>{@code request-appointment-change-options} (P17_Task_RequestSlot) asks the scheduling
 *       service for new options and publishes them for the waiting catch event.</li>
 *   <li>{@code notify-changed-appointment} (P17_Task_NotifyPatient) tells the patient the updated
 *       appointment details.</li>
 *   <li>{@code refer-funding-change} (P17_Task_ReferFundingChange) hands a payment or funding
 *       change to Finance.</li>
 *   <li>{@code refer-treatment-plan-change} (P17_Task_ReferPlanChange) hands a plan change to the
 *       clinician for authorisation.</li>
 *   <li>{@code request-clinical-priority-review} (P17_Task_SendPriorityReview) asks a Consultant
 *       for a priority decision on a scheduling conflict.</li>
 * </ul>
 *
 * <p>The change itself lives in the {@code appointment} the pathway booked: asking for new options
 * marks the appointment as waiting for its new date, and the notification moves it. A change
 * request can also arrive for a referral whose booking this pathway does not have on record (the
 * message is correlated by referral, not by appointment); the two steps then only offer and
 * announce the options, and nothing is moved.
 */
@Component
public class AppointmentChangeWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(AppointmentChangeWorkers.class);

    private final ExternalPartyMessenger messenger;
    private final SimulationProperties properties;
    private final HospitalDatabase database;

    public AppointmentChangeWorkers(ExternalPartyMessenger messenger, SimulationProperties properties,
            HospitalDatabase database) {
        this.messenger = messenger;
        this.properties = properties;
        this.database = database;
    }

    /** P17_Task_RequestSlot - ask the scheduling service for new appointment options. */
    @JobWorker(type = "request-appointment-change-options")
    public Map<String, Object> requestAppointmentChangeOptions(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        boolean available = properties.isTreatmentServicesAvailable();

        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("slotAvailable", available ? "yes" : "no");
        answer.put("date_of_appointment", LocalDate.now().plusDays(10).toString());
        answer.put("appointmentTime", "11:00");
        answer.put("availability", available ? "available" : "unavailable");
        answer.put("appointmentChangeOptionsNote", "New appointment options from the scheduling service (simulated).");

        LOG.info("referral {}: appointment-change options requested, available={} (job {})",
                referralId, available, job.getKey());

        appointmentOf(referralId).ifPresent(booked -> {
            database.appointments().update(new Appointment(booked.appointmentId(), booked.referralId(),
                    booked.patientId(), booked.serviceCode(), booked.scheduledFor(), booked.location(),
                    booked.clinicianId(), "change-requested", booked.patientDecision(),
                    booked.alternativeOfferedFor()));
            LOG.info("referral {}: appointment {} marked as waiting for its new date", referralId,
                    booked.appointmentId());
        });

        messenger.publish(HospitalMessages.SCHEDULING_AVAILABILITY, variables, answer);
        return answer;
    }

    /** P17_Task_NotifyPatient - tell the patient the updated appointment details. */
    @JobWorker(type = "notify-changed-appointment")
    public Map<String, Object> notifyChangedAppointment(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        LocalDateTime changedFor = Variables.moment(variables, "date_of_appointment", "appointmentTime",
                LocalDateTime.now().plusDays(10));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appointmentChanged", true);
        result.put("patientNotified", true);
        result.put("changeNotificationReference", "CHG-NOT-" + referralId);

        appointmentOf(referralId).ifPresent(booked -> {
            database.appointments().update(new Appointment(booked.appointmentId(), booked.referralId(),
                    booked.patientId(), booked.serviceCode(), changedFor, booked.location(), booked.clinicianId(),
                    "booked", "accepted", booked.alternativeOfferedFor()));
            LOG.info("referral {}: appointment {} moved to {}", referralId, booked.appointmentId(), changedFor);
        });

        // The date the patient is told is the date the booking now carries, not the one the option
        // carried: after the move the record is what the hospital will act on.
        result.put("changedAppointmentDate", appointmentOf(referralId)
                .map(booked -> booked.scheduledFor().toLocalDate().toString())
                .orElse(changedFor.toLocalDate().toString()));

        LOG.info("referral {}: updated appointment details sent to the patient (job {})", referralId, job.getKey());
        return result;
    }

    /** P17_Task_ReferFundingChange - hand a payment or funding change to Finance. */
    @JobWorker(type = "refer-funding-change")
    public Map<String, Object> referFundingChange(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fundingChangeReferred", true);
        result.put("fundingChangeReference", "FND-CHG-" + referralId);
        result.put("fundingChangeReferredDate", LocalDate.now().toString());
        result.put("fundingChangeTeam", "Finance team");

        LOG.info("referral {}: funding change handed to Finance as {} (job {})",
                referralId, result.get("fundingChangeReference"), job.getKey());
        return result;
    }

    /** P17_Task_ReferPlanChange - hand a treatment-plan change to the clinician. */
    @JobWorker(type = "refer-treatment-plan-change")
    public Map<String, Object> referTreatmentPlanChange(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planChangeReferred", true);
        result.put("planChangeReference", "PLN-CHG-" + referralId);
        result.put("planChangeReferredDate", LocalDate.now().toString());
        result.put("planChangeAuthoriser", "Consultant");

        LOG.info("referral {}: treatment-plan change referred for clinical authorisation as {} (job {})",
                referralId, result.get("planChangeReference"), job.getKey());
        return result;
    }

    /** P17_Task_SendPriorityReview - ask a Consultant for a priority decision. */
    @JobWorker(type = "request-clinical-priority-review")
    public Map<String, Object> requestClinicalPriorityReview(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("priorityReviewRequested", true);
        result.put("priorityReviewReference", "PRI-REV-" + referralId);
        result.put("priorityReviewDate", LocalDate.now().toString());

        LOG.info("referral {}: clinical priority review requested as {} (job {})",
                referralId, result.get("priorityReviewReference"), job.getKey());
        return result;
    }

    /** The booking this referral has on record, which the appointment-change path moves. */
    private Optional<Appointment> appointmentOf(String referralId) {
        return database.appointments().findById("APPT-" + referralId);
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }
}
