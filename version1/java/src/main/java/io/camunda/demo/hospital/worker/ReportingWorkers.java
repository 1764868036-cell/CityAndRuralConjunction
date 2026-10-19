package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hospital.config.SimulationProperties;
import io.camunda.demo.hospital.persistence.HospitalDatabase;
import io.camunda.demo.hospital.persistence.model.PathwayRecord;
import io.camunda.demo.hospital.persistence.model.Report;
import io.camunda.demo.hospital.support.HospitalMessages;
import io.camunda.demo.hospital.support.PathwayAudit;
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
 * Process 15 "Monitoring workflow and report generation".
 *
 * <p>These steps are service tasks without user interaction: collect the pathway data,
 * generate the reports, publish them through the role-based access control of the hospital
 * system and record a pathway update back into the monitoring dataset. They report to state they
 * were started from (a pathway case or the monitoring run) and therefore never read case-specific
 * variables.
 *
 * <p>They are also the only steps that report <em>on</em> the system of record: the data set is
 * collected by counting what the record holds, the reports are generated as one row per report and
 * day (a second run of the same day refreshes them instead of duplicating them), publishing reads
 * the reports back and names what is on record, and the monitoring update is written as the
 * monitoring record of the referral plus an entry in the audit trail.
 */
@Component
public class ReportingWorkers {

    private static final Logger LOG = LoggerFactory.getLogger(ReportingWorkers.class);

    /** The reports the monitoring run produces, as the references the pathway carries. */
    private static final List<String> REPORT_REFERENCES = List.of(
            "RPT-REFERRALS", "RPT-WAITING", "RPT-LETTERS", "RPT-ENQUIRIES", "RPT-PAYMENTS", "RPT-REFUNDS");

    private final HospitalDatabase database;
    private final SimulationProperties properties;
    private final PathwayAudit audit;

    public ReportingWorkers(HospitalDatabase database, SimulationProperties properties, PathwayAudit audit) {
        this.database = database;
        this.properties = properties;
        this.audit = audit;
    }

    /** P15_Task_Collect - collect pathway status, dates, ownership and exception indicators. */
    @JobWorker(type = "collect-pathway-data")
    public Map<String, Object> collectPathwayData(final ActivatedJob job) {
        long referrals = database.referrals().count();
        long appointments = database.appointments().count();
        long letters = database.clinicLetters().count();
        long enquiries = database.enquiries().count();
        long authorisations = database.fundingAuthorisations().count();
        long charges = database.payments().count();
        long refundCases = database.refundCases().count();
        long monitoring = database.pathwayRecords().count();
        long events = database.eventLog().count();

        Map<String, Object> dataSet = new LinkedHashMap<>();
        dataSet.put("reportDataSetReference", "DS-" + job.getKey());
        dataSet.put("dataSetCollectedAt", LocalDateTime.now().toString());
        dataSet.put("dataSetSources", "referrals; appointments; clinic letters; enquiries; funding; payments; refunds");
        dataSet.put("dataSetRecordCount", (int) (referrals + appointments + letters + enquiries + authorisations
                + charges + refundCases + monitoring + events));

        LOG.info("monitoring run: collected data set {} of {} record(s) - {} referral(s), {} appointment(s), "
                        + "{} letter(s), {} enquiry/enquiries, {} charge(s), {} monitoring record(s), {} event(s) "
                        + "(job {})",
                dataSet.get("reportDataSetReference"), dataSet.get("dataSetRecordCount"), referrals, appointments,
                letters, enquiries, charges, monitoring, events, job.getKey());
        return dataSet;
    }

