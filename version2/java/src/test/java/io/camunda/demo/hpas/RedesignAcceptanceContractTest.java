package io.camunda.demo.hpas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.client.annotation.JobWorker;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Static acceptance contracts corresponding to AT-01 and AT-16, adapted to HPAS redesign. */
@DisplayName("Redesign acceptance contracts: deployment, forms and workers")
class RedesignAcceptanceContractTest {
    private static final String B = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String Z = "http://camunda.org/schema/zeebe/1.0";
    private static final String ROOT = "..";
    private static Document model;
    private static Path modelPath;

    @BeforeAll
    static void loadModel() throws Exception {
        Path root = Path.of(ROOT).toAbsolutePath().normalize();
        modelPath = Boolean.getBoolean("acceptance.snapshot")
                ? root.resolve("evidence/acceptance-input/hospital-patient-pathway.bpmn.keep")
                : root.resolve("hospital-patient-pathway.bpmn");
        assertThat(Files.isRegularFile(modelPath)).as("canonical deployment BPMN").isTrue();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try (InputStream in = Files.newInputStream(modelPath)) {
            model = factory.newDocumentBuilder().parse(in);
        }
    }

    @Test
    void camundaBpmnSchemaAcceptsAllDiagramPlanes() {
        io.camunda.zeebe.model.bpmn.Bpmn.validateModel(
            io.camunda.zeebe.model.bpmn.Bpmn.readModelFromFile(modelPath.toFile()));
    }

    @Test
    @DisplayName("AT-01: one executable hospital process and unique deployable resources")
    void deploymentBundleHasOneExecutableHospitalProcess() throws Exception {
        var processes = model.getElementsByTagNameNS(B, "process");
        int executable = 0;
        Set<String> processIds = new HashSet<>();
        for (int i = 0; i < processes.getLength(); i++) {
            Element p = (Element) processes.item(i);
            assertThat(processIds.add(p.getAttribute("id"))).as("unique process id").isTrue();
            if ("true".equals(p.getAttribute("isExecutable"))) {
                executable++;
                assertThat(p.getAttribute("id")).isEqualTo("Process_HPAS");
            }
        }
        assertThat(executable).isEqualTo(1);
        assertThat(uniqueIds(model)).isTrue();
        assertThat(forms().keySet()).as("all referenced deployment-bound forms exist")
                .containsAll(referencedFormIds());
        Set<String> hospitalForms = new TreeSet<>();
        for (var binding : taskFormBindings().entrySet())
            if (isInsideExecutable(binding.getKey())) hospitalForms.add(binding.getValue());
        assertThat(hospitalForms).as("redesign executable hospital forms").hasSize(37);
        assertThat(executableTaskFormCount()).as("hospital tasks with deployment-bound forms").isEqualTo(37);
    }

    @Test
    @DisplayName("AT-16: every hospital form has a task-specific section and valid component metadata")
    void formsMatchTheirUserTasksAndHaveUniqueIds() throws Exception {
        Map<String, JsonNode> forms = forms();
        Map<String, String> taskToForm = taskFormBindings();
        assertThat(taskToForm).hasSize(37);
        assertThat(new HashSet<>(taskToForm.values())).as("one form id per hospital user task").hasSize(37);
        for (var binding : taskToForm.entrySet()) {
            if (!isInsideExecutable(binding.getKey())) continue;
            JsonNode form = forms.get(binding.getValue());
            assertThat(form).as(binding.getKey()).isNotNull();
            assertThat(form.path("id").asText()).isEqualTo(binding.getValue());
            assertThat(form.path("components").isArray()).isTrue();
            assertThat(hasTaskScopedFields(form.path("components"), binding.getKey()))
                    .as("task section in " + binding.getValue()).isTrue();
            Set<String> componentIds = new HashSet<>();
            collectComponentIds(form.path("components"), componentIds);
        }
    }

