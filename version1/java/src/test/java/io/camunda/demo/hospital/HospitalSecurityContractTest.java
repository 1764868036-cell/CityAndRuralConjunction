package io.camunda.demo.hospital;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.demo.hospital.support.Bundle;
import io.camunda.demo.hospital.validation.HospitalFormValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.yaml.snakeyaml.Yaml;

class HospitalSecurityContractTest {
    private static final String B = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String Z = "http://camunda.org/schema/zeebe/1.0";

    @Test
    void modelPassesCamundaSchemaValidation() {
        io.camunda.zeebe.model.bpmn.Bpmn.validateModel(
                io.camunda.zeebe.model.bpmn.Bpmn.readModelFromFile(Bundle.model().toFile()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyHospitalTaskHasAnInitializedGroupAndACompletionValidator() throws Exception {
        Map<String, Object> config = new Yaml().load(Files.readString(Bundle.folder().resolve("security/camunda-roles.yaml")));
        var security = (Map<String, Object>) ((Map<?, ?>) config.get("camunda")).get("security");
        assertThat(((Map<?, ?>) security.get("authentication")).get("unprotected-api")).isEqualTo(false);
        assertThat(((Map<?, ?>) security.get("authorizations")).get("enabled")).isEqualTo(true);
        var init = (Map<String, Object>) security.get("initialization");
        var groups = (List<Map<String, Object>>) init.get("groups");
        Set<String> ids = groups.stream().map(g -> (String) g.get("groupId")).collect(Collectors.toSet());
        var taskWorker = (Map<?, ?>) ((Map<?, ?>) init.get("default-roles")).get("task-worker");
        assertThat((List<String>) taskWorker.get("groups")).containsExactlyInAnyOrderElementsOf(ids);
        var auths = (List<Map<String, Object>>) init.get("authorizations");
        assertThat(auths).allSatisfy(auth -> {
            assertThat(auth.get("resourceType")).isEqualTo("COMPONENT");
            assertThat(auth.get("resourceId")).isEqualTo("tasklist");
            assertThat((List<String>) auth.get("permissions")).containsExactly("ACCESS");
        });

        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var model = factory.newDocumentBuilder().parse(Bundle.model().toFile());
        var validator = new HospitalFormValidator();
        int count = 0;
        var processes = model.getElementsByTagNameNS(B, "process");
        for (int p = 0; p < processes.getLength(); p++) {
            Element process = (Element) processes.item(p);
            if (!"true".equals(process.getAttribute("isExecutable"))) continue;
            var tasks = process.getElementsByTagNameNS(B, "userTask");
            for (int i = 0; i < tasks.getLength(); i++) {
                Element task = (Element) tasks.item(i);
                String id = task.getAttribute("id");
                assertThat(validator.taskIds()).contains(id);
                var assignments = task.getElementsByTagNameNS(Z, "assignmentDefinition");
                assertThat(assignments.getLength()).as(id).isEqualTo(1);
                Element assignment = (Element) assignments.item(0);
                assertThat(assignment.hasAttribute("assignee")).as(id).isFalse();
                assertThat(ids).contains(assignment.getAttribute("candidateGroups"));
                assertThat(task.getElementsByTagNameNS(Z, "userTask").getLength()).as(id).isEqualTo(1);
                var listeners = task.getElementsByTagNameNS(Z, "taskListener");
                assertThat(listeners.getLength()).as(id).isEqualTo(1);
                assertThat(((Element) listeners.item(0)).getAttribute("eventType")).isEqualTo("completing");
                assertThat(((Element) listeners.item(0)).getAttribute("type")).isEqualTo("validate-hospital-user-input");
                count++;
            }
        }
        assertThat(count).isEqualTo(70);
    }

    @Test
    void packagedValidationFormsExactlyMatchTheDeploymentForms() throws Exception {
        for (var form : Bundle.forms()) {
            String name = form.getFileName().toString().replace(".form", ".json");
            try (var input = getClass().getResourceAsStream("/hospital-forms/" + name)) {
                assertThat(input).as(name).isNotNull();
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo(Files.readString(form));
            }
        }
    }
}
