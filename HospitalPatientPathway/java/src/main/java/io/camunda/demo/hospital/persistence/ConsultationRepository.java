package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Consultation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The consultations and the consent decisions of the pathway (table {@code consultation}).
 *
 * <p>Consent is written after the consultation was documented and before the treatment starts, so
 * one row carries both and the pathway can always answer whether the patient agreed to this
 * treatment.
 */
@Component
public class ConsultationRepository extends JdbcRepository<Consultation, String> {

    ConsultationRepository(JdbcClient jdbc) {
        super(jdbc, "consultation", "consultation_id");
    }

    /** Documents a consultation, with the consent decision when it is already known. */
    public void insert(Consultation consultation) {
        jdbc().sql("""
                INSERT INTO consultation (consultation_id, referral_id, appointment_id, patient_id, clinician_id,
                                          consultation_type, occurred_on, recommendation, outcome, consent_status,
                                          consent_recorded_by, consent_recorded_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(consultation.consultationId(), consultation.referralId(), consultation.appointmentId(),
                        consultation.patientId(), consultation.clinicianId(), consultation.consultationType(),
                        consultation.occurredOn(), consultation.recommendation(), consultation.outcome(),
                        consultation.consentStatus(), consultation.consentRecordedBy(),
                        consultation.consentRecordedOn())
                .update();
    }

    /** Overwrites the mutable fields of a consultation, which is how the consent decision is added. */
    public void update(Consultation consultation) {
        jdbc().sql("""
                UPDATE consultation
                   SET referral_id = ?, appointment_id = ?, patient_id = ?, clinician_id = ?,
                       consultation_type = ?, occurred_on = ?, recommendation = ?, outcome = ?,
                       consent_status = ?, consent_recorded_by = ?, consent_recorded_on = ?
                 WHERE consultation_id = ?
                """)
                .params(consultation.referralId(), consultation.appointmentId(), consultation.patientId(),
                        consultation.clinicianId(), consultation.consultationType(), consultation.occurredOn(),
                        consultation.recommendation(), consultation.outcome(), consultation.consentStatus(),
                        consultation.consentRecordedBy(), consultation.consentRecordedOn(),
                        consultation.consultationId())
                .update();
    }

    /** The consultations of one referral, oldest first. */
    public List<Consultation> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM consultation WHERE referral_id = ? ORDER BY occurred_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    @Override
    protected Consultation mapRow(ResultSet row, int index) throws SQLException {
        return new Consultation(
                row.getString("consultation_id"),
                row.getString("referral_id"),
                row.getString("appointment_id"),
                row.getString("patient_id"),
                row.getString("clinician_id"),
                row.getString("consultation_type"),
                row.getObject("occurred_on", LocalDateTime.class),
                row.getString("recommendation"),
                row.getString("outcome"),
                row.getString("consent_status"),
                row.getString("consent_recorded_by"),
                row.getObject("consent_recorded_on", LocalDateTime.class));
    }
}
