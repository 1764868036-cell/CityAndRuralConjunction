"""Keep REST template and collapsed collaboration edges compatible with Modeler/Camunda 8.9."""
from pathlib import Path
from lxml import etree as E
ROOT=Path(__file__).resolve().parent.parent
B="http://www.omg.org/spec/BPMN/20100524/MODEL"
D="http://www.omg.org/spec/BPMN/20100524/DI"
DC="http://www.omg.org/spec/DD/20100524/DC"
DI="http://www.omg.org/spec/DD/20100524/DI"
Z="http://camunda.org/schema/zeebe/1.0"
N={"b":B,"d":D,"z":Z}
q=lambda u,n:f"{{{u}}}{n}"
def apply(path=ROOT/"bpmn/hospital-patient-pathway.bpmn"):
    t=E.parse(str(path)); r=t.getroot()
    task=r.xpath('//*[@id="SP05_RequestAvailability"]')[0]
    task.set(q(Z,"modelerTemplate"),"io.camunda.connectors.HttpJson.v2")
    task.set(q(Z,"modelerTemplateVersion"),"13")
    headers=task.find(q(B,"extensionElements")+"/"+q(Z,"taskHeaders"))
    for key,value in [("elementTemplateId","io.camunda.connectors.HttpJson.v2"),("elementTemplateVersion","13")]:
        old=headers.xpath('./z:header[@key=$k]',namespaces=N,k=key)
        if old: old[0].set("value",value)
        else:E.SubElement(headers,q(Z,"header"),key=key,value=value)
    plane=r.xpath('//d:BPMNPlane[@bpmnElement="Collaboration_HPAS"]',namespaces=N)[0]
    shapes={s.get("bpmnElement"):s for s in plane.findall(q(D,"BPMNShape"))}
    ids={e.get("id"):e for e in r.iter() if e.get("id")}
    def visible(ref):
        node=ids[ref]
        while node is not None:
            if node.get("id") in shapes:return node.get("id")
            node=node.getparent()
        raise ValueError("No visible collaboration endpoint for "+ref)
    def box(ref):
        b=shapes[ref].find(q(DC,"Bounds"));return [float(b.get(k)) for k in ("x","y","width","height")]
    def route(edge,s,d):
        x,y,w,h=box(s);a=(x+w/2,y+h/2)
        x,y,w,h=box(d);b=(x+w/2,y+h/2)
        vertical=abs(b[1]-a[1])>abs(b[0]-a[0])
        def port(ref,toward):
            x,y,w,h=box(ref);cx,cy=x+w/2,y+h/2
            if vertical:return (cx,y+h if toward[1]>cy else y)
            return (x+w if toward[0]>cx else x,cy)
        start=port(s,b);end=port(d,a)
        if vertical:
            mid=(start[1]+end[1])/2;points=[start,(start[0],mid),(end[0],mid),end]
        else:
            mid=(start[0]+end[0])/2;points=[start,(mid,start[1]),(mid,end[1]),end]
        for c in list(edge):edge.remove(c)
        for x,y in points:E.SubElement(edge,q(DI,"waypoint"),x=f"{x:g}",y=f"{y:g}")
    changed=0
    for flow in r.xpath('//b:collaboration/b:messageFlow',namespaces=N):
        fid=flow.get("id");s=flow.get("sourceRef");d=flow.get("targetRef")
        vs,vd=visible(s),visible(d)
        edges=plane.xpath('./d:BPMNEdge[@bpmnElement=$i]',namespaces=N,i=fid)
        if (s,d)!=(vs,vd):
            doc=E.Element(q(B,"documentation"))
            doc.text=f"Detailed message endpoints: source={s}; target={d}. The collaboration overview connects the visible collapsed subprocess boundary."
            flow.insert(0,doc);flow.set("sourceRef",vs);flow.set("targetRef",vd);changed+=1
        if not edges:
            edge=E.SubElement(plane,q(D,"BPMNEdge"),id="ModelerEdge_"+fid,bpmnElement=fid)
            route(edge,vs,vd)
        elif (s,d)!=(vs,vd):route(edges[0],vs,vd)
    t.write(str(path),encoding="utf-8",xml_declaration=True,pretty_print=True)
    from configure_tasklist_start import apply as configure_tasklist_start
    configure_tasklist_start(path)
    print(f"REST template v13 (Camunda ^8.9); normalized {changed} collapsed message-flow endpoints.")
if __name__=="__main__":apply()