    /** P15_Task_Generate - generate the pathway and operational reports. */
    @JobWorker(type = "generate-pathway-reports")
    public Map<String, Object> generatePathwayReports(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String period = Variables.text(variables, "reportingPeriod", LocalDate.now().toString());
        String requestedBy = Variables.text(variables, "requestedBy", "Performance and quality team");

        // One report per reference, refreshed when the run of the same day runs again: the report id
        // carries the day, so the newest report of a type is the one of the current monitoring day.
        for (String reference : REPORT_REFERENCES) {
            int rows = reportedRows(reference);
            String reportId = reference + "-" + LocalDate.now();
            Report report = new Report(reportId, reference, period, requestedBy, LocalDateTime.now(), rows,
                    "generated", "# " + reference + "\nscope: " + period + "\nrows: " + rows + "\n");

            Optional<Report> onRecord = database.reports().findById(reportId);
            if (onRecord.isEmpty()) {
                database.reports().insert(report);
            } else {
                database.reports().update(report);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportReferences", String.join("; ", REPORT_REFERENCES));
        result.put("reportGeneratedAt", LocalDateTime.now().toString());
        result.put("reportDataSetReference", Variables.text(variables, "reportDataSetReference", "DS-" + job.getKey()));
        result.put("reportCount", REPORT_REFERENCES.size());

        LOG.info("monitoring run: generated {} reports for {}, data set {} (job {})",
                result.get("reportCount"), period, result.get("reportDataSetReference"), job.getKey());
        return result;
    }

    /** P15_Task_Publish - apply role-based access and publish the reports. */
    @JobWorker(type = "publish-pathway-reports")
    public Map<String, Object> publishPathwayReports(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);

        // Publishing names what is on record: every report of the run that was generated is read
        // back and listed, so a report that never reached the record is never announced.
        List<String> published = REPORT_REFERENCES.stream()
                .map(database.reports()::findLatestByReportType)
                .flatMap(Optional::stream)
                .map(report -> report.reportId() + " (" + report.rowCount() + " rows)")
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publishedReportReferences", published.isEmpty()
                ? Variables.text(variables, "reportReferences", "RPT-ALL")
                : String.join("; ", published));
        result.put("publishedAt", LocalDateTime.now().toString());
        result.put("reportAccessScope", "role-based (identifiable pathway details limited to roles that require them)");
        result.put("reportsPublished", true);

        LOG.info("monitoring run: published {} report(s) with role-based access (job {})",
                published.size(), job.getKey());
        return result;
    }

    /** P15_Task_UpdateMonitoringRecord - record the pathway change into the monitoring dataset. */
    @JobWorker(type = "record-pathway-update")
    public Map<String, Object> recordPathwayUpdate(final ActivatedJob job) {
        Map<String, Object> variables = Variables.copyOf(job);
        String referralId = referralId(variables);
        String recordId = "PR-" + referralId;
        LocalDateTime now = LocalDateTime.now();

        Optional<PathwayRecord> onRecord = database.pathwayRecords().findById(recordId);
        PathwayRecord record = new PathwayRecord(
                recordId, referralId, patientId(variables), job.getProcessInstanceKey(),
                Variables.text(variables, "currentStage", Variables.text(variables, "changedRecord", "pathway update")),
                true,
                Variables.date(variables, "followUpDue", null),
                now,
                "monitoring",
                Variables.text(variables, "changeSummary", "Pathway record updated by the monitoring run (simulated)"));

        if (onRecord.isEmpty()) {
            database.pathwayRecords().insert(record);
        } else {
            database.pathwayRecords().update(record);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("monitoringRecordUpdated", true);
        result.put("monitoringUpdateReference", recordId);
        result.put("monitoringUpdateRecordedAt", now.toString());
        result.put("auditTrailUpdated", true);
        result.put("updatedDataSetReference", Variables.text(variables, "reportDataSetReference", "DS-" + job.getKey()));

        LOG.info("monitoring run: pathway update recorded as {} (job {})", recordId, job.getKey());

        audit.jobCompleted(job, variables);
        return result;
    }

    /** The number of records a report covers, read from the aggregate it reports on. */
    private int reportedRows(String reference) {
        return switch (reference) {
            case "RPT-REFERRALS" -> (int) database.referrals().count();
            case "RPT-WAITING" -> (int) database.slotRequests().count();
            case "RPT-LETTERS" -> (int) database.clinicLetters().count();
            case "RPT-ENQUIRIES" -> (int) database.enquiries().count();
            case "RPT-PAYMENTS" -> (int) database.payments().count();
            case "RPT-REFUNDS" -> (int) database.refundCases().count();
            default -> 0;
        };
    }

    private String referralId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.REFERRAL_ID, properties.getDefaultReferralId());
    }

    private String patientId(Map<String, Object> variables) {
        return Variables.text(variables, HospitalMessages.PATIENT_ID, properties.getDefaultPatientId());
    }
}
