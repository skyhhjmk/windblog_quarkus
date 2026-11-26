// Run with: node --test scripts/inline-loader-swarm.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const perfTierSource = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/perf-tier.js', 'utf8');
const lifecycleSource = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/pjax-lifecycle.js', 'utf8');
const inlineLoaderSource = fs.readFileSync('src/main/resources/META-INF/resources/assets/js/inline-loader.js', 'utf8');

function createHarness(options = {}) {
    let nowMs = 1000;
    let nextTimerId = 1;
    const timers = new Map();
    const rafQueue = new Map();
    let nextRafId = 1;
    const windowListeners = new Map();
    const docListeners = new Map();

    function makeStorage() {
        const store = new Map();
        return {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, String(v)),
            removeItem: (k) => store.delete(k)
        };
    }

    function addListener(map, type, fn, opts) {
        if (typeof fn !== 'function') return;
        if (!map.has(type)) map.set(type, []);
        map.get(type).push({ fn, once: Boolean(opts && opts.once) });
    }

    function removeListener(map, type, fn) {
        if (!map.has(type)) return;
        map.set(type, map.get(type).filter((entry) => entry.fn !== fn));
    }

    function dispatchTo(map, event) {
        const list = map.get(event.type);
        if (!list || list.length === 0) return;
        const snapshot = [...list];
        snapshot.forEach((entry) => {
            if (entry.once) removeListener(map, event.type, entry.fn);
            entry.fn(event);
        });
    }

    function createClassList(el) {
        const set = new Set();
        return {
            add: (...names) => names.forEach((n) => n && set.add(n)),
            remove: (...names) => names.forEach((n) => set.delete(n)),
            contains: (n) => set.has(n),
            toggle: (n, force) => {
                if (force === undefined) {
                    if (set.has(n)) { set.delete(n); return false; }
                    set.add(n); return true;
                }
                if (force) set.add(n); else set.delete(n);
                return Boolean(force);
            },
            toString: () => Array.from(set).join(' '),
            _set: set
        };
    }

    function createStyle() {
        const props = new Map();
        return new Proxy({
            setProperty: (k, v) => props.set(k, String(v)),
            removeProperty: (k) => props.delete(k),
            getPropertyValue: (k) => props.get(k) || '',
            cssText: ''
        }, {
            get(target, prop) {
                if (prop in target) return target[prop];
                return props.get(prop) || '';
            },
            set(target, prop, value) {
                if (prop === 'cssText') { target.cssText = value; return true; }
                props.set(prop, String(value));
                return true;
            }
        });
    }

    function createCanvasContext() {
        return {
            setTransform() {},
            clearRect() {},
            save() {},
            restore() {},
            translate() {},
            rotate() {},
            scale() {},
            beginPath() {},
            moveTo() {},
            lineTo() {},
            closePath() {},
            arc() {},
            fill() {},
            stroke() {},
            fillRect() {},
            strokeRect() {},
            roundRect() {},
            rect() {},
            fillText() {},
            setLineDash() {},
            measureText: (txt) => ({ width: String(txt || '').length * 6 }),
            globalAlpha: 1,
            fillStyle: '',
            strokeStyle: '',
            lineWidth: 1,
            font: ''
        };
    }

    class MockElement {
        constructor(tagName = 'div', attrs = {}) {
            this.tagName = String(tagName).toUpperCase();
            this.children = [];
            this.parentNode = null;
            this.classList = createClassList(this);
            this.dataset = {};
            this.style = createStyle();
            this.attributes = new Map();
            this.hidden = false;
            this.textContent = '';
            this.width = 0;
            this.height = 0;
            this.clientWidth = 320;
            this.clientHeight = 320;
            this._rect = { left: 0, top: 0, width: 320, height: 120, right: 320, bottom: 120 };
            Object.entries(attrs).forEach(([k, v]) => this.setAttribute(k, v));
        }
        get firstChild() { return this.children[0] || null; }
        get className() { return this.classList.toString(); }
        set className(val) {
            this.classList._set.clear();
            String(val || '').split(/\s+/).filter(Boolean).forEach((c) => this.classList.add(c));
        }
        get id() { return this.attributes.get('id') || ''; }
        set id(val) { this.attributes.set('id', String(val)); }
        get isConnected() {
            let cur = this;
            while (cur) {
                if (cur === documentElement) return true;
                cur = cur.parentNode;
            }
            return false;
        }
        setAttribute(k, v) {
            this.attributes.set(k, String(v));
            if (k === 'class') {
                this.className = v;
            } else if (k.startsWith('data-')) {
                const camel = k.slice(5).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
                this.dataset[camel] = String(v);
            }
        }
        getAttribute(k) { return this.attributes.has(k) ? this.attributes.get(k) : null; }
        removeAttribute(k) {
            this.attributes.delete(k);
            if (k.startsWith('data-')) {
                const camel = k.slice(5).replace(/-([a-z])/g, (_, ch) => ch.toUpperCase());
                delete this.dataset[camel];
            }
        }
        appendChild(child) {
            if (child.parentNode) child.remove();
            child.parentNode = this;
            this.children.push(child);
            return child;
        }
        insertBefore(child, ref) {
            if (child.parentNode) child.remove();
            child.parentNode = this;
            const idx = this.children.indexOf(ref);
            if (idx === -1) this.children.push(child);
            else this.children.splice(idx, 0, child);
            return child;
        }
        remove() {
            if (!this.parentNode) return;
            const idx = this.parentNode.children.indexOf(this);
            if (idx !== -1) this.parentNode.children.splice(idx, 1);
            this.parentNode = null;
        }
        cloneNode(deep) {
            const copy = new MockElement(this.tagName);
            this.attributes.forEach((v, k) => copy.setAttribute(k, v));
            copy.className = this.className;
            copy.textContent = this.textContent;
            copy._rect = { ...this._rect };
            if (deep) {
                this.children.forEach((c) => copy.appendChild(c.cloneNode(true)));
            }
            return copy;
        }
        getBoundingClientRect() {
            const r = typeof this._rectFn === 'function' ? this._rectFn() : this._rect;
            const left = r.left ?? 0;
            const top = r.top ?? 0;
            const width = r.width ?? 0;
            const height = r.height ?? 0;
            return { left, top, width, height, right: left + width, bottom: top + height };
        }
        getContext(type) {
            if (type === '2d') return createCanvasContext();
            return null;
        }
        matches(selector) {
            return matchesSelector(this, selector);
        }
        closest(selector) {
            let cur = this;
            while (cur) {
                if (cur.matches && cur.matches(selector)) return cur;
                cur = cur.parentNode;
            }
            return null;
        }
        querySelectorAll(selector) {
            const parts = selector.split(',').map((s) => s.trim()).filter(Boolean);
            const results = [];
            const visit = (node) => {
                for (const child of node.children) {
                    if (parts.some((sel) => matchesSelector(child, sel))) {
                        results.push(child);
                    }
                    visit(child);
                }
            };
            visit(this);
            return results;
        }
        querySelector(selector) {
            return this.querySelectorAll(selector)[0] || null;
        }
    }

    function matchesSimplePart(el, part) {
        if (!el || !(el instanceof MockElement)) return false;
        let rest = part.trim();
        if (!rest) return false;
        if (rest === '*') return true;

        // Handle :not(...)
        const notMatches = [];
        rest = rest.replace(/:not\(([^)]+)\)/g, (_, inner) => {
            notMatches.push(inner);
            return '';
        });
        for (const inner of notMatches) {
            if (matchesSimplePart(el, inner)) return false;
        }

        const tagMatch = rest.match(/^[a-zA-Z0-9_-]+/);
        if (tagMatch) {
            if (el.tagName.toLowerCase() !== tagMatch[0].toLowerCase()) return false;
            rest = rest.slice(tagMatch[0].length);
        }

        const attrRegex = /\[([^\]=]+)(?:="([^"]*)")?\]/g;
        let m;
        while ((m = attrRegex.exec(rest)) !== null) {
            const attrName = m[1];
            const expected = m[2];
            const actual = el.getAttribute(attrName);
            if (expected !== undefined) {
                if (actual !== expected) return false;
            } else if (actual === null) {
                return false;
            }
        }
        rest = rest.replace(attrRegex, '');

        const idMatch = rest.match(/#([a-zA-Z0-9_-]+)/);
        if (idMatch) {
            if (el.id !== idMatch[1]) return false;
            rest = rest.replace(idMatch[0], '');
        }

        const classMatches = rest.match(/\.[a-zA-Z0-9_-]+/g) || [];
        for (const cls of classMatches) {
            if (!el.classList.contains(cls.slice(1))) return false;
        }
        return true;
    }

    function matchesSelector(el, selector) {
        const groups = selector.split(',').map((s) => s.trim()).filter(Boolean);
        return groups.some((group) => {
            const tokens = group.split(/\s+/).filter(Boolean);
            let idx = tokens.length - 1;
            if (!matchesSimplePart(el, tokens[idx])) return false;
            let cur = el.parentNode;
            idx--;
            while (idx >= 0) {
                if (tokens[idx] === '>') {
                    idx--;
                    if (!cur || !matchesSimplePart(cur, tokens[idx])) return false;
                    cur = cur.parentNode;
                    idx--;
                } else {
                    while (cur && !matchesSimplePart(cur, tokens[idx])) {
                        cur = cur.parentNode;
                    }
                    if (!cur) return false;
                    cur = cur.parentNode;
                    idx--;
                }
            }
            return true;
        });
    }

    const documentElement = new MockElement('html');
    documentElement.dataset.perfTier = options.initialPerfTier || 'high';
    const body = new MockElement('body');
    documentElement.appendChild(body);

    const pjaxContainer = new MockElement('div', { id: 'pjax-container' });
    const pjaxContentRoot = new MockElement('div', { id: 'pjax-content-root' });
    pjaxContainer.appendChild(pjaxContentRoot);
    body.appendChild(pjaxContainer);

    const inlineLoader = new MockElement('div', { id: 'wb-inline-loader' });
    const cursorGlyph = new MockElement('span', { id: 'wb-loader-cursor-glyph' });
    const statusWord = new MockElement('span', { id: 'wb-loader-status-word' });
    const chipSvg = new MockElement('svg', { id: 'wb-loader-chip-svg' });
    const orbitCanvas = new MockElement('canvas', { id: 'wb-loader-orbit-canvas' });
    inlineLoader.appendChild(cursorGlyph);
    inlineLoader.appendChild(statusWord);
    inlineLoader.appendChild(chipSvg);
    inlineLoader.appendChild(orbitCanvas);
    if (options.dismissedInitialLoader !== false) {
        inlineLoader.classList.add('is-dismissed');
    }
    body.appendChild(inlineLoader);

    const ioInstances = [];
    class MockIntersectionObserver {
        constructor(cb) {
            this.cb = cb;
            this.observed = new Set();
            ioInstances.push(this);
        }
        observe(el) { this.observed.add(el); }
        unobserve(el) { this.observed.delete(el); }
        disconnect() { this.observed.clear(); }
        trigger(entries) { this.cb(entries, this); }
    }

    const roInstances = [];
    class MockResizeObserver {
        constructor(cb) {
            this.cb = cb;
            this.observed = new Set();
            roInstances.push(this);
        }
        observe(el) { this.observed.add(el); }
        disconnect() { this.observed.clear(); }
        trigger() { this.cb([], this); }
    }

    class MockMutationObserver {
        observe() {}
        disconnect() {}
    }

    class MockCustomEvent {
        constructor(type, init = {}) {
            this.type = type;
            this.detail = init.detail;
        }
    }

    const sessionStorage = makeStorage();
    const localStorage = makeStorage();

    const windowObj = {
        innerWidth: 1280,
        innerHeight: 800,
        scrollX: 0,
        scrollY: 0,
        pageXOffset: 0,
        pageYOffset: 0,
        devicePixelRatio: 1,
        __wbBfCachePersisted: Boolean(options.bfCachePersisted),
        __wbManualReload: false,
        __wbInitialTargetTier: options.initialTargetTier || 'high',
        location: {
            href: options.initialUrl || 'http://localhost/',
            pathname: options.initialPathname || '/',
            search: options.initialSearch || ''
        },
        navigator: {
            hardwareConcurrency: options.cores ?? 16,
            deviceMemory: options.memoryGB ?? 16
        },
        matchMedia: (q) => ({
            matches: q.includes('prefers-reduced-motion') ? Boolean(options.reducedMotion) : false,
            addEventListener() {}
        }),
        getComputedStyle: () => ({
            position: 'static',
            getPropertyValue: () => ''
        }),
        setTimeout: (fn, delay = 0) => {
            const id = nextTimerId++;
            timers.set(id, { fn, at: nowMs + Math.max(0, delay), interval: null });
            return id;
        },
        clearTimeout: (id) => timers.delete(id),
        setInterval: (fn, delay = 16) => {
            const id = nextTimerId++;
            const step = Math.max(1, delay);
            timers.set(id, { fn, at: nowMs + step, interval: step });
            return id;
        },
        clearInterval: (id) => timers.delete(id),
        requestAnimationFrame: (fn) => {
            const id = nextRafId++;
            rafQueue.set(id, fn);
            return id;
        },
        cancelAnimationFrame: (id) => rafQueue.delete(id),
        addEventListener: (type, fn, opts) => addListener(windowListeners, type, fn, opts),
        removeEventListener: (type, fn) => removeListener(windowListeners, type, fn),
        dispatchEvent: (ev) => dispatchTo(windowListeners, ev)
    };

    const documentObj = {
        documentElement,
        body,
        readyState: options.readyState || 'complete',
        hidden: false,
        createElement: (tag) => new MockElement(tag),
        getElementById: (id) => documentElement.querySelector('#' + id),
        querySelector: (sel) => documentElement.querySelector(sel),
        querySelectorAll: (sel) => documentElement.querySelectorAll(sel),
        addEventListener: (type, fn, opts) => addListener(docListeners, type, fn, opts),
        removeEventListener: (type, fn) => removeListener(docListeners, type, fn),
        dispatchEvent: (ev) => dispatchTo(docListeners, ev)
    };

    const navType = options.navType || 'navigate';
    const context = {
        window: windowObj,
        document: documentObj,
        Element: MockElement,
        CustomEvent: MockCustomEvent,
        IntersectionObserver: MockIntersectionObserver,
        ResizeObserver: MockResizeObserver,
        MutationObserver: MockMutationObserver,
        performance: {
            now: () => nowMs,
            getEntriesByType: (t) => (t === 'navigation' ? [{ type: navType }] : [])
        },
        navigator: windowObj.navigator,
        sessionStorage,
        localStorage,
        console,
        Math,
        Number,
        String,
        Boolean,
        Array,
        Object,
        Set,
        Map,
        JSON,
        Promise,
        setTimeout: windowObj.setTimeout,
        clearTimeout: windowObj.clearTimeout,
        requestAnimationFrame: windowObj.requestAnimationFrame,
        cancelAnimationFrame: windowObj.cancelAnimationFrame
    };
    windowObj.window = windowObj;
    windowObj.document = documentObj;
    windowObj.performance = context.performance;
    windowObj.sessionStorage = sessionStorage;
    windowObj.localStorage = localStorage;

    vm.createContext(context);

    function stepRaf(frames = 1, dt = 16) {
        for (let f = 0; f < frames; f++) {
            nowMs += dt;
            const pending = Array.from(rafQueue.entries());
            rafQueue.clear();
            for (const [, fn] of pending) {
                fn(nowMs);
            }
        }
    }

    function advanceTime(ms) {
        const target = nowMs + ms;
        while (true) {
            let earliest = null;
            for (const [id, t] of timers.entries()) {
                if (t.at <= target && (!earliest || t.at < earliest.t.at)) {
                    earliest = { id, t };
                }
            }
            if (!earliest) break;
            nowMs = earliest.t.at;
            if (earliest.t.interval !== null) {
                earliest.t.at = nowMs + earliest.t.interval;
            } else {
                timers.delete(earliest.id);
            }
            earliest.t.fn();
        }
        nowMs = target;
    }

    function addArticleCard(slug, rect, opts = {}) {
        const article = new MockElement('article', {
            class: 'card' + (opts.flipTarget ? ' pjax-article-transition-target-card' : ''),
            'data-article-transition-slug': slug,
            'data-article-transition-card': slug
        });
        article._rect = rect;
        const h2 = new MockElement('h2', {
            'data-article-transition-part': 'title',
            'data-article-transition-slug': slug
        });
        const link = new MockElement('a', { href: '/post/' + slug });
        link.textContent = 'Title ' + slug;
        h2.appendChild(link);
        article.appendChild(h2);
        pjaxContentRoot.appendChild(article);
        return article;
    }

    function navigatePjax(pathname, search, setupFn) {
        const url = 'http://localhost' + pathname + (search || '');
        windowObj.WindBlogLifecycle.beginNavigation({ url });
        pjaxContentRoot.children.slice().forEach((c) => c.remove());
        windowObj.location.href = url;
        windowObj.location.pathname = pathname;
        windowObj.location.search = search || '';
        if (setupFn) setupFn();
        windowObj.WindBlogLifecycle.mountPage({ url });
    }

    return {
        context,
        window: windowObj,
        document: documentObj,
        documentElement,
        pjaxContainer,
        pjaxContentRoot,
        inlineLoader,
        ioInstances,
        roInstances,
        dispatchDoc: (ev) => dispatchTo(docListeners, ev),
        dispatchWin: (ev) => dispatchTo(windowListeners, ev),
        stepRaf,
        advanceTime,
        addArticleCard,
        navigatePjax,
        MockElement,
        loadScripts: (withPerfTier = false) => {
            if (withPerfTier) vm.runInContext(perfTierSource, context);
            vm.runInContext(lifecycleSource, context);
            vm.runInContext(inlineLoaderSource, context);
        }
    };
}

