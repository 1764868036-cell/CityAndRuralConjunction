"""Summarize version 2 JUnit, original-plan gaps and actual CPT coverage without mixing denominators."""
import hashlib
import json
import re
import shutil
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPORTS = ROOT / 'java/target/surefire-reports'
SUITES = {
    'io.camunda.demo.hpas.RedesignAcceptanceContractTest': 'model-contract',
    'io.camunda.demo.hpas.persistence.HospitalDatabaseTest': 'database',
    'io.camunda.demo.hpas.worker.HpasJobAuditPersistenceTest': 'worker-audit',
    'io.camunda.demo.hpas.Version2AcceptanceIT': 'engine-routing',
    'io.camunda.demo.hpas.OriginalPlanParityIT': 'original-plan-parity',
}
PARITY_ATS = ['AT-05', 'AT-07', 'AT-08', 'AT-09', 'AT-11', 'AT-11', 'AT-11', 'AT-13']
NAMES = ['部署与资源完整性', '完整适宜转诊', '缺资料关联往返', '不适宜转诊', '排班、窗口与升级',
         '知情同意拒绝', '服务与资金阻断', '支付与重复收费', '治疗继续、停止与变更', '正式治疗变更与费用复核',
         '预约变更身份、日期和联系', '取消、临床复核和未到诊', '授权退款和资金转移', '问询、信件与计时器',
         '权限与停机恢复', '表单渲染和 Worker 契约']


def at_for(method):
    if method.startswith('originalDecisionContractExists'):
        match = re.search(r'\[(\d+)\]', method)
        return PARITY_ATS[int(match.group(1)) - 1] if match else None
    if method.startswith('missingDocuments'): return 'AT-03'
    if method.startswith('declinedReferral'): return 'AT-04'
    if method.startswith('monitoringRun'): return 'AT-01'
    if method in ('camundaBpmnSchemaAcceptsAllDiagramPlanes', 'deploymentBundleHasOneExecutableHospitalProcess',
                  'eventSubprocessesHaveLaneAwareDrillDownDi'): return 'AT-01'
    if method in ('formsMatchTheirUserTasksAndHaveUniqueIds', 'modelJobTypesMatchWorkerRegistrations',
                  'allMessageReferencesResolveAndHaveCorrelationKeys'): return 'AT-16'
    match = re.match(r'at(\d\d)_', method)
    return 'AT-' + match.group(1) if match else None


