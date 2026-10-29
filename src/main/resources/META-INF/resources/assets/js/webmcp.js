/* WindBlog WebMCP tools. Loaded once by the shared page shell, including PJAX pages. */
(() => {
    'use strict';
    if (window.windblogWebMcpLoaded) return;
    window.windblogWebMcpLoaded = true;
    let lifetime;
    const schema = (properties = {}, required = []) => ({type: 'object', properties, required, additionalProperties: false});
    const slugSchema = {type: 'string', minLength: 1, maxLength: 200};
    const pageSchema = {type: 'integer', minimum: 1, maximum: 1000, default: 1};
    const langSchema = {type: 'string', minLength: 2, maxLength: 30};

    function text(value, label, max = 200) {
        if (typeof value !== 'string' || !value.trim() || value.length > max) throw new Error(`Invalid ${label}`);
        return value.trim();
    }
    function page(value = 1, minimum = 1) {
        if (!Number.isInteger(value) || value < minimum || value > 1000) throw new Error('Invalid page');
        return value;
    }
    function postPath(slug) {
        const value = text(slug, 'slug');
        if (value === '.' || value === '..' || /[\\/]/.test(value)) throw new Error('Invalid slug');
        return '/post/' + encodeURIComponent(value);
    }
    function getAdminToken() {
        try {
            return (typeof localStorage !== 'undefined' && (
                localStorage.getItem('windblog_admin_token') ||
                localStorage.getItem('admin_auth_token') ||
                localStorage.getItem('token')
            )) || '';
        } catch (_) { return ''; }
    }

    async function retrieve(path, options = {}, json = false) {
        const signal = options.signal ? AbortSignal.any([options.signal, lifetime.signal]) : lifetime.signal;
        const response = await fetch(path, {credentials: 'omit', signal,
            headers: {Accept: json ? 'application/json' : 'text/html'}});
        if (!response.ok) throw new Error(`Public request failed (${response.status})`);
        const url = new URL(response.url || path, location.origin);
        if (url.origin !== location.origin) throw new Error('Unexpected external redirect');
        if (json) return response.json();
        const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
        const content = doc.querySelector('#pjax-container') || doc.querySelector('main');
        if (!content) throw new Error('Public content not found');
        content.querySelectorAll('script, style, form, nav').forEach(node => node.remove());
        const links = [...content.querySelectorAll('a[href]')].map(link => {
            const target = new URL(link.getAttribute('href'), url);
            return {title: link.textContent.trim(), url: target.href};
        }).filter(link => new URL(link.url).origin === location.origin && /\/(post|category|tag)\//.test(new URL(link.url).pathname));
        return {url: url.href, title: doc.title, text: content.textContent.trim().slice(0, 30000), links: links.slice(0, 100)};
    }

    async function apiRequest(path, options = {}) {
        const signal = options.signal ? AbortSignal.any([options.signal, lifetime.signal]) : lifetime.signal;
        const headers = {'Accept': 'application/json', ...(options.headers || {})};
        const adminToken = getAdminToken();
        if (adminToken && !headers['Authorization']) {
            headers['Authorization'] = 'Bearer ' + adminToken;
        }
        let body = options.body;
        if (body && typeof body === 'object') {
            headers['Content-Type'] = 'application/json';
            body = JSON.stringify(body);
        }
        const response = await fetch(path, {
            method: options.method || 'GET',
            credentials: 'include',
            signal,
            headers,
            body
        });
        const url = new URL(response.url || path, location.origin);
        if (url.origin !== location.origin) throw new Error('Unexpected external redirect');
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(data.message || `Request failed (${response.status})`);
        }
        return data;
    }

    function extractCurrentArticle() {
        if (typeof document === 'undefined') return null;
        const pathname = (typeof location !== 'undefined' ? location.pathname : '') || '';
        const match = pathname.match(/(?:^|\/)(?:[a-zA-Z0-9_-]+\/)?post\/([^/?#]+)/);
        const slug = match ? decodeURIComponent(match[1]) : '';
        const titleEl = document.querySelector('h1') || document.querySelector('article h1') || document.querySelector('.post-title');
        const title = (titleEl ? titleEl.textContent : (document.title || '')).trim();
        const articleEl = document.querySelector('article') || document.querySelector('.prose') || document.querySelector('#pjax-container');
        const contentText = articleEl ? articleEl.textContent.trim() : '';
        const postIdMeta = document.querySelector('meta[name="post-id"]') || document.querySelector('[data-post-id]');
        const postId = postIdMeta ? (postIdMeta.getAttribute('content') || postIdMeta.getAttribute('data-post-id')) : null;
        const lang = document.documentElement?.lang || 'zh-CN';
        return {
            slug,
            postId: postId ? parseInt(postId, 10) : null,
            title,
            contentMarkdown: contentText,
            sourceLanguage: lang
        };
    }

    function definitions() {
        return [
            {name: 'search_posts', description: 'Search published WindBlog articles by keyword and retrieve the public result page.',
                inputSchema: schema({query: {type: 'string', minLength: 1, maxLength: 200}, page: pageSchema}, ['query']),
                execute: async (input, options) => retrieve('/search?' + new URLSearchParams({q: text(input.query, 'query'), page: page(input.page)}), options)},
            {name: 'get_post', description: 'Retrieve the public article text and related links by its slug. Restricted content remains subject to site access rules.',
                inputSchema: schema({slug: slugSchema}, ['slug']), execute: async (input, options) => retrieve(postPath(input.slug), options)},
            {name: 'get_post_comments', description: 'Retrieve approved public comments for a published article. Page numbering starts at zero.',
                inputSchema: schema({slug: slugSchema, page: {...pageSchema, minimum: 0, default: 0}}, ['slug']),
                execute: async (input, options) => retrieve('/api/comments/post/' + postPath(input.slug).split('/').pop() + '?' + new URLSearchParams({page: page(input.page ?? 0, 0), size: 10}), options, true)},
            {name: 'browse_site', description: 'Retrieve a public page of recent articles, categories, or tags.',
                inputSchema: schema({section: {type: 'string', enum: ['home', 'categories', 'tags']}, page: pageSchema}, ['section']),
                execute: async (input, options) => {
                    const paths = {home: '/', categories: '/category', tags: '/tag'};
                    if (!Object.hasOwn(paths, input.section)) throw new Error('Invalid section');
                    return retrieve(paths[input.section] + '?' + new URLSearchParams({page: page(input.page)}), options);
                }},
            {name: 'navigate_site', description: 'Navigate the browser to the public homepage, category list, tag list, or an article by slug.',
                inputSchema: schema({section: {type: 'string', enum: ['home', 'categories', 'tags', 'post']}, slug: slugSchema}, ['section']),
                execute: async input => {
                    const paths = {home: '/', categories: '/category', tags: '/tag'};
                    const path = input.section === 'post' ? postPath(input.slug) : paths[input.section];
                    if (!path || !Object.hasOwn({...paths, post: true}, input.section)) throw new Error('Invalid section');
                    const url = new URL(path, location.origin).href;
                    setTimeout(() => { if (!lifetime.signal.aborted) location.assign(url); }, 100);
                    return {url};
                }},
            {name: 'get_session_status', description: 'Check the current login session in WindBlog using browser credentials. Returns user details, role, and authentication status.',
                inputSchema: schema(),
                execute: async (input, options) => {
                    const adminToken = getAdminToken();
                    try {
                        const userRes = await apiRequest('/user/api/profile', options);
                        if (userRes && (userRes.success || userRes.data)) {
                            return {authenticated: true, type: 'user', user: userRes.data || userRes};
                        }
                    } catch (_) {}
                    if (adminToken) {
                        return {authenticated: true, type: 'admin', hasAdminToken: true};
                    }
                    return {authenticated: false, type: 'anonymous'};
                }},
            {name: 'get_article_for_translation', description: 'Retrieve article title, summary, markdown content, and language for translation. If no slug/postId is provided, extracts from the current viewing article page.',
                inputSchema: schema({slug: {type: 'string'}, postId: {type: 'integer'}}, []),
                execute: async (input, options) => {
                    const current = extractCurrentArticle();
                    const targetSlug = input.slug || current?.slug;
                    const targetPostId = input.postId || current?.postId;
                    let title = current?.title || '';
                    let contentMarkdown = current?.contentMarkdown || '';
                    let summary = '';
                    let sourceLanguage = current?.sourceLanguage || 'zh-CN';

                    if (targetSlug && (!title || !contentMarkdown || input.slug)) {
                        try {
                            const publicPost = await retrieve(postPath(targetSlug), options);
                            title = title || publicPost.title;
                            contentMarkdown = contentMarkdown || publicPost.text;
                        } catch (_) {}
                    }
                    return {
                        postId: targetPostId,
                        slug: targetSlug,
                        title,
                        summary,
                        contentMarkdown,
                        sourceLanguage
                    };
                }},
            {name: 'translate_article', description: 'Translate article title, summary, and markdown content to targetLanguage using the active login session and WindBlog AI translation service.',
                inputSchema: schema({
                    sourceLanguage: langSchema,
                    targetLanguage: langSchema,
                    postId: {type: 'integer'},
                    slug: {type: 'string'},
                    title: {type: 'string'},
                    summary: {type: 'string'},
                    contentMarkdown: {type: 'string'}
                }, ['sourceLanguage', 'targetLanguage']),
                execute: async (input, options) => {
                    const current = extractCurrentArticle();
                    const title = (input.title || current?.title || '').trim();
                    const summary = (input.summary || '').trim();
                    const contentMarkdown = (input.contentMarkdown || current?.contentMarkdown || '').trim();
                    const postId = input.postId || current?.postId;
                    if (!title) throw new Error('Article title is required for translation');
                    if (!contentMarkdown) throw new Error('Article content is required for translation');

                    const payload = {
                        sourceLanguage: text(input.sourceLanguage, 'sourceLanguage'),
                        targetLanguage: text(input.targetLanguage, 'targetLanguage'),
                        title,
                        summary,
                        contentMarkdown
                    };

                    const adminToken = getAdminToken();
                    if (adminToken && postId) {
                        return apiRequest('/api/admin/posts/' + postId + '/translation', {method: 'POST', body: payload, signal: options.signal});
                    } else if (adminToken) {
                        return apiRequest('/api/admin/posts/translate', {method: 'POST', body: payload, signal: options.signal});
                    } else {
                        const res = await apiRequest('/user/api/posts/translate', {method: 'POST', body: payload, signal: options.signal});
                        return res.data || res;
                    }
                }},
            {name: 'save_article_translation', description: 'Save a translated edition (targetLanguage, title, summary, contentMarkdown) of an article back to WindBlog using the authenticated session.',
                inputSchema: schema({
                    postId: {type: 'integer'},
                    targetLanguage: langSchema,
                    title: {type: 'string', minLength: 1},
                    summary: {type: 'string'},
                    contentMarkdown: {type: 'string', minLength: 1}
                }, ['postId', 'targetLanguage', 'title', 'contentMarkdown']),
                execute: async (input, options) => {
                    const postId = input.postId;
                    if (!Number.isInteger(postId) || postId <= 0) throw new Error('Invalid postId');
                    const payload = {
                        targetLanguage: text(input.targetLanguage, 'targetLanguage'),
                        title: text(input.title, 'title', 500),
                        summary: input.summary || '',
                        contentMarkdown: text(input.contentMarkdown, 'contentMarkdown', 5000000)
                    };
                    const adminToken = getAdminToken();
                    if (adminToken) {
                        return apiRequest('/api/admin/posts/' + postId + '/translation', {method: 'PUT', body: payload, signal: options.signal});
                    } else {
                        const res = await apiRequest('/user/api/posts/' + postId + '/translation', {method: 'PUT', body: payload, signal: options.signal});
                        return res.data || res;
                    }
                }}
        ];
    }
    function register() {
        const context = navigator.modelContext || document.modelContext;
        if (!context || typeof context.registerTool !== 'function' || (lifetime && !lifetime.signal.aborted)) return;
        lifetime = new AbortController();
        for (const tool of definitions()) {
            try {
                const result = navigator.modelContext
                    ? navigator.modelContext.registerTool(tool, {signal: lifetime.signal})
                    : document.modelContext.registerTool(tool, {signal: lifetime.signal});
                Promise.resolve(result).catch(error => console.warn('WebMCP registration failed:', tool.name, error));
            } catch (error) { console.warn('WebMCP registration failed:', tool.name, error); }
        }
    }
    window.addEventListener('pagehide', () => lifetime?.abort());
    window.addEventListener('pageshow', register);
    register();
})();
