package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Enquiry;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The enquiries of the pathway (table {@code enquiry}).
 *
 * <p>The question and its answer are one row: the enquiry worker writes the question when the
 * corridor message arrives and completes the same row when somebody has answered it, which is what
 * the enquiry segment waits for.
 */
@Component
public class EnquiryRepository extends JdbcRepository<Enquiry, String> {

    EnquiryRepository(JdbcClient jdbc) {
        super(jdbc, "enquiry", "enquiry_id");
    }

    /** Records an enquiry that arrived. */
    public void insert(Enquiry enquiry) {
        jdbc().sql("""
                INSERT INTO enquiry (enquiry_id, patient_id, referral_id, channel, raised_by, contact_details,
                                     question, response, status, received_on, answered_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(enquiry.enquiryId(), enquiry.patientId(), enquiry.referralId(), enquiry.channel(),
                        enquiry.raisedBy(), enquiry.contactDetails(), enquiry.question(), enquiry.response(),
                        enquiry.status(), enquiry.receivedOn(), enquiry.answeredOn())
                .update();
    }

    /** Overwrites the mutable fields of an enquiry, which is how the answer is recorded. */
    public void update(Enquiry enquiry) {
        jdbc().sql("""
                UPDATE enquiry
                   SET patient_id = ?, referral_id = ?, channel = ?, raised_by = ?, contact_details = ?,
                       question = ?, response = ?, status = ?, answered_on = ?
                 WHERE enquiry_id = ?
                """)
                .params(enquiry.patientId(), enquiry.referralId(), enquiry.channel(), enquiry.raisedBy(),
                        enquiry.contactDetails(), enquiry.question(), enquiry.response(), enquiry.status(),
                        enquiry.answeredOn(), enquiry.enquiryId())
                .update();
    }

    /** The enquiries of one referral, oldest first. */
    public List<Enquiry> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM enquiry WHERE referral_id = ? ORDER BY received_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The enquiries in one status, oldest first - the work list of the enquiry segment. */
    public List<Enquiry> findByStatus(String status) {
        return jdbc().sql("SELECT * FROM enquiry WHERE status = ? ORDER BY received_on")
                .param(status)
                .query(rows())
                .list();
    }

    @Override
    protected Enquiry mapRow(ResultSet row, int index) throws SQLException {
        return new Enquiry(
                row.getString("enquiry_id"),
                row.getString("patient_id"),
                row.getString("referral_id"),
                row.getString("channel"),
                row.getString("raised_by"),
                row.getString("contact_details"),
                row.getString("question"),
                row.getString("response"),
                row.getString("status"),
                row.getObject("received_on", LocalDateTime.class),
                row.getObject("answered_on", LocalDateTime.class));
    }
}
