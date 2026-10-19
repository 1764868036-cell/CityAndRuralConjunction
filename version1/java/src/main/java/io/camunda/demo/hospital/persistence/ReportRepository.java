package io.camunda.demo.hospital.persistence;

import io.camunda.demo.hospital.persistence.model.Report;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The reports the pathway generated (table {@code report}).
 *
 * <p>A report is stored with its body, so the reporting worker can hand the content to the model
 * and a reviewer can read it afterwards without re-running the query; the metadata is what the
 * newest report of a type is picked by.
 */
@Component
public class ReportRepository extends JdbcRepository<Report, String> {

    ReportRepository(JdbcClient jdbc) {
        super(jdbc, "report", "report_id");
    }

    /** Stores a generated report. */
    public void insert(Report report) {
        jdbc().sql("""
                INSERT INTO report (report_id, report_type, report_scope, generated_by, generated_on, row_count,
                                    status, content)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)
                .params(report.reportId(), report.reportType(), report.reportScope(), report.generatedBy(),
                        report.generatedOn(), report.rowCount(), report.status(), report.content())
                .update();
    }

    /** Overwrites the mutable fields of a report, which is how a re-rendered report is recorded. */
    public void update(Report report) {
        jdbc().sql("""
                UPDATE report
                   SET report_type = ?, report_scope = ?, generated_by = ?, generated_on = ?, row_count = ?,
                       status = ?, content = ?
                 WHERE report_id = ?
                """)
                .params(report.reportType(), report.reportScope(), report.generatedBy(), report.generatedOn(),
                        report.rowCount(), report.status(), report.content(), report.reportId())
                .update();
    }

    /** The reports of one type, newest first. */
    public List<Report> findByReportType(String reportType) {
        return jdbc().sql("SELECT * FROM report WHERE report_type = ? ORDER BY generated_on DESC")
                .param(reportType)
                .query(rows())
                .list();
    }

    /** The report of a type that was generated most recently. */
    public Optional<Report> findLatestByReportType(String reportType) {
        // One report per type and day is the normal case, so the query has to pick a row instead of
        // relying on the result set holding one: without the limit the client receives two rows and
        // fails with IncorrectResultSizeDataAccessException from the second day of a persistent
        // database on, which would end the monitoring step in an incident it can never leave.
        return jdbc().sql("SELECT * FROM report WHERE report_type = ? ORDER BY generated_on DESC "
                        + "FETCH FIRST 1 ROW ONLY")
                .param(reportType)
                .query(rows())
                .optional();
    }

    @Override
    protected Report mapRow(ResultSet row, int index) throws SQLException {
        return new Report(
                row.getString("report_id"),
                row.getString("report_type"),
                row.getString("report_scope"),
                row.getString("generated_by"),
                row.getObject("generated_on", LocalDateTime.class),
                row.getInt("row_count"),
                row.getString("status"),
                row.getString("content"));
    }
}
