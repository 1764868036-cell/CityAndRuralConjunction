package io.camunda.demo.hospital;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.client.annotation.JobWorker;
import io.camunda.demo.hospital.support.Bundle;
import io.camunda.demo.hospital.support.HospitalMessages;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Checks that the worker project and the BPMN model fit together, without starting a cluster.
 *
 * <p>The model is the contract: every service task, send task and message throw event must name a
 * job type that a worker subscribes to, and every message the pathway waits for must be a message
 * this project is able to publish. The gaps the model had before this project (service tasks
 * without a job type, send tasks whose job type was the element id) fail this test as well, so a
 * model that was edited without updating the workers is caught here.
 */
@DisplayName("BPMN model and workers fit together")
class BpmnModelCoverageTest {

    private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String ZEEBE_NS = "http://camunda.org/schema/zeebe/1.0";
    private static final String EXECUTABLE_PROCESS_ID = "Process_Hospital_Integrated";
    private static final String WORKER_PACKAGE = "io.camunda.demo.hospital";

    private static Document model;

    @BeforeAll
    static void readModel() throws Exception {
        Path modelFile = Bundle.model();
        try (InputStream input = Files.newInputStream(modelFile)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            model = factory.newDocumentBuilder().parse(input);
        }
    }

    @Test
    @DisplayName("every automated step of the pathway names a job type")
    void everyAutomatedStepNamesAJobType() {
        Map<String, String> incomplete = new TreeMap<>();
        for (String tag : new String[] {"serviceTask", "sendTask"}) {
            for (Element step : elementsOfExecutableProcess(tag)) {
                String jobType = jobType(step);
                if (jobType == null || jobType.isBlank()) {
                    incomplete.put(step.getAttribute("id"), "<no zeebe:taskDefinition type>");
                } else if (jobType.startsWith("P1") || jobType.equals("send") || jobType.equals("pass information")) {
                    incomplete.put(step.getAttribute("id"), jobType + " (placeholder, not a job type)");
                }
            }
        }
        assertThat(incomplete)
                .as("service and send tasks without a usable job type would never be picked up by a worker")
                .isEmpty();
    }

    @Test
    @DisplayName("every job type of the model has a worker")
    void everyJobTypeHasAWorker() {
        Set<String> implemented = implementedJobTypes();
        Map<String, String> unimplemented = new TreeMap<>();
        for (Map.Entry<String, String> step : automatedSteps().entrySet()) {
            if (!implemented.contains(step.getValue())) {
                unimplemented.put(step.getKey(), step.getValue());
            }
        }
        assertThat(unimplemented)
                .as("these steps would stay untouched forever; implemented job types: " + new TreeSet<>(implemented))
                .isEmpty();
    }

    @Test
    @DisplayName("every worker belongs to a step of the model")
    void everyWorkerBelongsToAStep() {
        Set<String> modelJobTypes = new TreeSet<>(automatedSteps().values());
        Set<String> orphaned = new TreeSet<>(implementedJobTypes());
        orphaned.removeAll(modelJobTypes);
        assertThat(orphaned)
                .as("workers that no element of %s can ever trigger", Bundle.modelName())
                .isEmpty();
    }

    @Test
    @DisplayName("every message the pathway waits for can be published")
    void everyWaitedForMessageCanBePublished() {
        Map<String, String> unknown = new TreeMap<>();
        for (Map.Entry<String, String> waitPoint : waitPoints().entrySet()) {
            String messageName = waitPoint.getValue();
            if (messageName == null || messageName.isBlank()) {
                unknown.put(waitPoint.getKey(), "<messageRef without a matching bpmn:message name>");
            } else if (!HospitalMessages.isKnown(messageName)) {
                unknown.put(waitPoint.getKey(), messageName);
            }
        }
        assertThat(unknown)
                .as("a waiting step nobody answers blocks the pathway; known messages: "
                        + new TreeSet<>(HospitalMessages.allMessageNames()))
                .isEmpty();
    }

    @Test
    @DisplayName("the correlation key of every message matches the model")
    void correlationKeysMatchTheModel() {
        Map<String, String> mismatches = new TreeMap<>();
        for (Map.Entry<String, String> waitPoint : waitPoints().entrySet()) {
            String messageName = waitPoint.getValue();
            if (messageName == null || messageName.isBlank()) {
                continue;
            }
            String modelKey = correlationKeyVariableOf(messageName);
            String projectKey = HospitalMessages.correlationKeyVariable(messageName);
            if (!Objects.equals(modelKey, projectKey)) {
                mismatches.put(messageName, "model: " + modelKey + ", project: " + projectKey);
            }
        }
        assertThat(mismatches)
                .as("a message published with the wrong correlation key never reaches the waiting step")
                .isEmpty();
    }

    @Test
    @DisplayName("every form the model references is in the bundle folder")
    void everyReferencedFormExistsInTheFolder() {
        Map<String, String> available = new TreeMap<>();
        for (Path form : Bundle.forms()) {
            available.put(Bundle.formId(form), form.getFileName().toString());
        }

        Map<String, String> missing = new TreeMap<>();
        for (String formId : referencedFormIds()) {
            if (!available.containsKey(formId)) {
                missing.put(formId, "<no .form file declares this id>");
            }
        }

        assertThat(missing)
                .as("deployment-bound forms are part of the deployment of the model, so a users task "
                        + "whose form is missing makes the whole deployment fail; forms in the folder: %s", available.values())
                .isEmpty();
    }

