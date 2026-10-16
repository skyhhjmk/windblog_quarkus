(function () {
    'use strict';
    const namespace = 'http://www.w3.org/2000/svg';
    let active = null;

    function svgElement(name, attributes, text) {
        const element = document.createElementNS(namespace, name);
        Object.entries(attributes || {}).forEach(([key, value]) => element.setAttribute(key, value));
        if (text !== undefined) element.textContent = text;
        return element;
    }

    function render(container, graph) {
        const status = container.querySelector('[data-graph-status]');
        const view = container.querySelector('[data-graph-view]');
        const list = container.querySelector('[data-graph-list]');
        list.replaceChildren();
        const nodes = graph.nodes || [];
        const root = nodes.find(node => node.id === graph.rootId);
        const targets = nodes.filter(node => node.id !== graph.rootId);
        if (!root) { status.textContent = '暂无可显示的关联文章。'; return; }
        status.textContent = targets.length ? `当前文章直接引用了 ${targets.length} 篇公开文章。${graph.truncated ? '关联较多，仅展示部分结果。' : ''}` : '当前公开正文暂未引用其他公开文章。';
        const height = Math.max(220, 120 + Math.ceil(targets.length / 4) * 108);
        const svg = svgElement('svg', {viewBox: `0 0 920 ${height}`, width: '920', height: String(height), role: 'img', 'aria-label': '当前文章的直接引用关系图'});
        svg.style.cssText = 'display:block;width:100%;min-width:640px;height:auto;color:var(--text-main,currentColor)';
        const defs = svgElement('defs');
        const marker = svgElement('marker', {id: 'internal-graph-arrow', viewBox: '0 0 10 10', refX: '9', refY: '5', markerWidth: '6', markerHeight: '6', orient: 'auto-start-reverse'});
        marker.appendChild(svgElement('path', {d: 'M 0 0 L 10 5 L 0 10 z', fill: '#64748b'}));
        defs.appendChild(marker); svg.appendChild(defs);
        function drawNode(node, x, y, isRoot) {
            const anchor = svgElement('a', {href: node.url, 'aria-label': node.title});
            anchor.appendChild(svgElement('title', {}, node.title));
            anchor.appendChild(svgElement('rect', {x: String(x - 102), y: String(y - 28), width: '204', height: '56', rx: '12', fill: isRoot ? '#2563eb' : 'var(--panel-bg,#f1f5f9)', stroke: isRoot ? '#2563eb' : '#94a3b8'}));
            anchor.appendChild(svgElement('text', {x: String(x), y: String(y + 5), 'text-anchor': 'middle', 'font-size': '13', fill: isRoot ? '#ffffff' : 'currentColor'}, node.title.length > 13 ? node.title.slice(0, 13) + '…' : node.title));
            svg.appendChild(anchor);
        }
        targets.forEach((node, index) => {
            const x = 115 + (index % 4) * 230;
            const y = 165 + Math.floor(index / 4) * 108;
            svg.appendChild(svgElement('path', {d: `M460 88 C460 110 ${x} ${y - 70} ${x} ${y - 32}`, fill: 'none', stroke: '#64748b', 'stroke-width': '1.4', opacity: '0.55', 'marker-end': 'url(#internal-graph-arrow)'}));
        });
        drawNode(root, 460, 60, true);
        targets.forEach((node, index) => drawNode(node, 115 + (index % 4) * 230, 165 + Math.floor(index / 4) * 108, false));
        view.replaceChildren(svg);
        targets.forEach(node => {
            const item = document.createElement('li');
            const link = document.createElement('a');
            link.href = node.url; link.textContent = node.title;
            link.className = 'hover:text-accent underline';
            item.appendChild(link); list.appendChild(item);
        });
    }

    async function initialize() {
        if (active) active.abort();
        const container = document.querySelector('[data-internal-link-graph]');
        if (!container) return;
        const controller = new AbortController(); active = controller;
        try {
            const query = new URLSearchParams({language: document.documentElement.lang || 'zh-cn'});
            const response = await fetch(`/api/posts/${encodeURIComponent(container.dataset.slug)}/link-graph?${query}`, {signal: controller.signal, headers: {Accept: 'application/json'}, cache: 'no-store'});
            if (response.status === 404) { container.remove(); return; }
            if (!response.ok) throw new Error('graph unavailable');
            const graph = await response.json();
            if (container.isConnected && !controller.signal.aborted) render(container, graph);
        } catch (error) {
            if (error.name === 'AbortError') return;
            if (container.isConnected) container.querySelector('[data-graph-status]').textContent = '内部链接图暂时无法加载，请稍后刷新。';
        }
    }
    document.addEventListener('pjax:start', () => { if (active) active.abort(); });
    document.addEventListener('pjax:complete', initialize);
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialize);
    else initialize();
})();
