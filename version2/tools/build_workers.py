"""Generate typed @JobWorker registrations for every BPMN job type."""

from pathlib import Path
import re
from lxml import etree as ET

ROOT = Path(__file__).resolve().parent.parent
N = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL",
     "z": "http://camunda.org/schema/zeebe/1.0"}
tree = ET.parse(str(ROOT / "hospital-patient-pathway.bpmn"))
types = sorted(set(tree.xpath('//b:process[@id="Process_HPAS"]//z:taskDefinition/@type', namespaces=N)))
out = ROOT / "java/src/main/java/io/camunda/demo/hpas/HpasJobWorkers.java"
lines = [
    "package io.camunda.demo.hpas;", "",
    "import io.camunda.client.annotation.JobWorker;",
    "import io.camunda.client.api.response.ActivatedJob;",
    "import java.util.Map;", "import org.springframework.stereotype.Component;", "",
    "/** One typed Camunda worker registration per automated BPMN step. */",
    "@Component", "public class HpasJobWorkers {",
    "  private final HpasJobExecutor executor;", "",
    "  public HpasJobWorkers(HpasJobExecutor executor) { this.executor = executor; }", "",
]
for i, kind in enumerate(types):
    method = re.sub(r"[^a-zA-Z0-9]", "_", kind)
    lines += [f'  @JobWorker(type = "{kind}")',
              f'  public Map<String, Object> {method}(ActivatedJob job) {{',
              f'    return executor.execute("{kind}", job);',
              "  }", ""]
lines.append("}")
out.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(f"Generated {len(types)} typed Java job workers")
