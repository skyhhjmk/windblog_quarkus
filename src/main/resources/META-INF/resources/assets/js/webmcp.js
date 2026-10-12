/* Public browser-agent tools. Loaded once by the shared page shell, including PJAX pages. */
(() => {
    'use strict';
    if (window.windblogWebMcpLoaded) return;
    window.windblogWebMcpLoaded = true;
    let lifetime;
    const schema = (properties = {}, required = []) => ({type: 'object', properties, required, additionalProperties: false});
    const slugSchema = {type: 'string', minLength: 1, maxLength: 200};
    const pageSchema = {type: 'integer', minimum: 1, maximum: 1000, default: 1};
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
                    // Allow the tool result to reach the agent before unloading this document.
                    setTimeout(() => { if (!lifetime.signal.aborted) location.assign(url); }, 100);
                    return {url};
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
