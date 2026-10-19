package io.camunda.demo.hospital.persistence.model;

import java.time.LocalDateTime;

/**
 * A report the pathway generated.
 *
 * <p>The report body is kept as text next to its metadata, so a demo can show what the worker
 * produced without a second document store; the metadata is what the reporting worker lists and
 * what a re-run can be compared against.
 *
 * @param reportId    the report's own reference, the primary key
 * @param reportType  which report it is, e.g. pathway-volume or funding-summary
 * @param reportScope what the report covers, e.g. a period or a service
 * @param generatedBy who or what generated it
 * @param generatedOn when it was generated
 * @param rowCount    how many records went into it
 * @param status      generated or superseded
 * @param content     the rendered report
 */
public record Report(
        String reportId,
        String reportType,
        String reportScope,
        String generatedBy,
        LocalDateTime generatedOn,
        int rowCount,
        String status,
        String content) {
}
