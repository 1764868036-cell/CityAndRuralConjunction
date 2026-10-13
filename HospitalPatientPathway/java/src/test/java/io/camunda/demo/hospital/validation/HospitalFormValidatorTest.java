package io.camunda.demo.hospital.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.demo.hospital.support.Bundle;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class HospitalFormValidatorTest {
    private static HospitalFormValidator validator;

    @BeforeAll
    static void setup() throws Exception { validator = new HospitalFormValidator(); }

    /** Real field names, nested task data and complete common inputs for process tests too. */
    public static Map<String, Object> validSubmission(String task) throws Exception {
        for (var file : Bundle.forms()) {
            JsonNode form = new ObjectMapper().readTree(file.toFile());
            for (JsonNode section : form.path("components")) {
                if (!section.path("id").asText().equals("Section_" + task)) continue;
                Map<String, Object> values = new HashMap<>();
                for (JsonNode field : form.path("components")) {
                    if (field.has("key")) values.put(field.path("key").asText(), sample(field));
                }
                Map<String, Object> data = new HashMap<>();
                for (JsonNode field : section.path("components")) {
                    if (field.has("key")) data.put(field.path("key").asText(), sample(field));
                }
                values.put("taskData_" + task, data);
                return values;
            }
        }
        throw new IllegalArgumentException(task);
    }

    private static Object sample(JsonNode field) {
        String key = field.path("key").asText();
        return switch (field.path("type").asText()) {
            case "number" -> 1;
            case "radio", "select" -> field.path("values").get(0).path("value").asText();
            case "checkbox" -> true;
            case "datetime" -> "2026-10-13";
            default -> key.equals("appointmentTime") ? "09:30"
                    : key.endsWith("Delay") ? "P1D"
                    : java.util.Set.of("appointmentChangeReceivedAt", "identityVerificationAt",
                            "telephoneAttemptAt", "pathwayEscalatedAt").contains(key)
                            ? "2026-10-13T09:30" : "TEST-1";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> values, String task) {
        return (Map<String, Object>) values.get("taskData_" + task);
    }

    @Test
    void allEightyOneFormSectionsAcceptACompleteSubmission() throws Exception {
        assertThat(validator.taskIds()).hasSize(81);
        for (String task : validator.taskIds()) {
            assertThat(validator.validate(task, validSubmission(task))).as(task).isEmpty();
        }
    }

    @Test
    void rejectsMissingWronglyShapedAndSpoofedTaskData() throws Exception {
        var values = validSubmission("R2_Review");
        values.put("hospitalFormTask", "R1_RegisterCheck");
        values.remove("taskData_R2_Review");
        assertThat(validator.validate("R2_Review", values)).anyMatch(e -> e.contains("taskData_R2_Review"));
        values.put("taskData_R2_Review", "not an object");
        assertThat(validator.validate("R2_Review", values)).isNotEmpty();
        assertThat(validator.validate("unknown-task", values)).isNotEmpty();
    }

    @Test
    void rejectsWhitespaceOverlongTextAndUnlistedChoices() throws Exception {
        var values = validSubmission("R2_Review");
        values.put("referralId", " ");
        data(values, "R2_Review").put("clinicalReferralFindings", "x".repeat(2001));
        data(values, "R2_Review").put("referralSuitableChoice", "maybe");
        assertThat(validator.validate("R2_Review", values)).hasSize(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-02-30", "13/10/2026", "2026-13-01"})
    void rejectsInvalidCalendarDates(String date) throws Exception {
        var values = validSubmission("R1_RegisterCheck");
        data(values, "R1_RegisterCheck").put("referralReceivedDate", date);
        assertThat(validator.validate("R1_RegisterCheck", values)).anyMatch(e -> e.startsWith("referralReceivedDate:"));
    }

    @Test
    void rejectsInvalidTimeTimestampAndUncheckedConfirmation() throws Exception {
        var booking = validSubmission("P17_Task_UpdateBooking");
        data(booking, "P17_Task_UpdateBooking").put("appointmentTime", "25:70");
        data(booking, "P17_Task_UpdateBooking").put("oldSlotReleased", false);
        assertThat(validator.validate("P17_Task_UpdateBooking", booking)).hasSize(2);
        var request = validSubmission("P17_Task_RecordRequest");
        data(request, "P17_Task_RecordRequest").put("appointmentChangeReceivedAt", "2026-02-30T10:00");
        assertThat(validator.validate("P17_Task_RecordRequest", request)).isNotEmpty();
    }

    @Test
    void rejectsNegativeFractionalExcessiveAndNonNumericAmounts() throws Exception {
        for (Object amount : new Object[] {-1, new BigDecimal("1.001"), 10000000000L, Double.NaN, "10.00", true}) {
            var values = validSubmission("R7_CalculateCharge");
            data(values, "R7_CalculateCharge").put("chargeAmount", amount);
            assertThat(validator.validate("R7_CalculateCharge", values)).as("amount %s", amount).isNotEmpty();
        }
        var values = validSubmission("R5_ReviewPlan");
        data(values, "R5_ReviewPlan").put("cycleNumber", 1.5);
        assertThat(validator.validate("R5_ReviewPlan", values)).isNotEmpty();
    }

    @Test
    void optionalNumbersStillRequireNumericTypesAndOptionalTimesRejectWhitespace() throws Exception {
        var values = validSubmission("R7_ReconcilePayment");
        data(values, "R7_ReconcilePayment").put("reconciledAmount", " ");
        assertThat(validator.validate("R7_ReconcilePayment", values)).isNotEmpty();
        data(values, "R7_ReconcilePayment").remove("reconciledAmount");
        assertThat(validator.validate("R7_ReconcilePayment", values)).isEmpty();
        var appointment = validSubmission("R3_RecordAvailability");
        data(appointment, "R3_RecordAvailability").put("appointmentTime", " ");
        assertThat(validator.validate("R3_RecordAvailability", appointment)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"P0D", "PT0S", "-P1D", "tomorrow", "P1M", "P1DT"})
    void rejectsInvalidOrNonPositiveRetryDurations(String duration) throws Exception {
        var values = validSubmission("R3_RecordAvailability");
        data(values, "R3_RecordAvailability").put("schedulingRetryDelay", duration);
        assertThat(validator.validate("R3_RecordAvailability", values)).isNotEmpty();
    }

    @Test
    void validatesOnlyVisibleFieldsOfTheActiveTask() throws Exception {
        var values = validSubmission("P9_Task_ConfirmClinicalApproval");
        var data = data(values, "P9_Task_ConfirmClinicalApproval");
        data.put("formalClinicalApproval", "no");
        data.remove("clinicalApprovalReference");
        data.remove("authorisingClinician");
        data.remove("approvalDate");
        assertThat(validator.validate("P9_Task_ConfirmClinicalApproval", values)).isEmpty();
        data.put("formalClinicalApproval", "yes");
        assertThat(validator.validate("P9_Task_ConfirmClinicalApproval", values)).hasSize(3);
    }

    @Test
    void validatesConditionalDecisionsAndRoleAllowlist() throws Exception {
        var referral = validSubmission("R1_RegisterCheck");
        data(referral, "R1_RegisterCheck").put("documentsCompleteChoice", "no");
        data(referral, "R1_RegisterCheck").remove("missingDocuments");
        assertThat(validator.validate("R1_RegisterCheck", referral)).isNotEmpty();
        var funding = validSubmission("R6_ClassifyFunding");
        data(funding, "R6_ClassifyFunding").put("patientShare", 2);
        assertThat(validator.validate("R6_ClassifyFunding", funding)).isNotEmpty();
        var refund = validSubmission("P11_Task_Decide");
        data(refund, "P11_Task_Decide").remove("approvedRefundAmount");
        assertThat(validator.validate("P11_Task_Decide", refund)).isNotEmpty();
        data(refund, "P11_Task_Decide").put("refundOutcome", "transfer");
        data(refund, "P11_Task_Decide").remove("transferBookingReference");
        assertThat(validator.validate("P11_Task_Decide", refund)).isNotEmpty();
        var access = validSubmission("P16_Task_Authenticate");
        data(access, "P16_Task_Authenticate").put("requestedRole", "admin");
        assertThat(validator.validate("P16_Task_Authenticate", access)).isNotEmpty();
    }
}
