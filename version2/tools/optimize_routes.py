from pathlib import Path
from lxml import etree as E
P=Path(__file__).parent.parent/'hospital-patient-pathway.bpmn'
n={'b':'http://www.omg.org/spec/BPMN/20100524/MODEL','d':'http://www.omg.org/spec/BPMN/20100524/DI','c':'http://www.omg.org/spec/DD/20100524/DC','w':'http://www.omg.org/spec/DD/20100524/DI'}
r=E.parse(str(P)); models={x.get('id'):x for x in r.xpath('//*[@id]')}
def rect(s):return [float(s.find('c:Bounds',n).get(k)) for k in ('x','y','width','height')]
def hit(a,b,o):
 x,y,w,h=o; pad=6;x-=pad;y-=pad;w+=2*pad;h+=2*pad
 if a[0]==b[0]:return x<a[0]<x+w and max(min(a[1],b[1]),y)<min(max(a[1],b[1]),y+h)
 return y<a[1]<y+h and max(min(a[0],b[0]),x)<min(max(a[0],b[0]),x+w)
total_before=total_after=0
for pl in r.xpath('//d:BPMNPlane',namespaces=n):
 shapes={s.get('bpmnElement'):s for s in pl.findall('d:BPMNShape',n)}
 nodes={i:rect(s) for i,s in shapes.items() if E.QName(models[i]).localname not in ('participant','lane')}
 # Compress empty horizontal strips without changing node sizes or order.
 intervals=sorted((v[1],v[1]+v[3]) for v in nodes.values()); end=0;gaps=[]
 for lo,hi in intervals:
  if lo-end>100:gaps.append((end+50,lo-50))
  end=max(end,hi)
 def cy(y):return y-sum(max(0,min(y,b)-a) for a,b in gaps if y>a)
 for s in shapes.values():
  x,y,w,h=rect(s);b=s.find('c:Bounds',n);kind=E.QName(models[s.get('bpmnElement')]).localname
  b.set('y',str(cy(y)))
  if kind in ('participant','lane') or s.get('isExpanded')=='true':b.set('height',str(cy(y+h)-cy(y)))
 nodes={i:rect(shapes[i]) for i in nodes}
 def visible(i):
  while i not in shapes:
   if i not in models:return None
   p=models[i].getparent();i=p.get('id') if p is not None else None
  return i
 for e in pl.findall('d:BPMNEdge',n):
  m=models[e.get('bpmnElement')];a=visible(m.get('sourceRef'));b=visible(m.get('targetRef'))
  if a not in nodes or b not in nodes:continue
  obs=[v for i,v in nodes.items() if i not in (a,b) and not (models[a] in models[i].iterancestors() or models[b] in models[i].iterancestors())]
  old=[(float(p.get('x')),float(p.get('y'))) for p in e.findall('w:waypoint',n)]
  total_before+=sum(hit(p,q,o) for p,q in zip(old,old[1:]) for o in obs)
  x,y,w,h=nodes[a];xx,yy,ww,hh=nodes[b]
  portsA=[(x+w,y+h/2),(x,y+h/2),(x+w/2,y),(x+w/2,y+h)]
  portsB=[(xx,yy+hh/2),(xx+ww,yy+hh/2),(xx+ww/2,yy),(xx+ww/2,yy+hh)]
  railsx={min(x,xx)-35,max(x+w,xx+ww)+35,(x+w+xx)/2}
  railsy={min(y,yy)-35,max(y+h,yy+hh)+35,(y+h+yy)/2}
  for ox,oy,ow,oh in obs:
   if min(y,yy)-100<oy<max(y+h,yy+hh)+100:railsx.update((ox-20,ox+ow+20))
   if min(x,xx)-100<ox<max(x+w,xx+ww)+100:railsy.update((oy-20,oy+oh+20))
  best=None;score=float('inf')
  for p in portsA:
   for q in portsB:
    candidates=[[p,(p[0],q[1]),q],[p,(q[0],p[1]),q]]
    candidates += [[p,(v,p[1]),(v,q[1]),q] for v in railsx]
    candidates += [[p,(p[0],v),(q[0],v),q] for v in railsy]
    for pts in candidates:
     hits=sum(hit(u,v,o) for u,v in zip(pts,pts[1:]) for o in obs)
     cost=hits*1000000+sum(abs(u[0]-v[0])+abs(u[1]-v[1]) for u,v in zip(pts,pts[1:]))
     if cost<score:score=cost;best=pts
  total_after+=int(score//1000000)
  for child in list(e):e.remove(child)
  for px,py in best:E.SubElement(e,'{'+n['w']+'}waypoint',x=str(px),y=str(py))
r.write(str(P),encoding='utf-8',xml_declaration=True,pretty_print=True)
print('Obstacle intersections before/after:',total_before,total_after)
