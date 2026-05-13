// /assets/js/ads.js
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function hasAdsenseScript() {
        const scripts = document.getElementsByTagName('script');
        for (let i = 0; i < scripts.length; i++) {
            if (scripts[i].src && scripts[i].src.indexOf('adsbygoogle.js') !== -1) return true;
        }
        return false;
    }

    function loadAdsense(clientId) {
        return new Promise((resolve) => {
            if (hasAdsenseScript()) return resolve();
            const s = document.createElement('script');
            s.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${clientId}`;
            s.async = true;
            s.crossOrigin = 'anonymous';
            s.onload = resolve;
            s.onerror = resolve; // 容错：不阻塞页面
            document.head.appendChild(s);
        });
    }

    function pushAds() {
        try {
            const ads = document.querySelectorAll('.adsbygoogle');
            if (!ads.length) return;
            // eslint-disable-next-line no-undef
            (adsbygoogle = window.adsbygoogle || []).push({});
        } catch (e) {
            // 忽略
        }
    }

    async function initAds() {
        const containers = document.querySelectorAll('[data-wb-ad-container]');
        if (!containers.length) return;

        const clientId = 'ca-pub-'; // TODO: 替换为你的 Adsense client id
        await loadAdsense(clientId);

        // 等待 DOM 稳定后 push
        setTimeout(pushAds, 120);
    }

    ready(() => {
        initAds();
        document.addEventListener('pjax:complete', initAds);
    });
})();