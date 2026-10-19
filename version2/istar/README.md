# VERSION2 integrated SD and SR model

[Editable TXT](../goalModel.txt) | [Single green SVG](Hospital_SD_SR_Version2.svg) | [PNG](Hospital_SD_SR_Version2.png) | [Interactive viewer](index.html)

The model adapts the VERSION2 BPMN to the revised hospital boundary: P01 through P08, P03B and P09 through P16. It contains **15 actors, including 4 external actors, 86 intentional elements and 27 dependencies**. All 54 dependency endpoints connect specific tasks. Diagram labels, descriptions and viewer controls use English without slash abbreviations. VERSION1 is unchanged.

## Actor scope

The original goal model contained 19 actors and 33 dependencies. Four external actors remain: Patient, Referring organisation, Funding organisation and Payment provider. Clinical support services are an internal hospital actor under the revised business scope.

| Previous actor | Current representation |
| --- | --- |
| External Scheduling Service | Appointment teams coordinate scheduling through tasks. The separate implementation actor is hidden in this view. |
| External Correspondence Service | Secretaries and appointment teams coordinate distribution. The separate implementation actor is hidden in this view. |
| Delay-tracking system | Pathway coordinators monitor outstanding items and generate reports. |
| Hospital IT and system administration | Administrative management owns access, downtime reconciliation and audit responsibilities, following P16. |
| Hospital senior management | Administrative management handles successive escalation levels, following P15. |
| Previously combined clinical actor | Consultants and the Clinical team are separate, following VERSION2 decision and treatment responsibilities. |

Scheduling and correspondence remain external BPMN participants. Hiding them in this view does not transfer their implementation responsibilities to hospital staff or change executable interfaces.

## Hospital service boundary

Clinical support services represent hospital departments that report internal service availability and perform laboratory tests. Internal facility booking now depends on their availability; it no longer uses the external booking task as the depender. External booking and capacity waiting remain conditional capabilities with their provider hidden at this strategic level.

This is a deliberate business-scope revision. The current executable BPMN still defines the laboratory and external treatment participant as external. Its source IDs trace the relevant interactions, not an unchanged provider boundary. The executable BPMN has not been modified. The model properties record this difference.

## Earlier semantic and layout improvements

- Immediate safety holds are independent of retrospective authorisation. A separate task obtains the Consultant decision after the hold, reflecting P09.
- Patient appointment changes depend on Outpatient bookings. Patient cancellations depend on Call handling, which records the event and routes the response through P10.
- Instructed follow-up has a separate Consultant task. Preparing and approving the clinic letter does not depend on completing the follow-up booking.
- Internal and external treatment resources are explicit OR alternatives. The BPMN gateway selects the applicable route; OR alone does not express exclusive execution.
- The router penalises crossings and shared segments. In revision `6d6add1`, compared with commit `3131fec`, measured crossings between dependency polylines decreased from 22 to 13. This count excludes shared endpoints, internal refinement links and renderer curve smoothing. Some visible crossings remain.

The added cancellation dependency increases the previous revision's 26 dependencies to 27, restoring a missing responsibility instead of combining cancellation and rescheduling into one ambiguous interaction.

## Business responsibility boundaries

- P03, P03B and P14 cover appointments, changes, follow-up and exceptions. Call handling performs telephone contact; appointment teams coordinate external slot searches.
- P04 and P10 distinguish consent, refusal and cancellation. A valid treatment request requires consent and complete clinical authorisation.
- P05 distinguishes hospital resources, external provisional bookings and pending capacity. Pending requests avoid duplicate bookings.
- P06, P07 and P11 cover funding, payment investigation, authorised urgent exceptions and refunds. Ambiguous payment status must not trigger automatic duplicate requests. Transaction records exclude card data.
- P08 and P09 assign fitness review and treatment changes to the Clinical team, retrospective authorisation to Consultants and financial decisions to Finance.
- P12 assigns classification and routing to Call handling, clinical urgency assessment to CNS and financial responses beyond authorised information to Finance.
- P13, P15 and P16 cover the seven-day letter timer, non-interrupting reminders, reports, escalation, access and downtime recovery.

Some receiving tasks are strategic abstractions rather than added executable BPMN tasks, including the patient consent decision, receipt of validated booking requests and receipt of retrospective authorisation. Applicability properties explain these boundaries. Each intentional element and dependency records its source BPMN IDs; the model records the source file hash.

## Notation

| Symbol or relationship | Meaning |
| --- | --- |
| Actor circle and dashed outline | A role and its responsibility boundary. The outline is not a dependency. |
| Rounded goal | A state to achieve. |
| Hexagonal task | Work performed by the actor. |
| Rectangular resource | Information or another resource needed or provided. |
| Cloud-shaped quality | A desired quality such as patient safety. |
| Dependency | Read as dependent task, required element, then provider task. The small D-shaped marker is native piStar direction notation; there are no artificial numbered labels. |
| AND refinement | Child elements jointly provide the parent capability. This does not specify execution order or require every conditional branch in every case. |
| OR refinement | Alternative means of achieving the parent. Applicability determines which means is appropriate. |
| Help contribution | Positive support for a quality, without guaranteeing it. |

This is a strategic capability model. Applicability properties describe conditional tasks. BPMN remains authoritative for gateways, messages, timing and exclusive routes.

## Editing and validation

Import `goalModel.txt` into piStar, edit and save normally. All nodes, dependencies and vertices use native features without a custom boundary-routing extension.

From the repository root:

```sh
python version2/tools/build_istar.py
```

This rebuilds the TXT and structural report. Re-export the SVG and PNG after model changes. The interactive viewer displays the same single model.

The structural report checks source IDs, task endpoints, different actor owners, consistent refinements and slash-free labels. The native report records import, save, reload, endpoint preservation, node overlap, unrelated-node obstruction and text overflow checks. No warnings or those layout defects were found. These checks do not validate or change the application's business acceptance results.
