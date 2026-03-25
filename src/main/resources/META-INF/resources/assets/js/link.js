(function () {
    'use strict';

    function initLinkList() {
        const grepSelect = document.getElementById('linkGrepSelect');
        const applyBtn = document.getElementById('linkFilterApplyBtn');
        if (!grepSelect || !applyBtn) return;

        applyBtn.onclick = function () {
            const grepMode = grepSelect.value;
            const params = new URLSearchParams();
            if (grepMode && grepMode !== 'All') {
                params.set('type', grepMode);
            }
            const newUrl = params.toString() ? `/link?${params.toString()}` : '/link';
            if (window.pjax && window.pjax.loadUrl) {
                window.pjax.loadUrl(newUrl);
            } else {
                window.location.href = newUrl;
            }
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