    @Test
    @DisplayName("AT-16: executable jobs resolve to Java workers or the SP05 REST Connector")
    void modelJobTypesMatchWorkerRegistrations() throws Exception {
        Set<String> modelTypes = new TreeSet<>();
        Element process = executableProcess();
        for (String tag : new String[] {"serviceTask", "sendTask", "intermediateThrowEvent"}) {
            NodeList nodes = process.getElementsByTagNameNS(B, tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element node = (Element) nodes.item(i);
                if ("intermediateThrowEvent".equals(tag)
                        && node.getElementsByTagNameNS(B, "messageEventDefinition").getLength() == 0) continue;
                String type = taskType(node);
                assertThat(type).as(node.getAttribute("id") + " job type").isNotBlank();
                modelTypes.add(type);
            }
        }
        Set<String> workerTypes = new TreeSet<>();
        for (Class<?> type : new Class<?>[] {HpasJobWorkers.class}) {
            for (Method method : type.getDeclaredMethods()) {
                JobWorker annotation = method.getAnnotation(JobWorker.class);
                if (annotation != null) workerTypes.add(annotation.type().isBlank() ? method.getName() : annotation.type());
            }
        }
        assertThat(modelTypes).hasSize(55);
        assertThat(modelTypes.remove("io.camunda:http-json:1")).isTrue();
        assertThat(workerTypes.remove("hpas-sp05-request-availability")).isTrue();
        assertThat(workerTypes).isEqualTo(modelTypes);
    }

    @Test
    @DisplayName("AT-16: BPMN waits and collaboration message flows resolve to subscribed messages")
    void allMessageReferencesResolveAndHaveCorrelationKeys() {
        Map<String, Element> messages = new HashMap<>();
        NodeList messageNodes = model.getElementsByTagNameNS(B, "message");
        for (int i = 0; i < messageNodes.getLength(); i++) {
            Element m = (Element) messageNodes.item(i);
            messages.put(m.getAttribute("id"), m);
            Element sub = firstChild(firstChild(m, "extensionElements"), "subscription");
            assertThat(sub).as(m.getAttribute("name") + " subscription").isNotNull();
            assertThat(sub.getAttribute("correlationKey")).as(m.getAttribute("name") + " correlation key").isNotBlank();
        }
        NodeList defs = executableProcess().getElementsByTagNameNS(B, "messageEventDefinition");
        for (int i = 0; i < defs.getLength(); i++) {
            Element def = (Element) defs.item(i);
            assertThat(messages).containsKey(def.getAttribute("messageRef"));
        }
        NodeList flows = model.getElementsByTagNameNS(B, "messageFlow");
        assertThat(flows.getLength()).isGreaterThan(0);
        for (int i = 0; i < flows.getLength(); i++) {
            assertThat(messages).containsKey(((Element) flows.item(i)).getAttribute("messageRef"));
        }
    }

    @Test
    @DisplayName("AT-01/AT-16: event branches remain collapsed, drill-down capable and lane mapped")
    void eventSubprocessesHaveLaneAwareDrillDownDi() {
        NodeList subprocesses = model.getElementsByTagNameNS(B, "subProcess");
        int branches = 0;
        for (int i = 0; i < subprocesses.getLength(); i++) {
            Element sp = (Element) subprocesses.item(i);
            if (!"true".equals(sp.getAttribute("triggeredByEvent"))) continue;
            branches++;
            assertThat(sp.getAttribute("id")).isNotBlank();
            assertThat(sp.getElementsByTagNameNS(B, "lane").getLength()).isGreaterThan(0);
        }
        assertThat(branches).isEqualTo(9);
    }