test('live PJAX navigation clears initial back_forward / pageshow BFCache latch and starts Swarm Rebuild', () => {
    const h = createHarness({ navType: 'back_forward', bfCachePersisted: true });
    h.addArticleCard('post-a', { left: 20, top: 100, width: 600, height: 180 });
    h.loadScripts(false);
    h.advanceTime(600);
    h.stepRaf(2);

    // Initial back_forward restore must NOT build
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), false);
    assert.equal(h.document.querySelectorAll('.wb-swarm-stage-canvas').length, 0);

    // Subsequent live PJAX pagination must clear the latch and start building
    h.navigatePjax('/page/2', '', () => {
        h.addArticleCard('post-b', { left: 20, top: 100, width: 600, height: 180 });
    });
    assert.equal(h.pjaxContentRoot.querySelector('article').classList.contains('wb-swarm-item-pending'), true);
    h.stepRaf(3);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);
    assert.equal(h.document.querySelectorAll('.wb-swarm-stage-canvas').length, 1);

    // Also verify pageshow.persisted resets on next PJAX navigation
    h.dispatchWin({ type: 'pageshow', persisted: true });
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), false);
    h.navigatePjax('/page/3', '', () => {
        h.addArticleCard('post-c', { left: 20, top: 100, width: 600, height: 180 });
    });
    h.stepRaf(3);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);
});

