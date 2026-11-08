// WindBlog Service Worker (/sw.js)
//
// Enhances browsing experience via:
// 1. Versioned static asset precaching & Cache-First/SWR runtime caching (/assets/..., /favicon...)
// 2. Public media & article cover image caching (/uploads/...) with LRU eviction for instant FLIP transitions
// 3. Isolated PJAX fragment caching & smart hover/touch prefetching (X-PJAX: true)
// 4. Full document navigation Network-First caching with Navigation Preload & Shell+PJAX offline synthesis
// 5. Read-only public API offline fallback (/api/posts/{slug}/link-graph, /api/comments/post/{slug}, /search/suggest)
(function () {
    'use strict';

    const CACHE_VERSION = '20261108-v1';
    const CACHE_PREFIX = 'windblog-';
    const STATIC_CACHE = CACHE_PREFIX + 'static-' + CACHE_VERSION;
    const MEDIA_CACHE = CACHE_PREFIX + 'media-' + CACHE_VERSION;
    const PJAX_CACHE = CACHE_PREFIX + 'pjax-' + CACHE_VERSION;
    const PAGES_CACHE = CACHE_PREFIX + 'pages-' + CACHE_VERSION;
    const API_CACHE = CACHE_PREFIX + 'api-' + CACHE_VERSION;

    const ACTIVE_CACHES = new Set([
        STATIC_CACHE,
        MEDIA_CACHE,
        PJAX_CACHE,
        PAGES_CACHE,
        API_CACHE
    ]);

    const MAX_MEDIA_ITEMS = 80;
    const MAX_PJAX_ITEMS = 50;
    const MAX_PAGES_ITEMS = 35;
    const MAX_API_ITEMS = 60;
    const PREFETCH_TTL_MS = 45 * 1000;
    const NETWORK_TIMEOUT_MS = 6500;

    const PJAX_SHELL_START_MARKER = '<!--WINDBLOG_PJAX_CONTENT_START-->';
    const PJAX_SHELL_END_MARKER = '<!--WINDBLOG_PJAX_CONTENT_END-->';

    const PRECACHE_STATIC_URLS = [
        '/favicon.svg',
        '/manifest.webmanifest',
        '/assets/img/avatar-default.svg',
        '/assets/vendor/fontawesome/css/all.min.css',
        '/assets/vendor/fontawesome/webfonts/fa-solid-900.woff2',
        '/assets/vendor/fontawesome/webfonts/fa-regular-400.woff2',
        '/assets/vendor/fontawesome/webfonts/fa-brands-400.woff2',
        '/assets/vendor/tailwind/tailwind-cdn.min.js',
        '/assets/css/theme.css',
        '/assets/css/ui.css',
        '/assets/css/post-quote.css',
        '/assets/css/drag-character-trail.css',
        '/assets/css/ruler-hud.css',
        '/assets/css/relax-games.css',
        '/assets/js/windblog-dom-safety.js',
        '/assets/js/ui.js',
        '/assets/js/main.js',
        '/assets/js/article-images.js',
        '/assets/js/article-lightbox.js',
        '/assets/js/sw-register.js'
    ];

    const PRECACHE_PAGE_URLS = [
        '/offline'
    ];

    function isSameOrigin(url) {
        return url.origin === self.location.origin;
    }

    function normalizePath(pathname) {
        if (!pathname || pathname === '/') {
            return '/';
        }
        return pathname.replace(/\/+$/, '') || '/';
    }

    function stripLangPrefix(pathname) {
        const normalized = normalizePath(pathname);
        const stripped = normalized.replace(/^\/(zh-cn|en-us)(?=\/|$)/i, '');
        return stripped || '/';
    }

    function isPrivateOrSystemPath(pathname) {
        const plain = stripLangPrefix(pathname);
        return plain === '/sw.js'
            || plain.startsWith('/admin')
            || plain.startsWith('/api/admin')
            || plain === '/user'
            || plain.startsWith('/user/')
            || plain.startsWith('/api/user')
            || plain.startsWith('/api/wallet')
            || plain.startsWith('/api/media/download')
            || plain.startsWith('/q/')
            || plain.startsWith('/sync/')
            || plain.startsWith('/grpc/')
            || plain.startsWith('/.well-known/')
            || plain.startsWith('/link/go/')
            || plain.startsWith('/r/');
    }

    function isStateMutatingAuthOrUserPath(pathname) {
        const plain = stripLangPrefix(pathname);
        return plain === '/user/api/login'
            || plain === '/user/api/logout'
            || plain.startsWith('/api/user/')
            || plain.startsWith('/api/wallet/');
    }

    function isPublicCacheablePagePath(pathname) {
        if (isPrivateOrSystemPath(pathname)) {
            return false;
        }
        const plain = stripLangPrefix(pathname);
        if (plain === '/search' || plain.startsWith('/search/')) {
            return false;
        }
        return plain === '/'
            || plain === '/offline'
            || /^\/page\/\d+$/.test(plain)
            || /^\/post\/[^/]+$/.test(plain)
            || /^\/category(?:\/[^/]+)?$/.test(plain)
            || /^\/tag(?:\/[^/]+)?$/.test(plain)
            || /^\/link(?:\/\d+)?$/.test(plain)
            || /^\/tools(?:\/[^/]+)?$/.test(plain)
            || plain === '/terms'
            || plain === '/privacy';
    }

    function isStaticAssetPath(pathname) {
        return pathname.startsWith('/assets/')
            || pathname === '/manifest.webmanifest'
            || /^\/favicon\.(svg|png|ico|webp|jpg|jpeg|gif|avif)$/i.test(pathname);
    }

    function isPublicMediaPath(pathname) {
        return pathname.startsWith('/uploads/')
            && !pathname.startsWith('/uploads/import-placeholder/');
    }

    function isPublicReadApiPath(pathname) {
        return /^\/api\/posts\/[^/]+\/link-graph$/.test(pathname)
            || /^\/api\/comments\/post\/[^/]+$/.test(pathname)
            || pathname === '/search/suggest';
    }

    function prefersMarkdown(request) {
        const accept = request.headers.get('Accept') || '';
        return accept.toLowerCase().includes('text/markdown');
    }

    function buildPjaxCacheKey(url) {
        const parsed = typeof url === 'string' ? new URL(url, self.location.origin) : url;
        return parsed.origin + '/__wb_sw_pjax__' + parsed.pathname + parsed.search;
    }

    function buildPageCacheKey(url) {
        const parsed = typeof url === 'string' ? new URL(url, self.location.origin) : url;
        return parsed.origin + parsed.pathname + parsed.search;
    }

    function hasPrivateResponseIndicators(response, allowPjaxNoStore) {
        if (!response || response.status !== 200 || response.type === 'opaque') {
            return true;
        }
        if (response.headers.get('X-WindBlog-Private') === 'true') {
            return true;
        }
        if (response.headers.get('Set-Cookie')) {
            return true;
        }
        const cacheControl = (response.headers.get('Cache-Control') || '').toLowerCase();
        if (cacheControl.includes('private')) {
            return true;
        }
        if (!allowPjaxNoStore && cacheControl.includes('no-store')) {
            return true;
        }
        return false;
    }

    async function cloneResponseWithHeaders(response, extraHeaders) {
        const body = await response.arrayBuffer();
        const headers = new Headers(response.headers);
        Object.entries(extraHeaders || {}).forEach(function ([key, value]) {
            if (value !== undefined && value !== null) {
                headers.set(key, String(value));
            }
        });
        if (response.url && !headers.has('X-WindBlog-Resolved-Url')) {
            headers.set('X-WindBlog-Resolved-Url', response.url);
        }
        return new Response(body, {
            status: response.status,
            statusText: response.statusText,
            headers: headers
        });
    }

    async function trimCache(cacheName, maxItems) {
        try {
            const cache = await caches.open(cacheName);
            const keys = await cache.keys();
            if (keys.length <= maxItems) {
                return;
            }
            const removeCount = keys.length - maxItems;
            for (let i = 0; i < removeCount; i++) {
                await cache.delete(keys[i]);
            }
        } catch (_) {
            // Cache eviction errors must never break request handling.
        }
    }

    async function purgeOlderVersionedAsset(cache, requestUrl) {
        try {
            const keys = await cache.keys();
            for (const req of keys) {
                const cachedUrl = new URL(req.url);
                if (cachedUrl.pathname === requestUrl.pathname && cachedUrl.search !== requestUrl.search) {
                    await cache.delete(req);
                }
            }
        } catch (_) {
            // Ignore cleanup failures.
        }
    }

    async function clearDynamicCaches() {
        await Promise.allSettled([
            caches.delete(PAGES_CACHE),
            caches.delete(PJAX_CACHE),
            caches.delete(API_CACHE)
        ]);
    }

    function fetchWithTimeout(request, timeoutMs) {
        if (!timeoutMs || timeoutMs <= 0) {
            return fetch(request);
        }
        return new Promise(function (resolve, reject) {
            const controller = new AbortController();
            const timer = setTimeout(function () {
                controller.abort();
                reject(new Error('SW network timeout'));
            }, timeoutMs);

            fetch(request, { signal: controller.signal }).then(function (response) {
                clearTimeout(timer);
                resolve(response);
            }).catch(function (error) {
                clearTimeout(timer);
                reject(error);
            });
        });
    }

    function extractTitleFromHtml(html, fallbackUrl) {
        if (!html) {
            return fallbackUrl || 'WindBlog';
        }
        const dataTitleMatch = html.match(/data-page-title="([^"]+)"/i);
        if (dataTitleMatch && dataTitleMatch[1]) {
            return decodeHtmlEntities(dataTitleMatch[1].trim());
        }
        const titleTagMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
        if (titleTagMatch && titleTagMatch[1]) {
            return decodeHtmlEntities(titleTagMatch[1].trim());
        }
        return fallbackUrl || 'WindBlog';
    }

    function decodeHtmlEntities(text) {
        return String(text || '')
            .replace(/&amp;/g, '&')
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&quot;/g, '"')
            .replace(/&#39;/g, "'");
    }

    function escapeHtml(text) {
        return String(text || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function buildOfflineImagePlaceholder() {
        const svg = '<svg xmlns="http://www.w3.org/2000/svg" width="640" height="360" viewBox="0 0 640 360" role="img" aria-label="Offline image placeholder">'
            + '<rect width="640" height="360" rx="16" fill="#18181b"/>'
            + '<rect x="12" y="12" width="616" height="336" rx="12" fill="none" stroke="#27272a" stroke-width="2" stroke-dasharray="6 6"/>'
            + '<g fill="none" stroke="#71717a" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" transform="translate(296, 136)">'
            + '<rect x="4" y="4" width="40" height="32" rx="4"/>'
            + '<circle cx="16" cy="15" r="3.5"/>'
            + '<path d="M44 27l-10-10L12 36"/>'
            + '</g>'
            + '<text x="320" y="210" text-anchor="middle" fill="#a1a1aa" font-family="system-ui,-apple-system,sans-serif" font-size="13">Offline · Image Unavailable</text>'
            + '</svg>';
        return new Response(svg, {
            status: 200,
            headers: {
                'Content-Type': 'image/svg+xml; charset=utf-8',
                'Cache-Control': 'no-store',
                'X-WindBlog-SW-Source': 'offline-placeholder'
            }
        });
    }

    function isEnglishPath(pathname) {
        return /^\/en-us(\/|$)/i.test(pathname || '');
    }

    function buildOfflinePjaxFragment(requestUrl) {
        const en = isEnglishPath(requestUrl.pathname);
        const title = en ? 'Offline Mode - WindBlog' : '离线浏览模式 - WindBlog';
        const badge = en ? 'OFFLINE MODE' : 'OFFLINE MODE · 离线状态';
        const heading = en ? 'You are currently offline' : '当前网络连接不可用';
        const desc = en
            ? 'This page has not been cached on your device yet. You can still browse any previously visited articles and pages below, or retry once your connection returns.'
            : '您尝试访问的页面尚未缓存在本地设备中。您仍可以继续阅读下方已缓存的文章与页面，或在网络恢复后点击重试。';
        const cachedTitle = en ? 'Cached Pages & Articles Available Offline' : '本地已缓存可读的文章与页面';
        const cachedLoading = en ? 'Scanning local cache…' : '正在扫描本地离线缓存…';
        const retryText = en ? 'Retry Connection' : '重新加载';
        const homeText = en ? 'Back to Home' : '返回首页';
        const homeHref = en ? '/en-US/' : '/';

        const html = '<div id="pjax-content-root" data-page-robots="noindex,follow" data-seeray-layout-version="windblog-pjax-v1" data-page-title="' + escapeHtml(title) + '">'
            + '<div id="pjax-nav-path" class="hidden">WindBlog / Offline</div>'
            + '<section class="space-y-8">'
            + '<div class="card card-static p-6 sm:p-8 rounded-2xl border border-border bg-card shadow-sm space-y-6">'
            + '<div class="flex items-center gap-2 text-xs font-mono text-accent">'
            + '<span class="w-2 h-2 rounded-full bg-amber-500 animate-pulse"></span>'
            + '<span>' + escapeHtml(badge) + '</span>'
            + '</div>'
            + '<div class="space-y-2">'
            + '<h1 class="text-2xl sm:text-3xl font-extrabold text-main tracking-tight">' + escapeHtml(heading) + '</h1>'
            + '<p class="text-sm sm:text-base text-body leading-relaxed">' + escapeHtml(desc) + '</p>'
            + '</div>'
            + '<div class="flex flex-wrap gap-3 pt-2">'
            + '<button type="button" data-sw-offline-retry="true" class="px-4 py-2 rounded-xl bg-accent text-[var(--accent-foreground)] text-xs font-bold hover:opacity-90 transition-all cursor-pointer">' + escapeHtml(retryText) + '</button>'
            + '<a href="' + escapeHtml(homeHref) + '" class="px-4 py-2 rounded-xl border border-border bg-bg text-main text-xs font-semibold hover:border-accent transition-all">' + escapeHtml(homeText) + '</a>'
            + '</div>'
            + '<div class="pt-6 border-t border-border/60 space-y-4">'
            + '<h2 class="text-sm font-bold text-main uppercase tracking-wider font-mono">' + escapeHtml(cachedTitle) + '</h2>'
            + '<div data-offline-cached-list="true" class="grid grid-cols-1 gap-3">'
            + '<p class="text-xs text-meta font-mono">' + escapeHtml(cachedLoading) + '</p>'
            + '</div>'
            + '</div>'
            + '</div>'
            + '</section>'
            + '</div>';

        return new Response(html, {
            status: 200,
            headers: {
                'Content-Type': 'text/html; charset=utf-8',
                'Cache-Control': 'no-store',
                'X-WindBlog-SW-Source': 'offline-fallback',
                'X-WindBlog-SW-Offline': 'true',
                'X-WindBlog-Resolved-Url': requestUrl.href
            }
        });
    }

    function buildSelfContainedOfflineDocument(requestUrl) {
        const en = isEnglishPath(requestUrl.pathname);
        const lang = en ? 'en-US' : 'zh-CN';
        const title = en ? 'Offline Mode - WindBlog' : '离线浏览模式 - WindBlog';
        const heading = en ? 'You are currently offline' : '当前处于离线浏览模式';
        const desc = en
            ? 'Your device is offline and this page is not in the local cache yet. You can read any cached article below or retry when back online.'
            : '当前设备未连接网络，且该页面尚未加入本地缓存。您可以直接阅读下方已缓存的文章，或在网络恢复后重试。';
        const cachedHeading = en ? 'Available Offline in Browser Cache' : '浏览器已缓存的可读内容';
        const retryLabel = en ? 'Retry Now' : '立即重试';
        const homeLabel = en ? 'Home' : '返回首页';
        const homeHref = en ? '/en-US/' : '/';

        const html = '<!DOCTYPE html><html lang="' + lang + '"><head><meta charset="UTF-8"/>'
            + '<meta name="viewport" content="width=device-width, initial-scale=1.0"/>'
            + '<meta name="robots" content="noindex,follow"/>'
            + '<title>' + escapeHtml(title) + '</title>'
            + '<link rel="stylesheet" href="/assets/css/theme.css"/>'
            + '<link rel="stylesheet" href="/assets/css/ui.css"/>'
            + '<style>'
            + 'body{margin:0;min-height:100vh;background:var(--bg,#0c0d10);color:var(--text-body,#a1a1aa);font-family:system-ui,-apple-system,sans-serif;display:flex;align-items:center;justify-content:center;padding:24px;}'
            + '.wb-offline-shell{max-width:680px;width:100%;background:var(--card-bg,#14161b);border:1px solid var(--border,rgba(255,255,255,0.08));border-radius:20px;padding:32px;box-shadow:0 20px 40px rgba(0,0,0,0.35);}'
            + '.wb-offline-badge{display:inline-flex;align-items:center;gap:8px;font-family:monospace;font-size:12px;color:var(--accent,#6366f1);margin-bottom:12px;}'
            + '.wb-offline-dot{width:8px;height:8px;border-radius:999px;background:#f59e0b;}'
            + 'h1{margin:0 0 12px;font-size:26px;color:var(--text-main,#f4f4f5);}'
            + 'p{margin:0 0 24px;line-height:1.65;font-size:15px;}'
            + '.wb-offline-actions{display:flex;gap:12px;flex-wrap:wrap;margin-bottom:28px;}'
            + '.wb-btn{padding:10px 18px;border-radius:10px;font-size:13px;font-weight:700;text-decoration:none;cursor:pointer;border:1px solid var(--border,rgba(255,255,255,0.12));background:transparent;color:var(--text-main,#f4f4f5);}'
            + '.wb-btn-primary{background:var(--accent,#6366f1);color:#fff;border-color:transparent;}'
            + '.wb-cached-section{border-top:1px solid var(--border,rgba(255,255,255,0.08));padding-top:20px;}'
            + '.wb-cached-title{font-size:13px;font-family:monospace;text-transform:uppercase;color:var(--text-main,#f4f4f5);margin:0 0 12px;}'
            + '.wb-cached-list{display:grid;gap:10px;}'
            + '.wb-cached-item{display:flex;justify-content:space-between;align-items:center;padding:12px 14px;border-radius:12px;border:1px solid var(--border,rgba(255,255,255,0.08));color:var(--text-main,#f4f4f5);text-decoration:none;font-size:14px;}'
            + '.wb-cached-item:hover{border-color:var(--accent,#6366f1);}'
            + '</style></head><body>'
            + '<main class="wb-offline-shell">'
            + '<div class="wb-offline-badge"><span class="wb-offline-dot"></span><span>OFFLINE MODE</span></div>'
            + '<h1>' + escapeHtml(heading) + '</h1>'
            + '<p>' + escapeHtml(desc) + '</p>'
            + '<div class="wb-offline-actions">'
            + '<button type="button" data-sw-offline-retry="true" class="wb-btn wb-btn-primary">' + escapeHtml(retryLabel) + '</button>'
            + '<a href="' + escapeHtml(homeHref) + '" class="wb-btn">' + escapeHtml(homeLabel) + '</a>'
            + '</div>'
            + '<div class="wb-cached-section">'
            + '<h2 class="wb-cached-title">' + escapeHtml(cachedHeading) + '</h2>'
            + '<div data-offline-cached-list="true" class="wb-cached-list"></div>'
            + '</div>'
            + '</main>'
            + '<script src="/assets/js/sw-register.js" defer></script>'
            + '</body></html>';

        return new Response(html, {
            status: 200,
            headers: {
                'Content-Type': 'text/html; charset=utf-8',
                'Cache-Control': 'no-store',
                'X-WindBlog-SW-Source': 'offline-fallback',
                'X-WindBlog-SW-Offline': 'true',
                'X-WindBlog-Resolved-Url': requestUrl.href
            }
        });
    }

    async function handleStaticAssetRequest(request, requestUrl) {
        const cache = await caches.open(STATIC_CACHE);
        const hasVersionParam = requestUrl.searchParams.has('v');
        const exactCached = await cache.match(requestUrl.href);

        const fetchAndUpdate = async function () {
            const response = await fetch(request);
            if (response && response.status === 200 && response.type !== 'opaque') {
                const cacheControl = (response.headers.get('Cache-Control') || '').toLowerCase();
                if (!cacheControl.includes('no-store') && !cacheControl.includes('private')) {
                    if (hasVersionParam) {
                        await purgeOlderVersionedAsset(cache, requestUrl);
                    }
                    await cache.put(requestUrl.href, response.clone());
                }
            }
            return response;
        };

        if (exactCached) {
            if (!hasVersionParam) {
                fetchAndUpdate().catch(function () {});
            }
            return exactCached;
        }

        if (!hasVersionParam) {
            const unversionedPrecache = await cache.match(requestUrl.pathname);
            if (unversionedPrecache) {
                fetchAndUpdate().catch(function () {});
                return unversionedPrecache;
            }
        }

        try {
            return await fetchAndUpdate();
        } catch (error) {
            const fallbackByPath = await cache.match(requestUrl.pathname);
            if (fallbackByPath) {
                return fallbackByPath;
            }
            throw error;
        }
    }

    async function handleMediaRequest(request, requestUrl) {
        const cache = await caches.open(MEDIA_CACHE);
        const cacheKey = requestUrl.origin + requestUrl.pathname;
        const cached = await cache.match(cacheKey);
        if (cached) {
            return cached;
        }

        try {
            const response = await fetch(request);
            if (!hasPrivateResponseIndicators(response, false)) {
                await cache.put(cacheKey, response.clone());
                trimCache(MEDIA_CACHE, MAX_MEDIA_ITEMS);
            }
            return response;
        } catch (_) {
            const stale = await cache.match(cacheKey);
            if (stale) {
                return stale;
            }
            return buildOfflineImagePlaceholder();
        }
    }

    async function handlePjaxRequest(request, requestUrl) {
        const isPrefetch = request.headers.get('X-WindBlog-Prefetch') === 'true';
        const cacheableRoute = isPublicCacheablePagePath(requestUrl.pathname);
        const pjaxCacheKey = buildPjaxCacheKey(requestUrl);
        const cache = await caches.open(PJAX_CACHE);

        if (cacheableRoute) {
            const cached = await cache.match(pjaxCacheKey);
            if (cached) {
                const cachedAt = Number(cached.headers.get('X-WindBlog-SW-Cached-At') || 0);
                const age = Date.now() - cachedAt;
                const isFreshPrefetch = cached.headers.get('X-WindBlog-SW-Prefetched') === 'true'
                    && age >= 0 && age <= PREFETCH_TTL_MS;

                if (isPrefetch && age >= 0 && age <= PREFETCH_TTL_MS) {
                    return cloneResponseWithHeaders(cached, {
                        'X-WindBlog-SW-Source': 'prefetch-already-warm',
                        'X-WindBlog-Resolved-Url': cached.headers.get('X-WindBlog-Resolved-Url') || requestUrl.href
                    });
                }

                if (!isPrefetch && isFreshPrefetch) {
                    // Consume the fresh prefetched fragment immediately for <2ms FLIP
                    // transition startup, and refresh the entry asynchronously.
                    fetchAndCachePjax(request, requestUrl, cache, pjaxCacheKey, false).catch(function () {});
                    return cloneResponseWithHeaders(cached, {
                        'X-WindBlog-SW-Source': 'prefetch-cache',
                        'X-WindBlog-Resolved-Url': cached.headers.get('X-WindBlog-Resolved-Url') || requestUrl.href
                    });
                }
            }
        }

        try {
            return await fetchAndCachePjax(request, requestUrl, cache, pjaxCacheKey, isPrefetch);
        } catch (networkError) {
            if (isPrefetch) {
                throw networkError;
            }
            const cachedFragment = await cache.match(pjaxCacheKey);
            if (cachedFragment) {
                return cloneResponseWithHeaders(cachedFragment, {
                    'X-WindBlog-SW-Source': 'offline-cache',
                    'X-WindBlog-SW-Offline': 'true',
                    'X-WindBlog-Resolved-Url': cachedFragment.headers.get('X-WindBlog-Resolved-Url') || requestUrl.href
                });
            }

            const pagesCache = await caches.open(PAGES_CACHE);
            const cachedFullPage = await pagesCache.match(buildPageCacheKey(requestUrl));
            if (cachedFullPage) {
                return cloneResponseWithHeaders(cachedFullPage, {
                    'X-WindBlog-SW-Source': 'offline-page-cache',
                    'X-WindBlog-SW-Offline': 'true',
                    'X-WindBlog-Resolved-Url': cachedFullPage.headers.get('X-WindBlog-Resolved-Url') || requestUrl.href
                });
            }

            return buildOfflinePjaxFragment(requestUrl);
        }
    }

    async function fetchAndCachePjax(request, requestUrl, cache, pjaxCacheKey, isPrefetch) {
        const response = await fetchWithTimeout(request, NETWORK_TIMEOUT_MS);
        const contentType = (response.headers.get('Content-Type') || '').toLowerCase();
        const resolvedUrl = response.url || requestUrl.href;
        const resolvedParsed = new URL(resolvedUrl, self.location.origin);

        if (isSameOrigin(resolvedParsed)
            && isPublicCacheablePagePath(resolvedParsed.pathname)
            && contentType.includes('text/html')
            && !hasPrivateResponseIndicators(response, true)) {
            const stamped = await cloneResponseWithHeaders(response, {
                'X-WindBlog-SW-Cached-At': String(Date.now()),
                'X-WindBlog-SW-Prefetched': isPrefetch ? 'true' : 'false',
                'X-WindBlog-Resolved-Url': resolvedUrl
            });
            const effectiveKey = buildPjaxCacheKey(resolvedParsed);
            await cache.put(effectiveKey, stamped.clone());
            if (effectiveKey !== pjaxCacheKey) {
                await cache.put(pjaxCacheKey, stamped.clone());
            }
            trimCache(PJAX_CACHE, MAX_PJAX_ITEMS);
            return cloneResponseWithHeaders(stamped, {
                'X-WindBlog-SW-Source': isPrefetch ? 'prefetch-network' : 'network',
                'X-WindBlog-Resolved-Url': resolvedUrl
            });
        }

        return cloneResponseWithHeaders(response, {
            'X-WindBlog-SW-Source': 'network',
            'X-WindBlog-Resolved-Url': resolvedUrl
        });
    }

    async function trySynthesizePageFromShellAndPjax(requestUrl, pagesCache, pjaxCache) {
        const cachedPjax = await pjaxCache.match(buildPjaxCacheKey(requestUrl));
        if (!cachedPjax) {
            return null;
        }
        const fragmentHtml = await cachedPjax.text();
        if (!fragmentHtml || !fragmentHtml.includes('id="pjax-content-root"')) {
            return null;
        }

        const pageKeys = await pagesCache.keys();
        let shellHtml = null;
        for (const req of pageKeys) {
            const reqUrl = new URL(req.url);
            if (normalizePath(reqUrl.pathname) === '/offline') {
                continue;
            }
            const candidate = await pagesCache.match(req);
            if (!candidate) {
                continue;
            }
            const text = await candidate.text();
            if (text.includes(PJAX_SHELL_START_MARKER) && text.includes(PJAX_SHELL_END_MARKER)) {
                shellHtml = text;
                break;
            }
        }
        if (!shellHtml) {
            return null;
        }

        const startIdx = shellHtml.indexOf(PJAX_SHELL_START_MARKER);
        const endIdx = shellHtml.indexOf(PJAX_SHELL_END_MARKER);
        if (startIdx < 0 || endIdx <= startIdx) {
            return null;
        }

        const fragmentTitle = extractTitleFromHtml(fragmentHtml, requestUrl.pathname);
        let synthesized = shellHtml.slice(0, startIdx + PJAX_SHELL_START_MARKER.length)
            + '\n' + fragmentHtml + '\n'
            + shellHtml.slice(endIdx);
        synthesized = synthesized.replace(
            /<title[^>]*>[\s\S]*?<\/title>/i,
            '<title>' + escapeHtml(fragmentTitle) + '</title>'
        );

        return new Response(synthesized, {
            status: 200,
            headers: {
                'Content-Type': 'text/html; charset=utf-8',
                'Cache-Control': 'no-store',
                'X-WindBlog-SW-Source': 'offline-synthesized',
                'X-WindBlog-SW-Offline': 'true',
                'X-WindBlog-Resolved-Url': requestUrl.href
            }
        });
    }

    async function handleNavigateRequest(event, request, requestUrl) {
        if (requestUrl.searchParams.has('lang')) {
            await clearDynamicCaches();
            return fetch(request);
        }

        const pagesCache = await caches.open(PAGES_CACHE);
        const pageCacheKey = buildPageCacheKey(requestUrl);

        try {
            const preloadResponse = await event.preloadResponse;
            const response = preloadResponse || await fetchWithTimeout(request, NETWORK_TIMEOUT_MS);
            const contentType = (response.headers.get('Content-Type') || '').toLowerCase();
            const resolvedUrl = response.url || requestUrl.href;
            const resolvedParsed = new URL(resolvedUrl, self.location.origin);

            if (isSameOrigin(resolvedParsed)
                && isPublicCacheablePagePath(resolvedParsed.pathname)
                && contentType.includes('text/html')
                && !hasPrivateResponseIndicators(response, false)) {
                const stamped = await cloneResponseWithHeaders(response, {
                    'X-WindBlog-SW-Cached-At': String(Date.now()),
                    'X-WindBlog-Resolved-Url': resolvedUrl
                });
                await pagesCache.put(buildPageCacheKey(resolvedParsed), stamped.clone());
                trimCache(PAGES_CACHE, MAX_PAGES_ITEMS);
            }
            return response;
        } catch (_) {
            const exactPage = await pagesCache.match(pageCacheKey)
                || await pagesCache.match(requestUrl.origin + normalizePath(requestUrl.pathname));
            if (exactPage) {
                return cloneResponseWithHeaders(exactPage, {
                    'X-WindBlog-SW-Source': 'offline-cache',
                    'X-WindBlog-SW-Offline': 'true',
                    'X-WindBlog-Resolved-Url': exactPage.headers.get('X-WindBlog-Resolved-Url') || requestUrl.href
                });
            }

            const pjaxCache = await caches.open(PJAX_CACHE);
            const synthesized = await trySynthesizePageFromShellAndPjax(requestUrl, pagesCache, pjaxCache);
            if (synthesized) {
                return synthesized;
            }

            const cachedOfflinePage = await pagesCache.match(requestUrl.origin + '/offline');
            if (cachedOfflinePage) {
                return cloneResponseWithHeaders(cachedOfflinePage, {
                    'X-WindBlog-SW-Source': 'offline-fallback',
                    'X-WindBlog-SW-Offline': 'true',
                    'X-WindBlog-Resolved-Url': requestUrl.href
                });
            }

            return buildSelfContainedOfflineDocument(requestUrl);
        }
    }

    async function handlePublicReadApiRequest(request, requestUrl) {
        const cache = await caches.open(API_CACHE);
        const cacheKey = requestUrl.origin + requestUrl.pathname + requestUrl.search;

        try {
            const response = await fetchWithTimeout(request, 5000);
            if (response && response.status === 200 && response.type !== 'opaque') {
                const stamped = await cloneResponseWithHeaders(response, {
                    'X-WindBlog-SW-Cached-At': String(Date.now())
                });
                await cache.put(cacheKey, stamped.clone());
                trimCache(API_CACHE, MAX_API_ITEMS);
                return response;
            }
            return response;
        } catch (error) {
            const cached = await cache.match(cacheKey);
            if (cached) {
                return cloneResponseWithHeaders(cached, {
                    'X-WindBlog-SW-Source': 'offline-cache',
                    'X-WindBlog-SW-Offline': 'true'
                });
            }
            throw error;
        }
    }

    async function collectCachedArticles() {
        const itemsByUrl = new Map();

        async function scanCache(cacheName, isPjax) {
            const cache = await caches.open(cacheName);
            const keys = await cache.keys();
            for (const req of keys) {
                try {
                    const res = await cache.match(req);
                    if (!res) continue;
                    let rawUrl = res.headers.get('X-WindBlog-Resolved-Url') || req.url;
                    rawUrl = rawUrl.replace('/__wb_sw_pjax__', '');
                    const parsed = new URL(rawUrl, self.location.origin);
                    const plainPath = stripLangPrefix(parsed.pathname);
                    if (plainPath === '/offline' || plainPath.startsWith('/__wb_sw_pjax__')) {
                        continue;
                    }
                    const html = await res.text();
                    const title = extractTitleFromHtml(html, parsed.pathname);
                    const cachedAt = Number(res.headers.get('X-WindBlog-SW-Cached-At') || 0);
                    const kind = /^\/post\/[^/]+$/.test(plainPath) ? 'post' : 'page';
                    const key = parsed.pathname + parsed.search;
                    const existing = itemsByUrl.get(key);
                    if (!existing || cachedAt > existing.cachedAt || (!isPjax && existing.source === 'pjax')) {
                        itemsByUrl.set(key, {
                            url: key,
                            title: title,
                            kind: kind,
                            cachedAt: cachedAt,
                            source: isPjax ? 'pjax' : 'page'
                        });
                    }
                } catch (_) {
                    // Skip unreadable cache entries.
                }
            }
        }

        await scanCache(PAGES_CACHE, false);
        await scanCache(PJAX_CACHE, true);

        return Array.from(itemsByUrl.values()).sort(function (a, b) {
            if (a.kind !== b.kind) {
                return a.kind === 'post' ? -1 : 1;
            }
            return (b.cachedAt || 0) - (a.cachedAt || 0);
        });
    }

    async function getServiceWorkerStatus() {
        const counts = {};
        for (const [label, cacheName] of [
            ['static', STATIC_CACHE],
            ['media', MEDIA_CACHE],
            ['pjax', PJAX_CACHE],
            ['pages', PAGES_CACHE],
            ['api', API_CACHE]
        ]) {
            try {
                const cache = await caches.open(cacheName);
                const keys = await cache.keys();
                counts[label] = keys.length;
            } catch (_) {
                counts[label] = 0;
            }
        }
        return {
            version: CACHE_VERSION,
            caches: counts
        };
    }

    async function prefetchUrlsInBackground(urls) {
        if (!Array.isArray(urls) || urls.length === 0) {
            return;
        }
        const pjaxCache = await caches.open(PJAX_CACHE);
        const mediaCache = await caches.open(MEDIA_CACHE);

        for (const rawUrl of urls.slice(0, 6)) {
            if (typeof rawUrl !== 'string' || !rawUrl.trim()) continue;
            try {
                const targetUrl = new URL(rawUrl, self.location.origin);
                if (!isSameOrigin(targetUrl)) continue;

                if (isPublicMediaPath(targetUrl.pathname)) {
                    const mediaKey = targetUrl.origin + targetUrl.pathname;
                    const existingMedia = await mediaCache.match(mediaKey);
                    if (!existingMedia) {
                        const mediaRes = await fetch(targetUrl.href, { credentials: 'same-origin' });
                        if (!hasPrivateResponseIndicators(mediaRes, false)) {
                            await mediaCache.put(mediaKey, mediaRes);
                            await trimCache(MEDIA_CACHE, MAX_MEDIA_ITEMS);
                        }
                    }
                    continue;
                }

                if (isPublicCacheablePagePath(targetUrl.pathname)) {
                    const pjaxKey = buildPjaxCacheKey(targetUrl);
                    const existingPjax = await pjaxCache.match(pjaxKey);
                    if (existingPjax) {
                        const cachedAt = Number(existingPjax.headers.get('X-WindBlog-SW-Cached-At') || 0);
                        if (Date.now() - cachedAt <= PREFETCH_TTL_MS) {
                            continue;
                        }
                    }
                    const pjaxReq = new Request(targetUrl.href, {
                        method: 'GET',
                        credentials: 'same-origin',
                        headers: {
                            'Accept': 'text/html',
                            'X-PJAX': 'true',
                            'X-PJAX-Container': '#pjax-container',
                            'X-WindBlog-Prefetch': 'true'
                        }
                    });
                    await fetchAndCachePjax(pjaxReq, targetUrl, pjaxCache, pjaxKey, true);
                }
            } catch (_) {
                // Prefetch failures are best-effort and silent.
            }
        }
    }

    self.addEventListener('install', function (event) {
        event.waitUntil((async function () {
            const staticCache = await caches.open(STATIC_CACHE);
            await Promise.allSettled(
                PRECACHE_STATIC_URLS.map(async function (url) {
                    const response = await fetch(url, { cache: 'reload', credentials: 'same-origin' });
                    if (response && response.status === 200) {
                        await staticCache.put(new URL(url, self.location.origin).href, response);
                    }
                })
            );

            const pagesCache = await caches.open(PAGES_CACHE);
            await Promise.allSettled(
                PRECACHE_PAGE_URLS.map(async function (url) {
                    const response = await fetch(url, { cache: 'reload', credentials: 'same-origin' });
                    if (response && response.status === 200) {
                        const fullUrl = new URL(url, self.location.origin).href;
                        const stamped = await cloneResponseWithHeaders(response, {
                            'X-WindBlog-SW-Cached-At': String(Date.now()),
                            'X-WindBlog-Resolved-Url': fullUrl
                        });
                        await pagesCache.put(fullUrl, stamped);
                    }
                })
            );

            await self.skipWaiting();
        })());
    });

    self.addEventListener('activate', function (event) {
        event.waitUntil((async function () {
            const keys = await caches.keys();
            await Promise.allSettled(
                keys.map(function (key) {
                    if (key.startsWith(CACHE_PREFIX) && !ACTIVE_CACHES.has(key)) {
                        return caches.delete(key);
                    }
                    return Promise.resolve();
                })
            );

            if (self.registration && self.registration.navigationPreload) {
                try {
                    await self.registration.navigationPreload.enable();
                } catch (_) {
                    // Navigation preload is optional.
                }
            }

            await self.clients.claim();
        })());
    });

    self.addEventListener('fetch', function (event) {
        const request = event.request;
        if (!request) return;

        let requestUrl;
        try {
            requestUrl = new URL(request.url);
        } catch (_) {
            return;
        }

        if (!isSameOrigin(requestUrl)) {
            return;
        }

        if (request.method !== 'GET') {
            if (isStateMutatingAuthOrUserPath(requestUrl.pathname)) {
                event.waitUntil(clearDynamicCaches());
            }
            return;
        }

        if (request.headers.has('Authorization') || prefersMarkdown(request)) {
            return;
        }

        if (isPrivateOrSystemPath(requestUrl.pathname)) {
            return;
        }

        if (isStaticAssetPath(requestUrl.pathname)) {
            event.respondWith(handleStaticAssetRequest(request, requestUrl));
            return;
        }

        if (isPublicMediaPath(requestUrl.pathname)) {
            event.respondWith(handleMediaRequest(request, requestUrl));
            return;
        }

        if (request.headers.get('X-PJAX') === 'true') {
            event.respondWith(handlePjaxRequest(request, requestUrl));
            return;
        }

        if (isPublicReadApiPath(requestUrl.pathname)) {
            event.respondWith(handlePublicReadApiRequest(request, requestUrl));
            return;
        }

        if (request.mode === 'navigate' && isPublicCacheablePagePath(requestUrl.pathname)) {
            event.respondWith(handleNavigateRequest(event, request, requestUrl));
        }
    });

    self.addEventListener('message', function (event) {
        const data = event.data;
        if (!data || typeof data.type !== 'string') {
            return;
        }

        const reply = function (payload) {
            if (event.ports && event.ports[0]) {
                event.ports[0].postMessage(payload);
            } else if (event.source && typeof event.source.postMessage === 'function') {
                event.source.postMessage(payload);
            }
        };

        if (data.type === 'SKIP_WAITING') {
            self.skipWaiting();
            reply({ ok: true });
            return;
        }

        if (data.type === 'CLEAR_DYNAMIC_CACHES') {
            event.waitUntil(
                clearDynamicCaches().then(function () {
                    reply({ ok: true, type: 'DYNAMIC_CACHES_CLEARED' });
                })
            );
            return;
        }

        if (data.type === 'PREFETCH_URLS') {
            event.waitUntil(
                prefetchUrlsInBackground(data.urls).then(function () {
                    reply({ ok: true, type: 'PREFETCH_DONE' });
                })
            );
            return;
        }

        if (data.type === 'GET_CACHED_ARTICLES') {
            event.waitUntil(
                collectCachedArticles().then(function (articles) {
                    reply({ ok: true, type: 'CACHED_ARTICLES', articles: articles });
                })
            );
            return;
        }

        if (data.type === 'GET_SW_STATUS') {
            event.waitUntil(
                getServiceWorkerStatus().then(function (status) {
                    reply({ ok: true, type: 'SW_STATUS', status: status });
                })
            );
        }
    });
})();