    private static boolean uniqueIds(Document doc) {
        Set<String> ids = new HashSet<>();
        NodeList all = doc.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Node n = all.item(i);
            if (n instanceof Element e && e.hasAttribute("id") && !ids.add(e.getAttribute("id"))) return false;
        }
        return true;
    }

    private static Map<String, JsonNode> forms() throws Exception {
        Map<String, JsonNode> result = new HashMap<>();
        Path folder = modelPath.getParent().resolve("forms");
        ObjectMapper mapper = new ObjectMapper();
        try (var files = Files.list(folder)) {
            for (Path file : files.filter(p -> p.toString().endsWith(Boolean.getBoolean("acceptance.snapshot")
                    ? ".form.keep" : ".form")).toList()) {
                JsonNode form = mapper.readTree(file.toFile());
                assertThat(result.put(form.path("id").asText(), form)).as(file.toString()).isNull();
            }
        }
        assertThat(result).hasSize(37);
        return result;
    }

    private static Set<String> referencedFormIds() {
        Set<String> result = new TreeSet<>();
        NodeList defs = model.getElementsByTagNameNS(Z, "formDefinition");
        for (int i = 0; i < defs.getLength(); i++) result.add(((Element) defs.item(i)).getAttribute("formId"));
        return result;
    }

    private static Map<String, String> taskFormBindings() {
        Map<String, String> result = new HashMap<>();
        NodeList tasks = model.getElementsByTagNameNS(B, "userTask");
        for (int i = 0; i < tasks.getLength(); i++) {
            Element task = (Element) tasks.item(i);
            Element def = firstChild(firstChild(task, "extensionElements"), "formDefinition");
            if (def != null) result.put(task.getAttribute("id"), def.getAttribute("formId"));
        }
        return result;
    }

    private static int formTaskCount() { return taskFormBindings().size(); }
    private static int executableTaskFormCount() {
        int count = 0;
        for (String taskId : taskFormBindings().keySet()) if (isInsideExecutable(taskId)) count++;
        return count;
    }
    private static boolean isInsideExecutable(String id) {
        NodeList tasks = executableProcess().getElementsByTagNameNS(B, "userTask");
        for (int i = 0; i < tasks.getLength(); i++) if (id.equals(((Element) tasks.item(i)).getAttribute("id"))) return true;
        return false;
    }
    private static Element executableProcess() {
        NodeList processes = model.getElementsByTagNameNS(B, "process");
        for (int i = 0; i < processes.getLength(); i++) {
            Element process = (Element) processes.item(i);
            if ("Process_HPAS".equals(process.getAttribute("id")) && "true".equals(process.getAttribute("isExecutable"))) return process;
        }
        throw new AssertionError("Process_HPAS executable process is missing");
    }
    private static String taskType(Element node) {
        Element def = firstChild(firstChild(node, "extensionElements"), "taskDefinition");
        return def == null ? "" : def.getAttribute("type");
    }
    private static Element firstChild(Element parent, String local) {
        if (parent == null) return null;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling())
            if (child instanceof Element e && local.equals(e.getLocalName())) return e;
        return null;
    }
    private static boolean hasTaskScopedFields(JsonNode components, String task) {
        String normalized = task.toLowerCase(java.util.Locale.ROOT);
        return hasScopedId(components, task) || hasScopedId(components, normalized) || hasTaskKey(components);
    }
    private static boolean hasScopedId(JsonNode components, String task) {
        for (JsonNode component : components) {
            String id = component.path("id").asText();
            if (id.contains(task)) return true;
            if (component.path("components").isArray() && hasScopedId(component.path("components"), task)) return true;
        }
        return false;
    }
    private static boolean hasTaskKey(JsonNode components) {
        for (JsonNode component : components) {
            if (component.path("key").asText().equals("SP08_DelayHandling_actor")) return true;
        }
        return false;
    }
    private static boolean containsTaskSection(JsonNode components, String task) {
        for (JsonNode component : components) {
            if (component.path("id").asText().equals("Section_" + task)) return true;
            if (component.path("components").isArray() && containsTaskSection(component.path("components"), task)) return true;
        }
        return false;
    }
    private static void collectComponentIds(JsonNode components, Set<String> ids) {
        for (JsonNode component : components) {
            String id = component.path("id").asText();
            if (!id.isBlank()) assertThat(ids.add(id)).as("unique form component id " + id).isTrue();
            if (component.path("components").isArray()) collectComponentIds(component.path("components"), ids);
        }
    }
}