    @Test
    @DisplayName("every form id appears once under the bundle folder")
    void formIdsAreUniqueInTheFolderTree() {
        Map<String, List<String>> byFormId = new TreeMap<>();
        for (Path form : Bundle.forms()) {
            byFormId.computeIfAbsent(Bundle.formId(form), id -> new ArrayList<>())
                    .add(Bundle.relative(form));
        }

        Map<String, List<String>> duplicated = new TreeMap<>();
        byFormId.forEach((formId, files) -> {
            if (files.size() > 1) {
                duplicated.put(formId, files);
            }
        });

        assertThat(duplicated)
                .as("the folder is a Camunda Modeler process application, so Deploy sends every .form "
                        + "below it: a second copy of a form (a stale build output, for example) becomes a "
                        + "second version of the same form id in the deployment")
                .isEmpty();
    }

    /** Form ids the executable process references through {@code zeebe:formDefinition}. */
    private static Set<String> referencedFormIds() {
        Set<String> formIds = new TreeSet<>();
        NodeList definitions = executableProcess().getElementsByTagNameNS(ZEEBE_NS, "formDefinition");
        for (int i = 0; i < definitions.getLength(); i++) {
            formIds.add(((Element) definitions.item(i)).getAttribute("formId"));
        }
        return formIds;
    }

    /** Process variable the model correlates this message on, or {@code null} for no key. */
    private static String correlationKeyVariableOf(String messageName) {
        for (Element message : childElements(model.getDocumentElement(), "message")) {
            if (!messageName.equals(message.getAttribute("name"))) {
                continue;
            }
            for (Element extensionElements : childElements(message, "extensionElements")) {
                for (Element subscription : childElements(extensionElements, "subscription")) {
                    if (ZEEBE_NS.equals(subscription.getNamespaceURI())) {
                        String expression = subscription.getAttribute("correlationKey").trim();
                        return expression.startsWith("=") ? expression.substring(1).trim() : expression;
                    }
                }
            }
            return null;
        }
        return null;
    }

    // --- model reading -------------------------------------------------------------------

    /** Automated steps of the executable process: element id -> job type. */
    private static Map<String, String> automatedSteps() {
        Map<String, String> steps = new LinkedHashMap<>();
        for (String tag : new String[] {"serviceTask", "sendTask"}) {
            for (Element step : elementsOfExecutableProcess(tag)) {
                steps.put(step.getAttribute("id"), jobType(step));
            }
        }
        for (Element event : elementsOfExecutableProcess("intermediateThrowEvent")) {
            if (event.getElementsByTagNameNS(BPMN_NS, "messageEventDefinition").getLength() > 0) {
                steps.put(event.getAttribute("id"), jobType(event));
            }
        }
        return steps;
    }

    /** Steps of the executable process that wait for a message: element id -> message name. */
    private static Map<String, String> waitPoints() {
        Map<String, String> waitPoints = new LinkedHashMap<>();
        for (String tag : new String[] {"receiveTask", "intermediateCatchEvent", "startEvent", "boundaryEvent"}) {
            for (Element element : elementsOfExecutableProcess(tag)) {
                NodeList definitions = element.getElementsByTagNameNS(BPMN_NS, "messageEventDefinition");
                String messageRef;
                if (definitions.getLength() > 0) {
                    messageRef = ((Element) definitions.item(0)).getAttribute("messageRef");
                } else {
                    messageRef = element.getAttribute("messageRef");
                }
                if (messageRef != null && !messageRef.isBlank()) {
                    waitPoints.put(element.getAttribute("id"), messageName(messageRef));
                }
            }
        }
        return waitPoints;
    }

    private static String messageName(String messageId) {
        for (Element message : childElements(model.getDocumentElement(), "message")) {
            if (messageId.equals(message.getAttribute("id"))) {
                return message.getAttribute("name");
            }
        }
        return null;
    }

    /** The executable process of the model. */
    private static Element executableProcess() {
        Element process = null;
        for (Element candidate : childElements(model.getDocumentElement(), "process")) {
            if (EXECUTABLE_PROCESS_ID.equals(candidate.getAttribute("id"))) {
                process = candidate;
            }
        }
        assertThat(process)
                .as("process %s must exist in %s", EXECUTABLE_PROCESS_ID, Bundle.modelName())
                .isNotNull();
        return process;
    }

    /** All elements with this tag inside the executable process of the model. */
    private static List<Element> elementsOfExecutableProcess(String tag) {
        Element process = executableProcess();

        List<Element> elements = new ArrayList<>();
        NodeList nodes = process.getElementsByTagNameNS(BPMN_NS, tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            elements.add((Element) nodes.item(i));
        }
        return elements;
    }

    private static String jobType(Element step) {
        for (Element extensionElements : childElements(step, "extensionElements")) {
            for (Element taskDefinition : childElements(extensionElements, "taskDefinition")) {
                if (ZEEBE_NS.equals(taskDefinition.getNamespaceURI())) {
                    return taskDefinition.getAttribute("type");
                }
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && localName.equals(element.getLocalName())) {
                children.add(element);
            }
        }
        return children;
    }

    // --- worker reading ------------------------------------------------------------------

    /** Job types of all methods annotated with {@code @JobWorker} in this project. */
    private static Set<String> implementedJobTypes() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true);
        Set<String> jobTypes = new TreeSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(WORKER_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("cannot load " + candidate.getBeanClassName(), e);
            }
            for (Method method : type.getDeclaredMethods()) {
                JobWorker annotation = method.getAnnotation(JobWorker.class);
                if (annotation != null) {
                    jobTypes.add(annotation.type().isBlank() ? method.getName() : annotation.type());
                }
            }
        }
        assertThat(jobTypes).as("@JobWorker methods in %s", WORKER_PACKAGE).isNotEmpty();
        return jobTypes;
    }
}
