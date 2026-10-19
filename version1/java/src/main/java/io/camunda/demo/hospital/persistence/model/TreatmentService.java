package io.camunda.demo.hospital.persistence.model;

/**
 * One entry of the hospital's service catalogue.
 *
 * <p>Seeded reference data (see {@code db/data.sql}) rather than a business object: the slot and
 * treatment workers look up what a service needs so they can answer the model's decision points
 * without hard-coding cycle counts or lead times in the worker code.
 *
 * @param serviceCode   the catalogue key the referral and appointment rows point at
 * @param serviceName   name as the patient sees it
 * @param category      outpatient, diagnostics, laboratory, therapy, surgery
 * @param defaultCycles how many treatment cycles the service normally takes
 * @param leadTimeDays  how far ahead the scheduling service books this service
 * @param active        whether the hospital still delivers the service
 */
public record TreatmentService(
        String serviceCode,
        String serviceName,
        String category,
        int defaultCycles,
        int leadTimeDays,
        boolean active) {
}
