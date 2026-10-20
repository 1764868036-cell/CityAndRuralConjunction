package io.camunda.demo.hpas;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import java.util.Map;
import org.springframework.stereotype.Component;

/** One typed Camunda worker registration per automated BPMN step. */
@Component
public class HpasJobWorkers {
  private final HpasJobExecutor executor;

  public HpasJobWorkers(HpasJobExecutor executor) { this.executor = executor; }

  @JobWorker(type = "hpas-sp03b-validate-slot")
  public Map<String, Object> hpas_sp03b_validate_slot(ActivatedJob job) {
    return executor.execute("hpas-sp03b-validate-slot", job);
  }

  @JobWorker(type = "hpas-sp11-transfer")
  public Map<String, Object> hpas_sp11_transfer(ActivatedJob job) {
    return executor.execute("hpas-sp11-transfer", job);
  }

  @JobWorker(type = "hpas-notify-clinic-letter")
  public Map<String, Object> hpas_notify_clinic_letter(ActivatedJob job) {
    return executor.execute("hpas-notify-clinic-letter", job);
  }

  @JobWorker(type = "hpas-notify-pathway-closure")
  public Map<String, Object> hpas_notify_pathway_closure(ActivatedJob job) {
    return executor.execute("hpas-notify-pathway-closure", job);
  }

  @JobWorker(type = "hpas-notify-refund-case")
  public Map<String, Object> hpas_notify_refund_case(ActivatedJob job) {
    return executor.execute("hpas-notify-refund-case", job);
  }

  @JobWorker(type = "hpas-sp01-request-missing-info")
  public Map<String, Object> hpas_sp01_request_missing_info(ActivatedJob job) {
    return executor.execute("hpas-sp01-request-missing-info", job);
  }

  @JobWorker(type = "hpas-sp02-notify-redirect")
  public Map<String, Object> hpas_sp02_notify_redirect(ActivatedJob job) {
    return executor.execute("hpas-sp02-notify-redirect", job);
  }

  @JobWorker(type = "hpas-sp02-notify-rejection")
  public Map<String, Object> hpas_sp02_notify_rejection(ActivatedJob job) {
    return executor.execute("hpas-sp02-notify-rejection", job);
  }

  @JobWorker(type = "hpas-sp02-request-info")
  public Map<String, Object> hpas_sp02_request_info(ActivatedJob job) {
    return executor.execute("hpas-sp02-request-info", job);
  }

  @JobWorker(type = "hpas-sp03-book-next-available")
  public Map<String, Object> hpas_sp03_book_next_available(ActivatedJob job) {
    return executor.execute("hpas-sp03-book-next-available", job);
  }

  @JobWorker(type = "hpas-sp03-confirm-booking")
  public Map<String, Object> hpas_sp03_confirm_booking(ActivatedJob job) {
    return executor.execute("hpas-sp03-confirm-booking", job);
  }

  @JobWorker(type = "hpas-sp03-flag-urgent")
  public Map<String, Object> hpas_sp03_flag_urgent(ActivatedJob job) {
    return executor.execute("hpas-sp03-flag-urgent", job);
  }

  @JobWorker(type = "hpas-sp03-search-slot")
  public Map<String, Object> hpas_sp03_search_slot(ActivatedJob job) {
    return executor.execute("hpas-sp03-search-slot", job);
  }

  @JobWorker(type = "hpas-sp03-send-appointment-letter")
  public Map<String, Object> hpas_sp03_send_appointment_letter(ActivatedJob job) {
    return executor.execute("hpas-sp03-send-appointment-letter", job);
  }

  @JobWorker(type = "hpas-sp03b-apply-reschedule")
  public Map<String, Object> hpas_sp03b_apply_reschedule(ActivatedJob job) {
    return executor.execute("hpas-sp03b-apply-reschedule", job);
  }

  @JobWorker(type = "hpas-sp03b-notify-patient")
  public Map<String, Object> hpas_sp03b_notify_patient(ActivatedJob job) {
    return executor.execute("hpas-sp03b-notify-patient", job);
  }

  @JobWorker(type = "hpas-sp03b-search-slot")
  public Map<String, Object> hpas_sp03b_search_slot(ActivatedJob job) {
    return executor.execute("hpas-sp03b-search-slot", job);
  }

  @JobWorker(type = "hpas-sp04-validate-request")
  public Map<String, Object> hpas_sp04_validate_request(ActivatedJob job) {
    return executor.execute("hpas-sp04-validate-request", job);
  }

