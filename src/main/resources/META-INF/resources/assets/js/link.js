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

    function initLinkCard() {
        const cards = document.querySelectorAll('.link-card');
        cards.forEach(card => {
            card.onclick = function (e) {
                // If clicked on the direct link anchor, don't show modal
                if (e.target.closest('.link-anchor')) return;

                const id = card.dataset.id;
                const name = card.dataset.name;
                const url = card.dataset.url;
                const icon = card.dataset.icon;
                const description = card.dataset.description;

                let iconHtml = icon
                    ? `<img src="${icon}" class="w-16 h-16 rounded-xl border border-border mx-auto mb-4" />`
                    : `<div class="w-16 h-16 rounded-xl border border-border bg-white/5 flex items-center justify-center mx-auto mb-4"><svg class="w-8 h-8 text-gray-600" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M10.172 13.828a4 4 0 015.656 0l4 4a4 4 0 11-5.656 5.656l-1.102-1.101" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></div>`;

                window.showModal({
                    title: "资源详情",
                    message: `
                        <div class="text-center py-4">
                            ${iconHtml}
                            <h3 class="text-xl font-bold text-main mb-1">${name}</h3>
                            <p class="text-xs meta-mono text-accent mb-6">${url}</p>
                            <div class="p-4 bg-white/5 rounded-lg border border-border text-left text-sm text-body leading-relaxed mb-6">
                                ${description || '该资源暂无描述。'}
                            </div>
                            <div class="flex justify-center">
                                <a href="${url}" target="_blank" class="btn-action-primary px-12 py-3">
                                    访问资源
                                </a>
                            </div>
                        </div>
                    `,
                    showCancel: true
                });
            };
        });
    }

    function initPage() {
        initLinkList();
        initLinkCard();
    }

    document.addEventListener('DOMContentLoaded', initPage);
    document.addEventListener('page:ready', initPage);
})();
