package io.camunda.demo.hpas;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hpas.persistence.HospitalDatabase;
import io.camunda.demo.hpas.persistence.model.EventLog;
import io.camunda.demo.hpas.persistence.model.Payment;
import java.math.BigDecimal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Demonstration adapters for the redesigned BPMN. Human clinical and finance
 * decisions are read from forms; no worker invents an authorisation. External
 * systems and patient replies are simulated with correlating BPMN messages.
 */
@Component
public class HpasJobExecutor {
  private static final Duration TTL = Duration.ofMinutes(15);
  private final CamundaClient client;
  private final boolean simulate;
  private final HospitalDatabase database;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public HpasJobExecutor(CamundaClient client,
                         @Value("${hpas.simulation.external-responses:true}") boolean simulate,
                         HospitalDatabase database) {
    this.client = client;
    this.simulate = simulate;
    this.database = database;
  }

  public Map<String, Object> execute(String type, ActivatedJob job) {
    Map<String, Object> input = job.getVariablesAsMap();
    String referralId = required(input, "referralId");
    Map<String, Object> output = new HashMap<>();
    output.put("lastAutomatedAction", type);
    output.put("lastAutomatedAt", Instant.now().toString());

    switch (type) {
      case "hpas-sp01-request-missing-info", "hpas-sp02-request-info" ->
          reply("HPAS: Supplementary information received", referralId,
              Map.of("supplementaryReceived", true));
      case "hpas-sp02-notify-rejection", "hpas-sp02-notify-redirect" ->
          output.put("referralOutcomeNotified", true);
      case "hpas-sp03-flag-urgent" -> output.put("urgentFlagRecorded", true);
      case "hpas-sp03-search-slot" ->
          reply("HPAS: Scheduling result", referralId,
              Map.of("slotFound", bool(input, "simulatedSlotFound", true),
                     "appointmentDate", text(input, "simulatedAppointmentDate", LocalDate.now().plusDays(21).toString()),
                     "slotSearchAttempts", number(input, "slotSearchAttempts", 0) + 1));
      case "hpas-sp03-book-next-available", "hpas-sp03-confirm-booking" -> {
        output.put("appointmentId", text(input, "appointmentId", "APT-" + referralId));
        output.put("withinTwoWeeks", Boolean.parseBoolean(text(input, "withinTwoWeeks", "false")));
      }
      case "hpas-sp03-send-appointment-letter" -> {
        output.put("appointmentLetterRequested", true);
        // Only the demo auto-responder plays the patient's attendance.
        reply("HPAS: Clinic visit completed", referralId,
            Map.of("sp03Outcome", "attended"));
      }
      case "hpas-sp04-validate-request" -> output.put("requestValid",
          has(input, "treatmentPlanId") && has(input, "authorisingClinician") &&
              has(input, "requiredStartDate") && has(input, "resourceType"));
      case "hpas-notify-clinic-letter" ->
          publish("HPAS: Clinic letter required", referralId, Map.of("letterRequired", true));
      case "hpas-sp05-book-internal", "hpas-sp05-book-external" -> {
        required(input, "treatmentPlanId");
        output.put("provisionalBookingId", text(input, "provisionalBookingId", "TRT-" + referralId));
        output.put("bookingStatus", "provisional");
      }
      case "hpas-sp05-request-availability" ->
          output.put("externalAvailable", bool(input, "simulatedExternalAvailable", true));
      case "hpas-sp05-notify-pending" -> {
        output.put("bookingStatus", "pending-external-service");
        reply("HPAS: External slot released", referralId, Map.of("externalAvailable", true));
      }
      case "hpas-sp06-determine-funding" ->
          output.put("fundingRoute", text(input, "fundingRoute", "hospital"));
      case "hpas-sp06-record-exemption" -> {
        if (!bool(input, "exemptionAuthorised", false)) {
          throw new IllegalStateException("An authorised exemption must be recorded first");
        }
        output.put("fundingClearance", "exemption");
      }
      case "hpas-sp06-request-approval" ->
          reply("HPAS: Funding approval received", referralId,
              Map.of("fundingApprovalReceived", true));
      case "hpas-sp07-calculate-charge" -> {
        // For a demo, the charge is supplied as a process variable; a missing
        // amount is an incident rather than an invented patient charge.
        if (!(input.get("chargeAmount") instanceof Number)) {
          throw new IllegalStateException("Finance must provide chargeAmount before payment");
        }
        boolean alreadyPaid = database.payments().findPaidByReferralId(referralId).isPresent();
        boolean priorCharge = alreadyPaid || bool(input, "priorChargeFound", false);
        output.put("priorChargeFound", priorCharge);
        if (priorCharge) {
          output.put("paymentStatus", "completed");
          output.put("paymentVerified", true);
        }
        output.put("chargeCalculated", true);
      }
      case "hpas-sp07-send-payment-request" -> {
        if (bool(input, "priorChargeFound", false)
            || database.payments().findPaidByReferralId(referralId).isPresent()) {
          throw new IllegalStateException("A prior paid charge exists; do not issue another payment request");
        }
        required(input, "appointmentId");
        String requestId = text(input, "paymentRequestId", "PAY-" + referralId);
        Payment existing = database.payments().findByReferralId(referralId).stream()
            .filter(payment -> requestId.equals(payment.paymentId())).findFirst().orElse(null);
        if (existing == null) {
          BigDecimal amount = new BigDecimal(String.valueOf(input.getOrDefault("chargeAmount", "0")));
          database.payments().insert(new Payment(requestId, referralId, text(input, "patientId", "UNKNOWN"),
              text(input, "chargeReference", requestId), amount, text(input, "currency", "GBP"),
              "requested", null, false, LocalDateTime.now(), null));
        } else if (!"paid".equals(existing.status())) {
          database.payments().update(new Payment(existing.paymentId(), existing.referralId(), existing.patientId(),
              existing.chargeReference(), existing.amount(), existing.currency(), "requested", null,
              existing.priorChargeFound(), existing.requestedOn(), null));
        }
        output.put("paymentRequestId", requestId); // deterministic idempotency key
        reply("HPAS: Payment status", referralId,
            Map.of("paymentStatus", text(input, "simulatedPaymentStatus", "completed"),
                   "transactionReference", text(input, "transactionReference", "TX-" + referralId),
                   "paymentRequestId", requestId));
      }
      case "hpas-sp07-record-transaction" -> {
        String status = text(input, "paymentStatus", "");
        if (!"completed".equals(status) && !"taken".equals(text(input, "investigationOutcome", ""))
            && !bool(input, "priorChargeFound", false)) {
          throw new IllegalStateException("Do not clear treatment before PSP payment is verified");
        }
        output.put("paymentStatus", "completed");
        output.put("paymentVerified", true);
        String requestId = text(input, "paymentRequestId", "PAY-" + referralId);
        database.payments().findByReferralId(referralId).stream()
            .filter(payment -> requestId.equals(payment.paymentId())).findFirst()
            .ifPresent(payment -> database.payments().update(new Payment(payment.paymentId(), payment.referralId(),
                payment.patientId(), payment.chargeReference(), payment.amount(), payment.currency(), "paid",
                text(input, "transactionReference", null), payment.priorChargeFound(), payment.requestedOn(),
                LocalDateTime.now())));
      }
      case "hpas-sp07-confirm-booking" -> {
        if (!"completed".equals(text(input, "paymentStatus", ""))
            && !bool(input, "priorChargeFound", false)) {
          throw new IllegalStateException("Advance payment has not been verified");
        }
        output.put("bookingStatus", "confirmed");
      }
      case "hpas-sp07-notify-issue" -> output.put("paymentIssueNotified", true);
      case "hpas-sp07-refer-finance" -> {
        required(input, "urgentOverrideReason");
        required(input, "overrideClinician");
        output.put("urgentOverrideAuthorised", true);
        output.put("financeFollowUpRequired", true);
      }
      case "hpas-sp07-mark-investigation" ->
          output.put("paymentInvestigationRequired", true);
      case "hpas-sp08-request-bloods" ->
          reply("HPAS: Lab result", referralId,
              Map.of("bloodTestReference", "LAB-" + referralId));
      case "hpas-sp08-record-cycle" -> output.put("cycleRecorded", true);
      case "hpas-sp08-notify-change" ->
          publish("HPAS: Treatment change requested", referralId,
              Map.of("changeSource", "clinical-review"));
      case "hpas-sp03b-search-slot" ->
          reply("HPAS: Scheduling result", referralId,
              Map.of("slotAcceptable", bool(input, "simulatedSlotAcceptable", true),
                     "offeredAppointmentDate", text(input, "simulatedAppointmentDate", LocalDate.now().plusDays(21).toString()),
                     "withinTwoWeeks", bool(input, "simulatedWithinTwoWeeks", false)));
      case "hpas-sp03b-apply-reschedule" -> {
        if (!bool(input, "identityVerified", false)) {
          throw new IllegalStateException("Patient identity must be verified before rescheduling");
        }
        LocalDate deadline = date(input, "requestedReviewBy");
        LocalDate offered = date(input, "offeredAppointmentDate");
        if (offered.isAfter(deadline)) {
          throw new IllegalStateException("Offered appointment is outside the requested review window");
        }
        if (bool(input, "withinTwoWeeks", false) && !bool(input, "phoneContactCompleted", false)) {
          throw new IllegalStateException("Near-term rescheduling requires telephone contact");
        }
        output.put("appointmentId", text(input, "appointmentId", "APT-" + referralId));
        output.put("appointmentChanged", true);
      }
      case "hpas-sp03b-notify-patient" -> output.put("patientChangeNotified", true);
      case "hpas-sp09-execute-hold" -> output.put("urgentSafetyHoldActive", true);
      case "hpas-sp09-validate-formal" -> output.put("formallyAuthorised",
          bool(input, "formallyAuthorised", false));
      case "hpas-sp09-apply-change" -> {
        if (!bool(input, "formallyAuthorised", false)) {
          throw new IllegalStateException("Treatment change lacks clinical authorisation");
        }
        if (bool(input, "financialImpact", false) && !bool(input, "financialReviewComplete", false)) {
          throw new IllegalStateException("Financial implications must be rechecked before applying the change");
        }
        output.put("treatmentChangeApplied", true);
      }
      case "hpas-sp09-notify-plan" ->
          publish("HPAS: Treatment plan updated", referralId, Map.of("planUpdated", true));
      case "hpas-notify-refund-case" ->
          publish("HPAS: Refund case opened", referralId, Map.of("refundCaseOpen", true));
      case "hpas-sp10-notify-rebook" ->
          publish("HPAS: Appointment change requested", referralId,
              Map.of("changeKind", "reschedule"));
      case "hpas-sp10-notify-referrer" -> output.put("referrerNotified", true);
      case "hpas-sp11-send-refund" -> {
        if (!bool(input, "refundDue", false) || !bool(input, "refundAuthorised", false)
            || !has(input, "financeApprover")) {
          throw new IllegalStateException("Finance approval required before refund");
        }
        output.put("refundRequestId", text(input, "refundRequestId", "REFUND-" + referralId));
        reply("HPAS: Refund result", referralId,
            Map.of("refundStatus", "completed", "refundReference", "RF-" + referralId));
      }
      case "hpas-sp11-transfer-funds" -> {
        if (!"transfer".equals(text(input, "refundScope", ""))
            || !bool(input, "fundTransferAuthorised", false) || !has(input, "financeApprover")) {
          throw new IllegalStateException("An authorised finance transfer decision is required");
        }
        output.put("fundTransferId", text(input, "fundTransferId", "TRANSFER-" + referralId));
        output.put("fundTransferRecorded", true);
      }
      case "hpas-sp08-stop-treatment" -> {
        if (!"stop".equals(text(input, "fitnessDecision", "")) || !has(input, "stopReason")) {
          throw new IllegalStateException("A recorded clinical stop decision and reason are required");
        }
        output.put("treatmentStopped", true);
      }
      case "hpas-sp11-record-refund" -> output.put("refundRecorded", true);
      case "hpas-sp11-record-no-refund" -> output.put("refundRecorded", false);
      case "hpas-sp12-flag-urgent" -> {
        if (!bool(input, "urgentConcern", false)) {
          throw new IllegalStateException("Only CNS-assessed urgent concerns are highlighted");
        }
        output.put("urgentClinicalAlert", true);
      }
      case "hpas-sp12-close-record" -> output.put("enquiryResolvedAt", Instant.now().toString());
      case "hpas-sp13-distribute" -> {
        if (!bool(input, "letterApproved", false)) {
          throw new IllegalStateException("Consultant approval required before letter distribution");
        }
        output.put("letterDistributedAt", Instant.now().toString());
      }
      case "hpas-sp13-record-dates" -> output.put("letterAuditRecordedAt", Instant.now().toString());
      case "hpas-sp13-notify-follow-up" ->
          publish("HPAS: Follow-up instruction authorised", referralId,
              Map.of("followUpInstructed", true));
      case "hpas-sp14-search-slot" ->
          reply("HPAS: Scheduling result", referralId,
              Map.of("inRequestedPeriod", bool(input, "simulatedInRequestedPeriod", true)));
      case "hpas-sp14-book-follow-up" -> output.put("followUpAppointmentId",
          text(input, "followUpAppointmentId", "FUP-" + referralId));
      case "hpas-sp14-inform-patient" -> output.put("followUpNotificationSent", true);
      case "hpas-sp15-scan-outstanding" -> output.put("letterOverdueMonths",
          input.getOrDefault("letterOverdueMonths", 0));
      case "hpas-sp15-generate-reports" -> output.put("reportGeneratedAt", Instant.now().toString());
      case "hpas-sp16-apply-access" -> output.put("accessDecisionRecorded",
          bool(input, "accessAuthorised", false) ? "approved" : "denied");
      case "hpas-sp16-reconcile" -> output.put("downtimeReconciledAt", Instant.now().toString());
      case "hpas-sp16-verify-restoration" -> {
        required(input, "incidentReference");
        output.put("restorationVerified", true);
      }
      case "hpas-sp16-audit" -> output.put("auditCheckpointAt", Instant.now().toString());
      case "hpas-notify-pathway-closure" -> output.put("closureNotifiedAt", Instant.now().toString());
      default -> throw new IllegalArgumentException("Unimplemented BPMN job type: " + type);
    }
    // Keep an audit of completed worker decisions, but never persist submitted form variables.
    // The output map contains generated result fields only; subject identifiers remain indexed
    // columns so unknown/external referral IDs do not block the audit trail.
    try {
      database.eventLog().append(new EventLog(
          UUID.randomUUID().toString(), job.getProcessInstanceKey(), job.getElementId(), type,
          null, "job-completed", referralId, text(input, "patientId", null),
          LocalDateTime.now(), objectMapper.writeValueAsString(output)));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not serialize worker result for audit", e);
    }
    return output;
  }

