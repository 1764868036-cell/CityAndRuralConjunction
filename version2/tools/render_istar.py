"""Render the BPMN-aligned piStar JSON model to SVG, PNG and a local viewer."""

import html
import json
import pathlib
import textwrap

import cairosvg

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODEL = ROOT / 'goalModel.txt'
OUT = ROOT / 'istar'
SVG_PATH = OUT / 'Hospital_SD_SR_Version2.svg'
PNG_PATH = OUT / 'Hospital_SD_SR_Version2.png'
HTML_PATH = OUT / 'index.html'

COLORS = {
    'Actor': ('#f8fcf9', '#78a986'),
    'External': ('#f5faf7', '#4d8b68'),
    'Goal': ('#cfe8d3', '#6f9c7b'),
    'Task': ('#e1f0df', '#7da78a'),
    'Resource': ('#edf5df', '#88a98a'),
    'Quality': ('#bfe1c8', '#659276'),
    'Dependency': ('#d6ead7', '#71987b'),
}


def esc(value):
    return html.escape(str(value), quote=True)


def wrap(label, width=20):
    return textwrap.wrap(label, width=width, break_long_words=True) or ['']


def text_block(x, y, label, font_size=17, color='#254b32', width=20):
    lines = wrap(label, width)
    dy = font_size * 1.1
    first_y = y - (len(lines) - 1) * dy / 2
    return ''.join(
        f'<text x="{x:.1f}" y="{first_y + i * dy:.1f}" text-anchor="middle" '
        f'font-family="Arial, sans-serif" font-size="{font_size}" fill="{color}">{esc(line)}</text>'
        for i, line in enumerate(lines)
    )


def points_for(link, source, target, display):
    start = (source['x'] + 95, source['y'] + 31)
    end = (target['x'] + 95, target['y'] + 31)
    vertices = display.get(link['id'], {}).get('vertices', [])
    pts = [start, *((v['x'], v['y']) for v in vertices), end]
    return ' '.join((f'M {pts[0][0]:.2f} {pts[0][1]:.2f}', *(
        f'L {x:.2f} {y:.2f}' for x, y in pts[1:])))


def draw_actor(actor):
    external = actor.get('customProperties', {}).get('Scope') == 'External'
    fill, stroke = COLORS['External' if external else 'Actor']
    nodes = actor.get('nodes', [])
    left = actor['x'] - 20
    top = actor['y'] - 30
    right = max(n['x'] + 190 for n in nodes) + 20
    bottom = max(n['y'] + 62 for n in nodes) + 22
    label = esc(actor['text'])
    tag = ' · EXTERNAL' if external else ''
    return (
        f'<g id="{esc(actor["id"])}"><title>{label}{tag}</title>'
        f'<rect x="{left}" y="{top}" width="{right-left}" height="{bottom-top}" '
        f'rx="34" fill="{fill}" fill-opacity=".35" stroke="{stroke}" stroke-width="3" '
        f'stroke-dasharray="12 9"/>'
        f'<circle cx="{actor["x"]+20}" cy="{actor["y"]+3}" r="25" fill="#acd4b6" stroke="{stroke}" stroke-width="3"/>'
        f'<text x="{actor["x"]+58}" y="{actor["y"]+10}" font-family="Arial, sans-serif" '
        f'font-size="22" font-weight="700" fill="#245333">{label}{tag}</text></g>'
    )


def draw_node(node, display):
    kind = node['type'].split('.')[-1]
    fill, stroke = COLORS.get(kind, COLORS['Resource'])
    x, y = node['x'], node['y']
    w = display.get(node['id'], {}).get('width', 190)
    h = display.get(node['id'], {}).get('height', 62)
    cp = node.get('customProperties', {})
    tooltip = ' | '.join(f'{k}: {v}' for k, v in cp.items())
    title = f'<title>{esc(tooltip)}</title>' if tooltip else ''
    if kind == 'Task':
        shape = f'<polygon points="{x+18},{y} {x+w-18},{y} {x+w},{y+h/2} {x+w-18},{y+h} {x+18},{y+h} {x},{y+h/2}" fill="{fill}" stroke="{stroke}" stroke-width="3"/>'
    elif kind == 'Goal':
        shape = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{h/2}" fill="{fill}" stroke="{stroke}" stroke-width="3"/>'
    elif kind == 'Quality':
        shape = f'<path d="M {x+28} {y+5} C {x+5} {y-4} {x-4} {y+18} {x+8} {y+30} C {x-4} {y+49} {x+15} {y+66} {x+34} {y+57} C {x+50} {y+73} {x+72} {y+62} {x+74} {y+51} C {x+99} {y+69} {x+120} {y+58} {x+121} {y+48} C {x+147} {y+62} {x+173} {y+50} {x+169} {y+34} C {x+190} {y+18} {x+169} {y+1} {x+151} {y+10} C {x+139} {y-7} {x+116} {y-1} {x+109} {y+12} C {x+91} {y-3} {x+71} {y+2} {x+67} {y+14} C {x+54} {y-1} {x+39} {y-2} {x+28} {y+5} Z" fill="{fill}" stroke="{stroke}" stroke-width="3"/>'
    else:
        shape = f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{fill}" stroke="{stroke}" stroke-width="3"/>'
    labels = text_block(x + w / 2, y + h / 2 + 5, node.get('text', ''), 16, width=21)
    return f'<g id="{esc(node["id"])}">{title}{shape}{labels}</g>'


