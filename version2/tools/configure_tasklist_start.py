"""Enable Tasklist start-form intake while preserving message-driven API entry."""
from pathlib import Path
import json
from lxml import etree as E
ROOT=Path(__file__).resolve().parent.parent
B="http://www.omg.org/spec/BPMN/20100524/MODEL";Z="http://camunda.org/schema/zeebe/1.0";D="http://www.omg.org/spec/BPMN/20100524/DI";DC="http://www.omg.org/spec/DD/20100524/DC";DI="http://www.omg.org/spec/DD/20100524/DI"
N={"b":B,"z":Z,"d":D};q=lambda n,t:f"{{{n}}}{t}"
def apply(path=None):
    path=Path(path) if path else ROOT/"bpmn/hospital-patient-pathway.bpmn"
    t=E.parse(str(path));r=t.getroot();proc=r.xpath('//b:process[@id="Process_HPAS"]',namespaces=N)[0]
    def node(i):return r.xpath('//*[@id=$i]',i=i)[0]
    start=node("Top_Start");ext=start.find(q(B,"extensionElements"))
    if ext is None:ext=E.Element(q(B,"extensionElements"));start.insert(0,ext)
    form=ext.find(q(Z,"formDefinition"))
    if form is None:form=E.SubElement(ext,q(Z,"formDefinition"))
    form.set("formId","hpas_start_referral");form.set("bindingType","deployment")
    if not r.xpath('//*[@id="Top_GW_ReferralEntry"]'):
        gateway=E.Element(q(B,"exclusiveGateway"),id="Top_GW_ReferralEntry",name="Referral received at intake?",default="Flow_Entry_WaitMessage")
        E.SubElement(gateway,q(B,"incoming")).text="Flow_001"
        for fid in ["Flow_Entry_Tasklist","Flow_Entry_WaitMessage"]:E.SubElement(gateway,q(B,"outgoing")).text=fid
        proc.insert(list(proc).index(start)+1,gateway)
        node("Flow_001").set("targetRef","Top_GW_ReferralEntry")
        catch=node("Top_Catch_Referral");catch.find(q(B,"incoming")).text="Flow_Entry_WaitMessage"
        f=E.SubElement(proc,q(B,"sequenceFlow"),id="Flow_Entry_Tasklist",name="received via intake form",sourceRef="Top_GW_ReferralEntry",targetRef="SP01_CheckDocuments")
        E.SubElement(f,q(B,"conditionExpression"),{"{http://www.w3.org/2001/XMLSchema-instance}type":"bpmn:tFormalExpression"}).text="= if is defined(referralReceived) then referralReceived = true else false"
        E.SubElement(proc,q(B,"sequenceFlow"),id="Flow_Entry_WaitMessage",name="await external referral",sourceRef="Top_GW_ReferralEntry",targetRef="Top_Catch_Referral")
        doc=node("SP01_CheckDocuments");refs=doc.findall(q(B,"incoming"));idx=list(doc).index(refs[-1])+1
        incoming=E.Element(q(B,"incoming"));incoming.text="Flow_Entry_Tasklist";doc.insert(idx,incoming)
        for lane in proc.xpath('./b:laneSet/b:lane',namespaces=N):
            if "Top_Start" in lane.xpath('./b:flowNodeRef/text()',namespaces=N):E.SubElement(lane,q(B,"flowNodeRef")).text="Top_GW_ReferralEntry"
        plane=r.xpath('//d:BPMNPlane[@bpmnElement="Collaboration_HPAS"]',namespaces=N)[0]
        def box(i):
            s=plane.xpath('./d:BPMNShape[@bpmnElement=$i]',namespaces=N,i=i)[0];b=s.find(q(DC,"Bounds"));return [float(b.get(k)) for k in ("x","y","width","height")]
        sx,sy,sw,sh=box("Top_Start");cx,cy,cw,ch=box("Top_Catch_Referral");tx,ty,tw,th=box("SP01_CheckDocuments")
        gx=(sx+sw+cx)/2-25;gy=sy+sh/2-25
        shape=E.SubElement(plane,q(D,"BPMNShape"),id="DI_Top_GW_ReferralEntry",bpmnElement="Top_GW_ReferralEntry",isMarkerVisible="true")
        E.SubElement(shape,q(DC,"Bounds"),x=str(gx),y=str(gy),width="50",height="50")
        for e in plane.xpath('./d:BPMNEdge[@bpmnElement="Flow_001"]',namespaces=N):
            for c in list(e):e.remove(c)
            for x,y in [(sx+sw,sy+sh/2),(gx,gy+25)]:E.SubElement(e,q(DI,"waypoint"),x=str(x),y=str(y))
        for fid,pts in [("Flow_Entry_WaitMessage",[(gx+50,gy+25),(cx,cy+ch/2)]),("Flow_Entry_Tasklist",[(gx+25,gy+50),(gx+25,ty+th/2),(tx,ty+th/2)])]:
            e=E.SubElement(plane,q(D,"BPMNEdge"),id="DI_"+fid,bpmnElement=fid)
            for x,y in pts:E.SubElement(e,q(DI,"waypoint"),x=str(x),y=str(y))
    fields=[{"type":"text","id":"start_heading","text":"# Open a referral case"}]
    for key,label in [("referralId","Referral ID"),("patientId","Patient ID"),("intakeStaffId","Responsible staff ID")]:
        fields.append({"type":"textfield","id":"start_"+key,"key":key,"label":label,"validate":{"required":True,"maxLength":160,"pattern":".*\\S.*"},"layout":{"row":"row_"+key,"columns":None}})
    fields.append({"type":"checkbox","id":"start_referralReceived","key":"referralReceived","label":"Referral already received - start document checks now","defaultValue":True,"layout":{"row":"row_received","columns":None}})
    fields.append({"type":"text","id":"start_help","text":"Use a unique referral ID. Keep the checkbox selected to create the first Tasklist task immediately. Uncheck it only when waiting for a referring-organisation message."})
    schema={"id":"hpas_start_referral","type":"default","schemaVersion":19,"executionPlatform":"Camunda Cloud","executionPlatformVersion":"8.9.0","exporter":{"name":"Camunda Modeler","version":"5.46.1"},"components":fields}
    (path.parent/"hpas_start_referral.form").write_text(json.dumps(schema,indent=2),encoding="utf-8")
    t.write(str(path),encoding="utf-8",xml_declaration=True,pretty_print=True)
    print("Tasklist startup configured: 1 start form + 37 task forms; API message entry retained.")
if __name__=="__main__":apply()
