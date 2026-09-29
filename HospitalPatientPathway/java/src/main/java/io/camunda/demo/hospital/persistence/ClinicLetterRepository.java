package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.ClinicLetter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The letters the pathway produced (table {@code clinic_letter}).
 *
 * <p>The letter is stored before it is sent, so a letter that the hospital's letter system never
 * accepted stays visible as a draft instead of disappearing with the failed job.
 *
 * <p>What the steps of process 13 write into {@code status}: {@code draft} (the record is open),
 * {@code approved} (the consultant released the letter), {@code sent} (it is with the correspondence
 * service), {@code complete} (it was distributed; {@code sent_on} is stamped with it) and
 * {@code correction requested} (it went back to the consultant). {@link #findDrafts()} is therefore
 * the work list of the letters that are still open, and one row per letter
 * ({@code LET-<patientId>}) is why a step decides between insert and update on the primary key.
 */
@Component
public class ClinicLetterRepository extends JdbcRepository<ClinicLetter, String> {

    ClinicLetterRepository(JdbcClient jdbc) {
        super(jdbc, "clinic_letter", "letter_id");
    }

    /** Records a letter the worker composed. */
    public void insert(ClinicLetter letter) {
        jdbc().sql("""
                INSERT INTO clinic_letter (letter_id, referral_id, patient_id, consultation_id, letter_type,
                                           recipient, subject, body_summary, status, created_on, sent_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(letter.letterId(), letter.referralId(), letter.patientId(), letter.consultationId(),
                        letter.letterType(), letter.recipient(), letter.subject(), letter.bodySummary(),
                        letter.status(), letter.createdOn(), letter.sentOn())
                .update();
    }

    /** Overwrites the mutable fields of a letter, which is how sending it is recorded. */
    public void update(ClinicLetter letter) {
        jdbc().sql("""
                UPDATE clinic_letter
                   SET referral_id = ?, patient_id = ?, consultation_id = ?, letter_type = ?, recipient = ?,
                       subject = ?, body_summary = ?, status = ?, sent_on = ?
                 WHERE letter_id = ?
                """)
                .params(letter.referralId(), letter.patientId(), letter.consultationId(), letter.letterType(),
                        letter.recipient(), letter.subject(), letter.bodySummary(), letter.status(),
                        letter.sentOn(), letter.letterId())
                .update();
    }

    /** The letters of one referral, oldest first. */
    public List<ClinicLetter> findByReferralId(String referralId) {
        return jdbc().sql("SELECT * FROM clinic_letter WHERE referral_id = ? ORDER BY created_on")
                .param(referralId)
                .query(rows())
                .list();
    }

    /** The letters that were composed but not accepted by the letter system yet. */
    public List<ClinicLetter> findDrafts() {
        return jdbc().sql("SELECT * FROM clinic_letter WHERE status = 'draft' ORDER BY created_on")
                .query(rows())
                .list();
    }

    @Override
    protected ClinicLetter mapRow(ResultSet row, int index) throws SQLException {
        return new ClinicLetter(
                row.getString("letter_id"),
                row.getString("referral_id"),
                row.getString("patient_id"),
                row.getString("consultation_id"),
                row.getString("letter_type"),
                row.getString("recipient"),
                row.getString("subject"),
                row.getString("body_summary"),
                row.getString("status"),
                row.getObject("created_on", LocalDateTime.class),
                row.getObject("sent_on", LocalDateTime.class));
    }
}
