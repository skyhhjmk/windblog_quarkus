const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/webmcp.js', 'utf8');
function harness(entry = 'navigator') {
    const tools = new Map(), events = {}, requests = [], destinations = [];
    const modelContext = {registerTool(tool, {signal}) {
        assert.equal(signal.aborted, false);
        assert.equal(typeof tool.execute, 'function');
        tools.set(tool.name, tool);
        signal.addEventListener('abort', () => tools.delete(tool.name));
    }};
    const context = {AbortController, AbortSignal, URL, URLSearchParams, console, setTimeout: fn => fn(),
        navigator: {}, document: {}, location: {origin: 'https://example.com', assign: url => destinations.push(url)},
        window: {addEventListener: (name, fn) => events[name] = fn},
        fetch: async (url, options) => {requests.push({url, options}); return {ok: true, url: 'https://example.com' + url, json: async () => ({comments: []})};}};
    if (entry) context[entry].modelContext = modelContext;
    vm.createContext(context);
    vm.runInContext(source, context);
    return {tools, events, requests, destinations, context};
}
test('registers on load, survives PJAX and restores after pagehide/pageshow without duplicates', () => {
    for (const entry of ['navigator', 'document']) {
        const h = harness(entry);
        assert.equal(h.tools.size, 5);
        h.events.pageshow();
        assert.equal(h.tools.size, 5);
        vm.runInContext(source, h.context);
        assert.equal(h.tools.size, 5);
        h.events.pagehide();
        assert.equal(h.tools.size, 0);
        h.events.pageshow();
        assert.equal(h.tools.size, 5);
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
