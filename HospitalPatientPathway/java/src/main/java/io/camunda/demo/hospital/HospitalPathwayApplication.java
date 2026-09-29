package io.camunda.demo.hospital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Job workers for the merged hospital patient pathway
 * ({@code 1-17.bpmn}: processes 1-16 in a single executable pool).
 *
 * <p>Every automated step of the model is handled by an {@code @JobWorker} method, and every
 * message the pathway waits for is published by the worker of the step that asks for it (or by
 * the simulated external party in {@code hospital.simulation}).
 */
@SpringBootApplication
@EnableScheduling
public class HospitalPathwayApplication {

    public static void main(String[] args) {
        SpringApplication.run(HospitalPathwayApplication.class, args);
    }
}
