package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Referral;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The referrals of the pathway (table {@code referral}).
 *
 * <p>Every other aggregate points at a referral, so this is also the table the reporting and
 * monitoring steps list when they need the pathways of a patient or of a status.
 */
@Component
public class ReferralRepository extends JdbcRepository<Referral, String> {

    ReferralRepository(JdbcClient jdbc) {
        super(jdbc, "referral", "referral_id");
    }

    /** Writes the referral the incoming entry message brought in. */
    public void insert(Referral referral) {
        jdbc().sql("""
                INSERT INTO referral (referral_id, patient_id, referring_organisation, referring_clinician,
                                      priority, suspected_condition, external_reference, received_on, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(referral.referralId(), referral.patientId(), referral.referringOrganisation(),
                        referral.referringClinician(), referral.priority(), referral.suspectedCondition(),
                        referral.externalReference(), referral.receivedOn(), referral.status())
                .update();
    }

    /** Overwrites the mutable fields of a referral; the reference and the receipt date stay. */
    public void update(Referral referral) {
        jdbc().sql("""
                UPDATE referral
                   SET patient_id = ?, referring_organisation = ?, referring_clinician = ?, priority = ?,
                       suspected_condition = ?, external_reference = ?, status = ?
                 WHERE referral_id = ?
                """)
                .params(referral.patientId(), referral.referringOrganisation(), referral.referringClinician(),
                        referral.priority(), referral.suspectedCondition(), referral.externalReference(),
                        referral.status(), referral.referralId())
                .update();
    }

    /** The referrals of one patient, newest first. */
    public List<Referral> findByPatientId(String patientId) {
        return jdbc().sql("SELECT * FROM referral WHERE patient_id = ? ORDER BY received_on DESC")
                .param(patientId)
                .query(rows())
                .list();
    }

    /** The referrals in one status, oldest first - the work list of a monitoring step. */
    public List<Referral> findByStatus(String status) {
        return jdbc().sql("SELECT * FROM referral WHERE status = ? ORDER BY received_on")
                .param(status)
                .query(rows())
                .list();
    }

    @Override
    protected Referral mapRow(ResultSet row, int index) throws SQLException {
        return new Referral(
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("referring_organisation"),
                row.getString("referring_clinician"),
                row.getString("priority"),
                row.getString("suspected_condition"),
                row.getString("external_reference"),
                row.getObject("received_on", LocalDateTime.class),
                row.getString("status"));
    }
}
