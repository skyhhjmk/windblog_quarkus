/**
 * WindBlog 访客归属地欢迎提示：全页加载展示一次欢迎 Toast，PJAX 站内切换不重复弹窗。
 */
(function () {
    'use strict';

    let requested = false;

    function showVisitorWelcome() {
        if (requested) {
            return;
        }
        requested = true;

        const fetchOptions = {
            method: 'GET',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'Accept': 'application/json' }
        };
        if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
            fetchOptions.signal = AbortSignal.timeout(5000);
        }

        fetch('/api/public/visitor-location', fetchOptions)
            .then(res => (res && res.ok ? res.json() : null))
            .then(data => {
                if (!data || !data.success || !data.welcomeMessage) {
                    return;
                }
                if (typeof window.showToast === 'function') {
                    window.showToast(data.welcomeMessage, 'info', 4500);
                }
            })
            .catch(() => {
                // 静默降级：归属地查询失败不干扰前台正常浏览
            });
    }

    if (window.WindBlogLifecycle && typeof window.WindBlogLifecycle.register === 'function') {
        window.WindBlogLifecycle.register('visitor-welcome', {
            persistent: true,
            mount: showVisitorWelcome
        });
    } else if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', showVisitorWelcome, { once: true });
    } else {
        setTimeout(showVisitorWelcome, 0);
    }
})();