test('cards shifting into viewport after initial PJAX settle start building without needing a scroll event', () => {
    const h = createHarness();
    h.loadScripts(false);
    h.advanceTime(600);

    // Case 1: Card 1 is visible, Card 2 is initially below viewport due to pre-Tailwind image height,
    // then shifts into viewport when ResizeObserver / settle timer runs.
    let card2Top = 1200;
    const card1 = h.addArticleCard('p1', { left: 20, top: 100, width: 600, height: 180 });
    const card2 = h.addArticleCard('p2', { left: 20, top: 1200, width: 600, height: 180 });
    card2._rectFn = () => ({ left: 20, top: card2Top, width: 600, height: 180 });

    h.navigatePjax('/page/2', '', () => {
        h.pjaxContentRoot.appendChild(card1);
        h.pjaxContentRoot.appendChild(card2);
    });
    h.stepRaf(3);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);
    assert.equal(card2.classList.contains('wb-swarm-item-pending'), true);

    // Simulate layout settling and bringing card2 into viewport without firing scroll event
    card2Top = 320;
    h.advanceTime(100);
    h.stepRaf(2);
    // Card 2 should now be enqueued (eventually transitions out of pending or completes)
    h.advanceTime(1800);
    for (let i = 0; i < 60; i++) h.stepRaf(1, 32);
    assert.equal(card2.classList.contains('wb-swarm-item-pending'), false);
});

