// Run with: node --test scripts/pjax-navigation.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/main.js', 'utf8');
const navigation = source.slice(source.indexOf('        async function loadByPjax('), source.indexOf('        function extractPjaxHtml('));

function harness(fetch) {
    const events = [], commits = [], failures = [], history = [];
    const container = { innerHTML: 'original', getAnimations: () => [] };
    const context = {
        URL, AbortController, console: { warn() {} },
        CustomEvent: class { constructor(type) { this.type = type; } },
        window: {
            location: { href: 'http://localhost/', origin: 'http://localhost' },
            history: { state: {}, replaceState: (state, _, url) => history.push(url) },
            matchMedia: () => ({ matches: true })
        },
        document: {
            title: 'Original', head: { querySelectorAll: () => [] },
            getElementById: () => container,
            dispatchEvent: event => events.push(event.type)
        },
        activePjaxController: null, activeTransitionItems: [],
        displayedUrl: 'http://localhost/', displayedHistoryState: {},
        getCurrentScrollPosition: () => ({ left: 0, top: 100 }),
        getHistoryScrollPosition: () => ({ left: 0, top: 0 }),
        isArticleDetailUrl: () => false, rememberArticleListUrl: () => '',
        cleanupArticleTransitionItems() {}, scrollToPositionInstantly() {},
        showNavigationError: (url, retry) => failures.push({ url, retry }),
        extractPjaxHtml: html => { if (html === 'invalid') throw Error('Invalid fragment'); return html; },
        hasArticleTransitionSource: () => false, logArticleTransitionMode() {},
        updatePjaxContainerState: (_, html, url) => {
            container.innerHTML = html;
            commits.push(url);
            context.window.location.href = url;
        },
        finalizePjaxLoad: () => events.push('complete'), fetch
    };
    vm.createContext(context);
    vm.runInContext(navigation, context);
    return { context, container, commits, events, failures, history };
}

const response = (url, html = 'new', type = 'text/html') => ({
    ok: true, url, headers: { get: () => type }, text: async () => html
});

test('failed navigation preserves the document and exposes a working retry', async () => {
    let fail = true;
    const h = harness(async url => { if (fail) throw Error('offline'); return response(url); });
    await h.context.loadByPjax('http://localhost/tag', true, '');
    assert.equal(h.container.innerHTML, 'original');
    assert.equal(h.context.window.location.href, 'http://localhost/');
    assert.equal(h.failures.length, 1);
    fail = false;
    await h.failures[0].retry();
    assert.deepEqual(h.commits, ['http://localhost/tag']);
});

test('a stale response cannot overwrite a newer navigation or finish its progress', async () => {
    let release;
    const h = harness(url => url.endsWith('/old') ? new Promise(resolve => { release = resolve; }) : Promise.resolve(response(url)));
    const old = h.context.loadByPjax('http://localhost/old', true, '');
    await h.context.loadByPjax('http://localhost/new', true, '');
    release(response('http://localhost/old'));
    await old;
    assert.deepEqual(h.commits, ['http://localhost/new']);
    assert.equal(h.events.filter(event => event === 'complete').length, 1);
    assert.equal(h.failures.length, 0);
});

test('HTTP errors, invalid content and foreign redirects never replace the page', async () => {
    for (const result of [{ ok: false, status: 500 }, response('http://localhost/tag', 'invalid'), response('http://localhost/feed', '{}', 'application/json'), response('https://elsewhere.test/')]) {
        const h = harness(async () => result);
        await h.context.loadByPjax('http://localhost/tag', true, '');
        assert.equal(h.container.innerHTML, 'original');
        assert.equal(h.failures.length, 1);
        assert.equal(h.commits.length, 0);
    }
});

test('animation rejection does not turn a successful navigation into a failure', async () => {
    const h = harness(async url => response(url));
    h.context.window.matchMedia = () => ({ matches: false });
    h.container.animate = () => ({ finished: Promise.reject(Error('cancelled')) });
    await h.context.loadByPjax('http://localhost/tag', true, '');
    assert.deepEqual(h.commits, ['http://localhost/tag']);
    assert.equal(h.failures.length, 0);
    assert.equal(h.events.filter(event => event === 'complete').length, 1);
});

test('same-address clicks are intercepted, while modifier clicks keep native behavior', () => {
    const start = source.indexOf("        document.addEventListener('click', (event) => {\n            if (event.defaultPrevented)");
    const end = source.indexOf("        window.addEventListener('popstate'", start);
    let handler, prevented = 0, navigations = 0;
    const anchor = { href: 'http://localhost/', matches: () => false };
    const context = {
        URL,
        document: { addEventListener: (_, listener) => { handler = listener; } },
        window: { location: { href: 'http://localhost/', pathname: '/', search: '' }, history: { state: {} } },
        canUsePjax: () => true,
        loadByPjax: () => { navigations++; }
    };
    vm.runInNewContext(source.slice(start, end), context);
    const event = { button: 0, target: { closest: () => anchor }, preventDefault: () => { prevented++; } };
    handler(event);
    assert.equal(prevented, 1);
    assert.equal(navigations, 0);
    handler({ ...event, ctrlKey: true });
    assert.equal(prevented, 1);
});
