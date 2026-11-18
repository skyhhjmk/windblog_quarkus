// Run with: node --test scripts/asciinema-block.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const source = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/asciinema-block.js', 'utf8');

function loadHelpers() {
    const start = source.indexOf('    function isLightThemeActive()');
    const end = source.indexOf('    function createCardScaffold(');
    const snippet = source.slice(start, end);
    const documentElement = {
        classes: new Set(),
        classList: {
            contains(cls) { return documentElement.classes.has(cls); },
            add(cls) { documentElement.classes.add(cls); },
            remove(cls) { documentElement.classes.delete(cls); }
        }
    };
    const context = {
        Number, String, Math, Array, Set, JSON, parseFloat, parseInt,
        document: { documentElement }
    };
    vm.createContext(context);
    vm.runInContext(snippet, context);
    return { context, documentElement };
}

test('resolves theme automatically based on html.light class and respects explicit theme override', () => {
    const { context, documentElement } = loadHelpers();
    assert.equal(context.resolveThemeName('auto'), 'windblog-dark');
    documentElement.classList.add('light');
    assert.equal(context.resolveThemeName('auto'), 'windblog-light');
    assert.equal(context.resolveThemeName('dracula'), 'dracula');
});

test('parses marker attributes in JSON and semicolon-delimited formats', () => {
    const { context } = loadHelpers();
    const parsedDelim = JSON.parse(JSON.stringify(context.parseMarkersAttr('3.0:安装依赖;8.5:启动服务')));
    assert.deepEqual(parsedDelim, [
        [3, '安装依赖'],
        [8.5, '启动服务']
    ]);

    const parsedJson = JSON.parse(JSON.stringify(context.parseMarkersAttr('[[1.2, "Init"], [4.5, "Build"]]')));
    assert.deepEqual(parsedJson, [
        [1.2, 'Init'],
        [4.5, 'Build']
    ]);
});

test('parses cast metadata with idleTimeLimit compression and embedded markers', () => {
    const { context } = loadHelpers();
    const sampleCast = [
        '{"version": 2, "width": 80, "height": 24, "idle_time_limit": 2}',
        '[0.5, "o", "ls\\r\\n"]',
        '[1.5, "m", "First Marker"]',
        '[11.5, "o", "after 10s idle\\r\\n"]',
        '[12.5, "m", "Second Marker"]'
    ].join('\n');

    const meta = JSON.parse(JSON.stringify(context.parseCastTextMetadata(sampleCast, 2)));
    // 0.5 + 1.0 + min(10.0, 2.0) + 1.0 = 4.5s
    assert.equal(meta.duration, 4.5);
    assert.deepEqual(meta.markers, [
        [1.5, 'First Marker'],
        [4.5, 'Second Marker']
    ]);
});
