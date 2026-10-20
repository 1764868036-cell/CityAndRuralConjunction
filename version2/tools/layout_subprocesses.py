"""Lay out only subprocess pages: responsibility columns, forward flow top to bottom."""
from pathlib import Path
from lxml import etree as E
from collections import defaultdict, deque
import re, json, hashlib
ROOT=Path(__file__).resolve().parents[1]
MODEL=ROOT/'bpmn/hospital-patient-pathway.bpmn'
OUT=ROOT/'evidence/subprocess-layout-deploy-20261020'
OUT.mkdir(parents=True,exist_ok=True)
text=MODEL.read_text(encoding='utf-8')
if not (OUT/'before.bpmn.keep').exists():(OUT/'before.bpmn.keep').write_bytes(MODEL.read_bytes())
r=E.fromstring(text.encode());N={'b':'http://www.omg.org/spec/BPMN/20100524/MODEL','d':'http://www.omg.org/spec/BPMN/20100524/DI','c':'http://www.omg.org/spec/DD/20100524/DC','w':'http://www.omg.org/spec/DD/20100524/DI'}
Q=lambda p,t:'{'+N[p]+'}'+t
def add(p,ns,t,**attrs):return E.SubElement(p,Q(ns,t),{k:str(v) for k,v in attrs.items()})
stats=[]
for diagram in r.findall(Q('d','BPMNDiagram')):
 plane=diagram.find(Q('d','BPMNPlane'));sid=plane.get('bpmnElement')
 if sid=='Collaboration_HPAS':continue
 scope=r.xpath('//*[@id=$id]',id=sid)[0]
 nodes={e.get('id'):e for e in scope if E.QName(e).localname in {'startEvent','endEvent','userTask','serviceTask','sendTask','exclusiveGateway','intermediateCatchEvent','boundaryEvent','subProcess'}}
 flows=scope.findall(Q('b','sequenceFlow'));graph=defaultdict(list)
 for f in flows:graph[f.get('sourceRef')].append(f.get('targetRef'))
 for e in nodes.values():
  if e.tag==Q('b','boundaryEvent'):graph[e.get('attachedToRef')].append(e.get('id'))
 color={};back=set()
 def visit(n):
  color[n]=1
  for t in graph[n]:
   if color.get(t)==1:back.add((n,t))
   elif not color.get(t):visit(t)
  color[n]=2
 for id in nodes:
  if not color.get(id):visit(id)
 incoming={n:0 for n in nodes}
 for n in nodes:
  for t in graph[n]:
   if (n,t) not in back:incoming[t]+=1
 todo=deque(n for n in nodes if not incoming[n]);rank={n:0 for n in nodes}
 while todo:
  n=todo.popleft()
  for t in graph[n]:
   if (n,t) in back:continue
   rank[t]=max(rank[t],rank[n]+1);incoming[t]-=1
   if not incoming[t]:todo.append(t)
 lanes=scope.findall(Q('b','laneSet')+'/'+Q('b','lane'));groups=[]
 for lane in lanes:
  refs=[e.text for e in lane.findall(Q('b','flowNodeRef')) if e.text in nodes]
  byrank=defaultdict(list)
  for id in refs:
   if nodes[id].tag!=Q('b','boundaryEvent'):byrank[rank[id]].append(id)
  slots=max([len(v) for v in byrank.values()]+[1]);width=max(330,slots*300+40)
  groups.append((lane,refs,byrank,width))
 height=(max(rank.values())+1)*145+150;x=30;coords={}
 plane.clear();plane.set('id','VerticalPlane_'+sid);plane.set('bpmnElement',sid)
 for lane,refs,byrank,width in groups:
  sh=add(plane,'d','BPMNShape',id='VerticalDI_'+lane.get('id'),bpmnElement=lane.get('id'),isHorizontal='false')
  add(sh,'c','Bounds',x=x,y=30,width=width,height=height)
  for level,ids in sorted(byrank.items()):
   for i,id in enumerate(ids):
    tag=E.QName(nodes[id]).localname
    w,h=(210,80) if tag.endswith('Task') or tag=='subProcess' else ((50,50) if tag.endswith('Gateway') else (36,36))
    px=x+35+i*300;py=120+level*145
    coords[id]=(px,py,w,h)
    shape=add(plane,'d','BPMNShape',id='VerticalDI_'+id,bpmnElement=id)
    if tag=='subProcess':shape.set('isExpanded','false')
    add(shape,'c','Bounds',x=px,y=py,width=w,height=h)
    if not tag.endswith('Task') and tag!='subProcess':
     label=add(shape,'d','BPMNLabel');add(label,'c','Bounds',x=px+w+10,y=py,width=155,height=45)
  x+=width
 for id,node in nodes.items():
  if node.tag==Q('b','boundaryEvent'):
   px,py,w,h=coords[node.get('attachedToRef')];coords[id]=(px+w-42,py+h-18,36,36)
   sh=add(plane,'d','BPMNShape',id='VerticalDI_'+id,bpmnElement=id);add(sh,'c','Bounds',x=px+w-42,y=py+h-18,width=36,height=36)
   label=add(sh,'d','BPMNLabel');add(label,'c','Bounds',x=px+w+12,y=py+h-10,width=160,height=40)
 for f in flows:
  a,b=coords[f.get('sourceRef')],coords[f.get('targetRef')]
  ed=add(plane,'d','BPMNEdge',id='VerticalDI_'+f.get('id'),bpmnElement=f.get('id'))
  add(ed,'w','waypoint',x=a[0]+a[2]/2,y=a[1]+a[3]);add(ed,'w','waypoint',x=b[0]+b[2]/2,y=b[1])
 out=re.sub(r' xmlns(?::[\w]+)?="[^"]+"','',E.tostring(diagram,encoding='unicode',with_tail=False))
 pattern=r'<bpmndi:BPMNDiagram\b[^>]*\bid="'+re.escape(diagram.get('id'))+r'"[^>]*>.*?</bpmndi:BPMNDiagram>'
 text=re.sub(pattern,lambda _:out,text,flags=re.S)
 stats.append({'scope':sid,'lanes':len(lanes),'width':x-30,'height':height,'edges':len(flows),'returnEdges':len(back)})
MODEL.write_text(text,encoding='utf-8')
(OUT/'layout-pages.json').write_text(json.dumps(stats,indent=2)+'\n',encoding='utf-8')
print(json.dumps(stats))
