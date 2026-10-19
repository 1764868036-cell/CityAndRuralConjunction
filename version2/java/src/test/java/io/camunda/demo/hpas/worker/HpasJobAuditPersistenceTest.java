package io.camunda.demo.hpas.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.demo.hpas.HpasJobExecutor;
import io.camunda.demo.hpas.persistence.EventLogRepository;
import io.camunda.demo.hpas.persistence.HospitalDatabase;
import io.camunda.demo.hpas.persistence.PathwayRecordRepository;
import io.camunda.demo.hpas.persistence.PatientRepository;
import io.camunda.demo.hpas.persistence.ReferralRepository;
import io.camunda.demo.hpas.persistence.model.EventLog;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HpasJobAuditPersistenceTest {

    @Test
    void completedWorkerActionIsAuditedWithoutPersistingSubmittedFormValues() {
        CamundaClient client = mock(CamundaClient.class);
        EventLogRepository events = mock(EventLogRepository.class);
        HospitalDatabase database = new HospitalDatabase(
                mock(PatientRepository.class), mock(ReferralRepository.class),
                mock(io.camunda.demo.hpas.persistence.TreatmentServiceRepository.class),
                mock(io.camunda.demo.hpas.persistence.AppointmentRepository.class),
                mock(io.camunda.demo.hpas.persistence.SlotRequestRepository.class),
                mock(io.camunda.demo.hpas.persistence.ConsultationRepository.class),
                mock(io.camunda.demo.hpas.persistence.TreatmentCycleRepository.class),
                mock(io.camunda.demo.hpas.persistence.FundingAuthorisationRepository.class),
                mock(io.camunda.demo.hpas.persistence.FundingClearanceRepository.class),
                mock(io.camunda.demo.hpas.persistence.PaymentRepository.class),
                mock(io.camunda.demo.hpas.persistence.RefundCaseRepository.class),
                mock(io.camunda.demo.hpas.persistence.ClinicLetterRepository.class),
                mock(io.camunda.demo.hpas.persistence.EnquiryRepository.class),
                mock(io.camunda.demo.hpas.persistence.AccessAuditRepository.class),
                mock(PathwayRecordRepository.class), mock(io.camunda.demo.hpas.persistence.ReportRepository.class),
                events);
        ActivatedJob job = mock(ActivatedJob.class);
        when(job.getVariablesAsMap()).thenReturn(Map.of(
                "referralId", "TEST-REF-1", "patientId", "TEST-PAT-1", "clinicalNotes", "PRIVATE-SENTINEL"));
        when(job.getProcessInstanceKey()).thenReturn(42L);
        when(job.getElementId()).thenReturn("SP02_NotifyRejected");

        HpasJobExecutor executor = new HpasJobExecutor(client, false, database);
        var result = executor.execute("hpas-sp02-notify-rejection", job);

        assertThat(result).containsEntry("referralOutcomeNotified", true);
        var captor = org.mockito.ArgumentCaptor.forClass(EventLog.class);
        verify(events).append(captor.capture());
        EventLog logged = captor.getValue();
        assertThat(logged.processInstanceKey()).isEqualTo(42L);
        assertThat(logged.elementId()).isEqualTo("SP02_NotifyRejected");
        assertThat(logged.jobType()).isEqualTo("hpas-sp02-notify-rejection");
        assertThat(logged.referralId()).isEqualTo("TEST-REF-1");
        assertThat(logged.patientId()).isEqualTo("TEST-PAT-1");
        assertThat(logged.payload()).doesNotContain("PRIVATE-SENTINEL", "clinicalNotes");
    }
}