  @JobWorker(type = "hpas-sp05-book-external")
  public Map<String, Object> hpas_sp05_book_external(ActivatedJob job) {
    return executor.execute("hpas-sp05-book-external", job);
  }

  @JobWorker(type = "hpas-sp05-book-internal")
  public Map<String, Object> hpas_sp05_book_internal(ActivatedJob job) {
    return executor.execute("hpas-sp05-book-internal", job);
  }

  @JobWorker(type = "hpas-sp05-notify-pending")
  public Map<String, Object> hpas_sp05_notify_pending(ActivatedJob job) {
    return executor.execute("hpas-sp05-notify-pending", job);
  }

  @JobWorker(type = "hpas-sp05-request-availability")
  public Map<String, Object> hpas_sp05_request_availability(ActivatedJob job) {
    return executor.execute("hpas-sp05-request-availability", job);
  }

  @JobWorker(type = "hpas-sp06-determine-funding")
  public Map<String, Object> hpas_sp06_determine_funding(ActivatedJob job) {
    return executor.execute("hpas-sp06-determine-funding", job);
  }

  @JobWorker(type = "hpas-sp06-record-exemption")
  public Map<String, Object> hpas_sp06_record_exemption(ActivatedJob job) {
    return executor.execute("hpas-sp06-record-exemption", job);
  }

  @JobWorker(type = "hpas-sp06-request-approval")
  public Map<String, Object> hpas_sp06_request_approval(ActivatedJob job) {
    return executor.execute("hpas-sp06-request-approval", job);
  }

  @JobWorker(type = "hpas-sp07-calculate-charge")
  public Map<String, Object> hpas_sp07_calculate_charge(ActivatedJob job) {
    return executor.execute("hpas-sp07-calculate-charge", job);
  }

  @JobWorker(type = "hpas-sp07-confirm-booking")
  public Map<String, Object> hpas_sp07_confirm_booking(ActivatedJob job) {
    return executor.execute("hpas-sp07-confirm-booking", job);
  }

  @JobWorker(type = "hpas-sp07-mark-investigation")
  public Map<String, Object> hpas_sp07_mark_investigation(ActivatedJob job) {
    return executor.execute("hpas-sp07-mark-investigation", job);
  }

  @JobWorker(type = "hpas-sp07-notify-issue")
  public Map<String, Object> hpas_sp07_notify_issue(ActivatedJob job) {
    return executor.execute("hpas-sp07-notify-issue", job);
  }

  @JobWorker(type = "hpas-sp07-record-transaction")
  public Map<String, Object> hpas_sp07_record_transaction(ActivatedJob job) {
    return executor.execute("hpas-sp07-record-transaction", job);
  }

  @JobWorker(type = "hpas-sp07-refer-finance")
  public Map<String, Object> hpas_sp07_refer_finance(ActivatedJob job) {
    return executor.execute("hpas-sp07-refer-finance", job);
  }

  @JobWorker(type = "hpas-sp07-send-payment-request")
  public Map<String, Object> hpas_sp07_send_payment_request(ActivatedJob job) {
    return executor.execute("hpas-sp07-send-payment-request", job);
  }

  @JobWorker(type = "hpas-sp08-notify-change")
  public Map<String, Object> hpas_sp08_notify_change(ActivatedJob job) {
    return executor.execute("hpas-sp08-notify-change", job);
  }

  @JobWorker(type = "hpas-sp08-record-cycle")
  public Map<String, Object> hpas_sp08_record_cycle(ActivatedJob job) {
    return executor.execute("hpas-sp08-record-cycle", job);
  }

  @JobWorker(type = "hpas-sp08-request-bloods")
  public Map<String, Object> hpas_sp08_request_bloods(ActivatedJob job) {
    return executor.execute("hpas-sp08-request-bloods", job);
  }

  @JobWorker(type = "hpas-sp09-apply-change")
  public Map<String, Object> hpas_sp09_apply_change(ActivatedJob job) {
    return executor.execute("hpas-sp09-apply-change", job);
  }

  @JobWorker(type = "hpas-sp09-execute-hold")
  public Map<String, Object> hpas_sp09_execute_hold(ActivatedJob job) {
    return executor.execute("hpas-sp09-execute-hold", job);
  }

