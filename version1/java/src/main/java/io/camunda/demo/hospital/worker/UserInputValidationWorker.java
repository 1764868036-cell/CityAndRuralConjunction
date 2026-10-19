package io.camunda.demo.hospital.worker;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.api.response.ActivatedJob;
import io.camunda.client.api.worker.JobClient;
import io.camunda.demo.hospital.validation.HospitalFormValidator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class UserInputValidationWorker {
    private final HospitalFormValidator validator;

    public UserInputValidationWorker(HospitalFormValidator validator) { this.validator = validator; }

    @JobWorker(type = "validate-hospital-user-input", autoComplete = false)
    public void validate(JobClient client, ActivatedJob job) {
        List<String> errors = validator.validate(job.getElementId(), job.getVariablesAsMap());
        // Return only field names and constraints, never submitted patient/staff values.
        client.newCompleteCommand(job.getKey())
                .withResult(result -> result.forUserTask().deny(!errors.isEmpty(), String.join("; ", errors)))
                .send().join();
    }
}
