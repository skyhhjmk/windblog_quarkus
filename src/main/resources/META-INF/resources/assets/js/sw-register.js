// WindBlog Service Worker Client Runtime & Smart Prefetch Controller
//
// Responsibilities:
// 1. Register `/sw.js` with Trusted Types compliance and seamless lifecycle updates.
// 2. Smart intent-driven prefetching (hover / touchstart / focusin / viewport idle)
//    for PJAX fragments and FLIP shared-element article cover images (`/uploads/...`),
//    respecting `navigator.onLine`, `navigator.connection.saveData`, and rate limits.
// 3. Offline/Online state awareness, toast notifications, and offline retry handling.
// 4. Hydrate `[data-offline-cached-list]` containers on offline fallback pages with
//    articles already cached in the browser's Service Worker storage.
// 5. Automatically purge dynamic SW caches on login/logout, language switch, or
//    password-protected article unlock so personalized/unlocked state stays fresh.
(function () {
    'use strict';

    if (typeof window === 'undefined' || typeof document === 'undefined') {
        return;
    }

    const SW_SCRIPT_URL = '/sw.js';
    const PREFETCH_HOVER_DELAY_MS = 85;
    const PREFETCH_COOLDOWN_MS = 90 * 1000;
    const MAX_PREFETCH_QUEUE_PER_MINUTE = 18;
    const PAGES_CACHE_PREFIX = 'windblog-pages-';
    const PJAX_CACHE_PREFIX = 'windblog-pjax-';
    const PJAX_SYNTHETIC_PREFIX = '/__wb_sw_pjax__';

    const prefetchedUrls = new Map();
    const prefetchedCoverUrls = new Set();
    let prefetchTokens = MAX_PREFETCH_QUEUE_PER_MINUTE;
    let lastTokenRefillTime = Date.now();
    let hoverTimer = null;
    let activeHoverAnchor = null;
    let viewportObserver = null;
    let swRegistration = null;
    let wasOffline = !navigator.onLine;
    let lastObservedThemeColor = '';

    const NON_PREFETCH_PREFIXES = [
        '/admin',
        '/api/',
        '/q/',
        '/user',
        '/uploads/',
        '/cdn-cgi/',
        '/manifest.webmanifest',
        '/sw.js',
        '/robots.txt',
        '/sitemap',
        '/rss',
        '/feed',
        '/atom'
    ];

    function t(key, fallback) {
        if (typeof window.wbT === 'function') {
            const fullKey = key.indexOf('js.') === 0 ? key : 'js.' + key;
            const resolved = window.wbT(fullKey, '');
            if (resolved && resolved !== fullKey && resolved !== key) {
                return resolved;
            }
        }
        if (window.i18n && typeof window.i18n[key] === 'string' && window.i18n[key].trim() !== '') {
            return window.i18n[key];
        }
        if (window.WINDBLOG_I18N && typeof window.WINDBLOG_I18N[key] === 'string' && window.WINDBLOG_I18N[key].trim() !== '') {
            return window.WINDBLOG_I18N[key];
        }
        const isEn = (document.documentElement.getAttribute('lang') || '').toLowerCase().startsWith('en');
        const defaults = isEn ? {
            sw_offline_mode_toast: 'You are currently offline. Showing cached content when available.',
            sw_online_restored_toast: 'Network connection restored.',
            sw_serving_cached_toast: 'Offline mode: loaded cached snapshot of this page.',
            sw_offline_unavailable_toast: 'Currently offline and this page has not been cached yet.',
            sw_cached_articles_empty: 'No cached pages stored in this browser yet.',
            sw_cached_badge_page: 'Full Page',
            sw_cached_badge_pjax: 'Instant View'
        } : {
            sw_offline_mode_toast: '当前处于离线状态，已为您开启离线缓存浏览模式',
            sw_online_restored_toast: '网络连接已恢复',
            sw_serving_cached_toast: '离线模式：已为您加载本地缓存页面',
            sw_offline_unavailable_toast: '当前处于离线状态，且该页面尚未缓存',
            sw_cached_articles_empty: '当前浏览器暂无已缓存的离线文章',
            sw_cached_badge_page: '完整页面',
            sw_cached_badge_pjax: '极速片段'
        };
        return defaults[key] || fallback || key;
    }

    function notifyToast(message, type) {
        if (!message) {
            return;
        }
        if (typeof window.showToast === 'function') {
            window.showToast(message, type || 'info');
        }
    }

    function isSaveDataEnabled() {
        const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
        if (!conn) {
            return false;
        }
        if (conn.saveData === true) {
            return true;
        }
        const effectiveType = String(conn.effectiveType || '').toLowerCase();
        return effectiveType === 'slow-2g' || effectiveType === '2g';
    }

    function consumePrefetchToken() {
        const now = Date.now();
        if (now - lastTokenRefillTime >= 60 * 1000) {
            prefetchTokens = MAX_PREFETCH_QUEUE_PER_MINUTE;
            lastTokenRefillTime = now;
        }
        if (prefetchTokens <= 0) {
            return false;
        }
        prefetchTokens -= 1;
        return true;
    }

    function stripLanguagePrefix(pathname) {
        const clean = pathname || '/';
        const stripped = clean.replace(/^\/(?:en(?:-us)?|zh(?:-cn)?)(?=\/|$)/i, '');
        return stripped || '/';
    }

    function isEligiblePrefetchUrl(rawUrl) {
        if (!rawUrl || !navigator.onLine || isSaveDataEnabled()) {
            return false;
        }
        let parsed;
        try {
            parsed = new URL(rawUrl, window.location.origin);
        } catch (err) {
            return false;
        }

        if (parsed.origin !== window.location.origin) {
            return false;
        }
        if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
            return false;
        }
        if (parsed.href === window.location.href || (parsed.pathname === window.location.pathname && parsed.search === window.location.search)) {
            return false;
        }

        const barePath = stripLanguagePrefix(parsed.pathname);
        for (let i = 0; i < NON_PREFETCH_PREFIXES.length; i += 1) {
            const prefix = NON_PREFETCH_PREFIXES[i];
            if (barePath === prefix || barePath.startsWith(prefix)) {
                return false;
            }
        }

        if (parsed.searchParams.has('lang') || parsed.searchParams.has('logout') || parsed.searchParams.has('token')) {
            return false;
        }

        return true;
    }

    function normalizePrefetchKey(rawUrl) {
        try {
            const parsed = new URL(rawUrl, window.location.origin);
            parsed.hash = '';
            return parsed.pathname + parsed.search;
        } catch (err) {
            return '';
        }
    }

    function findCardCoverUrl(anchor) {
        if (!anchor || typeof anchor.closest !== 'function') {
            return null;
        }
        const card = anchor.closest('article, .post-card, .group, li');
        if (!card) {
            return null;
        }
        const coverImg = card.querySelector('img.post-list-cover-img[src], img[data-pjax-cover][src]');
        if (!coverImg) {
            return null;
        }
        const src = coverImg.currentSrc || coverImg.getAttribute('src');
        if (!src) {
            return null;
        }
        try {
            const parsed = new URL(src, window.location.origin);
            if (parsed.origin === window.location.origin && parsed.pathname.startsWith('/uploads/')) {
                return parsed.pathname + parsed.search;
            }
        } catch (err) {
            // Ignore malformed image URL
        }
        return null;
    }

    function postMessageToServiceWorker(payload, withResponse) {
        if (!('serviceWorker' in navigator)) {
            return Promise.resolve(null);
        }
        const controller = navigator.serviceWorker.controller || (swRegistration && swRegistration.active);
        if (!controller) {
            return Promise.resolve(null);
        }

        if (!withResponse || typeof MessageChannel === 'undefined') {
            try {
                controller.postMessage(payload);
            } catch (err) {
                // Ignore messaging errors
            }
            return Promise.resolve(null);
        }

        return new Promise(function (resolve) {
            const channel = new MessageChannel();
            const timeoutId = window.setTimeout(function () {
                resolve(null);
            }, 2500);

            channel.port1.onmessage = function (event) {
                window.clearTimeout(timeoutId);
                resolve(event.data || null);
            };

            try {
                controller.postMessage(payload, [channel.port2]);
            } catch (err) {
                window.clearTimeout(timeoutId);
                resolve(null);
            }
        });
    }

    function triggerPrefetch(rawUrl, coverUrl) {
        if (!isEligiblePrefetchUrl(rawUrl)) {
            return;
        }

        const key = normalizePrefetchKey(rawUrl);
        if (!key) {
            return;
        }

        const now = Date.now();
        const lastPrefetchedAt = prefetchedUrls.get(key) || 0;
        if (now - lastPrefetchedAt < PREFETCH_COOLDOWN_MS) {
            return;
        }
        if (!consumePrefetchToken()) {
            return;
        }

        prefetchedUrls.set(key, now);

        const coverUrls = [];
        if (coverUrl && !prefetchedCoverUrls.has(coverUrl)) {
            prefetchedCoverUrls.add(coverUrl);
            coverUrls.push(coverUrl);
        }

        if (navigator.serviceWorker && navigator.serviceWorker.controller) {
            postMessageToServiceWorker({
                type: 'PREFETCH_URLS',
                urls: [key],
                coverUrls: coverUrls
            }, false);
            return;
        }

        // Fallback direct low-priority fetch if SW does not yet control the page
        fetch(key, {
            method: 'GET',
            credentials: 'same-origin',
            headers: {
                'X-PJAX': 'true',
                'X-WindBlog-Prefetch': 'true',
                'Accept': 'text/html'
            }
        }).catch(function () {
            // Silently ignore background prefetch failure
        });
    }

    function isAnchorPrefetchable(anchor) {
        if (!anchor || anchor.tagName !== 'A') {
            return false;
        }
        const href = anchor.getAttribute('href');
        if (!href || href.startsWith('#') || href.startsWith('javascript:') || href.startsWith('mailto:') || href.startsWith('tel:')) {
            return false;
        }
        if (anchor.hasAttribute('download') || anchor.hasAttribute('data-no-pjax') || anchor.hasAttribute('data-no-prefetch')) {
            return false;
        }
        if (anchor.target && anchor.target !== '_self') {
            return false;
        }
        return isEligiblePrefetchUrl(anchor.href);
    }

    function bindIntentPrefetchListeners() {
        document.addEventListener('mouseover', function (event) {
            if (!event.target || typeof event.target.closest !== 'function') {
                return;
            }
            const anchor = event.target.closest('a[href]');
            if (!isAnchorPrefetchable(anchor)) {
                return;
            }
            if (activeHoverAnchor === anchor) {
                return;
            }
            activeHoverAnchor = anchor;
            if (hoverTimer) {
                window.clearTimeout(hoverTimer);
            }
            hoverTimer = window.setTimeout(function () {
                if (activeHoverAnchor === anchor) {
                    triggerPrefetch(anchor.href, findCardCoverUrl(anchor));
                }
            }, PREFETCH_HOVER_DELAY_MS);
        }, { passive: true });

        document.addEventListener('mouseout', function (event) {
            if (!activeHoverAnchor || !event.target || typeof event.target.closest !== 'function') {
                return;
            }
            const anchor = event.target.closest('a[href]');
            if (anchor && anchor === activeHoverAnchor) {
                if (hoverTimer) {
                    window.clearTimeout(hoverTimer);
                    hoverTimer = null;
                }
                activeHoverAnchor = null;
            }
        }, { passive: true });

        document.addEventListener('touchstart', function (event) {
            if (!event.target || typeof event.target.closest !== 'function') {
                return;
            }
            const anchor = event.target.closest('a[href]');
            if (isAnchorPrefetchable(anchor)) {
                triggerPrefetch(anchor.href, findCardCoverUrl(anchor));
            }
        }, { passive: true });

        document.addEventListener('focusin', function (event) {
            if (!event.target || typeof event.target.closest !== 'function') {
                return;
            }
            const anchor = event.target.closest('a[href]');
            if (isAnchorPrefetchable(anchor)) {
                triggerPrefetch(anchor.href, findCardCoverUrl(anchor));
            }
        }, { passive: true });
    }

    function observeViewportArticleLinks() {
        if (!('IntersectionObserver' in window) || !navigator.onLine || isSaveDataEnabled()) {
            return;
        }
        if (viewportObserver) {
            viewportObserver.disconnect();
        }

        viewportObserver = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) {
                    return;
                }
                const anchor = entry.target;
                viewportObserver.unobserve(anchor);
                const scheduleIdle = window.requestIdleCallback || function (cb) {
                    return window.setTimeout(cb, 350);
                };
                scheduleIdle(function () {
                    if (isAnchorPrefetchable(anchor)) {
                        triggerPrefetch(anchor.href, findCardCoverUrl(anchor));
                    }
                });
            });
        }, {
            rootMargin: '160px 0px'
        });

        const container = document.getElementById('pjax-container') || document;
        const postLinks = container.querySelectorAll('a[href*="/post/"], a[href*="/posts/"]');
        let observedCount = 0;
        for (let i = 0; i < postLinks.length && observedCount < 8; i += 1) {
            const link = postLinks[i];
            if (isAnchorPrefetchable(link)) {
                viewportObserver.observe(link);
                observedCount += 1;
            }
        }
    }

    function extractTitleFromMarkup(html) {
        if (!html) {
            return '';
        }
        const dataTitleMatch = html.match(/data-page-title=["']([^"']+)["']/i);
        if (dataTitleMatch && dataTitleMatch[1]) {
            return decodeBasicEntities(dataTitleMatch[1].trim());
        }
        const pjaxTitleMatch = html.match(/id=["']pjax-title["'][^>]*>([\s\S]*?)<\/div>/i);
        if (pjaxTitleMatch && pjaxTitleMatch[1]) {
            return decodeBasicEntities(pjaxTitleMatch[1].replace(/<[^>]+>/g, '').trim());
        }
        const docTitleMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
        if (docTitleMatch && docTitleMatch[1]) {
            return decodeBasicEntities(docTitleMatch[1].replace(/<[^>]+>/g, '').trim());
        }
        return '';
    }

    function decodeBasicEntities(str) {
        return String(str || '')
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&quot;/g, '"')
            .replace(/&#39;/g, "'");
    }

    async function readCachedArticlesFromCacheStorage() {
        if (!('caches' in window)) {
            return [];
        }
        try {
            const keys = await window.caches.keys();
            const itemsByUrl = new Map();

            for (let i = 0; i < keys.length; i += 1) {
                const cacheName = keys[i];
                const isPagesCache = cacheName.startsWith(PAGES_CACHE_PREFIX);
                const isPjaxCache = cacheName.startsWith(PJAX_CACHE_PREFIX);
                if (!isPagesCache && !isPjaxCache) {
                    continue;
                }

                const cache = await window.caches.open(cacheName);
                const requests = await cache.keys();
                for (let j = 0; j < requests.length; j += 1) {
                    const req = requests[j];
                    const parsed = new URL(req.url);
                    let routePath = parsed.pathname + parsed.search;
                    if (isPjaxCache && routePath.startsWith(PJAX_SYNTHETIC_PREFIX)) {
                        routePath = routePath.slice(PJAX_SYNTHETIC_PREFIX.length) || '/';
                    }
                    if (routePath === '/offline' || routePath.endsWith('/offline')) {
                        continue;
                    }
                    const response = await cache.match(req);
                    if (!response) {
                        continue;
                    }
                    const html = await response.text();
                    const title = extractTitleFromMarkup(html) || routePath;
                    const cachedAt = Number(response.headers.get('X-WindBlog-SW-Cached-At') || '0') || 0;
                    const existing = itemsByUrl.get(routePath);
                    if (!existing || cachedAt > existing.cachedAt) {
                        itemsByUrl.set(routePath, {
                            url: routePath,
                            title: title,
                            cachedAt: cachedAt,
                            type: isPagesCache ? 'page' : 'pjax'
                        });
                    }
                }
            }

            return Array.from(itemsByUrl.values())
                .sort(function (a, b) {
                    return (b.cachedAt || 0) - (a.cachedAt || 0);
                })
                .slice(0, 20);
        } catch (err) {
            return [];
        }
    }

    async function getCachedArticles() {
        const swResponse = await postMessageToServiceWorker({ type: 'GET_CACHED_ARTICLES' }, true);
        if (swResponse && Array.isArray(swResponse.articles) && swResponse.articles.length > 0) {
            return swResponse.articles;
        }
        return readCachedArticlesFromCacheStorage();
    }

    async function hydrateOfflineCachedArticleLists() {
        const containers = document.querySelectorAll('[data-offline-cached-list="true"]');
        if (!containers || containers.length === 0) {
            return;
        }

        const articles = await getCachedArticles();
        containers.forEach(function (container) {
            while (container.firstChild) {
                container.removeChild(container.firstChild);
            }

            if (!articles || articles.length === 0) {
                const emptyP = document.createElement('p');
                emptyP.className = 'text-xs text-meta font-mono py-3';
                emptyP.textContent = container.getAttribute('data-empty-text') || t('sw_cached_articles_empty');
                container.appendChild(emptyP);
                return;
            }

            const list = document.createElement('div');
            list.className = 'grid grid-cols-1 sm:grid-cols-2 gap-3';

            articles.forEach(function (item) {
                const link = document.createElement('a');
                link.href = item.url || '/';
                link.className = 'group flex items-center justify-between gap-3 p-3.5 rounded-xl border border-border bg-bg/70 hover:border-accent/60 hover:bg-hover-surface transition-all duration-200';

                const textWrap = document.createElement('div');
                textWrap.className = 'min-w-0 flex-1 text-left';

                const titleEl = document.createElement('div');
                titleEl.className = 'text-sm font-semibold text-main group-hover:text-accent truncate transition-colors';
                titleEl.textContent = item.title || item.url;

                const urlEl = document.createElement('div');
                urlEl.className = 'text-xs text-meta truncate mt-0.5 font-mono';
                urlEl.textContent = item.url;

                textWrap.appendChild(titleEl);
                textWrap.appendChild(urlEl);

                const badge = document.createElement('span');
                badge.className = 'shrink-0 inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-mono font-semibold bg-accent/10 text-accent border border-accent/20';
                badge.textContent = item.type === 'page' ? t('sw_cached_badge_page') : t('sw_cached_badge_pjax');

                link.appendChild(textWrap);
                link.appendChild(badge);
                list.appendChild(link);
            });

            container.appendChild(list);
        });
    }

    function syncOfflineBannerState() {
        const isOffline = !navigator.onLine;
        document.documentElement.toggleAttribute('data-windblog-offline', isOffline);
        const badges = document.querySelectorAll('[data-sw-network-status]');
        badges.forEach(function (badge) {
            badge.textContent = isOffline ? 'OFFLINE' : 'ONLINE';
        });
    }

    function clearDynamicCaches() {
        prefetchedUrls.clear();
        prefetchedCoverUrls.clear();
        return postMessageToServiceWorker({ type: 'CLEAR_DYNAMIC_CACHES' }, true);
    }

    function bindStateInvalidationHooks() {
        // Retry button on offline fallback fragment / page
        document.addEventListener('click', function (event) {
            if (!event.target || typeof event.target.closest !== 'function') {
                return;
            }
            const retryBtn = event.target.closest('[data-sw-offline-retry]');
            if (retryBtn) {
                event.preventDefault();
                if (typeof window.loadByPjax === 'function' && navigator.onLine) {
                    window.loadByPjax(window.location.href, false);
                } else {
                    window.location.reload();
                }
                return;
            }

            // Clear dynamic caches when switching language or logging out
            const anchor = event.target.closest('a[href]');
            if (anchor) {
                const href = anchor.getAttribute('href') || '';
                if (href.includes('lang=') || href.includes('/logout') || anchor.hasAttribute('data-lang-switch')) {
                    clearDynamicCaches();
                }
            }
        });

        // Clear dynamic caches when submitting login, password unlock, or auth forms
        document.addEventListener('submit', function (event) {
            const form = event.target;
            if (!form || form.tagName !== 'FORM') {
                return;
            }
            const action = (form.getAttribute('action') || '').toLowerCase();
            const formId = (form.getAttribute('id') || '').toLowerCase();
            if (action.includes('/unlock') || action.includes('/login') || action.includes('/logout') || formId.includes('password') || formId.includes('login')) {
                clearDynamicCaches();
            }
        });

        window.addEventListener('offline', function () {
            wasOffline = true;
            syncOfflineBannerState();
            notifyToast(t('sw_offline_mode_toast'), 'warning');
        });

        window.addEventListener('online', function () {
            syncOfflineBannerState();
            if (wasOffline) {
                wasOffline = false;
                notifyToast(t('sw_online_restored_toast'), 'success');
            }
        });

        document.addEventListener('pjax:complete', function (event) {
            const detail = (event && event.detail) || {};
            const swSource = String(detail.swSource || '');
            if (swSource === 'offline-cache' || swSource === 'offline-page-cache') {
                notifyToast(t('sw_serving_cached_toast'), 'info');
            } else if (swSource === 'offline-fallback') {
                notifyToast(t('sw_offline_unavailable_toast'), 'warning');
            }
            hydrateOfflineCachedArticleLists();
            observeViewportArticleLinks();
        });
    }

    function syncDarkModeThemeColor() {
        const themeMeta = document.querySelector('meta[name="theme-color"]');
        if (!themeMeta) {
            return;
        }
        const isLight = document.documentElement.classList.contains('light');
        const targetColor = isLight ? '#f8fafc' : '#0f172a';
        if (lastObservedThemeColor !== targetColor) {
            lastObservedThemeColor = targetColor;
            themeMeta.setAttribute('content', targetColor);
        }
    }

    function observeThemeChanges() {
        syncDarkModeThemeColor();
        if (typeof MutationObserver === 'undefined') {
            return;
        }
        const observer = new MutationObserver(function () {
            syncDarkModeThemeColor();
        });
        observer.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['class']
        });
    }

    function createTrustedServiceWorkerUrl(url) {
        if (window.windblogTrustedTypesPolicy && typeof window.windblogTrustedTypesPolicy.createScriptURL === 'function') {
            return window.windblogTrustedTypesPolicy.createScriptURL(url);
        }
        return url;
    }

    function registerServiceWorker() {
        if (!('serviceWorker' in navigator)) {
            return;
        }
        const isLocalhost = window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1' || window.location.hostname === '[::1]';
        if (window.location.protocol !== 'https:' && !isLocalhost) {
            return;
        }

        const registerTask = function () {
            try {
                const scriptUrl = createTrustedServiceWorkerUrl(SW_SCRIPT_URL);
                navigator.serviceWorker.register(scriptUrl, {
                    scope: '/',
                    updateViaCache: 'none'
                }).then(function (registration) {
                    swRegistration = registration;

                    if (registration.waiting) {
                        try {
                            registration.waiting.postMessage({ type: 'SKIP_WAITING' });
                        } catch (err) {
                            // Ignore
                        }
                    }

                    registration.addEventListener('updatefound', function () {
                        const installingWorker = registration.installing;
                        if (!installingWorker) {
                            return;
                        }
                        installingWorker.addEventListener('statechange', function () {
                            if (installingWorker.state === 'installed' && navigator.serviceWorker.controller) {
                                try {
                                    installingWorker.postMessage({ type: 'SKIP_WAITING' });
                                } catch (err) {
                                    // Ignore
                                }
                            }
                        });
                    });
                }).catch(function () {
                    // Fail silently if Service Worker registration is blocked by browser settings
                });
            } catch (err) {
                // Fail silently
            }
        };

        if (document.readyState === 'complete') {
            registerTask();
        } else {
            window.addEventListener('load', registerTask, { once: true });
        }
    }

    // Expose public diagnostic & control API on window.WindBlogSW
    window.WindBlogSW = {
        getStatus: function () {
            return postMessageToServiceWorker({ type: 'GET_SW_STATUS' }, true);
        },
        getCachedArticles: getCachedArticles,
        prefetch: function (url, coverUrl) {
            triggerPrefetch(url, coverUrl);
        },
        clearDynamicCaches: clearDynamicCaches,
        hydrateOfflineLists: hydrateOfflineCachedArticleLists
    };

    // Initialize runtime
    syncOfflineBannerState();
    observeThemeChanges();
    bindIntentPrefetchListeners();
    bindStateInvalidationHooks();
    registerServiceWorker();

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            hydrateOfflineCachedArticleLists();
            observeViewportArticleLinks();
        });
    } else {
        hydrateOfflineCachedArticleLists();
        observeViewportArticleLinks();
    }
})();