test('article-to-list FLIP return waits for windblog:pjax-transition-settled and skips target card', () => {
    const h = createHarness();
    h.loadScripts(false);
    h.advanceTime(600);

    h.pjaxContainer.dataset.pjaxFlipSettling = 'true';
    let targetCard;
    let otherCard;
    h.navigatePjax('/', '', () => {
        targetCard = h.addArticleCard('clicked-post', { left: 20, top: 100, width: 600, height: 180 }, { flipTarget: true });
        otherCard = h.addArticleCard('second-post', { left: 20, top: 320, width: 600, height: 180 });
    });

    // Target FLIP card must NOT be preblocked; other uncached card IS preblocked
    assert.equal(targetCard.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(otherCard.classList.contains('wb-swarm-item-pending'), true);

    // Before FLIP settles, Swarm Rebuild must NOT start yet
    h.stepRaf(3);
    assert.equal(h.document.querySelectorAll('.wb-swarm-stage-canvas').length, 0);

    // When main.js finishes settling FLIP targets and dispatches windblog:pjax-transition-settled
    delete h.pjaxContainer.dataset.pjaxFlipSettling;
    h.dispatchWin(new h.context.CustomEvent('windblog:pjax-transition-settled', { detail: { url: 'http://localhost/' } }));
    h.stepRaf(2);

    assert.equal(h.document.querySelectorAll('.wb-swarm-stage-canvas').length, 1);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);
    // FLIP target card remains untouched by Swarm pending/building classes
    assert.equal(targetCard.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(targetCard.classList.contains('wb-swarm-item-building'), false);
});

test('early exit clears preblocked classes so cards never remain invisible at opacity 0', () => {
    const h = createHarness({ initialPerfTier: 'high' });
    h.loadScripts(false);
    h.advanceTime(600);

    let card;
    h.navigatePjax('/category/tech', '', () => {
        card = h.addArticleCard('cat-post', { left: 20, top: 100, width: 600, height: 180 });
    });
    assert.equal(card.classList.contains('wb-swarm-item-pending'), true);

    // Tier drops to medium between preblock and rAF start
    h.documentElement.dataset.perfTier = 'medium';
    h.stepRaf(3);

    assert.equal(card.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(h.documentElement.classList.contains('wb-swarm-preblock'), false);
});

test('card-click scatter caches clicked card as completed and resumes remaining cards if navigation aborts', () => {
    const h = createHarness();
    h.loadScripts(false);
    h.advanceTime(600);

    let c1, c2;
    h.navigatePjax('/', '', () => {
        c1 = h.addArticleCard('card-1', { left: 20, top: 80, width: 600, height: 160 });
        c2 = h.addArticleCard('card-2', { left: 20, top: 280, width: 600, height: 160 });
    });
    h.stepRaf(4, 16);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);

    // Primary pointerdown on card-1 triggers startled scatter
    h.dispatchDoc({
        type: 'pointerdown',
        button: 0,
        ctrlKey: false,
        metaKey: false,
        shiftKey: false,
        altKey: false,
        clientX: 120,
        clientY: 140,
        target: c1
    });

    // Clicked card is immediately restored AND persisted as completed in sessionStorage
    assert.equal(c1.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(c1.classList.contains('wb-swarm-item-building'), false);
    const cacheRaw = h.context.sessionStorage.getItem('wb_swarm_progress_v1');
    assert.ok(cacheRaw);
    const cache = JSON.parse(cacheRaw);
    assert.equal(cache['/'].completed['card-1'], true);

    // Simulate user cancelling navigation (remaining on same page): scatter finishes and resumes card-2!
    for (let i = 0; i < 160; i++) {
        h.stepRaf(1, 20);
    }
    assert.equal(c2.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(c2.classList.contains('wb-swarm-item-building'), false);
});

test('perf-tier.js keeps strong hardware at high across a single 33ms frame spike and exposes getTargetTier', async () => {
    const h = createHarness({
        initialPerfTier: 'minimal',
        dismissedInitialLoader: true,
        cores: 16,
        memoryGB: 16
    });
    h.window.WindBlogPerf = null;
    h.loadScripts(true);

    // During progressive upgrade, getTargetTier() must immediately report 'high'
    assert.equal(h.window.WindBlogPerf.getTargetTier(), 'high');

    // Finish progressive steps and idle benchmark scheduling
    h.advanceTime(500);
    // Step through idle + 10 idle rAFs + 12 composite rAFs with a single 33.4ms jank spike
    h.advanceTime(3000);
    for (let i = 0; i < 30; i++) {
        const dt = (i === 18) ? 33.4 : 16.5;
        h.stepRaf(1, dt);
        await Promise.resolve();
    }
    h.advanceTime(500);

    assert.equal(h.window.WindBlogPerf.getTier(), 'high');
    assert.equal(h.window.WindBlogPerf.getTargetTier(), 'high');
    const report = h.window.WindBlogPerf.getReport();
    assert.equal(report.source, 'benchmark');
});

test('perf-tier.js discards benchmark sample interrupted by PJAX URL change and retries cleanly', async () => {
    const h = createHarness({
        initialPerfTier: 'minimal',
        dismissedInitialLoader: true,
        cores: 16,
        memoryGB: 16
    });
    h.window.WindBlogPerf = null;
    h.loadScripts(true);

    h.advanceTime(3000);
    // Step halfway through benchmark then simulate a PJAX URL change mid-benchmark
    for (let i = 0; i < 6; i++) {
        h.stepRaf(1, 16.5);
        await Promise.resolve();
    }
    h.window.location.href = 'http://localhost/page/2';
    for (let i = 0; i < 20; i++) {
        h.stepRaf(1, 80); // huge simulated frame lag during navigation must be ignored!
        await Promise.resolve();
    }
    assert.equal(h.window.WindBlogPerf.getTier(), 'high');
    assert.notEqual(h.window.WindBlogPerf.getReport().source, 'benchmark');

    // Retry fires after 900ms on the new URL with clean frames and succeeds
    h.advanceTime(950);
    for (let i = 0; i < 30; i++) {
        h.stepRaf(1, 16.5);
        await Promise.resolve();
    }
    assert.equal(h.window.WindBlogPerf.getTier(), 'high');
    assert.equal(h.window.WindBlogPerf.getReport().source, 'benchmark');
});

test('back_forward initial load with wb-swarm-preblock clears preblock so cards never stay invisible', () => {
    const h = createHarness({ navType: 'back_forward', dismissedInitialLoader: false });
    h.documentElement.classList.add('wb-swarm-preblock');
    const card = h.addArticleCard('bf-card-1', { left: 20, top: 80, width: 600, height: 160 });

    h.loadScripts(false);
    h.advanceTime(600);

    assert.equal(h.documentElement.classList.contains('wb-swarm-preblock'), false);
    assert.equal(card.classList.contains('wb-swarm-item-pending'), false);
});

test('initial load while document.hidden waits for visibilitychange before starting Swarm Rebuild', () => {
    const h = createHarness({ dismissedInitialLoader: false });
    h.context.document.hidden = true;
    h.documentElement.classList.add('wb-swarm-preblock');
    const card = h.addArticleCard('hidden-card-1', { left: 20, top: 80, width: 600, height: 160 });

    h.loadScripts(false);
    h.advanceTime(700);

    // While hidden, overlay/preblock holds without starting a broken offscreen session
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), true);

    // Wake / switch to tab: Swarm Rebuild launches and completes building the card
    h.context.document.hidden = false;
    h.dispatchDoc({ type: 'visibilitychange' });
    assert.equal(h.documentElement.classList.contains('wb-swarm-preblock'), false);
    assert.equal(card.classList.contains('wb-swarm-item-pending'), true);

    for (let i = 0; i < 120; i++) {
        h.stepRaf(1, 20);
    }
    assert.equal(card.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(card.classList.contains('wb-swarm-item-building'), false);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), false);
});

test('session in idle-waiting wakes up on visibilitychange/focus when cards become visible without scroll', () => {
    const h = createHarness();
    h.loadScripts(false);
    h.advanceTime(600);

    let card;
    h.navigatePjax('/category/test', '', () => {
        // Initially zero height (e.g. background/suspended layout) so session enters idle-waiting
        card = h.addArticleCard('wake-card-1', { left: 20, top: 80, width: 600, height: 0 });
    });
    h.advanceTime(1100);
    assert.equal(card.classList.contains('wb-swarm-item-pending'), true);

    // After wake / tab focus, layout is ready; visibilitychange/focus wakes up the swarm without scroll
    card._rect = { left: 20, top: 80, width: 600, height: 160, right: 620, bottom: 240 };
    h.window.dispatchEvent({ type: 'focus' });

    for (let i = 0; i < 120; i++) {
        h.stepRaf(1, 20);
    }
    assert.equal(card.classList.contains('wb-swarm-item-pending'), false);
    assert.equal(card.classList.contains('wb-swarm-item-building'), false);
    assert.equal(h.window.WindBlogInlineLoader.isBusy(), false);
});