  @JobWorker(type = "hpas-sp09-notify-plan")
  public Map<String, Object> hpas_sp09_notify_plan(ActivatedJob job) {
    return executor.execute("hpas-sp09-notify-plan", job);
  }

  @JobWorker(type = "hpas-sp09-validate-formal")
  public Map<String, Object> hpas_sp09_validate_formal(ActivatedJob job) {
    return executor.execute("hpas-sp09-validate-formal", job);
  }

  @JobWorker(type = "hpas-sp10-notify-rebook")
  public Map<String, Object> hpas_sp10_notify_rebook(ActivatedJob job) {
    return executor.execute("hpas-sp10-notify-rebook", job);
  }

  @JobWorker(type = "hpas-sp10-notify-referrer")
  public Map<String, Object> hpas_sp10_notify_referrer(ActivatedJob job) {
    return executor.execute("hpas-sp10-notify-referrer", job);
  }

  @JobWorker(type = "hpas-sp11-record-no-refund")
  public Map<String, Object> hpas_sp11_record_no_refund(ActivatedJob job) {
    return executor.execute("hpas-sp11-record-no-refund", job);
  }

  @JobWorker(type = "hpas-sp11-record-refund")
  public Map<String, Object> hpas_sp11_record_refund(ActivatedJob job) {
    return executor.execute("hpas-sp11-record-refund", job);
  }

  @JobWorker(type = "hpas-sp11-send-refund")
  public Map<String, Object> hpas_sp11_send_refund(ActivatedJob job) {
    return executor.execute("hpas-sp11-send-refund", job);
  }

  @JobWorker(type = "hpas-sp12-close-record")
  public Map<String, Object> hpas_sp12_close_record(ActivatedJob job) {
    return executor.execute("hpas-sp12-close-record", job);
  }

  @JobWorker(type = "hpas-sp12-flag-urgent")
  public Map<String, Object> hpas_sp12_flag_urgent(ActivatedJob job) {
    return executor.execute("hpas-sp12-flag-urgent", job);
  }

  @JobWorker(type = "hpas-sp13-distribute")
  public Map<String, Object> hpas_sp13_distribute(ActivatedJob job) {
    return executor.execute("hpas-sp13-distribute", job);
  }

  @JobWorker(type = "hpas-sp13-notify-follow-up")
  public Map<String, Object> hpas_sp13_notify_follow_up(ActivatedJob job) {
    return executor.execute("hpas-sp13-notify-follow-up", job);
  }

  @JobWorker(type = "hpas-sp13-record-dates")
  public Map<String, Object> hpas_sp13_record_dates(ActivatedJob job) {
    return executor.execute("hpas-sp13-record-dates", job);
  }

  @JobWorker(type = "hpas-sp14-book-follow-up")
  public Map<String, Object> hpas_sp14_book_follow_up(ActivatedJob job) {
    return executor.execute("hpas-sp14-book-follow-up", job);
  }

  @JobWorker(type = "hpas-sp14-inform-patient")
  public Map<String, Object> hpas_sp14_inform_patient(ActivatedJob job) {
    return executor.execute("hpas-sp14-inform-patient", job);
  }

  @JobWorker(type = "hpas-sp14-search-slot")
  public Map<String, Object> hpas_sp14_search_slot(ActivatedJob job) {
    return executor.execute("hpas-sp14-search-slot", job);
  }

  @JobWorker(type = "hpas-sp15-generate-reports")
  public Map<String, Object> hpas_sp15_generate_reports(ActivatedJob job) {
    return executor.execute("hpas-sp15-generate-reports", job);
  }

  @JobWorker(type = "hpas-sp15-scan-outstanding")
  public Map<String, Object> hpas_sp15_scan_outstanding(ActivatedJob job) {
    return executor.execute("hpas-sp15-scan-outstanding", job);
  }

  @JobWorker(type = "hpas-sp16-apply-access")
  public Map<String, Object> hpas_sp16_apply_access(ActivatedJob job) {
    return executor.execute("hpas-sp16-apply-access", job);
  }

  @JobWorker(type = "hpas-sp16-audit")
  public Map<String, Object> hpas_sp16_audit(ActivatedJob job) {
    return executor.execute("hpas-sp16-audit", job);
  }

  @JobWorker(type = "hpas-sp16-reconcile")
  public Map<String, Object> hpas_sp16_reconcile(ActivatedJob job) {
    return executor.execute("hpas-sp16-reconcile", job);
  }

}
