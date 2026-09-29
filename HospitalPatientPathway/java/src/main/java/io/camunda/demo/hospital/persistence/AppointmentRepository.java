package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Appointment;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The appointment offers of the pathway (table {@code appointment}).
 *
 * <p>The offer is written as soon as the scheduling service answers, before the patient has decided:
 * the decision is what the pathway branches on, so it is stored on the same row instead of in a
 * second table that could be missing when the branch is taken.
 *
 * <p>What the steps write: {@code status} runs {@code offered} (the offer is out), {@code booked}
 * (the patient accepted it) and {@code change-requested} (process 17 has to move it), and
 * {@code patient_decision} carries the answer that produced that status ({@code accept},
 * {@code alternative} or {@code decline}). One row per appointment
 * ({@code APPT-<referralId>}, the follow-up {@code APPT-FU-<referralId>}), so a step decides
 * between insert and update on the primary key.
 */
@Component
public class AppointmentRepository extends JdbcRepository<Appointment, String> {

    AppointmentRepository(JdbcClient jdbc) {
        super(jdbc, "appointment", "appointment_id");
    }

    /** Writes the slot the scheduling service offered. */
    public void insert(Appointment appointment) {
        jdbc().sql("""
                INSERT INTO appointment (appointment_id, referral_id, patient_id, service_code, scheduled_for,
                                         location, clinician_id, status, patient_decision, alternative_offered_for)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(appointment.appointmentId(), appointment.referralId(), appointment.patientId(),
                        appointment.serviceCode(), appointment.scheduledFor(), appointment.location(),
                        appointment.clinicianId(), appointment.status(), appointment.patientDecision(),
                        appointment.alternativeOfferedFor())
                .update();
    }

    /** Overwrites the mutable fields of an offer, which is how a decision or a cancellation is recorded. */
    public void update(Appointment appointment) {
        jdbc().sql("""
                UPDATE appointment
                   SET referral_id = ?, patient_id = ?, service_code = ?, scheduled_for = ?, location = ?,
                       clinician_id = ?, status = ?, patient_decision = ?, alternative_offered_for = ?
                 WHERE appointment_id = ?
                """)
                .params(appointment.referralId(), appointment.patientId(), appointment.serviceCode(),
                        appointment.scheduledFor(), appointment.location(), appointment.clinicianId(),
                        appointment.status(), appointment.patientDecision(), appointment.alternativeOfferedFor(),
                        appointment.appointmentId())
                .update();
    }

    /** The offers for one referral, earliest slot first. */
    public List<Appointment> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM appointment WHERE referral_id = ? ORDER BY scheduled_for")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The offers that are booked and fall due before this moment - the reminders to send. */
    public List<Appointment> findBookedBefore(LocalDateTime moment) {
        return jdbc().sql("SELECT * FROM appointment WHERE status = 'booked' AND scheduled_for < ? "
                        + "ORDER BY scheduled_for")
                .param(moment)
                .query(rows())
                .list();
    }

    @Override
    protected Appointment mapRow(ResultSet row, int index) throws SQLException {
        return new Appointment(
                row.getString("appointment_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("service_code"),
                row.getObject("scheduled_for", LocalDateTime.class),
                row.getString("location"),
                row.getString("clinician_id"),
                row.getString("status"),
                row.getString("patient_decision"),
                row.getObject("alternative_offered_for", LocalDateTime.class));
    }
}
