package io.camunda.demo.hospital.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

/** Validates the active task's nested form data, using the deployed form definitions. */
@Component
public class HospitalFormValidator {
    private static final Set<String> TIMESTAMPS = Set.of("appointmentChangeReceivedAt",
            "identityVerificationAt", "telephoneAttemptAt", "pathwayEscalatedAt");
    private static final Pattern CONDITION = Pattern.compile(
            "=\\s*([\\w.]+)\\s*(!=|=)\\s*\"([^\"]*)\"");
    private final Map<String, TaskForm> tasks = new HashMap<>();

    private record TaskForm(List<JsonNode> common, JsonNode section) {}

    public HospitalFormValidator() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        Resource[] forms = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:hospital-forms/*.json");
        if (forms.length == 0) throw new IllegalStateException("No hospital forms packaged for validation");
        for (Resource resource : forms) {
            JsonNode form;
            try (var input = resource.getInputStream()) { form = mapper.readTree(input); }
            List<JsonNode> common = new ArrayList<>();
            for (JsonNode field : form.path("components")) {
                if (field.has("key")) common.add(field);
            }
            for (JsonNode section : form.path("components")) {
                if (!section.path("id").asText().startsWith("Section_")) continue;
                String task = section.path("id").asText().substring("Section_".length());
                if (!section.path("path").asText().equals("taskData_" + task)) {
                    throw new IllegalStateException("Unexpected form data path for " + task);
                }
                for (JsonNode field : section.path("components")) {
                    String condition = field.path("conditional").path("hide").asText();
                    if (!condition.isEmpty() && !CONDITION.matcher(condition).matches()) {
                        throw new IllegalStateException("Unsupported field condition: " + condition);
                    }
                }
                if (tasks.put(task, new TaskForm(List.copyOf(common), section)) != null) {
                    throw new IllegalStateException("Duplicate task form: " + task);
                }
            }
        }
    }

    public Set<String> taskIds() { return Set.copyOf(tasks.keySet()); }

    /** Element ID is supplied by the engine, never by the hospitalFormTask form variable. */
    public List<String> validate(String elementId, Map<String, Object> variables) {
        TaskForm form = tasks.get(elementId);
        if (form == null) return List.of("No validation contract for task " + elementId);
        List<String> errors = new ArrayList<>();
        for (JsonNode field : form.common()) validateField(field, variables.get(field.path("key").asText()), errors);
        Object raw = variables.get(form.section().path("path").asText());
        if (!(raw instanceof Map<?, ?> data)) {
            errors.add("taskData_" + elementId + ": an object is required");
            return errors;
        }
        for (JsonNode field : form.section().path("components")) {
            if (!field.has("key") || hidden(field, variables)) continue;
            validateField(field, data.get(field.path("key").asText()), errors);
        }
        if (errors.isEmpty()) validateDecisions(elementId, data, errors);
        return List.copyOf(errors);
    }

    private static boolean hidden(JsonNode field, Map<String, Object> variables) {
        String condition = field.path("conditional").path("hide").asText();
        if (condition.isEmpty()) return false;
        var matcher = CONDITION.matcher(condition);
        if (!matcher.matches()) throw new IllegalStateException("Unsupported condition: " + condition);
        Object value = variables;
        for (String part : matcher.group(1).split("\\.")) {
            value = value instanceof Map<?, ?> map ? map.get(part) : null;
        }
        boolean equal = matcher.group(3).equals(value);
        return matcher.group(2).equals("=") ? equal : !equal;
    }

    private static void validateField(JsonNode field, Object value, List<String> errors) {
        String key = field.path("key").asText();
        String type = field.path("type").asText();
        JsonNode rules = field.path("validate");
        boolean required = rules.path("required").asBoolean();
        if (value == null) {
            if (required) errors.add(key + ": required");
            return;
        }
        if (value instanceof String text && text.isBlank()
                && !type.equals("number") && !type.equals("checkbox")) {
            if (required) errors.add(key + ": required");
            else if (!text.isEmpty()) errors.add(key + ": must not contain only whitespace");
            return;
        }
        if (type.equals("checkbox")) {
            if (!(value instanceof Boolean) || required && !Boolean.TRUE.equals(value)) {
                errors.add(key + ": confirmation must be true");
            }
            return;
        }
        if (type.equals("number")) {
            if (!(value instanceof Number)) { errors.add(key + ": a number is required"); return; }
            try {
                BigDecimal number = new BigDecimal(value.toString());
                if (rules.has("min") && number.compareTo(rules.get("min").decimalValue()) < 0
                        || rules.has("max") && number.compareTo(rules.get("max").decimalValue()) > 0) {
                    errors.add(key + ": outside the allowed range");
                }
                if (number.stripTrailingZeros().scale() > field.path("decimalDigits").asInt(2)) {
                    errors.add(key + ": too many decimal places");
                }
            } catch (NumberFormatException e) { errors.add(key + ": a finite number is required"); }
            return;
        }
        if (!(value instanceof String text)) { errors.add(key + ": text is required"); return; }
        if (rules.has("maxLength") && text.length() > rules.path("maxLength").asInt()) {
            errors.add(key + ": exceeds maximum length");
        }
        if (rules.has("pattern") && !Pattern.compile(rules.path("pattern").asText()).matcher(text).matches()) {
            errors.add(key + ": invalid format");
        }
        if (type.equals("radio") || type.equals("select")) {
            boolean allowed = false;
            for (JsonNode option : field.path("values")) allowed |= option.path("value").asText().equals(text);
            if (!allowed) errors.add(key + ": select a listed option");
        }
        try {
            if (type.equals("datetime")) LocalDate.parse(text);
            if (TIMESTAMPS.contains(key)) LocalDateTime.parse(text);
            if (key.endsWith("Delay")) {
                Duration duration = Duration.parse(text);
                if (duration.isNegative() || duration.isZero()) errors.add(key + ": duration must be positive");
            }
        } catch (java.time.DateTimeException | ArithmeticException e) {
            errors.add(key + ": invalid date, time or duration");
        }
    }

    private static void validateDecisions(String task, Map<?, ?> data, List<String> errors) {
        if ((task.equals("R1_RegisterCheck") || task.equals("R1_UpdateReferral"))
                && "no".equals(data.get("documentsCompleteChoice"))) {
            require(data, "missingDocuments", errors);
        }
        if (task.equals("R6_ClassifyFunding") && decimal(data, "patientShare")
                .compareTo(decimal(data, "estimatedCost")) > 0) {
            errors.add("patientShare: cannot exceed estimatedCost");
        }
        if (task.equals("P11_Task_Decide")) {
            if ("transfer".equals(data.get("refundOutcome"))) require(data, "transferBookingReference", errors);
            if ("refund".equals(data.get("refundOutcome")) && decimal(data, "approvedRefundAmount").signum() <= 0) {
                errors.add("approvedRefundAmount: a positive amount is required for a refund");
            }
        }
    }

    private static BigDecimal decimal(Map<?, ?> data, String key) {
        Object value = data.get(key);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static void require(Map<?, ?> data, String key, List<String> errors) {
        if (!(data.get(key) instanceof String text) || text.isBlank()) errors.add(key + ": required for this decision");
    }
}
