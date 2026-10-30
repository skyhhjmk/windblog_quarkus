// /assets/js/ruler-hud.js
(function () {
    'use strict';

    if (!window.matchMedia) return;

    const desktopMedia = window.matchMedia('(min-width: 1024px) and (hover: hover) and (pointer: fine)');
    const reducedMotionMedia = window.matchMedia('(prefers-reduced-motion: reduce)');

    // Configuration Tunables (faithful to ArtCraft's Ruler Layout / Motion / Look)
    const LAYOUT = {
        railW: 56,
        edgePad: 14,
        minorPct: 1,
        labelPct: 5,
        thresholdPct: 14,
        headingPx: 22,
        ridingPx: 14,
        queuePx: 13,
        topPad: 20,
        currentGap: 20,
        queueSlot: 19,
        queuePad: 28,
        textPad: 6,
        maxLabelUnits: 16,
        maxSections: 12
    };

    const MOTION = {
        flipZone: 185,
        detachZone: 210,
        stagger: 0.15,
        arc: 26,
        jumpDur: 0.95,
        snapDelay: 400,
        snapDur: 0.58,
        introDur: 0.75,
        introStagger: 0.007,
        velRadius: 180,
        velMax: 0.65,
        zoomInDelay: 140,
        zoomOutDelay: 140,
        zoomLerp: 10,
        dragLerp: 0.22
    };

    const LOOK = {
        minorAlpha: 0.34,
        majorAlpha: 0.78,
        labelAlpha: 0.52,
        minorLen: 10,
        majorLen: 20,
        ghostAlpha: 0.95,
        queueAlpha: 0.42,
        yieldPad: 22,
        needleFadePct: 2,
        mapAlpha: 0.85,
        poolAlpha: 0.92,
        poolPad: 56
    };

    const CJK_REGEX = /[\u1100-\u115F\u2E80-\u9FFF\uAC00-\uD7AF\uF900-\uFAFF\uFE10-\uFE6F\uFF00-\uFF60\uFFE0-\uFFE6]/;

    function clamp01(v) {
        return Math.max(0, Math.min(1, v));
    }

    function easeInOutCubic(t) {
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    function easeOutExpo(t) {
        return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
    }

    function getNavHeight() {
        const header = document.querySelector('body > header');
        return header ? Math.max(48, Math.round(header.getBoundingClientRect().height)) : 64;
    }

    function rulerMap(vh, edgePad, navH) {
        const base = navH + edgePad;
        const span = Math.max(1, vh - navH - 2 * edgePad);
        return {
            base: base,
            span: span,
            drift: function (progress) {
                return base - progress * (navH + 2 * edgePad);
            }
        };
    }

    // Detect if current page is one of the 5 target page types:
    // 首页 (index), 搜索页 (search), 标签页 (tag), 分类页 (category), 文章页 (post)
    function detectPageMode() {
        let p = (window.location.pathname || '/').replace(/^\/(zh-cn|en-us)(?=\/|$)/i, '');
        if (!p.startsWith('/')) p = '/' + p;
        if (p.length > 1) p = p.replace(/\/+$/, '');

        if (p === '/' || /^\/page\/\d+$/i.test(p)) return 'index';
        if (p === '/search' || p.startsWith('/search/')) return 'search';
        if (p === '/tag' || p.startsWith('/tag/')) return 'tag';
        if (p === '/category' || p.startsWith('/category/')) return 'category';
        if (p.startsWith('/post/')) return 'post';
        return null;
    }

    // Truncate title with visual width limit (CJK = 2 units, ASCII = 1 unit)
    function truncateLabel(rawText, maxUnits) {
        const limit = maxUnits || LAYOUT.maxLabelUnits;
        let clean = String(rawText || '')
            .replace(/^#+\s*/, '')
            .replace(/\s*#+$/, '')
            .replace(/\s+/g, ' ')
            .trim();
        if (!clean) return '';

        const chars = Array.from(clean);
        let units = 0;
        for (let i = 0; i < chars.length; i++) {
            units += CJK_REGEX.test(chars[i]) ? 2 : 1;
        }
        if (units <= limit) {
            return clean;
        }

        let acc = 0;
        let out = '';
        const cutLimit = Math.max(4, limit - 1);
        for (let i = 0; i < chars.length; i++) {
            const w = CJK_REGEX.test(chars[i]) ? 2 : 1;
            if (acc + w > cutLimit) break;
            acc += w;
            out += chars[i];
        }
        return out.trim() + '…';
    }

    function isElementVisible(el) {
        if (!el || !(el instanceof HTMLElement)) return false;
        if (el.hidden || el.classList.contains('hidden') || el.classList.contains('block-hidden')) return false;
        const style = window.getComputedStyle(el);
        if (style.display === 'none' || style.visibility === 'hidden') return false;
        const rect = el.getBoundingClientRect();
        return rect.width > 0 && rect.height > 0;
    }

    function sampleSections(items, maxCount) {
        if (items.length <= maxCount) return items;
        const result = [];
        for (let i = 0; i < maxCount; i++) {
            const idx = Math.round((i * (items.length - 1)) / (maxCount - 1));
            result.push(items[idx]);
        }
        return result;
    }

    // Collect target section elements and compute their truncated labels & anchors
    function collectSections(pageMode) {
        const root = document.getElementById('pjax-content-root') || document.querySelector('main') || document.body;
        if (!root) return [];

        let rawItems = [];

        if (pageMode === 'post') {
            const proseRoot = root.querySelector('#post-body-content') ||
                root.querySelector('#vditor-preview') ||
                root.querySelector('#post-content-section');
            let headings = [];
            if (proseRoot) {
                headings = Array.from(proseRoot.querySelectorAll('h1, h2, h3, h4, h5, h6'))
                    .filter(isElementVisible);
            }

            // If too many markdown headings, progressively narrow by heading depth before sampling
            if (headings.length > LAYOUT.maxSections) {
                const h1To4 = headings.filter(h => /^H[1-4]$/i.test(h.tagName));
                if (h1To4.length >= 3) headings = h1To4;
            }
            if (headings.length > LAYOUT.maxSections) {
                const h1To3 = headings.filter(h => /^H[1-3]$/i.test(h.tagName));
                if (h1To3.length >= 3) headings = h1To3;
            }
            if (headings.length > LAYOUT.maxSections) {
                const h1To2 = headings.filter(h => /^H[1-2]$/i.test(h.tagName));
                if (h1To2.length >= 2) headings = h1To2;
            }

            // Include main article title if markdown body has fewer than 2 headings
            const mainTitle = root.querySelector('article h1[data-article-transition-part="title"], article header h1, h1');
            if (headings.length === 0 && mainTitle && isElementVisible(mainTitle)) {
                headings = [mainTitle];
            } else if (headings.length === 1 && mainTitle && mainTitle !== headings[0] && isElementVisible(mainTitle)) {
                headings = [mainTitle, headings[0]];
            }

            headings = sampleSections(headings, LAYOUT.maxSections);
            headings.forEach((h, idx) => {
                const fullText = (h.textContent || '').replace(/\s+/g, ' ').trim();
                const label = truncateLabel(fullText, LAYOUT.maxLabelUnits);
                if (!label) return;
                if (!h.id) {
                    h.id = 'wb-post-heading-' + idx;
                }
                rawItems.push({
                    el: h,
                    id: h.id,
                    fullTitle: fullText,
                    label: label
                });
            });
        } else {
            // index / search / tag / category pages: collect article / tag / category cards
            const cards = Array.from(root.querySelectorAll('article[data-article-transition-slug], article.card'))
                .filter(isElementVisible);

            const validCards = [];
            cards.forEach((card) => {
                const titleNode = card.querySelector('h2 a, h2, h3 a, h3');
                if (!titleNode) return;
                const fullText = (titleNode.textContent || '').replace(/\s+/g, ' ').trim();
                const label = truncateLabel(fullText, LAYOUT.maxLabelUnits);
                if (!label) return;
                validCards.push({
                    el: card,
                    titleEl: titleNode,
                    fullTitle: fullText,
                    label: label
                });
            });

            // Fallback to page header h1 if list is empty
            if (validCards.length === 0) {
                const pageH1 = root.querySelector('h1');
                if (pageH1 && isElementVisible(pageH1)) {
                    const fullText = (pageH1.textContent || '').replace(/\s+/g, ' ').trim();
                    const label = truncateLabel(fullText, LAYOUT.maxLabelUnits);
                    if (label) {
                        validCards.push({
                            el: pageH1,
                            titleEl: pageH1,
                            fullTitle: fullText,
                            label: label
                        });
                    }
                }
            }

            const sampled = sampleSections(validCards, LAYOUT.maxSections);
            sampled.forEach((item, idx) => {
                if (!item.el.id) {
                    item.el.id = 'wb-ruler-sec-' + idx;
                }
                rawItems.push({
                    el: item.el,
                    id: item.el.id,
                    fullTitle: item.fullTitle,
                    label: item.label
                });
            });
        }

        const scrollY = window.scrollY || 0;
        const docH = Math.max(document.documentElement.scrollHeight, document.body.scrollHeight, window.innerHeight);
        const vh = window.innerHeight || 1;
        const maxScroll = Math.max(0, docH - vh);
        const navH = getNavHeight();
        const readLine = navH + 84;

        const sections = rawItems.map((item, idx) => {
            const rect = item.el.getBoundingClientRect();
            const anchor = Math.max(0, rect.top + scrollY);
            const bottom = Math.max(anchor + 24, rect.bottom + scrollY);
            return {
                id: item.id,
                el: item.el,
                label: item.label,
                fullTitle: item.fullTitle,
                chars: Array.from(item.label),
                index: idx,
                anchor: anchor,
                bottom: bottom,
                triggerScroll: Math.max(0, anchor - readLine)
            };
        });

        // Compute normalized triggerScroll values so every section 1..M-1 can smoothly
        // detach from bottom queue, ride the rail, and flip into the top stack within [0, maxScroll]
        if (sections.length > 1) {
            const effMaxScroll = Math.max(maxScroll, 600);
            const minStart = Math.min(effMaxScroll * 0.16, 150);
            const maxEnd = Math.max(minStart + 80, effMaxScroll - 24);

            const firstRaw = sections[1].triggerScroll;
            const lastRaw = sections[sections.length - 1].triggerScroll;
            const rawSpan = Math.max(1, lastRaw - firstRaw);

            for (let i = 1; i < sections.length; i++) {
                const ratio = sections.length === 2
                    ? 0.65
                    : (i - 1) / (sections.length - 2);
                const naturalRatio = clamp01((sections[i].triggerScroll - firstRaw) / rawSpan);
                // Blend natural DOM distribution with even spacing so closely-packed cards never collide
                const blended = 0.55 * naturalRatio + 0.45 * ratio;
                sections[i].normTrigger = minStart + blended * (maxEnd - minStart);
            }
        }

        return sections;
    }

    // Measure normalized per-character advance widths offscreen
    function measureLabels(sections) {
        const metricsMap = {};
        if (!sections || sections.length === 0) return metricsMap;

        const box = document.createElement('div');
        box.style.position = 'fixed';
        box.style.left = '-9999px';
        box.style.top = '0';
        box.style.visibility = 'hidden';
        box.style.whiteSpace = 'pre';
        box.style.fontFamily = 'var(--font-sans)';
        box.style.fontWeight = '700';
        box.style.fontSize = '100px';
        box.style.lineHeight = '1';
        box.style.letterSpacing = '-0.02em';
        document.body.appendChild(box);

        for (let s = 0; s < sections.length; s++) {
            const label = sections[s].label;
            if (metricsMap[label]) continue;

            box.replaceChildren();
            const chars = sections[s].chars;
            const spans = [];
            for (let i = 0; i < chars.length; i++) {
                const sp = document.createElement('span');
                sp.textContent = chars[i];
                box.appendChild(sp);
                spans.push(sp);
            }

            const adv = [];
            const cum = [];
            let total = 0;
            for (let i = 0; i < spans.length; i++) {
                const w = Math.max(0.28, spans[i].getBoundingClientRect().width / 100);
                adv.push(w);
                cum.push(total);
                total += w;
            }
            metricsMap[label] = { adv: adv, cum: cum, total: total };
        }

        box.remove();
        return metricsMap;
    }

    // Runtime State
    let activeMode = null;
    let rootEl = null;
    let railEl = null;
    let ticksTrackEl = null;
    let bracketEl = null;
    let needleEl = null;
    let readoutEl = null;
    let digitColEls = [];
    let ghostWrapEl = null;
    let ghostLineEl = null;
    let poolTopEl = null;
    let poolBottomEl = null;
    let hudEl = null;

    let tickData = [];
    let tickRowEls = [];
    let tickLineEls = [];
    let tickLabelEls = [];

    let sections = [];
    let labelMetrics = {};
    let sectionNodes = []; // [{ hit, letters: [span...] }]
    let hoverWeights = new Float32Array(0);
    let flashUntil = new Float32Array(0);
    let hoveredSectionIdx = -1;

    const state = {
        docH: 0,
        vh: 0,
        vw: 0,
        navH: 64,
        lastY: 0,
        vel: 0,
        virtualScroll: 0,
        prevPct: -1,
        prevDigits: [-1, -1, -1],
        readoutAbove: false,
        introStart: 0,
        introDone: false,
        zoomIntent: false,
        zoomTimer: 0,
        zoomTarget: 0,
        zoomP: 0,
        zoomWrote: false,
        dragging: false,
        dragStartY: 0,
        dragMoved: 0,
        ghostActive: false,
        ghostY: 0,
        snapSince: null,
        snapping: false,
        armed: true,
        jumping: false,
        rafId: 0,
        lastFrameTime: 0
    };

    const railOccupancySpans = [];

    // Custom Smooth Scroll Engine (does not interfere with PJAX instant scroll)
    let scrollAnim = null;

    function cancelSmoothScroll() {
        if (scrollAnim) {
            const cb = scrollAnim.onComplete;
            scrollAnim = null;
            if (typeof cb === 'function') cb(false);
        }
    }

    function startSmoothScroll(targetY, durationSec, easingFn, onComplete) {
        const maxScroll = Math.max(0, state.docH - state.vh);
        const useVirtual = maxScroll < 60;
        const fromY = useVirtual ? state.virtualScroll : (window.scrollY || 0);
        const clampedTarget = useVirtual
            ? Math.max(0, Math.min(600, targetY))
            : Math.max(0, Math.min(maxScroll, targetY));

        if (Math.abs(clampedTarget - fromY) < 1) {
            if (typeof onComplete === 'function') onComplete(true);
            return;
        }

        scrollAnim = {
            useVirtual: useVirtual,
            from: fromY,
            to: clampedTarget,
            startTime: performance.now(),
            durationMs: Math.max(180, durationSec * 1000),
            easing: easingFn || easeOutExpo,
            onComplete: onComplete
        };
        requestTick();
    }

    // Build DOM Tree using safe DOM creation (100% Trusted Types & CSP compliant)
    function ensureDom() {
        if (rootEl && document.body.contains(rootEl)) return;

        rootEl = document.createElement('div');
        rootEl.id = 'wb-ruler-system';
        rootEl.className = 'wb-ruler-root';

        // 1. Contrast Frosted Pools
        const poolsWrap = document.createElement('div');
        poolsWrap.className = 'wb-ruler-pools';
        poolsWrap.setAttribute('aria-hidden', 'true');

        poolTopEl = document.createElement('div');
        poolTopEl.className = 'wb-ruler-pool-top';
        poolBottomEl = document.createElement('div');
        poolBottomEl.className = 'wb-ruler-pool-bottom';
        poolsWrap.appendChild(poolTopEl);
        poolsWrap.appendChild(poolBottomEl);

        // 2. Right Edge Tick Rail
        railEl = document.createElement('div');
        railEl.className = 'wb-ruler-rail';
        railEl.setAttribute('role', 'scrollbar');
        railEl.setAttribute('aria-controls', 'pjax-container');
        railEl.setAttribute('aria-orientation', 'vertical');
        railEl.setAttribute('aria-valuemin', '0');
        railEl.setAttribute('aria-valuemax', '100');
        railEl.setAttribute('aria-valuenow', '0');
        railEl.setAttribute('aria-label', 'Page scroll progress');

        const frostEl = document.createElement('div');
        frostEl.className = 'wb-ruler-rail-frost';
        frostEl.setAttribute('aria-hidden', 'true');
        railEl.appendChild(frostEl);

        ticksTrackEl = document.createElement('div');
        ticksTrackEl.className = 'wb-ruler-ticks-track';
        ticksTrackEl.setAttribute('aria-hidden', 'true');
        railEl.appendChild(ticksTrackEl);

        // Zoom Bracket
        bracketEl = document.createElement('div');
        bracketEl.className = 'wb-ruler-bracket';
        bracketEl.setAttribute('aria-hidden', 'true');
        const bracketFill = document.createElement('div');
        bracketFill.className = 'wb-ruler-bracket-fill';
        const bracketTop = document.createElement('span');
        bracketTop.className = 'wb-ruler-bracket-edge-top';
        const bracketBottom = document.createElement('span');
        bracketBottom.className = 'wb-ruler-bracket-edge-bottom';
        bracketEl.appendChild(bracketFill);
        bracketEl.appendChild(bracketTop);
        bracketEl.appendChild(bracketBottom);
        railEl.appendChild(bracketEl);

        // Needle & Slot-Machine Digits Readout
        needleEl = document.createElement('div');
        needleEl.className = 'wb-ruler-needle';
        needleEl.setAttribute('aria-hidden', 'true');

        const needleLine = document.createElement('span');
        needleLine.className = 'wb-ruler-needle-line';
        needleEl.appendChild(needleLine);

        readoutEl = document.createElement('span');
        readoutEl.className = 'wb-ruler-readout';

        const digitsWrap = document.createElement('span');
        digitsWrap.className = 'wb-ruler-digits';
        digitColEls = [];
        for (let col = 0; col < 3; col++) {
            const colEl = document.createElement('span');
            colEl.className = 'wb-ruler-digit-col';
            for (let d = 0; d <= 9; d++) {
                const dSpan = document.createElement('span');
                dSpan.textContent = String(d);
                colEl.appendChild(dSpan);
            }
            digitsWrap.appendChild(colEl);
            digitColEls.push(colEl);
        }
        const pctSign = document.createElement('span');
        pctSign.className = 'wb-ruler-readout-pct';
        pctSign.textContent = '%';

        readoutEl.appendChild(digitsWrap);
        readoutEl.appendChild(pctSign);
        needleEl.appendChild(readoutEl);
        railEl.appendChild(needleEl);

        // 3. Hover Ghost Line
        ghostWrapEl = document.createElement('div');
        ghostWrapEl.className = 'wb-ruler-ghost-wrap';
        ghostWrapEl.setAttribute('aria-hidden', 'true');
        ghostLineEl = document.createElement('div');
        ghostLineEl.className = 'wb-ruler-ghost-line';
        ghostWrapEl.appendChild(ghostLineEl);

        // 4. Section Letters & Interactive Hit HUD
        hudEl = document.createElement('div');
        hudEl.className = 'wb-ruler-hud';

        rootEl.appendChild(poolsWrap);
        rootEl.appendChild(railEl);
        rootEl.appendChild(ghostWrapEl);
        rootEl.appendChild(hudEl);
        document.body.appendChild(rootEl);

        bindRailEvents();
    }

    function setZoomIntent(intent) {
        window.clearTimeout(state.zoomTimer);
        const delay = intent ? MOTION.zoomInDelay : MOTION.zoomOutDelay;
        state.zoomTimer = window.setTimeout(() => {
            state.zoomIntent = intent;
            requestTick();
        }, delay);
    }

    function bindRailEvents() {
        if (!railEl) return;

        railEl.addEventListener('pointerenter', () => {
            setZoomIntent(true);
            requestTick();
        });

        railEl.addEventListener('pointerleave', () => {
            state.ghostActive = false;
            if (!state.dragging) {
                setZoomIntent(false);
            }
            requestTick();
        });

        railEl.addEventListener('pointermove', (e) => {
            const map = rulerMap(state.vh, LAYOUT.edgePad, state.navH);
            const clampedY = Math.max(map.base, Math.min(map.base + map.span, e.clientY));
            const ratio = clamp01((clampedY - map.base) / map.span);
            state.ghostActive = true;
            state.ghostY = clampedY;

            if (state.dragging) {
                state.dragMoved = Math.max(state.dragMoved, Math.abs(e.clientY - state.dragStartY));
                const maxScroll = Math.max(0, state.docH - state.vh);
                if (maxScroll >= 60) {
                    const targetY = ratio * maxScroll;
                    const currentY = window.scrollY || 0;
                    const nextY = currentY + (targetY - currentY) * MOTION.dragLerp;
                    window.scrollTo(0, nextY);
                } else {
                    state.virtualScroll = ratio * 600;
                }
            }
            requestTick();
        });

        railEl.addEventListener('pointerdown', (e) => {
            if (e.button !== 0) return;
            e.preventDefault();
            cancelSmoothScroll();
            state.dragStartY = e.clientY;
            state.dragMoved = 0;
            state.dragging = true;
            state.armed = true;
            try {
                railEl.setPointerCapture(e.pointerId);
            } catch (_) {}
            requestTick();
        });

        const endDrag = (e) => {
            if (!state.dragging) return;
            state.dragging = false;
            try {
                railEl.releasePointerCapture(e.pointerId);
            } catch (_) {}
            const rect = railEl.getBoundingClientRect();
            const inside = e.clientX >= rect.left && e.clientX <= rect.right &&
                e.clientY >= rect.top && e.clientY <= rect.bottom;
            if (!inside) {
                state.ghostActive = false;
                setZoomIntent(false);
            }
            requestTick();
        };

        railEl.addEventListener('pointerup', endDrag);
        railEl.addEventListener('pointercancel', endDrag);

        railEl.addEventListener('click', (e) => {
            if (state.dragMoved > 5) return;
            const map = rulerMap(state.vh, LAYOUT.edgePad, state.navH);
            const ratio = clamp01((e.clientY - map.base) / map.span);
            const maxScroll = Math.max(0, state.docH - state.vh);
            state.armed = false;
            state.jumping = true;

            if (maxScroll >= 60) {
                const targetY = ratio * maxScroll;
                const dist = Math.abs(targetY - (window.scrollY || 0));
                const dur = MOTION.jumpDur * (0.4 + 0.6 * Math.min(1, dist / (2.5 * state.vh)));
                startSmoothScroll(targetY, dur, easeOutExpo, () => {
                    state.jumping = false;
                    requestTick();
                });
            } else {
                startSmoothScroll(ratio * 600, 0.55, easeOutExpo, () => {
                    state.jumping = false;
                    requestTick();
                });
            }
        });
    }

    // Build ticks on the rail for current document height
    function buildTicks() {
        if (!ticksTrackEl) return;
        ticksTrackEl.replaceChildren();
        tickData = [];
        tickRowEls = [];
        tickLineEls = [];
        tickLabelEls = [];

        if (!state.docH) return;
        ticksTrackEl.style.height = state.docH + 'px';

        for (let pct = 0; pct <= 100 + 1e-6; pct += LAYOUT.minorPct) {
            const rPct = Math.round(pct * 100) / 100;
            const isMajor = Math.abs(rPct % LAYOUT.labelPct) < 1e-6;
            const docY = (rPct / 100) * state.docH;
            const alpha = isMajor ? LOOK.majorAlpha : LOOK.minorAlpha;

            const rowEl = document.createElement('div');
            rowEl.className = 'wb-ruler-tick-row';
            rowEl.style.top = docY.toFixed(1) + 'px';

            const lineEl = document.createElement('span');
            lineEl.className = 'wb-ruler-tick-line';
            lineEl.style.width = (isMajor ? LOOK.majorLen : LOOK.minorLen) + 'px';
            lineEl.style.opacity = '0';
            lineEl.style.transform = 'scaleX(0)';
            rowEl.appendChild(lineEl);

            let labelEl = null;
            if (isMajor) {
                labelEl = document.createElement('span');
                labelEl.className = 'wb-ruler-tick-label';
                labelEl.style.opacity = String(LOOK.labelAlpha);
                labelEl.textContent = String(rPct);
                rowEl.appendChild(labelEl);
            }

            ticksTrackEl.appendChild(rowEl);
            tickData.push({ pct: rPct, docY: docY, major: isMajor, alpha: alpha });
            tickRowEls.push(rowEl);
            tickLineEls.push(lineEl);
            tickLabelEls.push(labelEl);
        }

        state.introStart = performance.now();
        state.introDone = reducedMotionMedia.matches;
    }

    // Build section letter spans and interactive hit targets
    function buildSectionDom() {
        if (!hudEl) return;
        hudEl.replaceChildren();
        sectionNodes = [];
        hoverWeights = new Float32Array(sections.length);
        flashUntil = new Float32Array(sections.length);
        hoveredSectionIdx = -1;

        if (!sections.length) {
            if (poolTopEl) poolTopEl.style.opacity = '0';
            if (poolBottomEl) poolBottomEl.style.opacity = '0';
            return;
        }

        let maxLabelPx = 220;
        for (let i = 0; i < sections.length; i++) {
            const m = labelMetrics[sections[i].label];
            if (m) {
                maxLabelPx = Math.max(maxLabelPx, m.total * LAYOUT.headingPx);
            }
        }
        const poolW = Math.round(maxLabelPx + LAYOUT.railW + LAYOUT.textPad + LOOK.poolPad);
        const poolTopH = Math.round(
            state.navH + LAYOUT.topPad + Math.max(0, sections.length - 1) * LAYOUT.queueSlot +
            LAYOUT.currentGap + LAYOUT.headingPx + LOOK.poolPad
        );
        const poolBottomH = Math.round(
            LAYOUT.queuePad + sections.length * LAYOUT.queueSlot + LOOK.poolPad
        );

        if (poolTopEl) {
            poolTopEl.style.width = poolW + 'px';
            poolTopEl.style.height = poolTopH + 'px';
        }
        if (poolBottomEl) {
            poolBottomEl.style.width = poolW + 'px';
            poolBottomEl.style.height = poolBottomH + 'px';
        }

        sections.forEach((sec, idx) => {
            const hit = document.createElement('a');
            hit.href = '#' + sec.id;
            hit.className = 'wb-ruler-hit';
            hit.setAttribute('aria-label', sec.fullTitle || sec.label);
            hit.setAttribute('title', sec.fullTitle || sec.label);
            hit.dataset.noPjax = 'true';

            hit.addEventListener('pointerenter', () => {
                hoveredSectionIdx = idx;
                requestTick();
            });

            hit.addEventListener('pointerleave', () => {
                if (hoveredSectionIdx === idx) {
                    hoveredSectionIdx = -1;
                    requestTick();
                }
            });

            hit.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                flashUntil[idx] = performance.now() + 380;
                state.armed = false;
                state.jumping = true;

                const maxScroll = Math.max(0, state.docH - state.vh);
                if (maxScroll >= 60) {
                    let targetY = 0;
                    if (idx > 0) {
                        const liveEl = document.getElementById(sec.id) || sec.el;
                        const domTarget = liveEl
                            ? liveEl.getBoundingClientRect().top + (window.scrollY || 0) - state.navH - 20
                            : sec.anchor - state.navH - 20;
                        // Ensure clicking a section reaches at least its flip completion scroll point
                        targetY = Math.max(domTarget, sec.normTrigger || 0);
                        targetY = Math.max(0, Math.min(maxScroll, targetY));
                    }
                    const dist = Math.abs(targetY - (window.scrollY || 0));
                    const dur = MOTION.jumpDur * (0.45 + 0.55 * Math.min(1, dist / (2.2 * state.vh)));
                    startSmoothScroll(targetY, dur, easeOutExpo, () => {
                        state.jumping = false;
                        pulseTargetElement(sec);
                        requestTick();
                    });
                } else {
                    const targetVirt = idx === 0 ? 0 : (sec.normTrigger || (idx / Math.max(1, sections.length - 1)) * 560);
                    startSmoothScroll(targetVirt, 0.55, easeOutExpo, () => {
                        state.jumping = false;
                        pulseTargetElement(sec);
                        requestTick();
                    });
                }
                requestTick();
            });

            hudEl.appendChild(hit);

            const letters = [];
            for (let c = 0; c < sec.chars.length; c++) {
                const chSpan = document.createElement('span');
                chSpan.className = 'wb-ruler-char';
                chSpan.setAttribute('aria-hidden', 'true');
                chSpan.style.fontSize = LAYOUT.headingPx + 'px';
                chSpan.textContent = sec.chars[c];
                hudEl.appendChild(chSpan);
                letters.push(chSpan);
            }

            sectionNodes.push({ hit: hit, letters: letters });
        });
    }

    function pulseTargetElement(sec) {
        const el = document.getElementById(sec.id) || sec.el;
        if (!el) return;
        el.classList.remove('wb-ruler-section-pulse');
        void el.offsetWidth;
        el.classList.add('wb-ruler-section-pulse');
        window.setTimeout(() => {
            el.classList.remove('wb-ruler-section-pulse');
        }, 1150);
    }

    function refreshLayoutAndSections() {
        const mode = detectPageMode();
        const isDesktop = desktopMedia.matches;

        if (!mode || !isDesktop) {
            teardownRuler();
            return;
        }

        activeMode = mode;
        const htmlEl = document.documentElement;
        htmlEl.dataset.ruler = reducedMotionMedia.matches ? 'static' : 'on';
        htmlEl.dataset.rulerSide = 'right';
        htmlEl.style.setProperty('--ruler-rail', LAYOUT.railW + 'px');

        ensureDom();
        rootEl.style.display = '';

        const docH = Math.max(
            document.documentElement.scrollHeight,
            document.body.scrollHeight,
            window.innerHeight
        );
        const vh = window.innerHeight || 1;
        const vw = window.innerWidth || 1;
        const navH = getNavHeight();

        const geomChanged = state.docH !== docH || state.vh !== vh || state.vw !== vw || state.navH !== navH;
        state.docH = docH;
        state.vh = vh;
        state.vw = vw;
        state.navH = navH;

        if (geomChanged || tickData.length === 0) {
            buildTicks();
        }

        sections = collectSections(mode);
        labelMetrics = measureLabels(sections);
        buildSectionDom();
        requestTick();
    }

    function teardownRuler() {
        activeMode = null;
        cancelSmoothScroll();
        if (state.rafId) {
            window.cancelAnimationFrame(state.rafId);
            state.rafId = 0;
        }
        const htmlEl = document.documentElement;
        delete htmlEl.dataset.ruler;
        delete htmlEl.dataset.rulerSide;
        htmlEl.style.removeProperty('--ruler-rail');
        if (rootEl) {
            rootEl.style.display = 'none';
        }
    }

    // Main 60/120fps Animation Frame Loop
    function tickFrame(now) {
        state.rafId = 0;
        if (!activeMode || !rootEl || !state.vh || !state.docH) return;

        const dt = state.lastFrameTime ? Math.min((now - state.lastFrameTime) / 1000, 0.1) : 0.016;
        state.lastFrameTime = now;

        let needsMoreFrames = false;

        // 1. Advance custom smooth scroll if active
        if (scrollAnim) {
            const elapsed = now - scrollAnim.startTime;
            const rawT = clamp01(elapsed / scrollAnim.durationMs);
            const easedT = scrollAnim.easing(rawT);
            const nextVal = scrollAnim.from + (scrollAnim.to - scrollAnim.from) * easedT;
            if (scrollAnim.useVirtual) {
                state.virtualScroll = nextVal;
            } else {
                window.scrollTo(0, nextVal);
            }
            if (rawT >= 1) {
                const cb = scrollAnim.onComplete;
                scrollAnim = null;
                if (typeof cb === 'function') cb(true);
            } else {
                needsMoreFrames = true;
            }
        }

        const vh = state.vh;
        const vw = state.vw;
        const docH = state.docH;
        const navH = state.navH;
        const maxScroll = Math.max(0, docH - vh);
        const useVirtual = maxScroll < 60;
        const effMaxScroll = useVirtual ? 600 : Math.max(1, maxScroll);
        const scrollY = useVirtual ? state.virtualScroll : (window.scrollY || 0);
        const progress = clamp01(scrollY / effMaxScroll);

        // Velocity tracking with exponential smoothing
        const instVel = (scrollY - state.lastY) / Math.max(0.004, dt);
        state.lastY = scrollY;
        state.vel += (instVel - state.vel) * (1 - Math.exp(-8 * dt));
        if (Math.abs(state.vel) > 0.8) {
            needsMoreFrames = true;
        } else {
            state.vel = 0;
        }

        const map = rulerMap(vh, LAYOUT.edgePad, navH);
        const drift = map.drift(progress);

        // 2. Update Zoom damping
        state.zoomTarget = (state.zoomIntent || state.dragging) ? 1 : 0;
        state.zoomP += (state.zoomTarget - state.zoomP) * (1 - Math.exp(-MOTION.zoomLerp * dt));
        if (Math.abs(state.zoomTarget - state.zoomP) > 0.001) {
            needsMoreFrames = true;
        } else {
            state.zoomP = state.zoomTarget;
        }
        const zoomEased = easeInOutCubic(state.zoomP);

        // 3. Update Section Pipeline (Bottom Queue -> Detach -> Vertical Rail -> Flip -> Top Stack)
        const flipLineY = (LAYOUT.thresholdPct / 100) * vh;
        const topBaseY = navH + LAYOUT.topPad;
        const bottomBaseY = vh - LAYOUT.queuePad;
        const D = LAYOUT.headingPx;
        const rideScale = LAYOUT.ridingPx / D;
        const queueScale = LAYOUT.queuePx / D;
        const railCenterX = vw - LAYOUT.railW / 2;

        // Dynamically compress slot spacing if viewport is tight with many sections
        const maxStackSpan = Math.max(110, (vh - navH - 180) * 0.42);
        const effSlot = sections.length > 2
            ? Math.min(LAYOUT.queueSlot, Math.max(13, maxStackSpan / (sections.length - 1)))
            : LAYOUT.queueSlot;

        const railTravelSpan = Math.max(260, (bottomBaseY + MOTION.detachZone) - flipLineY);
        const firstTrigger = sections.length > 1 ? Math.max(110, sections[1].normTrigger || 160) : 200;
        const travelScale = Math.max(1.2, (railTravelSpan * 0.86) / firstTrigger);

        const secStates = sections.map((sec, idx) => {
            const m = labelMetrics[sec.label];
            const rideLen = (m ? m.total : 1) * D * rideScale;
            let v = flipLineY;
            let flipP = 1;
            let detachP = 1;
            let triggerY = 0;

            if (idx > 0) {
                triggerY = sec.normTrigger || (idx / Math.max(1, sections.length - 1)) * effMaxScroll;
                v = flipLineY + (triggerY - scrollY) * travelScale;
                flipP = clamp01((flipLineY + MOTION.flipZone - v) / MOTION.flipZone);
                detachP = 0;
            }

            return {
                v: v,
                rideLen: rideLen,
                flipP: flipP,
                detachP: detachP,
                yq: bottomBaseY,
                triggerY: triggerY
            };
        });

        // Compute bottom queue vertical slots & detach progress from bottom-most section up to 1
        let qCount = 0;
        for (let i = sections.length - 1; i >= 1; i--) {
            const st = secStates[i];
            st.yq = bottomBaseY - effSlot * qCount;
            st.detachP = clamp01((st.yq + MOTION.detachZone - (st.v + st.rideLen)) / MOTION.detachZone);
            qCount += 1 - st.detachP;
        }

        railOccupancySpans.length = 0;
        let flippedCount = 0;
        let partialFlipSum = 0;
        for (let i = 0; i < secStates.length; i++) {
            if (secStates[i].flipP >= 1) flippedCount++;
            else if (secStates[i].flipP > 0) partialFlipSum += secStates[i].flipP;
        }

        let topPoolStrength = 0;
        let bottomPoolStrength = 0;
        for (let i = 0; i < secStates.length; i++) {
            if (secStates[i].flipP > topPoolStrength) topPoolStrength = secStates[i].flipP;
            if (i > 0) {
                const bStrength = 1 - secStates[i].detachP;
                if (bStrength > bottomPoolStrength) bottomPoolStrength = bStrength;
            }
        }
        if (poolTopEl) poolTopEl.style.opacity = (LOOK.poolAlpha * topPoolStrength).toFixed(3);
        if (poolBottomEl) poolBottomEl.style.opacity = (LOOK.poolAlpha * bottomPoolStrength).toFixed(3);

        // Coordinate helper B: Horizontal word right-aligned next to the rail
        const posHorizontal = (m, charIdx, y, scale, alpha) => {
            const charCenterFromLeft = (m.cum[charIdx] + m.adv[charIdx] / 2) * D * scale;
            const distFromRight = LAYOUT.railW + LAYOUT.textPad + (m.total * D * scale - charCenterFromLeft);
            return {
                x: vw - distFromRight,
                y: y,
                rot: 0,
                scale: scale,
                alpha: alpha
            };
        };

        // Coordinate helper Z: Vertical word (-90deg) riding on the center of the rail
        const posVertical = (m, charIdx, topY) => {
            return {
                x: railCenterX,
                y: topY + (m.total - m.cum[charIdx] - m.adv[charIdx] / 2) * D * rideScale,
                rot: -90,
                scale: rideScale,
                alpha: LOOK.queueAlpha
            };
        };

        // Transform every character of every section along quadratic Bezier flight paths
        for (let sIdx = 0; sIdx < sections.length; sIdx++) {
            const sec = sections[sIdx];
            const m = labelMetrics[sec.label];
            const node = sectionNodes[sIdx];
            if (!m || !node) continue;

            const st = secStates[sIdx];
            const h = st.v;
            const rideLen = st.rideLen;
            const flipP = st.flipP;
            const detachP = st.detachP;
            const yq = st.yq;
            const N = m.adv.length;

            let pVal = 1;
            let fromFn;
            let toFn;
            let reverseOrder = false;

            if (flipP > 0) {
                pVal = flipP;
                reverseOrder = true;
                fromFn = (cIdx) => posVertical(m, cIdx, h);
                if (flipP >= 1) {
                    const pushT = (sIdx === flippedCount - 1) ? partialFlipSum : 1;
                    const yTop = topBaseY + sIdx * effSlot + LAYOUT.currentGap * (1 - pushT);
                    const sTop = 1 - (1 - queueScale) * pushT;
                    const aTop = 1 - (1 - LOOK.queueAlpha) * pushT;
                    toFn = (cIdx) => posHorizontal(m, cIdx, yTop, sTop, aTop);
                } else {
                    const yActive = topBaseY + sIdx * effSlot + LAYOUT.currentGap;
                    toFn = (cIdx) => posHorizontal(m, cIdx, yActive, 1, 1);
                }
            } else if (detachP < 1) {
                pVal = detachP;
                reverseOrder = true;
                const railTargetTop = yq - rideLen;
                fromFn = (cIdx) => posHorizontal(m, cIdx, yq, queueScale, LOOK.queueAlpha);
                toFn = (cIdx) => posVertical(m, cIdx, railTargetTop);
            } else {
                pVal = 1;
                fromFn = toFn = (cIdx) => posVertical(m, cIdx, h);
            }

            const stag = MOTION.stagger;
            const totalStagSpan = 1 + Math.max(0, N - 1) * stag;
            const mapY = map.base + (sIdx === 0 ? 0 : clamp01(st.triggerY / effMaxScroll)) * map.span;

            // Smooth hover weight interpolation
            const targetHover = hoveredSectionIdx === sIdx ? 1 : 0;
            hoverWeights[sIdx] += (targetHover - hoverWeights[sIdx]) * (1 - Math.exp(-dt / 0.08));
            if (Math.abs(targetHover - hoverWeights[sIdx]) > 0.005) {
                needsMoreFrames = true;
            } else {
                hoverWeights[sIdx] = targetHover;
            }
            const hoverW = hoverWeights[sIdx];

            // Click flash interpolation
            const flashW = clamp01((flashUntil[sIdx] - now) / 380);
            if (flashW > 0) needsMoreFrames = true;

            let minX = Infinity;
            let maxX = -Infinity;
            let minY = Infinity;
            let maxY = -Infinity;
            let maxAlpha = 0;

            for (let cIdx = 0; cIdx < N; cIdx++) {
                const spanEl = node.letters[cIdx];
                if (!spanEl) continue;

                const orderIdx = reverseOrder ? (N - 1 - cIdx) : cIdx;
                const localLin = clamp01(pVal * totalStagSpan - orderIdx * stag);
                const d = easeInOutCubic(localLin);

                const p0 = fromFn(cIdx);
                const p2 = toFn(cIdx);
                // Quadratic Bezier control point bowing inward (-arc on right side)
                const cx = (p0.x + p2.x) * 0.5 - MOTION.arc;
                const cy = (p0.y + p2.y) * 0.5;
                const invD = 1 - d;

                let x = invD * invD * p0.x + 2 * invD * d * cx + d * d * p2.x;
                let y = invD * invD * p0.y + 2 * invD * d * cy + d * d * p2.y;
                let rot = p0.rot + (p2.rot - p0.rot) * d;
                let scale = p0.scale + (p2.scale - p0.scale) * d;
                let alpha = p0.alpha + (p2.alpha - p0.alpha) * d;

                // Blend toward full-page map position when hovering the ruler rail
                if (zoomEased > 0) {
                    const mapPos = posHorizontal(m, cIdx, mapY, queueScale, LOOK.mapAlpha);
                    x += (mapPos.x - x) * zoomEased;
                    y += (mapPos.y - y) * zoomEased;
                    rot *= (1 - zoomEased);
                    scale += (mapPos.scale - scale) * zoomEased;
                    alpha += (mapPos.alpha - alpha) * zoomEased;
                }

                spanEl.style.transform = `translate3d(${x.toFixed(2)}px, ${y.toFixed(2)}px, 0) translate(-50%, -50%) rotate(${rot.toFixed(2)}deg) scale(${scale.toFixed(3)})`;
                spanEl.style.opacity = '1';

                const effAlpha = alpha + (1 - alpha) * hoverW;
                let baseColor = effAlpha >= 0.99
                    ? 'var(--text-main)'
                    : `color-mix(in srgb, var(--text-main) ${(clamp01(effAlpha) * 100).toFixed(1)}%, var(--bg))`;

                const accentMix = Math.max(flashW * 0.85, hoverW * 0.55);
                spanEl.style.color = accentMix > 0.01
                    ? `color-mix(in srgb, var(--accent) ${(accentMix * 100).toFixed(0)}%, ${baseColor})`
                    : baseColor;

                if ( alpha > maxAlpha ) maxAlpha = alpha;

                const halfW = (m.adv[cIdx] * D * scale) * 0.5 + 2;
                const halfH = (D * scale) * 0.5 + 2;
                const isVert = Math.abs(rot) > 45;
                const rx = isVert ? halfH : halfW;
                const ry = isVert ? halfW : halfH;

                if (x - rx < minX) minX = x - rx;
                if (x + rx > maxX) maxX = x + rx;
                if (y - ry < minY) minY = y - ry;
                if (y + ry > maxY) maxY = y + ry;
            }

            // Record vertical rail occupancy so tick % labels yield (fade out) around riding text
            const onRailWeight = detachP * (1 - flipP);
            if (onRailWeight > 0.02) {
                railOccupancySpans.push({
                    top: h,
                    bottom: h + rideLen,
                    k: onRailWeight
                });
            }

            if (node.hit && Number.isFinite(minX)) {
                node.hit.style.left = (minX - 4).toFixed(1) + 'px';
                node.hit.style.top = (minY - 3).toFixed(1) + 'px';
                node.hit.style.width = Math.max(16, maxX - minX + 8).toFixed(1) + 'px';
                node.hit.style.height = Math.max(14, maxY - minY + 6).toFixed(1) + 'px';
                node.hit.style.pointerEvents = maxAlpha > 0.05 ? 'auto' : 'none';
            }
        }

        // 4. Flip Auto-Snap: if scroll rests with a section mid-flip (0.05 < flipP < 0.95), gently resolve it
        const midFlipIdx = secStates.findIndex(s => s.flipP > 0.05 && s.flipP < 0.95);
        if (state.dragging) state.armed = true;
        if (state.snapping) {
            if (midFlipIdx < 0) state.snapping = false;
        } else if (
            midFlipIdx > 0 &&
            state.armed &&
            !state.jumping &&
            Math.abs(state.vel) < 28 &&
            !state.dragging &&
            state.zoomP < 0.3 &&
            !reducedMotionMedia.matches
        ) {
            if (state.snapSince === null) {
                state.snapSince = now;
                needsMoreFrames = true;
            } else if (now - state.snapSince > MOTION.snapDelay) {
                const st = secStates[midFlipIdx];
                const snapComplete = st.flipP >= 0.5;
                const targetTriggerOffset = snapComplete
                    ? -4 / travelScale
                    : (MOTION.flipZone + 6) / travelScale;
                const snapTargetY = Math.max(0, Math.min(effMaxScroll, st.triggerY - targetTriggerOffset));
                state.snapping = true;
                state.armed = false;
                state.snapSince = null;
                startSmoothScroll(snapTargetY, MOTION.snapDur, easeInOutCubic, () => {
                    state.snapping = false;
                });
                needsMoreFrames = true;
            } else {
                needsMoreFrames = true;
            }
        } else {
            state.snapSince = null;
        }

        // 5. Update Ruler Ticks Track, Needle, Digits Readout, Bracket & Ghost Line
        const realScrollForTrack = progress * docH - progress * vh;
        const trackDrift = map.base - progress * (navH + 2 * LAYOUT.edgePad);
        if (ticksTrackEl) {
            ticksTrackEl.style.transform = `translate3d(0, ${(-realScrollForTrack + trackDrift).toFixed(2)}px, 0)`;
        }

        const needleY = map.base + progress * map.span;
        if (needleEl) {
            needleEl.style.transform = `translate3d(0, ${needleY.toFixed(2)}px, 0)`;
            const fadeRatio = clamp01(progress / Math.max(0.001, LOOK.needleFadePct / 100));
            needleEl.style.opacity = (0.42 + 0.58 * fadeRatio).toFixed(3);
        }

        const shouldReadoutAbove = needleY > vh - 28;
        if (readoutEl && shouldReadoutAbove !== state.readoutAbove) {
            state.readoutAbove = shouldReadoutAbove;
            readoutEl.style.top = shouldReadoutAbove ? '-15px' : '4px';
        }

        const pctInt = Math.round(progress * 100);
        if (pctInt !== state.prevPct) {
            state.prevPct = pctInt;
            if (railEl) railEl.setAttribute('aria-valuenow', String(pctInt));
            const padded = String(pctInt).padStart(3, '0');
            for (let c = 0; c < 3; c++) {
                const dVal = padded.charCodeAt(c) - 48;
                if (dVal !== state.prevDigits[c]) {
                    state.prevDigits[c] = dVal;
                    if (digitColEls[c]) {
                        digitColEls[c].style.transform = `translateY(${-dVal}em)`;
                    }
                }
            }
        }

        if (bracketEl) {
            const bracketTopY = map.base + (progress * (docH - vh) / Math.max(1, docH)) * map.span;
            const bracketH = Math.max(14, (vh / Math.max(1, docH)) * map.span);
            bracketEl.style.transform = `translate3d(0, ${bracketTopY.toFixed(2)}px, 0)`;
            bracketEl.style.height = bracketH.toFixed(2) + 'px';
            bracketEl.style.opacity = zoomEased.toFixed(3);
        }

        if (ghostWrapEl) {
            if (state.ghostActive) {
                ghostWrapEl.style.transform = `translate3d(0, ${state.ghostY.toFixed(1)}px, 0)`;
                ghostWrapEl.style.opacity = (LOOK.ghostAlpha * (0.25 + 0.75 * zoomEased)).toFixed(3);
            } else {
                ghostWrapEl.style.opacity = '0';
            }
        }

        // 6. Update individual ticks (Intro cascade, Zoom compression, Proximity Yield, Velocity stretch)
        const docFocusY = progress * docH;
        const velFactor = clamp01(Math.abs(state.vel) / 2600);
        const shouldWriteZoom = zoomEased > 0 || state.zoomWrote;
        const introElapsed = (now - state.introStart) / 1000;
        let allIntroFinished = true;

        for (let i = 0; i < tickData.length; i++) {
            const tk = tickData[i];
            const unzoomedScreenY = tk.docY - realScrollForTrack + trackDrift;
            const zoomedScreenY = map.base + (tk.pct / 100) * map.span;

            if (shouldWriteZoom && tickRowEls[i]) {
                tickRowEls[i].style.transform = zoomEased > 0
                    ? `translate3d(0, ${(zoomEased * (zoomedScreenY - unzoomedScreenY)).toFixed(2)}px, 0)`
                    : '';
            }

            // Proximity yield for major tick % labels when a vertical word rides past
            const labelEl = tickLabelEls[i];
            if (labelEl) {
                const effScreenY = unzoomedScreenY + zoomEased * (zoomedScreenY - unzoomedScreenY);
                let yieldFactor = 1;
                if (LOOK.yieldPad > 0 && zoomEased < 1) {
                    for (let s = 0; s < railOccupancySpans.length; s++) {
                        const sp = railOccupancySpans[s];
                        const dist = effScreenY < sp.top
                            ? sp.top - effScreenY
                            : effScreenY > sp.bottom
                                ? effScreenY - sp.bottom
                                : 0;
                        if (dist < LOOK.yieldPad) {
                            const f = 1 - sp.k * (1 - dist / LOOK.yieldPad);
                            if (f < yieldFactor) yieldFactor = f;
                        }
                    }
                }
                const labelOp = LOOK.labelAlpha * (1 - (1 - yieldFactor) * (1 - zoomEased));
                labelEl.style.opacity = labelOp.toFixed(3);
            }

            const lineEl = tickLineEls[i];
            if (!lineEl) continue;

            // Intro top-down cascade
            let introScale = 1;
            let introOp = 1;
            if (!state.introDone) {
                const delay = i * MOTION.introStagger;
                const tIntro = clamp01((introElapsed - delay) / MOTION.introDur);
                if (tIntro < 1) allIntroFinished = false;
                const easedIntro = 1 - Math.pow(1 - tIntro, 3);
                introScale = easedIntro;
                introOp = easedIntro;
            }

            const distFromNeedleDoc = Math.abs(tk.docY - docFocusY);
            if (distFromNeedleDoc < MOTION.velRadius && MOTION.velRadius > 0 && velFactor > 0.01) {
                const falloff = 1 - distFromNeedleDoc / MOTION.velRadius;
                const stretch = velFactor * falloff * falloff;
                lineEl.style.transform = `scaleX(${(introScale * (1 + MOTION.velMax * stretch)).toFixed(3)})`;
                lineEl.style.opacity = (introOp * Math.min(1, tk.alpha + 0.55 * stretch)).toFixed(3);
            } else {
                lineEl.style.transform = `scaleX(${introScale.toFixed(3)})`;
                lineEl.style.opacity = (introOp * tk.alpha).toFixed(3);
            }
        }

        if (!state.introDone) {
            if (allIntroFinished) state.introDone = true;
            else needsMoreFrames = true;
        }
        state.zoomWrote = zoomEased > 0;

        if (needsMoreFrames) {
            requestTick();
        } else {
            state.lastFrameTime = 0;
        }
    }

    function requestTick() {
        if (!state.rafId && activeMode) {
            state.rafId = window.requestAnimationFrame(tickFrame);
        }
    }

    // Event Listeners & Lifecycle Hooks
    function onUserScrollInput(e) {
        if (!activeMode) return;
        state.armed = true;
        if (scrollAnim) {
            cancelSmoothScroll();
            state.jumping = false;
            state.snapping = false;
        }
        // On non-scrollable short pages, let wheel events drive virtualScroll so the HUD still animates
        if (e && e.type === 'wheel' && Math.max(0, state.docH - state.vh) < 60) {
            state.virtualScroll = Math.max(0, Math.min(600, state.virtualScroll + e.deltaY * 0.65));
        }
        requestTick();
    }

    let refreshTimer = 0;
    function scheduleRefresh(delayMs) {
        window.clearTimeout(refreshTimer);
        refreshTimer = window.setTimeout(() => {
            refreshLayoutAndSections();
        }, delayMs || 60);
    }

    function init() {
        refreshLayoutAndSections();

        window.addEventListener('scroll', () => {
            requestTick();
        }, { passive: true });

        window.addEventListener('wheel', onUserScrollInput, { passive: true });
        window.addEventListener('touchmove', onUserScrollInput, { passive: true });
        window.addEventListener('keydown', (e) => {
            if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', 'Home', 'End', ' '].includes(e.key)) {
                onUserScrollInput(e);
            }
        });

        window.addEventListener('resize', () => scheduleRefresh(80), { passive: true });
        desktopMedia.addEventListener?.('change', () => scheduleRefresh(20));
        reducedMotionMedia.addEventListener?.('change', () => scheduleRefresh(20));

        // PJAX & dynamic content hooks
        document.addEventListener('page:ready', () => {
            state.virtualScroll = 0;
            state.prevPct = -1;
            state.prevDigits = [-1, -1, -1];
            scheduleRefresh(40);
        });
        document.addEventListener('pjax:complete', () => scheduleRefresh(60));
        document.addEventListener('sidebar:updated', () => scheduleRefresh(60));

        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(() => scheduleRefresh(40)).catch(() => {});
        }

        // Observe dynamic height changes (lazy images, comments, Vditor, unlocked paywall blocks)
        if (typeof ResizeObserver !== 'undefined') {
            const ro = new ResizeObserver(() => {
                if (!activeMode) return;
                const newDocH = Math.max(
                    document.documentElement.scrollHeight,
                    document.body.scrollHeight,
                    window.innerHeight
                );
                if (Math.abs(newDocH - state.docH) > 4) {
                    scheduleRefresh(90);
                }
            });
            ro.observe(document.body);
        }

        if (typeof MutationObserver !== 'undefined') {
            const mo = new MutationObserver((mutations) => {
                if (!activeMode) return;
                for (let i = 0; i < mutations.length; i++) {
                    const target = mutations[i].target;
                    if (target && rootEl && rootEl.contains(target)) continue;
                    scheduleRefresh(120);
                    break;
                }
            });
            const pjaxContainer = document.getElementById('pjax-container');
            if (pjaxContainer) {
                mo.observe(pjaxContainer, { childList: true, subtree: true });
            }
        }
    }

    if (document.readyState !== 'loading') {
        init();
    } else {
        document.addEventListener('DOMContentLoaded', init);
    }
})();
