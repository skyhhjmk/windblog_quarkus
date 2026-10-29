const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/webmcp.js', 'utf8');

function harness(entry = 'navigator', options = {}) {
    const tools = new Map(), events = {}, requests = [], destinations = [];
    const modelContext = {registerTool(tool, {signal}) {
        assert.equal(signal.aborted, false);
        assert.equal(typeof tool.execute, 'function');
        tools.set(tool.name, tool);
        signal.addEventListener('abort', () => tools.delete(tool.name));
    }};
    const store = options.localStorage || {};
    const mockLocalStorage = {
        getItem: key => store[key] || null,
        setItem: (key, val) => { store[key] = String(val); },
        removeItem: key => { delete store[key]; }
    };
    const context = {
        AbortController, AbortSignal, URL, URLSearchParams, console, setTimeout: fn => fn(),
        navigator: {},
        document: {
            title: 'Mock Page Title',
            querySelector: sel => {
                if (sel === 'h1' || sel === '.post-title') return {textContent: 'Current Post Title'};
                if (sel === 'article' || sel === '.prose') return {textContent: 'Current article markdown body text'};
                if (sel === 'meta[name="post-id"]') return {getAttribute: () => '42'};
                return null;
            },
            querySelectorAll: () => [],
            documentElement: {lang: 'zh-CN'}
        },
        location: {origin: 'https://example.com', pathname: '/post/current-post', assign: url => destinations.push(url)},
        window: {addEventListener: (name, fn) => events[name] = fn},
        localStorage: mockLocalStorage,
        DOMParser: class {
            parseFromString() {
                return {
                    title: 'Parsed Title',
                    querySelector: () => ({
                        querySelectorAll: () => [],
                        textContent: 'Parsed Content'
                    })
                };
            }
        },
        fetch: async (url, fetchOpts = {}) => {
            requests.push({url, options: fetchOpts});
            if (options.fetchHandler) {
                return options.fetchHandler(url, fetchOpts);
            }
            if (url.includes('/api/comments/')) {
                return {ok: true, url: 'https://example.com' + url, json: async () => ({comments: []})};
            }
            if (url.includes('/user/api/profile')) {
                return {ok: true, url: 'https://example.com' + url, json: async () => ({success: true, data: {id: 1, username: 'testuser', roleName: 'USER'}})};
            }
            if (url.includes('/translate')) {
                return {ok: true, url: 'https://example.com' + url, json: async () => ({
                    success: true,
                    data: {sourceLanguage: 'zh-cn', targetLanguage: 'en-us', title: 'Translated Title', summary: '', contentMarkdown: 'Translated Content'}
                })};
            }
            if (url.includes('/translation')) {
                return {ok: true, url: 'https://example.com' + url, json: async () => ({
                    id: 42,
                    sourceLanguage: 'zh-cn', targetLanguage: 'en-us', title: 'Translated Title', summary: '', contentMarkdown: 'Translated Content'
                })};
            }
            return {ok: true, url: 'https://example.com' + url, text: async () => '<html><body><main>Mock post body</main></body></html>', json: async () => ({})};
        }
    };
    if (entry) context[entry].modelContext = modelContext;
    vm.createContext(context);
    vm.runInContext(source, context);
    return {tools, events, requests, destinations, context};
}

test('registers on load, survives PJAX and restores after pagehide/pageshow without duplicates', () => {
    for (const entry of ['navigator', 'document']) {
        const h = harness(entry);
        assert.equal(h.tools.size, 9);
        h.events.pageshow();
        assert.equal(h.tools.size, 9);
        vm.runInContext(source, h.context);
        assert.equal(h.tools.size, 9);
        h.events.pagehide();
        assert.equal(h.tools.size, 0);
        h.events.pageshow();
        assert.equal(h.tools.size, 9);
    }
    assert.equal(harness(null).tools.size, 0);
});

test('public comments omit credentials and propagate execution cancellation', async () => {
    const h = harness(), controller = new AbortController();
    await h.tools.get('get_post_comments').execute({slug: 'hello world', page: 0}, {signal: controller.signal});
    assert.equal(h.requests[0].url, '/api/comments/post/hello%20world?page=0&size=10');
    assert.equal(h.requests[0].options.credentials, 'omit');
    controller.abort();
    assert.equal(h.requests[0].options.signal.aborted, true);
    await h.tools.get('get_post_comments').execute({slug: 'hello'}, {});
    h.events.pagehide();
    assert.equal(h.requests[1].options.signal.aborted, true);
});

test('navigation is restricted to known public routes and validates inputs', async () => {
    const h = harness();
    await h.tools.get('navigate_site').execute({section: 'post', slug: 'hello'});
    assert.deepEqual(h.destinations, ['https://example.com/post/hello']);
    for (const slug of ['..', '../admin', 'a/b', 'a\\b', '']) {
        await assert.rejects(h.tools.get('get_post').execute({slug}), /Invalid/);
    }
    await assert.rejects(h.tools.get('navigate_site').execute({section: '__proto__'}), /Invalid/);
    await assert.rejects(h.tools.get('search_posts').execute({query: 'hello', page: -1}), /Invalid/);
    await assert.rejects(h.tools.get('browse_site').execute({section: 'admin'}), /Invalid/);
});

test('get_session_status reports active login session', async () => {
    const h = harness();
    const status = await h.tools.get('get_session_status').execute({}, {});
    assert.equal(status.authenticated, true);
    assert.equal(status.type, 'user');
    assert.equal(status.user.username, 'testuser');
});

test('get_article_for_translation infers from active document if no args provided', async () => {
    const h = harness();
    const article = await h.tools.get('get_article_for_translation').execute({}, {});
    assert.equal(article.slug, 'current-post');
    assert.equal(article.title, 'Current Post Title');
    assert.equal(article.postId, 42);
    assert.equal(article.sourceLanguage, 'zh-CN');
});

test('translate_article calls user translation endpoint using login credentials', async () => {
    const h = harness();
    const result = await h.tools.get('translate_article').execute({
        sourceLanguage: 'zh-CN',
        targetLanguage: 'en-US'
    }, {});
    assert.equal(result.targetLanguage, 'en-us');
    assert.equal(result.title, 'Translated Title');

    const lastReq = h.requests.find(r => r.url === '/user/api/posts/translate');
    assert.ok(lastReq);
    assert.equal(lastReq.options.credentials, 'include');
    const body = JSON.parse(lastReq.options.body);
    assert.equal(body.sourceLanguage, 'zh-CN');
    assert.equal(body.targetLanguage, 'en-US');
    assert.equal(body.title, 'Current Post Title');
});

test('save_article_translation sends translated version to blog using session credentials', async () => {
    const h = harness();
    const result = await h.tools.get('save_article_translation').execute({
        postId: 42,
        targetLanguage: 'en-US',
        title: 'English Title',
        summary: 'English Summary',
        contentMarkdown: 'English Content'
    }, {});
    assert.equal(result.id, 42);

    const saveReq = h.requests.find(r => r.url === '/user/api/posts/42/translation');
    assert.ok(saveReq);
    assert.equal(saveReq.options.method, 'PUT');
    assert.equal(saveReq.options.credentials, 'include');
    const body = JSON.parse(saveReq.options.body);
    assert.equal(body.targetLanguage, 'en-US');
    assert.equal(body.title, 'English Title');
});
