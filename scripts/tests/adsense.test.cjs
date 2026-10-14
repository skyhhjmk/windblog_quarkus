const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const code = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/ads.js', 'utf8');
function setup(config) {
    const events = {};
    const scripts = [];
    const wrappers = [];
    const hosts = ['content_top', 'content_bottom', 'sidebar'].map(placement => element({wbAdPlacement: placement}));
    function element(dataset = {}) {
        return {dataset, style: {}, children: [], isConnected: true, offsetWidth: 300,
            get childElementCount() {return this.children.length;},
            appendChild(child) {this.children.push(child); child.parentElement = this; if (child.className?.startsWith('wb-ad-block')) wrappers.push(child);},
            setAttribute() {}, hasAttribute() {return false;}, querySelector() {return this.children[0];}};
    }
    let intersection;
    const document = {
        readyState: 'complete', head: {appendChild(script) {scripts.push(script); script.onload();}},
        createElement() {return element();},
        addEventListener(name, fn) {events[name] = fn;},
        querySelector(selector) {
            if (selector === '[data-wb-ads-config]') return {dataset: {wbAdsConfig: JSON.stringify(config)}};
            if (selector.startsWith('script')) return scripts[0];
            return {querySelector() {return true;}};
        },
        querySelectorAll(selector) {return selector === '[data-wb-ad-placement]' ? hosts : wrappers;}
    };
    const window = {IntersectionObserver: true};
    vm.runInNewContext(code, {document, window, setTimeout, IntersectionObserver: class {
        constructor(fn) {intersection = fn;} observe() {} unobserve() {} disconnect() {}
    }, MutationObserver: class {observe() {} disconnect() {}}});
    return {window, scripts, wrappers, events, trigger() {intersection(wrappers.map(target => ({target, isIntersecting: true})));}};
}
const config = {enabled: true, clientId: 'ca-pub-4006222603298393', lazyLoad: true, blocks: JSON.stringify([
    {slot: '123456', placement: 'sidebar'}, {slot: '789012', placement: 'content_bottom'}
])};
test('lazy blocks load one script and request each unit only once across PJAX', async () => {
    const env = setup(config);
    assert.equal(env.wrappers.length, 2);
    assert.equal(env.scripts.length, 0);
    env.trigger();
    await new Promise(setImmediate);
    assert.equal(env.scripts.length, 1);
    assert.equal(env.window.adsbygoogle.length, 2);
    env.events['pjax:complete']();
    env.trigger();
    await new Promise(setImmediate);
    assert.equal(env.window.adsbygoogle.length, 2);
});
test('disabled, invalid client and invalid block settings never load scripts', () => {
    for (const value of [{...config, enabled: false}, {...config, clientId: 'invalid'}, {...config, blocks: 'broken'}, {...config, blocks: '[{"slot":"<script>","placement":"sidebar"}]'}]) {
        const env = setup(value);
        assert.equal(env.scripts.length, 0);
        assert.equal(env.wrappers.length, 0);
    }
});
test('hidden units wait for a usable width', async () => {
    const env = setup(config);
    env.wrappers.forEach(wrapper => {wrapper.offsetWidth = 0;});
    env.trigger();
    assert.equal(env.scripts.length, 0);
    env.wrappers.forEach(wrapper => {wrapper.offsetWidth = 300;});
    env.trigger();
    await new Promise(setImmediate);
    assert.equal(env.window.adsbygoogle.length, 2);
});