def draw_link(link, entities, display):
    source, target = entities[link['source']], entities[link['target']]
    kind = link['type'].split('.')[-1]
    if kind == 'DependencyLink':
        style = 'stroke="#739b7d" stroke-width="3" marker-end="url(#arrow)"'
    elif kind == 'ContributionLink':
        style = 'stroke="#649677" stroke-width="3" stroke-dasharray="8 7" marker-end="url(#arrow)"'
    else:
        style = 'stroke="#9dbca3" stroke-width="2.5" marker-end="url(#arrow)"'
    path = points_for(link, source, target, display)
    extra = ''
    if link.get('label'):
        midx = (source['x'] + target['x']) / 2 + 95
        midy = (source['y'] + target['y']) / 2 + 31 - 8
        extra = f'<text x="{midx:.1f}" y="{midy:.1f}" text-anchor="middle" font-family="Arial, sans-serif" font-size="14" fill="#477754">{esc(link["label"])}</text>'
    return f'<g><title>{esc(kind)}: {esc(source.get("text", source.get("id")))} → {esc(target.get("text", target.get("id")))}</title><path d="{path}" fill="none" {style}/>{extra}</g>'


def render():
    model = json.loads(MODEL.read_text(encoding='utf-8-sig'))
    diagram = model['diagram']
    width, height = diagram['width'], diagram['height']
    actors = model['actors']
    nodes = {n['id']: n for a in actors for n in a.get('nodes', [])}
    deps = {d['id']: d for d in model.get('dependencies', [])}
    entities = {**nodes, **deps}
    display = model.get('display', {})
    external_count = sum(a.get('customProperties', {}).get('Scope') == 'External' for a in actors)
    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}" role="img" aria-labelledby="title desc">',
        '<title id="title">Hospital VERSION2 - BPMN-aligned integrated SD and SR strategic model</title>',
        '<desc id="desc">All BPMN collaboration participants with actor goals, tasks, resources, qualities and cross-actor dependencies.</desc>',
        '<defs><marker id="arrow" markerWidth="9" markerHeight="9" refX="7" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 Z" fill="#739b7d"/></marker></defs>',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        '<text x="90" y="65" font-family="Arial, sans-serif" font-size="34" font-weight="700" fill="#245333">HOSPITAL VERSION2 - BPMN-ALIGNED INTEGRATED SD AND SR</text>',
        f'<text x="90" y="102" font-family="Arial, sans-serif" font-size="20" fill="#245333">{len(actors)} actors | {len(nodes)} intentional elements | {len(deps)} dependencies | {external_count} external actors</text>',
        f'<text x="2930" y="65" font-family="Arial, sans-serif" font-size="22" font-weight="700" fill="#245333">BPMN PARTICIPANT BOUNDARY</text>',
        '<text x="2930" y="102" font-family="Arial, sans-serif" font-size="20" fill="#245333">D = dependency | AND = required capabilities | OR = alternative means</text>',
        '<line x1="90" y1="125" x2="4830" y2="125" stroke="#bad5bd" stroke-width="2"/>',
    ]
    elements.extend(draw_actor(a) for a in actors)
    for link in model.get('links', []):
        if link['source'] in entities and link['target'] in entities:
            elements.append(draw_link(link, entities, display))
    for actor in actors:
        for node in actor.get('nodes', []):
            elements.append(draw_node(node, display))
    for dep in deps.values():
        elements.append(draw_node(dep, display))
    elements.append('</svg>')
    svg = '\n'.join(elements)
    SVG_PATH.write_text(svg, encoding='utf-8')
    cairosvg.svg2png(bytestring=svg.encode('utf-8'), write_to=str(PNG_PATH), output_width=width, output_height=height)
    HTML_PATH.write_text(viewer_html(), encoding='utf-8')
    print(json.dumps({'svg': str(SVG_PATH), 'png': str(PNG_PATH), 'viewer': str(HTML_PATH), 'actors': len(actors), 'dependencies': len(deps)}))


def viewer_html():
    return '''<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hospital VERSION2 i* strategic model</title>
<style>body{margin:0;background:#f3f7f3;color:#21442b;font:15px Arial,sans-serif}header{position:sticky;top:0;background:#fff;padding:12px 18px;box-shadow:0 1px 5px #9bb6a333;z-index:2}button{border:1px solid #9ab9a1;border-radius:5px;background:#f7fbf7;padding:7px 12px;margin-right:5px;color:#21442b;cursor:pointer}main{overflow:auto;height:calc(100vh - 60px);padding:18px;box-sizing:border-box}img{display:block;width:100%;height:auto;transform-origin:top left}</style>
<header><strong>Hospital VERSION2 - BPMN-aligned integrated SD and SR</strong> &nbsp; <button onclick="zoom(1.25)">+</button><button onclick="zoom(.8)">−</button><button onclick="fit()">Fit</button><a href="Hospital_SD_SR_Version2.svg">SVG</a> · <a href="Hospital_SD_SR_Version2.png">PNG</a> · <a href="../goalModel.txt">Editable piStar model</a></header>
<main id="view"><img id="diagram" src="Hospital_SD_SR_Version2.svg" alt="Hospital i* strategic dependency and rationale model"></main>
<script>const image=document.getElementById('diagram'),view=document.getElementById('view');function zoom(f){image.style.width=(image.clientWidth*f)+'px'}function fit(){image.style.width=Math.max(900,view.clientWidth-36)+'px'}window.addEventListener('load',fit);</script></html>'''


if __name__ == '__main__':
    render()
