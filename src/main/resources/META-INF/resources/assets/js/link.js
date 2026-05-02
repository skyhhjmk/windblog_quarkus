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
            
            window.setLoading(applyBtn, true);
            
            if (window.pjax && window.pjax.loadUrl) {
                window.pjax.loadUrl(newUrl);
            } else {
                window.location.href = newUrl;
            }
        };
    }

    function initPage() {
        initLinkList();
    }

    document.addEventListener('DOMContentLoaded', initPage);
    document.addEventListener('page:ready', initPage);
})();
