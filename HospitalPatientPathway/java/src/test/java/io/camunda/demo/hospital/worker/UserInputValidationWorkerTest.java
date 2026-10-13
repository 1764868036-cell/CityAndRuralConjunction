package io.camunda.demo.hospital.worker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.camunda.client.api.CamundaFuture;
import io.camunda.client.api.command.CompleteJobCommandStep1;
import io.camunda.client.api.command.CompleteJobCommandStep1.CompleteJobCommandJobResultStep;
import io.camunda.client.api.command.CompleteJobResult;
import io.camunda.client.api.command.CompleteUserTaskJobResultStep1;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.response.CompleteJobResponse;
import io.camunda.client.api.worker.JobClient;
import io.camunda.demo.hospital.validation.HospitalFormValidator;
import io.camunda.demo.hospital.validation.HospitalFormValidatorTest;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserInputValidationWorkerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @SuppressWarnings("unchecked")
    void completesTheListenerWithAnExplicitDenialOrApproval(boolean valid) throws Exception {
        JobClient client = mock(JobClient.class);
        ActivatedJob job = mock(ActivatedJob.class);
        var command = mock(CompleteJobCommandStep1.class, RETURNS_SELF);
        var resultBuilder = mock(CompleteJobCommandJobResultStep.class);
        var taskResult = mock(CompleteUserTaskJobResultStep1.class, RETURNS_SELF);
        CamundaFuture<CompleteJobResponse> future = mock(CamundaFuture.class);
        when(job.getKey()).thenReturn(123L);
        when(job.getElementId()).thenReturn("R2_Review");
        Map<String, Object> values = HospitalFormValidatorTest.validSubmission("R2_Review");
        if (!valid) values.put("referralId", " ");
        when(job.getVariablesAsMap()).thenReturn(values);
        when(client.newCompleteCommand(123L)).thenReturn(command);
        when(resultBuilder.forUserTask()).thenReturn(taskResult);
        when(command.withResult(any())).thenAnswer(invocation -> {
            Function<CompleteJobCommandJobResultStep, CompleteJobResult> builder = invocation.getArgument(0);
            builder.apply(resultBuilder);
            return command;
        });
        when(command.send()).thenReturn(future);

        new UserInputValidationWorker(new HospitalFormValidator()).validate(client, job);

        if (valid) verify(taskResult).deny(false, "");
        else verify(taskResult).deny(eq(true), contains("referralId: required"));
        verify(future).join();
    }
}
