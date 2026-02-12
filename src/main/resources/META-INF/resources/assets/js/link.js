(function () {
    'use strict';

    function initLinkList() {
        const sortSelect = document.getElementById('linkSortSelect');
        const grid = document.querySelector('.link-grid');
        if (!sortSelect || !grid) return;

        const protocolRank = { CAT5: 5, CAT4: 4, CAT3: 3, CAT2: 2, CAT1: 1 };

        const getProtocolWeight = (el) => {
            const value = (el.getAttribute('data-protocol') || '').toUpperCase();
            const key = value.startsWith('CAT') ? value.slice(0, 4) : value;
            return protocolRank[key] || 0;
        };

        const getScore = (el) => Number.parseInt(el.getAttribute('data-score') || '0', 10) || 0;
        const getIndex = (el) => Number.parseInt(el.getAttribute('data-index') || '0', 10) || 0;

        sortSelect.onchange = function () {
            const mode = this.value;
            const items = Array.from(grid.children);
            const sorted = items.slice();

            if (mode === 'protocol') {
                sorted.sort((a, b) => getProtocolWeight(b) - getProtocolWeight(a));
            } else if (mode === 'score') {
                sorted.sort((a, b) => getScore(b) - getScore(a));
            } else {
                sorted.sort((a, b) => getIndex(a) - getIndex(b));
            }

            sorted.forEach((el) => grid.appendChild(el));
        };
    }

    function injectSidebar() {
        const template = document.getElementById('pjax-sidebar-html');
        const container = document.getElementById('sidebar-container');
        if (!template || !container) return;

        const nextHtml = template.innerHTML || '';
        if (container.innerHTML !== nextHtml) {
            container.innerHTML = nextHtml;
            document.dispatchEvent(new Event('sidebar:updated'));
        }
    }

    function initPage() {
        initLinkList();
        injectSidebar();
    }

    document.addEventListener('DOMContentLoaded', initPage);
    document.addEventListener('page:ready', initPage);
})();
