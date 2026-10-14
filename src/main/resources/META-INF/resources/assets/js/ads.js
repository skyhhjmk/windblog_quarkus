(function () {
    'use strict';
    let scriptPromise;
    let observer;
    let fillObserver;
    let generation = 0;
    const number = (value, fallback, max) => Number.isFinite(Number(value))
        ? Math.max(0, Math.min(max, Number(value))) : fallback;

    function loadScript(client) {
        if (scriptPromise) return scriptPromise;
        scriptPromise = new Promise((resolve, reject) => {
            let script = document.querySelector('script[src*="pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"]');
            if (script) { resolve(); return; }
            script = document.createElement('script');
            script.async = true;
            script.crossOrigin = 'anonymous';
            script.src = 'https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=' + encodeURIComponent(client);
            script.onload = resolve;
            script.onerror = () => { script.remove(); scriptPromise = undefined; reject(new Error('AdSense unavailable')); };
            document.head.appendChild(script);
        });
        return scriptPromise;
    }

    async function render(container, config, token) {
        if (container.dataset.wbAdStarted || !container.isConnected || container.offsetWidth === 0) return;
        container.dataset.wbAdStarted = 'true';
        try {
            await loadScript(config.clientId);
            if (!container.isConnected) {
                delete container.dataset.wbAdStarted;
                return;
            }
            const ad = container.querySelector('ins');
            if (ad && !ad.hasAttribute('data-adsbygoogle-status')) {
                (window.adsbygoogle = window.adsbygoogle || []).push({});
            }
        } catch (_) {
            container.hidden = true;
        }
    }

    function init() {
        const token = ++generation;
        if (observer) observer.disconnect();
        if (fillObserver) fillObserver.disconnect();
        const source = document.querySelector('[data-wb-ads-config]');
        if (!source) return;
        let config;
        let blocks;
        try {
            config = JSON.parse(source.dataset.wbAdsConfig);
            blocks = typeof config.blocks === 'string' ? JSON.parse(config.blocks) : (config.blocks || []);
        } catch (_) { return; }
        if (config.enabled !== true || !/^ca-pub-\d{16}$/.test(config.clientId)) return;
        if (!Array.isArray(blocks)) return;
        if (config.autoAds === true) {
            // Automatic placements are controlled by Google; only manual blocks use viewport loading.
            const start = () => { if (token === generation) loadScript(config.clientId).catch(() => {}); };
            if (config.lazyLoad !== false && 'requestIdleCallback' in window) window.requestIdleCallback(start, {timeout: 2000});
            else setTimeout(start, config.lazyLoad === false ? 0 : 1000);
        }
        if (config.lazyLoad !== false && 'IntersectionObserver' in window) {
            observer = new IntersectionObserver(entries => entries.forEach(entry => {
                if (entry.isIntersecting && entry.target.offsetWidth > 0) {
                    observer.unobserve(entry.target);
                    render(entry.target, config, token);
                }
            }), {rootMargin: number(config.rootMargin, 300, 2000) + 'px'});
        }
        if (config.collapseUnfilled !== false) {
            fillObserver = new MutationObserver(records => records.forEach(({target}) => {
                if (target.getAttribute('data-ad-status') === 'unfilled') target.parentElement.hidden = true;
            }));
        }
        [['content_top', '#pjax-container', true], ['content_bottom', '#pjax-container', false], ['sidebar', '#sidebar-container', true]].forEach(([placement, selector, first]) => {
            const parent = document.querySelector(selector);
            if (!parent || parent.querySelector('[data-wb-ad-placement="' + placement + '"]')) return;
            const host = document.createElement('div');
            host.dataset.wbAdPlacement = placement;
            if (first) parent.prepend(host);
            else parent.appendChild(host);
        });
        document.querySelectorAll('[data-wb-ad-placement]').forEach(host => {
            // Keep filled units intact if a PJAX update preserves their host.
            if (host.childElementCount) return;
            blocks.slice(0, 20).forEach(block => {
                if (!block || block.enabled === false || block.placement !== host.dataset.wbAdPlacement || !/^\d{1,20}$/.test(String(block.slot || ''))) return;
                if (!['auto', 'rectangle', 'horizontal', 'vertical', 'fluid', 'fixed'].includes(block.format || 'auto')) return;
                if (!['all', 'mobile', 'desktop'].includes(block.device || 'all')) return;
                const wrapper = document.createElement('div');
                wrapper.className = 'wb-ad-block wb-ad-' + (block.device || 'all');
                wrapper.style.minHeight = number(block.minHeight, 100, 1200) + 'px';
                wrapper.setAttribute('aria-label', '广告');
                const ad = document.createElement('ins');
                ad.className = 'adsbygoogle';
                ad.style.display = 'block';
                ad.dataset.adClient = config.clientId;
                ad.dataset.adSlot = String(block.slot);
                if (block.format === 'fixed') {
                    const width = number(block.width, 300, 1200);
                    const height = number(block.height, 250, 1200);
                    if (!width || !height) return;
                    ad.style.width = width + 'px';
                    ad.style.height = height + 'px';
                    wrapper.style.overflowX = 'auto';
                } else {
                    ad.dataset.adFormat = block.format || 'auto';
                    ad.dataset.fullWidthResponsive = String(block.responsive !== false);
                }
                if (block.format === 'fluid') {
                    if (typeof block.layout === 'string') ad.dataset.adLayout = block.layout;
                    if (typeof block.layoutKey === 'string') ad.dataset.adLayoutKey = block.layoutKey;
                }
                wrapper.appendChild(ad);
                host.appendChild(wrapper);
                if (fillObserver) fillObserver.observe(ad, {attributes: true, attributeFilter: ['data-ad-status']});
            });
        });
        // Re-observe pending preserved hosts after navigation and viewport changes.
        document.querySelectorAll('.wb-ad-block').forEach(wrapper => {
            if (wrapper.dataset.wbAdStarted) return;
            if (observer) observer.observe(wrapper);
            else render(wrapper, config, token);
        });
    }
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
    document.addEventListener('pjax:complete', init);
})();