summaries, cases = [], []
for suite, kind in SUITES.items():
    path = REPORTS / ('TEST-' + suite + '.xml')
    if not path.exists():
        raise SystemExit(f'Missing suite report: {path}; run mvnw.cmd -Pacceptance test first')
    root = ET.parse(path).getroot()
    summary = dict(suite=suite, kind=kind,
                   **{k: int(root.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')})
    summary['passed'] = summary['tests'] - summary['failures'] - summary['errors'] - summary['skipped']
    summaries.append(summary)
    for case in root.findall('testcase'):
        result = dict(suite=suite, kind=kind, name=case.get('name'), at=at_for(case.get('name')), status='PASS')
        for tag, status in [('failure', 'FAIL'), ('error', 'ERROR'), ('skipped', 'BLOCKED')]:
            detail = case.find(tag)
            if detail is not None:
                result.update(status=status, detail=(detail.get('message') or detail.text or '')[:2500])
                break
        cases.append(result)

coverage_path = ROOT / 'java/target/coverage-report/report.json'
coverage_data = json.loads(coverage_path.read_text(encoding='utf-8'))
coverage = next(c for c in coverage_data['coverages'] if c['processDefinitionId'] == 'Process_HPAS')
model_info = next(m for m in coverage_data['models'] if m['processDefinitionId'] == 'Process_HPAS')
snapshot = ROOT / 'evidence/acceptance-input/hospital-patient-pathway.bpmn.keep'
model = ET.parse(snapshot)
B = '{http://www.omg.org/spec/BPMN/20100524/MODEL}'
executable = next(p for p in model.findall('.//' + B + 'process') if p.get('id') == 'Process_HPAS')
human = {task.get('id') for task in executable.iter(B + 'userTask')}
covered_human = sorted(human & set(coverage['completedElements']))
Z = '{http://camunda.org/schema/zeebe/1.0}'
form_definitions = list(executable.iter(Z + 'formDefinition'))
deployment_bindings = sum(f.get('bindingType') == 'deployment' for f in form_definitions)
total_covered = len(coverage['completedElements']) + len(coverage['takenSequenceFlows'])
coverage_result = dict(processDefinitionId='Process_HPAS', covered=total_covered,
                       total=model_info['totalElementCount'], percent=round(coverage['coverage'] * 100, 2),
                       completedElements=coverage['completedElements'], takenSequenceFlows=coverage['takenSequenceFlows'],
                       completedHumanTaskCount=len(covered_human), totalHumanTaskCount=len(human),
                       missingHumanTasks=sorted(human - set(covered_human)))
coverage_result['explicitDeploymentFormBindings'] = deployment_bindings

# Coverage overlaps are reported separately from original-plan data/negative-case assertions.
suite_detail = json.loads((ROOT / 'java/target/coverage-report/io.camunda.demo.hpas.Version2AcceptanceIT/report.json')
                          .read_text(encoding='utf-8'))
segments = []
for index, run in enumerate(suite_detail.get('runs', [])):
    ids = set()
    for cov in run.get('coverages', []):
        if cov.get('processDefinitionId') == 'Process_HPAS':
            ids.update(cov.get('completedElements', []))
            ids.update(cov.get('takenSequenceFlows', []))
    segments.append(dict(index=index, name=f"{run['name']}#{index + 1}", coverageLabel=run['name'], ids=ids))
overlaps = []
for segment in segments:
    others = set().union(*(s['ids'] for s in segments if s['index'] != segment['index']))
    if not segment['ids'] - others:
        overlaps.append(segment['name'])
remaining = set(coverage['completedElements']) | set(coverage['takenSequenceFlows'])
greedy = []
while remaining:
    candidate = max(segments, key=lambda s: (len(s['ids'] & remaining), -len(s['ids'])))
    newly_covered = candidate['ids'] & remaining
    if not newly_covered: break
    greedy.append(dict(name=candidate['name'], newIds=len(newly_covered)))
    remaining -= newly_covered
runtime_names = [c['name'] for c in cases if c['kind'] == 'engine-routing']
coverage_result.update(coverageOnlyGreedySegments=greedy, overlappingRuns=overlaps,
                       duplicateRunNames=len(runtime_names) - len(set(runtime_names)),
                       parameterizedCoverageLabelCollisions=len(segments) - len({s['coverageLabel'] for s in segments}))

manifest = json.loads((ROOT / 'evidence/acceptance-input/manifest.json').read_text(encoding='utf-8'))
lint = json.loads((ROOT / 'evidence/acceptance-snapshot-lint.json').read_text(encoding='utf-8-sig'))
source_plan = ROOT / 'docs/source/Hospital_Patient_Pathway_Acceptance_Test_Plan.version1.docx'
totals = {k: sum(s[k] for s in summaries) for k in ('tests', 'passed', 'failures', 'errors', 'skipped')}
plan_rows = []
for i, title in enumerate(NAMES, 1):
    at = f'AT-{i:02d}'
    related = [c for c in cases if c['at'] == at]
    failures = [c for c in related if c['status'] in ('FAIL', 'ERROR')]
    blocked = [c for c in related if c['status'] == 'BLOCKED']
    status = '缺口/未满足原标准' if failures else '环境阻塞' if blocked else '路由预期已验证'
    if at == 'AT-01': status = '快照部署通过；主文件并行修改及 lint 需单独复核'
    if at == 'AT-16': status = '静态契约/API 完成已验证；部署绑定声明与浏览器渲染仍需复核'
    plan_rows.append(dict(at=at, title=title, status=status, passed=sum(c['status'] == 'PASS' for c in related),
                          failed=len(failures), blocked=len(blocked)))

result = dict(generatedAt=datetime.now(timezone.utc).isoformat(), scope='frozen version2 acceptance snapshot',
              sourcePlan=str(source_plan), sourcePlanSHA256=hashlib.sha256(source_plan.read_bytes()).hexdigest(),
              input=manifest, currentModelSHA256=hashlib.sha256((ROOT/'hospital-patient-pathway.bpmn').read_bytes()).hexdigest(),
              totals=totals, suites=summaries, coverage=coverage_result, acceptanceCases=plan_rows, tests=cases,
              lint={'errorCount': lint['errorCount'], 'warningCount': lint['warningCount'],
                    'evidence': 'evidence/acceptance-snapshot-lint.json'},
              accepted=False,
              limitations=['Engine routing uses controlled worker stubs, not real external services.',
                           'Structural parity failures retain original criteria; no business thresholds are invented.',
                           'Browser form rendering and full domain-worker persistence are separate checks.',
                           'Concurrent main-model edits are outside the frozen snapshot results.'])
(ROOT/'evidence/acceptance-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
archive = ROOT / 'evidence/acceptance-final'
(archive / 'junit').mkdir(parents=True, exist_ok=True)
for suite in SUITES:
    for name in ('TEST-' + suite + '.xml', suite + '.txt'):
        if (REPORTS / name).exists(): shutil.copyfile(REPORTS / name, archive / 'junit' / name)
shutil.copytree(ROOT / 'java/target/coverage-report', archive / 'coverage', dirs_exist_ok=True)
shutil.copyfile(ROOT/'evidence/acceptance-results.json', archive/'acceptance-results.json')

lines = ['# Version 2 验收测试结果', '',
         '验收基准为 version1 的 Acceptance Test Plan（AT-01—AT-16），本轮固定输入及哈希见 `evidence/acceptance-input/manifest.json`。', '',
         '**整体尚未通过原验收计划。以下区分路由测试、原标准缺口与基础回归。**', '',
         '| 套件 | 运行 | 通过 | 失败 | 错误 | 阻塞/跳过 |', '| --- | ---: | ---: | ---: | ---: | ---: |']
for summary in summaries:
    lines.append(f"| {summary['suite'].split('.')[-1]} | {summary['tests']} | {summary['passed']} | {summary['failures']} | {summary['errors']} | {summary['skipped']} |")
lines += ['', f"合计：{totals['tests']} 项，{totals['passed']} 通过、{totals['failures']} 失败、{totals['errors']} 错误、{totals['skipped']} 阻塞/跳过。", '',
          f"Process_HPAS 路由覆盖：**{coverage_result['covered']}/{coverage_result['total']}（{coverage_result['percent']}%）**。", '',
          f"用户任务完成覆盖：{len(covered_human)}/{len(human)}。这是引擎 API 完成覆盖，不等同于浏览器表单渲染覆盖。", '',
          f"表单绑定声明：{deployment_bindings}/{len(form_definitions)} 显式声明 bindingType=deployment；同批部署成功不代替原计划的部署绑定检查。", '',
          f"另行执行的 c8ctl lint：{lint['errorCount']} 个错误、{lint['warningCount']} 个警告（含 DI 与汇合分流规则）；引擎部署/Schema 通过并不会清除这些 lint 问题。", '',
          f"纯覆盖贪心子集为 {len(greedy)} 段；{len(overlaps)} 个场景存在覆盖重叠，保留原验收计划要求的不同数据/负面断言。重复测试名称 {coverage_result['duplicateRunNames']} 个。", '',
          '| AT | 范围 | 本轮结论 |', '| --- | --- | --- |']
lines += [f"| {r['at']} | {r['title']} | {r['status']} |" for r in plan_rows]
lines += ['', '## 未满足的原标准', '']
for case in cases:
    if case['status'] in ('FAIL', 'ERROR', 'BLOCKED'):
        lines.append(f"- {case['at'] or case['kind']} `{case['name']}`：{case['status']}。详见 Surefire XML 和 `evidence/acceptance-results.json`。")
lines += ['', '## 使用限制与复现', '',
          '- 本轮使用固定快照；源主图由其他任务并行修改。快照只显式清理空 DI 图层，所有执行语义均保留。',
          '- `OriginalPlanParityIT` 的静态决策契约检查与真实 Worker 拒绝测试均保留失败；没有把缺少的标准删掉。',
          '- 数据库测试验证仓储可用，不证明全部 BPMN 业务 Worker 都已写入对应领域表。',
          '- 已归档覆盖报告：[report.html](evidence/acceptance-final/coverage/report.html)。原始 Surefire XML/TXT：[junit](evidence/acceptance-final/junit/)。测试实例记录：[acceptance-instance-traces.jsonl](evidence/acceptance-instance-traces.jsonl)。',
          '- 命令：在 `java/` 运行 `.\\mvnw.cmd -Pacceptance test`，再在项目根目录运行 `python tools\\report_acceptance_results.py`。', '']
(ROOT/'TEST_RESULTS_VERSION2.zh-CN.md').write_text('\n'.join(lines), encoding='utf-8')
print(json.dumps(dict(totals=totals, coverage={k: coverage_result[k] for k in ('covered', 'total', 'percent', 'completedHumanTaskCount')}), ensure_ascii=False))
