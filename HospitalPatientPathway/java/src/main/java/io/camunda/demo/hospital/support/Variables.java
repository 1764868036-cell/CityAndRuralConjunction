package io.camunda.demo.hospital.support;

import io.camunda.client.api.response.ActivatedJob;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Small helpers for reading and writing job variables.
 *
 * <p>The model maps process variables into task-local variables and back out again
 * ({@code zeebe:ioMapping}), so a worker usually just needs the values of the variables that
 * carry the same name as the process variable.
 *
 * <p>The date, time and money readers exist because the workers also write those values into the
 * system of record, whose columns are typed: the form fields and the answers of the external
 * parties carry an ISO date or an {@code HH:mm} time as text, and parsing them here keeps the
 * conversion out of every worker. A value that is present but not parseable fails the job instead
 * of being silently replaced - an unreadable date is a defect of the step that wrote it.
 */
public final class Variables {

    private Variables() {
    }

    /** Writable copy of the variables the job was activated with. */
    public static Map<String, Object> copyOf(ActivatedJob job) {
        return new HashMap<>(job.getVariablesAsMap());
    }

    /** Variable value as text, or {@code null} when it is absent. */
    public static String text(Map<String, Object> variables, String name) {
        return text(variables, name, null);
    }

    /** Variable value as text, with a fallback for absent or empty values. */
    public static String text(Map<String, Object> variables, String name, String fallback) {
        Object value = variables.get(name);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isEmpty() ? fallback : text;
    }

    /** Variable value as boolean; understands real booleans and the form values yes/no. */
    public static boolean flag(Map<String, Object> variables, String name, boolean fallback) {
        Object value = variables.get(name);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text) || "yes".equalsIgnoreCase(text);
        }
        return fallback;
    }

    /** Variable value as int, with a fallback for absent or non-numeric values. */
    public static int number(Map<String, Object> variables, String name, int fallback) {
        Object value = variables.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** Counts how often a retry loop has already run. */
    public static int nextAttempt(Map<String, Object> variables, String counterName) {
        return number(variables, counterName, 0) + 1;
    }

    /** Variable value as an ISO date ({@code yyyy-MM-dd}); the fallback covers absent and empty values. */
    public static LocalDate date(Map<String, Object> variables, String name, LocalDate fallback) {
        String text = text(variables, name, null);
        return text == null ? fallback : LocalDate.parse(text.trim());
    }

    /**
     * Variable value as a date and time.
     *
     * <p>The date comes from {@code dateName} and may be a date or a timestamp; the time of day is
     * optional and read from {@code timeName} (the forms write it to its own field). Without a
     * time the moment is the start of the day named by the date.
     */
    public static LocalDateTime moment(Map<String, Object> variables, String dateName, String timeName,
            LocalDateTime fallback) {
        String dateText = text(variables, dateName, null);
        if (dateText == null) {
            return fallback;
        }
        String trimmed = dateText.trim().replace(' ', 'T');
        LocalDate day = trimmed.length() > 10 ? LocalDateTime.parse(trimmed).toLocalDate() : LocalDate.parse(trimmed);

        String timeText = text(variables, timeName, null);
        return timeText == null ? day.atStartOfDay() : LocalDateTime.of(day, LocalTime.parse(timeText.trim()));
    }

    /** Variable value as a decimal amount; the fallback covers absent and empty values. */
    public static BigDecimal amount(Map<String, Object> variables, String name, String fallback) {
        String text = text(variables, name, fallback);
        return text == null ? null : new BigDecimal(text.trim());
    }
}
