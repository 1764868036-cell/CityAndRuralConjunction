"""Orthogonal obstacle-aware routing, restricted to subprocess diagram planes."""
from pathlib import Path
from lxml import etree as E
import heapq, re, math
ROOT=Path(__file__).resolve().parents[1]
P=ROOT/'bpmn/hospital-patient-pathway.bpmn'
N={'b':'http://www.omg.org/spec/BPMN/20100524/MODEL','d':'http://www.omg.org/spec/BPMN/20100524/DI','c':'http://www.omg.org/spec/DD/20100524/DC','w':'http://www.omg.org/spec/DD/20100524/DI'}
Q=lambda p,n:'{'+N[p]+'}'+n
text=P.read_text(encoding='utf-8');r=E.fromstring(text.encode());byid={e.get('id'):e for e in r.iter() if e.get('id')}
def rect(bound):return tuple(float(bound.get(x)) for x in ['x','y','width','height'])
def collision(a,b,obstacles):
    x1,y1=a;x2,y2=b
    for x,y,w,h in obstacles:
        if x1==x2:
            if x < x1 < x+w and max(min(y1,y2),y)<min(max(y1,y2),y+h):return True
        elif y1==y2:
            if y < y1 < y+h and max(min(x1,x2),x)<min(max(x1,x2),x+w):return True
    return False
def route(start,end,obstacles,usage):
    xs=sorted({start[0],end[0],55.,1250.}|{v for x,y,w,h in obstacles for v in (x-12,x+w+12)})
    ys=sorted({start[1],end[1],5.}|{v for x,y,w,h in obstacles for v in (y-12,y+h+12)})
    sx,sy=xs.index(start[0]),ys.index(start[1]);ex,ey=xs.index(end[0]),ys.index(end[1])
    first=(sx,sy,0);queue=[(0,0,first)];cost={first:0};prev={};cache={}
    finish=None
    while queue:
        _,g,state=heapq.heappop(queue)
        if g!=cost[state]:continue
        ix,iy,direction=state
        if (ix,iy)==(ex,ey):finish=state;break
        for dx,dy,nd in [(1,0,1),(-1,0,1),(0,1,2),(0,-1,2)]:
            nx,ny=ix+dx,iy+dy
            if nx<0 or ny<0 or nx>=len(xs) or ny>=len(ys):continue
            a=(xs[ix],ys[iy]);b=(xs[nx],ys[ny]);seg=tuple(sorted([a,b]))
            if seg not in cache:cache[seg]=collision(a,b,obstacles)
            if cache[seg]:continue
            ng=g+abs(a[0]-b[0])+abs(a[1]-b[1])+(30 if direction and direction!=nd else 0)+usage.get(seg,0)*15
            new=(nx,ny,nd)
            if ng<cost.get(new,math.inf):
                cost[new]=ng;prev[new]=state
                heapq.heappush(queue,(ng+abs(xs[nx]-end[0])+abs(ys[ny]-end[1]),ng,new))
    if finish is None:raise ValueError(('No collision-free route',start,end))
    points=[];state=finish
    while state!=first:
        points.append((xs[state[0]],ys[state[1]]));old=prev[state]
        seg=tuple(sorted([points[-1],(xs[old[0]],ys[old[1]])]));usage[seg]=usage.get(seg,0)+1;state=old
    points.append(start);return points[::-1]
for diagram in r.findall(Q('d','BPMNDiagram')):
    plane=diagram.find(Q('d','BPMNPlane'))
    if plane.get('bpmnElement')=='Collaboration_HPAS':continue
    boxes={};obstacles=[]
    for shape in plane.findall(Q('d','BPMNShape')):
        model=byid[shape.get('bpmnElement')]
        if model.tag==Q('b','lane'):continue
        x,y,w,h=rect(shape.find(Q('c','Bounds')));boxes[model.get('id')]=(x,y,w,h)
        obstacles.append((x-5,y-5,w+10,h+10))
        label=shape.find(Q('d','BPMNLabel')+'/'+Q('c','Bounds'))
        if label is not None:
            lx,ly,lw,lh=rect(label);obstacles.append((lx-3,ly-3,lw+6,lh+6))
    usage={};label_boxes=[]
    for edge in plane.findall(Q('d','BPMNEdge')):
        f=byid[edge.get('bpmnElement')];a=boxes[f.get('sourceRef')];b=boxes[f.get('targetRef')]
        if b[1] > a[1]+a[3]+25:
            source=(a[0]+a[2]/2,a[1]+a[3]);target=(b[0]+b[2]/2,b[1])
            start=(source[0],source[1]+20);end=(target[0],target[1]-20)
        elif b[1]+b[3] < a[1]-25:
            source=(a[0],a[1]+a[3]/2);target=(b[0],b[1]+b[3]/2)
            start=(source[0]-20,source[1]);end=(target[0]-20,target[1])
        else:
            left = b[0]+b[2] < a[0]
            source=(a[0] if left else a[0]+a[2],a[1]+a[3]/2)
            target=(b[0]+b[2] if left else b[0],b[1]+b[3]/2)
            start=(source[0]+(-20 if left else 20),source[1])
            end=(target[0]+(20 if left else -20),target[1])
        points=[source]+route(start,end,obstacles,usage)+[target]
        compressed=[]
        for point in points:
            if compressed and point==compressed[-1]:continue
            while len(compressed)>1 and ((compressed[-2][0]==compressed[-1][0]==point[0]) or (compressed[-2][1]==compressed[-1][1]==point[1])):compressed.pop()
            compressed.append(point)
        for child in list(edge):edge.remove(child)
        for x,y in compressed:E.SubElement(edge,Q('w','waypoint'),x=str(x),y=str(y))
        if f.get('name'):
            segments=sorted(zip(compressed,compressed[1:]),key=lambda pair:-(abs(pair[0][0]-pair[1][0])+abs(pair[0][1]-pair[1][1])))
            candidates=[]
            for order,(a,b) in enumerate(segments):
                mx,my=(a[0]+b[0])/2,(a[1]+b[1])/2
                for shift in [0,-25,25,-50,50]:
                    if a[0]==b[0]:positions=[(mx+12,my-15+shift),(mx-132,my-15+shift)]
                    else:positions=[(mx-60+shift,my-34),(mx-60+shift,my+8)]
                    for lx,ly in positions:
                        overlap=sum(max(0,min(lx+120,x+w)-max(lx,x))*max(0,min(ly+30,y+h)-max(ly,y)) for x,y,w,h in obstacles+label_boxes)
                        candidates.append((overlap,order*100+abs(shift),lx,ly))
            _,_,lx,ly=min(candidates)
            label=E.SubElement(edge,Q('d','BPMNLabel'))
            E.SubElement(label,Q('c','Bounds'),x=str(lx),y=str(ly),width='120',height='30')
            label_boxes.append((lx-4,ly-4,128,38))
    out=re.sub(r' xmlns(?::[\w]+)?="[^"]+"','',E.tostring(diagram,encoding='unicode',with_tail=False))
    pattern=r'<bpmndi:BPMNDiagram\b[^>]*\bid="'+re.escape(diagram.get('id'))+r'"[^>]*>.*?</bpmndi:BPMNDiagram>'
    text=re.sub(pattern,lambda _:out,text,flags=re.S)
P.write_text(text,encoding='utf-8')
print('Subprocess edges routed around task and node-label obstacles; main DI unchanged')