  private void reply(String name, String referralId, Map<String, Object> variables) {
    if (simulate) publish(name, referralId, variables);
  }

  private void publish(String name, String referralId, Map<String, Object> variables) {
    client.newPublishMessageCommand().messageName(name).correlationKey(referralId)
        .messageId(name + ":" + referralId + ":" + UUID.randomUUID())
        .timeToLive(TTL).variables(variables).send().join();
  }

  private static String required(Map<String, Object> input, String key) {
    String value = text(input, key, "");
    if (value.isBlank()) throw new IllegalStateException("Missing required process variable: " + key);
    return value;
  }

  private static boolean has(Map<String, Object> input, String key) {
    return !text(input, key, "").isBlank();
  }

  private static String text(Map<String, Object> input, String key, String fallback) {
    Object value = input.get(key);
    return value == null ? fallback : String.valueOf(value);
  }

  private static boolean bool(Map<String, Object> input, String key, boolean fallback) {
    Object value = input.get(key);
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }

  private static int number(Map<String, Object> input, String key, int fallback) {
    Object value = input.get(key);
    if (value instanceof Number n) return n.intValue();
    try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return fallback; }
  }

  private static LocalDate date(Map<String, Object> input, String key) {
    String value = text(input, key, "");
    if (value.isBlank()) throw new IllegalStateException("Missing required process variable: " + key);
    return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value);
  }
}
