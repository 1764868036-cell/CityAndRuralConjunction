from pathlib import Path
from lxml import etree as E
P=Path(__file__).parent.parent/'hospital-patient-pathway.bpmn'
n={'b':'http://www.omg.org/spec/BPMN/20100524/MODEL','d':'http://www.omg.org/spec/BPMN/20100524/DI','c':'http://www.omg.org/spec/DD/20100524/DC','w':'http://www.omg.org/spec/DD/20100524/DI'}
r=E.parse(str(P)); pl=r.xpath('//d:BPMNPlane',namespaces=n)[0]
s={a.get('bpmnElement'):a for a in pl.findall('d:BPMNShape',n)}
def box(i): return [float(s[i].find('c:Bounds',n).get(k)) for k in ('x','y','width','height')]
def put(i,v):
 for k,x in zip(('x','y','width','height'),v): s[i].find('c:Bounds',n).set(k,str(round(x,2)))
proc=r.xpath('//b:process[@id="Process_HPAS"]',namespaces=n)[0]
y=50; width=4550
for lane in proc.xpath('./b:laneSet/b:lane',namespaces=n):
 lid=lane.get('id'); ox,oy,ow,oh=box(lid); refs=lane.xpath('./b:flowNodeRef/text()',namespaces=n)
 if lane.get('name')=='Event branches':
  h=510
  for j,i in enumerate(refs): put(i,[140+(j%3)*1450,y+45+(j//3)*150,1250,100])
 else:
  h=oh*3+100
  for i in refs:
   x,ny,w,nh=box(i); band=min(int(x//4200),2)
   put(i,[90+x-band*4200,y+(ny-oy)+band*oh+35,w,nh])
 put(lid,[30,y,width-30,h]); y+=h
put('Participant_Hospital',[0,0,width,y+40])
for j,p in enumerate(r.xpath('//b:participant[@processRef!="Process_HPAS"]',namespaces=n)):
 pid=p.get('id'); ox,oy,w,h=box(pid); nx=40+(j%3)*1480; ny=y+180+(j//3)*560
 refs={pid}; ep=r.xpath('//b:process[@id="'+p.get('processRef')+'"]',namespaces=n)[0]; refs.update(ep.xpath('.//@id'))
 for i in refs:
  if i in s:
   x0,y0,ww,hh=box(i);put(i,[x0+nx-ox,y0+ny-oy,ww,hh])
def visible(i):
 if i in s:return i
 a=r.xpath('//*[@id="'+i+'"]')
 if a:
  for par in a[0].iterancestors():
   if par.get('id') in s:return par.get('id')
for e in pl.findall('d:BPMNEdge',n):
 model=r.xpath('//*[@id="'+e.get('bpmnElement')+'"]')[0];a=visible(model.get('sourceRef'));b=visible(model.get('targetRef'))
 if not a or not b:continue
 x,y,w,h=box(a);xx,yy,ww,hh=box(b);sx=x+w;sy=y+h/2;tx=xx;ty=yy+hh/2
 pts=[(sx,sy),((sx+tx)/2,sy),((sx+tx)/2,ty),(tx,ty)]
 for child in list(e):e.remove(child)
 for px,py in pts:E.SubElement(e,'{'+n['w']+'}waypoint',x=str(px),y=str(py))
r.write(str(P),encoding='utf-8',xml_declaration=True,pretty_print=True)
print('Compact layout: 4550 wide; role lanes enlarged vertically; external pools in three columns')
