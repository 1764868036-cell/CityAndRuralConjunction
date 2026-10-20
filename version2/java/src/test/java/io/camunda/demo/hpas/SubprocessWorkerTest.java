package io.camunda.demo.hpas;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hpas.persistence.HospitalDatabase;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubprocessWorkerTest {
  private final HospitalDatabase database = mock(HospitalDatabase.class, RETURNS_DEEP_STUBS);
  private final CamundaClient client = mock(CamundaClient.class, RETURNS_DEEP_STUBS);
  private final HpasJobExecutor executor = new HpasJobExecutor(client, false, database);
  private ActivatedJob job(Map<String,Object> data) {
    var job=mock(ActivatedJob.class);
    var variables=new java.util.HashMap<String,Object>(data);variables.put("referralId","TEST-SUB");
    when(job.getVariablesAsMap()).thenReturn(variables);
    when(job.getKey()).thenReturn(42L);when(job.getElementInstanceKey()).thenReturn(43L);
    return job;
  }
  @Test void explicitRefundRefusalCannotBeOverriddenByApproverName() {
    assertThatThrownBy(()->executor.execute("hpas-sp11-send-refund",job(Map.of(
        "refundDue",true,"refundAuthorised",false,"refundScope","full","refundAmount",10,"financeApprover","TEST"))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void transferNeverUsesRefundWorker() {
    assertThatThrownBy(()->executor.execute("hpas-sp11-send-refund",job(Map.of(
        "refundDue",true,"refundAuthorised",true,"refundScope","transfer","refundAmount",10,"financeApprover","TEST"))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void zeroAmountCannotBeRefunded() {
    assertThatThrownBy(()->executor.execute("hpas-sp11-send-refund",job(Map.of(
        "refundDue",true,"refundAuthorised",true,"refundScope","full","refundAmount",0,"financeApprover","TEST"))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void transferHasIndependentResultAndRequiresDestination() {
    assertThatThrownBy(()->executor.execute("hpas-sp11-transfer",job(Map.of(
        "refundDue",true,"refundAuthorised",true,"refundScope","transfer","refundAmount",10,"financeApprover","TEST"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(executor.execute("hpas-sp11-transfer",job(Map.of(
        "refundDue",true,"refundAuthorised",true,"refundScope","transfer","refundAmount",10,
        "financeApprover","TEST","transferDestination","TEST-ACCOUNT"))))
        .containsEntry("transferStatus","instruction-recorded").doesNotContainKey("refundRecorded");
  }
  @Test void failedRefundMustNotBeRecordedAsCompleted() {
    assertThatThrownBy(()->executor.execute("hpas-sp11-record-refund",job(Map.of("refundStatus","failed"))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void financeRefusalPreventsFinanciallyAffectedChange() {
    assertThatThrownBy(()->executor.execute("hpas-sp09-apply-change",job(Map.of(
        "formallyAuthorised",true,"financialImpact",true,"financialReviewApproved",false))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void rescheduleRequiresIdentityAndDeadline() {
    assertThatThrownBy(()->executor.execute("hpas-sp03b-apply-reschedule",job(Map.of(
        "identityVerified",false,"appointmentDate","2026-11-01","requestedReviewBy","2026-12-01"))))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(()->executor.execute("hpas-sp03b-apply-reschedule",job(Map.of(
        "identityVerified",true,"appointmentDate","2026-12-02","requestedReviewBy","2026-12-01"))))
        .isInstanceOf(IllegalStateException.class);
  }
  @Test void positiveRefundUsesJobScopedIdAcrossRetries() {
    var input=job(Map.of("refundDue",true,"refundAuthorised",true,"refundScope","full",
        "refundAmount",10,"financeApprover","TEST"));
    assertThat(executor.execute("hpas-sp11-send-refund",input)).containsEntry("refundRequestId","REFUND-42");
    assertThat(executor.execute("hpas-sp11-send-refund",input)).containsEntry("refundRequestId","REFUND-42");
  }
  @Test void slotRepliesUseBranchSpecificMessageAndStableId() {
    var simulator=new HpasJobExecutor(client,true,database);
    var input=job(Map.of("requestedReviewBy","2099-01-01","simulatedAppointmentDate","2099-01-01"));
    assertThat(simulator.execute("hpas-sp03b-search-slot",input)).containsEntry("schedulingRequestId","SP03B-SLOT-42");
    simulator.execute("hpas-sp03b-search-slot",input);
    verify(client.newPublishMessageCommand().messageName("HPAS: SP03B scheduling result")
        .correlationKey("SP03B-SLOT-42"),times(2)).messageId("HPAS-SUB:42:HPAS: SP03B scheduling result");
  }
  @Test void offeredDatePastDeadlineIsNotAcceptable() {
    assertThat(executor.execute("hpas-sp03b-validate-slot",job(Map.of("appointmentDate","2099-01-02",
        "requestedReviewBy","2099-01-01","slotAcceptable",true))))
        .containsEntry("slotAcceptable",false);
  }
  @Test void monitoringReadsStoredLettersInsteadOfTrustingCallerAge() {
    when(database.clinicLetters().findByReferralId("TEST-SUB")).thenReturn(java.util.List.of());
    assertThat(executor.execute("hpas-sp15-scan-outstanding",job(Map.of("letterOverdueMonths",100))))
        .containsEntry("letterOverdueMonths",0L).containsEntry("monitoringOpenLetters",0);
    verify(database.clinicLetters()).findByReferralId("TEST-SUB");
  }
}
