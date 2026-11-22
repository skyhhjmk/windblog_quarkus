// /assets/js/inline-loader.js
// Progressive Inline Loading Overlay + Hybrid Steering Boids Around Pixel Chip +
// Swarm Rebuild List Animation with Startled Barrier Scatter & Half-Built Physics Throw.
(function () {
    'use strict';

    const TIER_RANK = { minimal: 0, low: 1, medium: 2, high: 3 };
    const AI_STATUS_WORDS = [
        'Thinking',
        'Analyzing',
        'Indexing',
        'Synthesizing',
        'Edited',
        'Rendering'
    ];
    const CURSOR_GLYPHS = ['\u2588', '/', '>'];
    const SWARM_PALETTE_DARK = [
        '#38bdf8', '#fb923c', '#a78bfa', '#34d399',
        '#f472b6', '#facc15', '#60a5fa', '#f87171',
        '#2dd4bf', '#c084fc', '#fbbf24', '#4ade80'
    ];
    const SWARM_PALETTE_LIGHT = [
        '#0284c7', '#ea580c', '#7c3aed', '#059669',
        '#db2777', '#ca8a04', '#2563eb', '#dc2626',
        '#0d9488', '#9333ea', '#d97706', '#16a34a'
    ];
    const AGENT_LABELS = [
        'core-01', 'parser', 'weaver', 'synth',
        'indexer', 'scribe', 'layout', 'flux',
        'vector', 'spark', 'matrix', 'node'
    ];

    // Track BFCache restoration so persisted pages NEVER replay the loader or swarm rebuild
    let isBfCacheRestore = false;
    try {
        if (window.__wbBfCachePersisted) {
            isBfCacheRestore = true;
        }
        const navEntries = performance.getEntriesByType && performance.getEntriesByType('navigation');
        if (navEntries && navEntries.length > 0 && navEntries[0].type === 'back_forward') {
            isBfCacheRestore = true;
        }
    } catch (_) {}

    window.addEventListener('pageshow', (event) => {
        if (event.persisted) {
            isBfCacheRestore = true;
            window.__wbBfCachePersisted = true;
            abortAndRevealAllImmediately();
        }
    });

    // Track live mouse coordinates for startled scatter convergence
    let mouseX = typeof window !== 'undefined' ? Math.round(window.innerWidth * 0.55) : 500;
    let mouseY = typeof window !== 'undefined' ? Math.round(window.innerHeight * 0.45) : 360;
    let hasRealPointer = false;

    window.addEventListener('pointermove', (e) => {
        if (typeof e.clientX === 'number' && typeof e.clientY === 'number') {
            mouseX = e.clientX;
            mouseY = e.clientY;
            hasRealPointer = true;
        }
    }, { passive: true });

    window.addEventListener('pointerdown', (e) => {
        if (typeof e.clientX === 'number' && typeof e.clientY === 'number') {
            mouseX = e.clientX;
            mouseY = e.clientY;
            hasRealPointer = true;
        }
    }, { passive: true });

    // =========================================================================
    // 1. Browser API Primary / Theme Color Resolution
    // =========================================================================
    function resolveBrowserThemeColors() {
        const root = document.documentElement;
        const isLight = root.classList.contains('light') ||
            (!root.classList.contains('dark') &&
             !localStorage.getItem('theme') &&
             window.matchMedia &&
             window.matchMedia('(prefers-color-scheme: light)').matches);

        let solidBg = isLight ? '#f8fafc' : '#090B10';
        let accentColor = isLight ? '#0284c7' : '#0ea5e9';
        let textColor = isLight ? '#0f172a' : '#f3f4f6';

        try {
            // 1. Computed CSS custom properties on root
            if (window.getComputedStyle) {
                const cs = window.getComputedStyle(root);
                const cssBg = cs.getPropertyValue('--bg').trim();
                const cssAccent = cs.getPropertyValue('--accent').trim();
                const cssText = cs.getPropertyValue('--text-main').trim();
                if (cssBg) solidBg = cssBg;
                if (cssAccent) accentColor = cssAccent;
                if (cssText) textColor = cssText;
            }
            // 2. Browser <meta name="theme-color"> API fallback/Synchronization
            const metaTheme = document.querySelector('meta[name="theme-color"]');
            if (metaTheme && metaTheme.content) {
                const metaColor = metaTheme.content.trim();
                if (metaColor && (!solidBg || solidBg === 'transparent')) {
                    solidBg = metaColor;
                }
            }
        } catch (_) {}

        return { isLight, solidBg, accentColor, textColor };
    }

    function applyThemeColorsToLoader(loaderEl) {
        if (!loaderEl) return;
        const { isLight, solidBg, accentColor, textColor } = resolveBrowserThemeColors();
        loaderEl.style.setProperty('--wb-loader-bg', solidBg);
        loaderEl.style.setProperty('--wb-loader-accent', accentColor);
        loaderEl.style.setProperty('--wb-loader-text', textColor);
        loaderEl.dataset.themeMode = isLight ? 'light' : 'dark';
    }

    // =========================================================================
    // 2. 2D Simplex / Value-Gradient Noise & Curl Noise Field
    // =========================================================================
    const PERM = new Uint8Array(512);
    const GRAD2 = [
        [1, 1], [-1, 1], [1, -1], [-1, -1],
        [1, 0], [-1, 0], [0, 1], [0, -1]
    ];
    (function initNoiseTable() {
        const p = new Uint8Array(256);
        for (let i = 0; i < 256; i++) p[i] = i;
        let seed = 1337;
        for (let i = 255; i > 0; i--) {
            seed = (seed * 16807) % 2147483647;
            const j = seed % (i + 1);
            const tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (let i = 0; i < 512; i++) PERM[i] = p[i & 255];
    })();

    function fade(t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    function lerp(a, b, t) {
        return a + t * (b - a);
    }

    function gradDot(hash, x, y) {
        const g = GRAD2[hash & 7];
        return g[0] * x + g[1] * y;
    }

    function perlin2D(x, y) {
        const X = Math.floor(x) & 255;
        const Y = Math.floor(y) & 255;
        const xf = x - Math.floor(x);
        const yf = y - Math.floor(y);
        const u = fade(xf);
        const v = fade(yf);

        const aa = PERM[PERM[X] + Y];
        const ab = PERM[PERM[X] + Y + 1];
        const ba = PERM[PERM[X + 1] + Y];
        const bb = PERM[PERM[X + 1] + Y + 1];

        const x1 = lerp(gradDot(aa, xf, yf), gradDot(ba, xf - 1, yf), u);
        const x2 = lerp(gradDot(ab, xf, yf - 1), gradDot(bb, xf - 1, yf - 1), u);
        return lerp(x1, x2, v);
    }

    // Divergence-free 2D Curl Noise: (dPsi/dy, -dPsi/dx)
    function sampleCurlNoise(x, y, t) {
        const eps = 0.015;
        const sx = x * 0.011 + t * 0.38;
        const sy = y * 0.011 - t * 0.31;
        const dPsiDy = (perlin2D(sx, sy + eps) - perlin2D(sx, sy - eps)) / (2 * eps);
        const dPsiDx = (perlin2D(sx + eps, sy) - perlin2D(sx - eps, sy)) / (2 * eps);
        return {
            x: dPsiDy,
            y: -dPsiDx
        };
    }

    // =========================================================================
    // 3. Inline Overlay Controller (Minimal -> Low -> Medium -> High)
    // =========================================================================
    let loaderEl = null;
    let cursorSymbolEl = null;
    let statusTrackEl = null;
    let chipEl = null;
    let orbitCanvas = null;
    let orbitCtx = null;

    let currentVisualTier = 'minimal';
    let overlayDismissed = false;
    let cursorCycleTimer = 0;
    let cursorGlyphIdx = 0;
    let statusTickerTimer = 0;
    let statusWordIdx = 0;
    let chipExpressionTimer = 0;
    let orbitRafId = 0;
    let orbitBoids = [];
    let orbitStartTime = performance.now();

    function getCurrentTier() {
        if (window.WindBlogPerf && typeof window.WindBlogPerf.getTier === 'function') {
            return window.WindBlogPerf.getTier();
        }
        return document.documentElement.dataset.perfTier || 'minimal';
    }

    function bindLoaderDom() {
        if (loaderEl) return true;
        loaderEl = document.getElementById('wb-inline-loader');
        if (!loaderEl) return false;
        cursorSymbolEl = document.getElementById('wb-loader-cursor-glyph');
        statusTrackEl = document.getElementById('wb-loader-status-word');
        chipEl = document.getElementById('wb-loader-chip-svg');
        orbitCanvas = document.getElementById('wb-loader-orbit-canvas');
        applyThemeColorsToLoader(loaderEl);
        return true;
    }

    // Low Tier: Smooth fade-in -> fade-out -> switch glyph while hidden (opacity 0) -> repeat
    function startLowTierCursorLoop() {
        if (cursorCycleTimer || !cursorSymbolEl) return;
        const halfCycleMs = 460; // 460ms fade in, 460ms fade out -> switch at valley
        if (window.__wbEarlyCursorTimer) {
            window.clearInterval(window.__wbEarlyCursorTimer);
            window.__wbEarlyCursorTimer = 0;
        }
        let fadedIn = true;
        cursorSymbolEl.classList.add('is-visible');

        cursorCycleTimer = window.setInterval(() => {
            if (overlayDismissed || !cursorSymbolEl) return;
            if (fadedIn) {
                cursorSymbolEl.classList.remove('is-visible');
                fadedIn = false;
            } else {
                // Hidden valley period: switch symbol & style
                cursorGlyphIdx = (cursorGlyphIdx + 1) % CURSOR_GLYPHS.length;
                const nextGlyph = CURSOR_GLYPHS[cursorGlyphIdx];
                cursorSymbolEl.textContent = nextGlyph;
                cursorSymbolEl.dataset.glyph = nextGlyph;
                cursorSymbolEl.classList.add('is-visible');
                fadedIn = true;
            }
        }, halfCycleMs);
    }

    // Medium Tier: Anthropomorphic GeekCoreLab Pixel Chip + Rolling Capitalized AI Status Words
    function startMediumTierChipAndStatus() {
        if (!statusTickerTimer && statusTrackEl) {
            statusTickerTimer = window.setInterval(() => {
                if (overlayDismissed || !statusTrackEl) return;
                statusWordIdx = (statusWordIdx + 1) % AI_STATUS_WORDS.length;
                const nextWord = AI_STATUS_WORDS[statusWordIdx];

                statusTrackEl.classList.add('is-rolling-out');
                window.setTimeout(() => {
                    if (!statusTrackEl) return;
                    statusTrackEl.textContent = nextWord;
                    statusTrackEl.classList.remove('is-rolling-out');
                    statusTrackEl.classList.add('is-rolling-in');
                    window.requestAnimationFrame(() => {
                        window.requestAnimationFrame(() => {
                            if (statusTrackEl) statusTrackEl.classList.remove('is-rolling-in');
                        });
                    });
                }, 170);

                // Pulse vortex speed slightly on status change
                orbitBoids.forEach((b) => {
                    b.boostUntil = performance.now() + 320;
                });
            }, 950);
        }

        if (!chipExpressionTimer && chipEl) {
            const expressions = ['neutral', 'look-left', 'look-right', 'blink', 'thinking', 'happy'];
            let expIdx = 0;
            chipExpressionTimer = window.setInterval(() => {
                if (overlayDismissed || !chipEl) return;
                expIdx = (expIdx + 1) % expressions.length;
                chipEl.dataset.expression = expressions[expIdx];
                if (expressions[expIdx] === 'blink') {
                    window.setTimeout(() => {
                        if (chipEl && chipEl.dataset.expression === 'blink') {
                            chipEl.dataset.expression = 'neutral';
                        }
                    }, 180);
                }
            }, 720);
        }
    }

    // High Tier: Hybrid Steering System (Boids + Centripetal + Vortex + Curl Noise)
    function initOrbitBoids(width, height) {
        const isLight = document.documentElement.classList.contains('light');
        const palette = isLight ? SWARM_PALETTE_LIGHT : SWARM_PALETTE_DARK;
        const cx = width * 0.5;
        const cy = height * 0.5;
        const count = 10;
        orbitBoids = [];

        for (let i = 0; i < count; i++) {
            const angle = (i / count) * Math.PI * 2 + (Math.random() - 0.5) * 0.35;
            const radius = 64 + (i % 3) * 14 + (Math.random() - 0.5) * 10;
            const speed = 2.1 + Math.random() * 0.7;
            const tx = -Math.sin(angle);
            const ty = Math.cos(angle);
            orbitBoids.push({
                id: i,
                x: cx + Math.cos(angle) * radius,
                y: cy + Math.sin(angle) * radius,
                vx: tx * speed,
                vy: ty * speed,
                orbitRadius: 64 + (i % 3) * 13,
                color: palette[i % palette.length],
                label: AGENT_LABELS[i % AGENT_LABELS.length],
                glyph: CURSOR_GLYPHS[i % CURSOR_GLYPHS.length],
                trail: [],
                phase: i * 1.37,
                boostUntil: 0
            });
        }
    }

    function stepAndDrawOrbitCanvas(now) {
        if (overlayDismissed || !orbitCanvas || !orbitCtx) {
            orbitRafId = 0;
            return;
        }
        const width = orbitCanvas.clientWidth || 320;
        const height = orbitCanvas.clientHeight || 320;
        const dpr = Math.min(2, window.devicePixelRatio || 1);
        if (orbitCanvas.width !== Math.round(width * dpr) || orbitCanvas.height !== Math.round(height * dpr)) {
            orbitCanvas.width = Math.round(width * dpr);
            orbitCanvas.height = Math.round(height * dpr);
            orbitCtx.setTransform(dpr, 0, 0, dpr, 0, 0);
        }

        if (orbitBoids.length === 0) {
            initOrbitBoids(width, height);
        }

        const cx = width * 0.5;
        const cy = height * 0.5;
        const t = (now - orbitStartTime) * 0.001;

        orbitCtx.clearRect(0, 0, width, height);

        // Physics parameters for Hybrid Steering System
        const sepDist = 26;
        const neighborDist = 68;
        const maxSpeed = 3.6;
        const minSpeed = 1.35;
        const maxForce = 0.24;

        for (let i = 0; i < orbitBoids.length; i++) {
            const b = orbitBoids[i];

            // 1. Boids local rules: Separation, Alignment, Cohesion
            let sepX = 0, sepY = 0, sepCount = 0;
            let aliX = 0, aliY = 0, aliCount = 0;
            let cohX = 0, cohY = 0, cohCount = 0;

            for (let j = 0; j < orbitBoids.length; j++) {
                if (i === j) continue;
                const other = orbitBoids[j];
                const dx = b.x - other.x;
                const dy = b.y - other.y;
                const distSq = dx * dx + dy * dy;
                if (distSq <= 0.0001 || distSq > neighborDist * neighborDist) continue;
                const dist = Math.sqrt(distSq);

                if (dist < sepDist) {
                    const inv = (sepDist - dist) / (sepDist * dist);
                    sepX += dx * inv;
                    sepY += dy * inv;
                    sepCount++;
                }
                aliX += other.vx;
                aliY += other.vy;
                aliCount++;

                cohX += other.x;
                cohY += other.y;
                cohCount++;
            }

            let fx = 0;
            let fy = 0;

            if (sepCount > 0) {
                fx += (sepX / sepCount) * 1.85;
                fy += (sepY / sepCount) * 1.85;
            }
            if (aliCount > 0) {
                fx += ((aliX / aliCount) - b.vx) * 0.08;
                fy += ((aliY / aliCount) - b.vy) * 0.08;
            }
            if (cohCount > 0) {
                const targetX = cohX / cohCount;
                const targetY = cohY / cohCount;
                fx += (targetX - b.x) * 0.004;
                fy += (targetY - b.y) * 0.004;
            }

            // 2. Centripetal Force Field + Tangential Vortex / Orbit Force Field
            const rx = b.x - cx;
            const ry = b.y - cy;
            const r = Math.max(1, Math.sqrt(rx * rx + ry * ry));
            const ux = rx / r;
            const uy = ry / r;

            // Breathing target orbit radius
            const dynamicOrbitR = b.orbitRadius + Math.sin(t * 2.2 + b.phase) * 7;
            const radialError = r - dynamicOrbitR;
            // Centripetal spring pull towards orbit ring
            fx += -ux * radialError * 0.045;
            fy += -uy * radialError * 0.045;

            // Tangential vortex force (counter-clockwise / clockwise smoothly)
            const tx = -uy;
            const ty = ux;
            const vortexBoost = now < b.boostUntil ? 1.55 : 1.0;
            fx += tx * 0.34 * vortexBoost;
            fy += ty * 0.34 * vortexBoost;

            // 3. Curl Noise (divergence-free turbulence to break synchronization)
            const curl = sampleCurlNoise(b.x + b.id * 37, b.y + b.id * 53, t);
            fx += curl.x * 0.42;
            fy += curl.y * 0.42;

            // Clamp steering force
            const fMag = Math.sqrt(fx * fx + fy * fy);
            if (fMag > maxForce) {
                fx = (fx / fMag) * maxForce;
                fy = (fy / fMag) * maxForce;
            }

            b.vx += fx;
            b.vy += fy;

            const speed = Math.sqrt(b.vx * b.vx + b.vy * b.vy);
            const effMax = maxSpeed * (now < b.boostUntil ? 1.25 : 1.0);
            if (speed > effMax) {
                b.vx = (b.vx / speed) * effMax;
                b.vy = (b.vy / speed) * effMax;
            } else if (speed < minSpeed && speed > 0.001) {
                b.vx = (b.vx / speed) * minSpeed;
                b.vy = (b.vy / speed) * minSpeed;
            }

            b.x += b.vx;
            b.y += b.vy;

            // Draw clean oriented cursor pointer arrowhead (no noisy trail lines)
            const angle = Math.atan2(b.vy, b.vx);
            orbitCtx.save();
            orbitCtx.translate(b.x, b.y);
            orbitCtx.rotate(angle);
            orbitCtx.globalAlpha = 0.95;
            orbitCtx.fillStyle = b.color;
            orbitCtx.beginPath();
            orbitCtx.moveTo(7, 0);
            orbitCtx.lineTo(-5, -4.2);
            orbitCtx.lineTo(-2.5, 0);
            orbitCtx.lineTo(-5, 4.2);
            orbitCtx.closePath();
            orbitCtx.fill();
            orbitCtx.restore();
        }

        orbitCtx.globalAlpha = 1;
        orbitRafId = window.requestAnimationFrame(stepAndDrawOrbitCanvas);
    }

    function startHighTierOrbit() {
        if (orbitRafId || !orbitCanvas) return;
        orbitCtx = orbitCanvas.getContext('2d');
        if (!orbitCtx) return;
        orbitStartTime = performance.now();
        orbitRafId = window.requestAnimationFrame(stepAndDrawOrbitCanvas);
    }

    // Progressive Monotonic Smooth Upgrade: minimal -> low -> medium -> high
    function syncOverlayTier(targetTier) {
        if (overlayDismissed || isBfCacheRestore) return;
        if (!bindLoaderDom()) return;

        const targetRank = TIER_RANK[targetTier] || 0;
        const currentRank = TIER_RANK[currentVisualTier] || 0;

        if (targetRank <= 0) {
            // Minimal tier: no animation, hide overlay immediately
            dismissInlineOverlay(true);
            return;
        }

        // Progressive upgrade: step through each tier smoothly
        if (targetRank >= 1 && currentRank < 1) {
            currentVisualTier = 'low';
            loaderEl.dataset.loaderTier = 'low';
            applyThemeColorsToLoader(loaderEl);
            startLowTierCursorLoop();
        }
        if (targetRank >= 2 && currentRank < 2) {
            currentVisualTier = 'medium';
            loaderEl.dataset.loaderTier = 'medium';
            startMediumTierChipAndStatus();
        }
        if (targetRank >= 3 && currentRank < 3) {
            currentVisualTier = 'high';
            loaderEl.dataset.loaderTier = 'high';
            startMediumTierChipAndStatus();
            startHighTierOrbit();
        }
    }

    function stopOverlayLoops() {
        if (window.__wbEarlyCursorTimer) {
            window.clearInterval(window.__wbEarlyCursorTimer);
            window.__wbEarlyCursorTimer = 0;
        }
        if (cursorCycleTimer) {
            window.clearInterval(cursorCycleTimer);
            cursorCycleTimer = 0;
        }
        if (statusTickerTimer) {
            window.clearInterval(statusTickerTimer);
            statusTickerTimer = 0;
        }
        if (chipExpressionTimer) {
            window.clearInterval(chipExpressionTimer);
            chipExpressionTimer = 0;
        }
        if (orbitRafId) {
            window.cancelAnimationFrame(orbitRafId);
            orbitRafId = 0;
        }
    }

    function dismissInlineOverlay(instant) {
        if (overlayDismissed) return;
        overlayDismissed = true;
        stopOverlayLoops();

        const el = loaderEl || document.getElementById('wb-inline-loader');
        if (!el) return;

        if (instant || currentVisualTier === 'minimal') {
            el.classList.add('is-dismissed');
            el.setAttribute('aria-hidden', 'true');
            el.style.display = 'none';
            return;
        }

        el.classList.add('is-leaving');
        el.setAttribute('aria-hidden', 'true');
        window.setTimeout(() => {
            if (el) {
                el.classList.add('is-dismissed');
                el.style.display = 'none';
            }
        }, 240);
    }

    // =========================================================================
    // 4. Swarm Rebuild List Engine + Zero-CLS Typing + Startled Scatter & Throw
    // =========================================================================
    const LIST_ITEM_SELECTOR = [
        '#pjax-content-root article[data-article-transition-card]:not(.card-static)',
        '#pjax-content-root article.card:not(.card-static)',
        '#pjax-content-root .link-grid .link-card',
        '#pjax-content-root #toolsIndexGrid [data-tool-card="true"]'
    ].join(', ');

    function collectArticleBodyBlocks(root) {
        const scope = root || document.getElementById('pjax-content-root') || document;
        if (!scope || typeof scope.querySelector !== 'function') return [];
        const bodyRoot = scope.querySelector('#post-body-content, #vditor-preview');
        if (!bodyRoot) return [];

        const rawChildren = Array.from(bodyRoot.children || []);
        const blocks = [];
        for (let i = 0; i < rawChildren.length; i++) {
            const el = rawChildren[i];
            if (!el || el.hidden || el.classList.contains('hidden')) continue;
            if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE' || el.tagName === 'TEMPLATE') continue;
            const r = el.getBoundingClientRect();
            if (r.width <= 0 && r.height <= 0) continue;
            blocks.push(el);
        }
        return blocks;
    }

    function isPlainTypableParagraph(el) {
        if (!el) return false;
        const tag = el.tagName;
        if (!/^H[1-6]$/.test(tag) && tag !== 'P') return false;
        // Skip paragraphs that contain images, videos, svgs, iframes or complex interactive embeds
        if (el.querySelector('img, video, audio, iframe, svg, canvas, pre, table, button, input')) {
            return false;
        }
        const text = (el.textContent || '').replace(/\s+/g, ' ').trim();
        return text.length >= 2 && text.length <= 420;
    }

    let activeSwarmSession = null;
    // [临时调试用] 动画速度倍率 (1.0 = 正常速度，如 0.25 = 0.25倍慢速播放)
    let debugTimeScale = 1.0;

    function collectListItems(root) {
        const scope = root || document.getElementById('pjax-content-root') || document;
        if (!scope || typeof scope.querySelectorAll !== 'function') return [];
        const nodes = Array.from(scope.querySelectorAll(LIST_ITEM_SELECTOR));
        // Deduplicate & filter visible cards
        const unique = [];
        const seen = new Set();
        for (let i = 0; i < nodes.length; i++) {
            const el = nodes[i];
            if (seen.has(el)) continue;
            seen.add(el);
            if (el.hidden || el.classList.contains('hidden')) continue;
            unique.push(el);
        }
        return unique;
    }

    // Per-URL build progress cache so FLIP entering an article and returning back
    // inherits the exact previous build progress (completed cards stay built,
    // partially built cards resume remaining parts, unseen offscreen cards wait for scroll).
    const SWARM_CACHE_STORAGE_KEY = 'wb_swarm_progress_v1';
    const pageProgressCache = new Map();

    function getPageCacheKey() {
        return (window.location.pathname || '/') + (window.location.search || '');
    }

    function readPageProgress(pageKey) {
        if (pageProgressCache.has(pageKey)) {
            return pageProgressCache.get(pageKey);
        }
        try {
            const raw = sessionStorage.getItem(SWARM_CACHE_STORAGE_KEY);
            if (raw) {
                const all = JSON.parse(raw);
                if (all && all[pageKey]) {
                    pageProgressCache.set(pageKey, all[pageKey]);
                    return all[pageKey];
                }
            }
        } catch (_) {}
        const empty = { completed: {}, partial: {} };
        pageProgressCache.set(pageKey, empty);
        return empty;
    }

    function persistPageProgress(pageKey, state) {
        pageProgressCache.set(pageKey, state);
        try {
            const raw = sessionStorage.getItem(SWARM_CACHE_STORAGE_KEY);
            const all = raw ? JSON.parse(raw) : {};
            all[pageKey] = state;
            // Keep at most 12 recent list pages in sessionStorage
            const keys = Object.keys(all);
            if (keys.length > 12) {
                delete all[keys[0]];
            }
            sessionStorage.setItem(SWARM_CACHE_STORAGE_KEY, JSON.stringify(all));
        } catch (_) {}
    }

    function clearPageProgress(pageKey) {
        const key = pageKey || getPageCacheKey();
        pageProgressCache.delete(key);
        try {
            const raw = sessionStorage.getItem(SWARM_CACHE_STORAGE_KEY);
            if (raw) {
                const all = JSON.parse(raw);
                delete all[key];
                sessionStorage.setItem(SWARM_CACHE_STORAGE_KEY, JSON.stringify(all));
            }
        } catch (_) {}
    }

    // Wrap a text element with a Zero-CLS typing overlay:
    // The original text remains in-flow with transparent color so exact line breaks and box height
    // never shift by even 0.1px. IMPORTANT: never set position:relative directly on an <a> with
    // Tailwind `after:inset-0`, otherwise the stretched-link hit area collapses and breaks FLIP PJAX!
    function prepareZeroClsTypingTarget(el) {
        if (!el || el.dataset.wbTypingPrepared === 'true') return null;
        let targetNode = el;
        if ((el.tagName === 'H2' || el.tagName === 'H3') && el.querySelector('a')) {
            targetNode = el.querySelector('a');
        }
        const fullText = (targetNode.textContent || '').replace(/\s+/g, ' ').trim();
        if (!fullText) return null;

        el.dataset.wbTypingPrepared = 'true';

        let hostNode = targetNode;
        let wrapperSpan = null;
        let origPosition = '';

        if (targetNode.tagName === 'A') {
            // Wrap inner content in a relative block span so <a> stays position:static and
            // its `after:absolute after:inset-0` stretched link still covers the entire card!
            wrapperSpan = document.createElement('span');
            wrapperSpan.className = 'wb-swarm-typing-wrap wb-swarm-typing-host';
            wrapperSpan.style.position = 'relative';
            wrapperSpan.style.display = 'block';
            while (targetNode.firstChild) {
                wrapperSpan.appendChild(targetNode.firstChild);
            }
            targetNode.appendChild(wrapperSpan);
            hostNode = wrapperSpan;
        } else {
            const computed = window.getComputedStyle(targetNode);
            origPosition = targetNode.style.position;
            if (computed.position === 'static') {
                targetNode.style.position = 'relative';
            }
            targetNode.classList.add('wb-swarm-typing-host');
        }

        const overlay = document.createElement('span');
        overlay.className = 'wb-swarm-typing-overlay';
        overlay.setAttribute('aria-hidden', 'true');

        const typedSpan = document.createElement('span');
        typedSpan.className = 'wb-swarm-typed-text';
        typedSpan.textContent = '';

        const caretSpan = document.createElement('span');
        caretSpan.className = 'wb-swarm-typed-caret';
        caretSpan.textContent = '\u2588';

        overlay.appendChild(typedSpan);
        overlay.appendChild(caretSpan);
        hostNode.appendChild(overlay);

        return {
            hostEl: el,
            targetNode,
            hostNode,
            wrapperSpan,
            origPosition,
            overlay,
            typedSpan,
            caretSpan,
            fullText,
            progress: 0,
            done: false,
            frozen: false
        };
    }

    function restoreZeroClsTypingTarget(typingState, freezeHalfBuilt) {
        if (!typingState) return;
        if (freezeHalfBuilt && !typingState.done) {
            typingState.frozen = true;
            if (typingState.caretSpan) {
                typingState.caretSpan.textContent = '\u258F';
                typingState.caretSpan.classList.add('is-frozen');
            }
            return;
        }
        typingState.done = true;
        if (typingState.overlay && typingState.overlay.parentNode) {
            typingState.overlay.remove();
        }
        if (typingState.wrapperSpan && typingState.wrapperSpan.parentNode) {
            const parent = typingState.wrapperSpan.parentNode;
            while (typingState.wrapperSpan.firstChild) {
                parent.insertBefore(typingState.wrapperSpan.firstChild, typingState.wrapperSpan);
            }
            typingState.wrapperSpan.remove();
        } else if (typingState.targetNode) {
            typingState.targetNode.classList.remove('wb-swarm-typing-host');
            if (typingState.origPosition) {
                typingState.targetNode.style.position = typingState.origPosition;
            } else {
                typingState.targetNode.style.removeProperty('position');
            }
        }
        if (typingState.hostEl) {
            delete typingState.hostEl.dataset.wbTypingPrepared;
        }
    }

    function abortAndRevealAllImmediately() {
        dismissInlineOverlay(true);
        if (activeSwarmSession) {
            activeSwarmSession.abortImmediately();
            activeSwarmSession = null;
        }
        document.querySelectorAll('.wb-swarm-item-pending, .wb-swarm-item-building, .wb-swarm-item-half-frozen').forEach((el) => {
            el.classList.remove(
                'wb-swarm-item-pending', 'wb-swarm-item-building', 'wb-swarm-item-half-frozen',
                'wb-has-meta', 'wb-has-cover', 'wb-has-title', 'wb-has-summary', 'wb-has-footer'
            );
            el.style.removeProperty('min-height');
            el.style.removeProperty('opacity');
            el.style.removeProperty('transform');
        });
        document.querySelectorAll('.wb-swarm-block-pending, .wb-swarm-block-building, .wb-swarm-block-built').forEach((el) => {
            el.classList.remove('wb-swarm-block-pending', 'wb-swarm-block-building', 'wb-swarm-block-built');
        });
        document.querySelectorAll('.wb-swarm-tag-pending, .wb-swarm-tag-built').forEach((el) => {
            el.classList.remove('wb-swarm-tag-pending', 'wb-swarm-tag-built');
        });
        document.querySelectorAll('.wb-swarm-typing-overlay').forEach((el) => el.remove());
        document.querySelectorAll('.wb-swarm-typing-wrap').forEach((wrap) => {
            const parent = wrap.parentNode;
            if (parent) {
                while (wrap.firstChild) parent.insertBefore(wrap.firstChild, wrap);
                wrap.remove();
            }
        });
        document.querySelectorAll('.wb-swarm-typing-host').forEach((el) => el.classList.remove('wb-swarm-typing-host'));
        document.documentElement.classList.remove('wb-swarm-preblock');
    }

    function easeOutCubic(t) {
        return 1 - Math.pow(1 - Math.max(0, Math.min(1, t)), 3);
    }

    function easeInOutCubic(t) {
        const x = Math.max(0, Math.min(1, t));
        return x < 0.5 ? 4 * x * x * x : 1 - Math.pow(-2 * x + 2, 3) / 2;
    }

    function startSwarmRebuildForPage(options) {
        const forceDebug = Boolean(options && options.debugForce);
        if (isBfCacheRestore && !forceDebug) return;
        const tier = getCurrentTier();
        if (tier !== 'high' && !forceDebug) return;
        if (!forceDebug && window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;

        if (activeSwarmSession) {
            activeSwarmSession.abortImmediately();
            activeSwarmSession = null;
        }

        const articleBlocks = collectArticleBodyBlocks();
        const isArticlePage = articleBlocks.length > 0;
        const items = isArticlePage ? articleBlocks : collectListItems();
        if (items.length === 0) {
            document.documentElement.classList.remove('wb-swarm-preblock');
            return;
        }

        const isLight = document.documentElement.classList.contains('light');
        const palette = isLight ? SWARM_PALETTE_LIGHT : SWARM_PALETTE_DARK;

        // Capture exact screen coordinates of orbiting cursors BEFORE dismissing overlay
        // so Swarm cursors transition smoothly from chip orbit with zero teleporting or streaking
        const orbitScreenCoords = [];
        if (options && options.fromOverlay && orbitCanvas && orbitBoids.length > 0) {
            const cr = orbitCanvas.getBoundingClientRect();
            if (cr.width > 0 && cr.height > 0) {
                for (let i = 0; i < orbitBoids.length; i++) {
                    orbitScreenCoords.push({
                        x: cr.left + orbitBoids[i].x,
                        y: cr.top + orbitBoids[i].y,
                        color: orbitBoids[i].color,
                        name: orbitBoids[i].label
                    });
                }
            }
        }

        const pageKey = getPageCacheKey();
        if (forceDebug) {
            clearPageProgress(pageKey);
        }
        const cachedPage = readPageProgress(pageKey);

        const stageHInit = window.innerHeight || 800;
        const cardJobs = items.map((cardEl, idx) => {
            if (isArticlePage) {
                const blockKey = 'blk:' + idx + ':' + cardEl.tagName.toLowerCase();
                const rect = cardEl.getBoundingClientRect();
                const isInViewport = rect.bottom > 0 && rect.top < stageHInit;
                const alreadyCompleted = Boolean(cachedPage.completed && cachedPage.completed[blockKey]);
                const typable = !alreadyCompleted && isPlainTypableParagraph(cardEl);
                const blockTyping = typable ? prepareZeroClsTypingTarget(cardEl) : null;

                if (!alreadyCompleted) {
                    cardEl.classList.add('wb-swarm-block-pending');
                }
                cardEl.dataset.wbSwarmIndex = String(idx);

                return {
                    index: idx,
                    isArticleBlock: true,
                    cardKey: blockKey,
                    cardEl,
                    rect,
                    isInViewport,
                    metaEl: null,
                    coverEl: null,
                    titleEl: null,
                    summaryBoxEl: null,
                    summaryTextEl: null,
                    footerEl: null,
                    badgeEls: [],
                    titleTyping: blockTyping,
                    summaryTyping: null,
                    state: alreadyCompleted ? 'completed' : 'pending',
                    tasksEnqueued: alreadyCompleted,
                    frameBuilt: true,
                    hasMeta: true,
                    hasCover: true,
                    hasTitle: alreadyCompleted || !blockTyping,
                    hasSummary: true,
                    hasFooter: true,
                    builtBadgeSet: new Set(),
                    progress: alreadyCompleted ? 1 : 0,
                    remainingTasks: 0,
                    totalTasks: 0,
                    assignedAgent: null
                };
            }

            const cardKey = cardEl.dataset.articleTransitionSlug ||
                cardEl.dataset.toolId ||
                cardEl.dataset.id ||
                ('idx:' + idx);

            const rect = cardEl.getBoundingClientRect();
            const isInViewport = rect.bottom > 0 && rect.top < stageHInit;

            const titleEl = cardEl.querySelector('[data-article-transition-part="title"], h2, h3');
            const summaryBoxEl = cardEl.querySelector('[data-article-transition-part="summary"]');
            const summaryTextEl = cardEl.querySelector('[data-article-transition-part="summary"] p, p.line-clamp-3, p.line-clamp-2');
            const metaEl = cardEl.querySelector('[data-article-transition-part="meta"], .meta-mono');
            const coverEl = cardEl.querySelector('[data-article-transition-part="cover"]');
            const footerEl = cardEl.querySelector('.border-t');
            const badgeEls = Array.from(
                cardEl.querySelectorAll('.badge-category, .badge-tag, .tag-pill, .border-t > a')
            ).filter((el) => {
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            });

            // Inherit cached progress when returning via FLIP / PJAX
            const alreadyCompleted = Boolean(cachedPage.completed && cachedPage.completed[cardKey]);
            const savedPartial = (!alreadyCompleted && cachedPage.partial && cachedPage.partial[cardKey])
                ? cachedPage.partial[cardKey]
                : null;

            const builtParts = {
                frameBuilt: Boolean(alreadyCompleted || (savedPartial && savedPartial.frameBuilt)),
                hasMeta: Boolean(alreadyCompleted || (savedPartial && savedPartial.hasMeta)),
                hasCover: Boolean(alreadyCompleted || (savedPartial && savedPartial.hasCover)),
                hasTitle: Boolean(alreadyCompleted || (savedPartial && savedPartial.hasTitle)),
                hasSummary: Boolean(alreadyCompleted || (savedPartial && savedPartial.hasSummary)),
                hasFooter: Boolean(alreadyCompleted || (savedPartial && savedPartial.hasFooter)),
                builtBadgeSet: new Set(
                    alreadyCompleted
                        ? badgeEls.map((_, bi) => bi)
                        : (savedPartial && Array.isArray(savedPartial.builtBadges) ? savedPartial.builtBadges : [])
                )
            };

            let titleTyping = null;
            let summaryTyping = null;

            if (!alreadyCompleted) {
                if (rect.height > 0) {
                    cardEl.style.minHeight = rect.height.toFixed(1) + 'px';
                }
                if (!builtParts.hasTitle) {
                    titleTyping = prepareZeroClsTypingTarget(titleEl);
                }
                if (!builtParts.hasSummary) {
                    summaryTyping = prepareZeroClsTypingTarget(summaryTextEl);
                }
                if (builtParts.frameBuilt) {
                    cardEl.classList.add('wb-swarm-item-building');
                    if (builtParts.hasMeta) cardEl.classList.add('wb-has-meta');
                    if (builtParts.hasCover) cardEl.classList.add('wb-has-cover');
                    if (builtParts.hasTitle) cardEl.classList.add('wb-has-title');
                    if (builtParts.hasSummary) cardEl.classList.add('wb-has-summary');
                    if (builtParts.hasFooter) cardEl.classList.add('wb-has-footer');
                } else {
                    // Both in-viewport and off-screen unbuilt cards start hidden until dragged into view!
                    cardEl.classList.add('wb-swarm-item-pending');
                }
                badgeEls.forEach((b, bi) => {
                    if (builtParts.builtBadgeSet.has(bi)) {
                        b.classList.add('wb-swarm-tag-built');
                    } else {
                        b.classList.add('wb-swarm-tag-pending');
                    }
                });
            }

            cardEl.dataset.wbSwarmIndex = String(idx);

            return {
                index: idx,
                cardKey,
                cardEl,
                rect,
                isInViewport,
                metaEl,
                coverEl,
                titleEl,
                summaryBoxEl,
                summaryTextEl,
                footerEl,
                badgeEls,
                titleTyping,
                summaryTyping,
                state: alreadyCompleted ? 'completed' : 'pending', // 'pending' | 'building' | 'completed' | 'frozen'
                tasksEnqueued: alreadyCompleted,
                frameBuilt: builtParts.frameBuilt,
                hasMeta: builtParts.hasMeta,
                hasCover: builtParts.hasCover,
                hasTitle: builtParts.hasTitle,
                hasSummary: builtParts.hasSummary,
                hasFooter: builtParts.hasFooter,
                builtBadgeSet: builtParts.builtBadgeSet,
                progress: alreadyCompleted ? 1 : 0,
                remainingTasks: 0,
                totalTasks: 0,
                assignedAgent: null
            };
        });
        document.documentElement.classList.remove('wb-swarm-preblock');

        // Build discrete single-purpose Task Queue (Swarm Rebuild: 1 cursor = 1 atomic task!)
        // Off-screen cards are NOT enqueued until IntersectionObserver sees them scroll into view.
        const taskQueue = [];
        function enqueueTasksForJob(job) {
            if (!job || job.tasksEnqueued || job.state === 'completed' || job.state === 'frozen') return;
            job.tasksEnqueued = true;

            const addTask = (task) => {
                job.totalTasks++;
                job.remainingTasks++;
                taskQueue.push(task);
            };

            // Article detail page: build each paragraph/heading/code-block/quote/image individually!
            if (job.isArticleBlock) {
                if (job.titleTyping) {
                    const isHeading = /^H[1-6]$/.test(job.cardEl.tagName);
                    addTask({
                        type: 'type-article-block',
                        job,
                        el: job.cardEl,
                        typing: job.titleTyping,
                        requiresFrame: false,
                        status: 'queued',
                        moveDur: 130,
                        workDur: isHeading
                            ? Math.min(360, Math.max(200, job.titleTyping.fullText.length * 14))
                            : Math.min(460, Math.max(240, job.titleTyping.fullText.length * 4)),
                        stageStart: 0
                    });
                } else {
                    addTask({
                        type: 'drag-article-block',
                        job,
                        el: job.cardEl,
                        requiresFrame: false,
                        status: 'queued',
                        moveDur: 145,
                        workDur: 230,
                        stageStart: 0
                    });
                }
                return;
            }

            if (!job.frameBuilt) {
                addTask({
                    type: 'drag-frame',
                    job,
                    el: job.cardEl,
                    requiresFrame: false,
                    status: 'queued',
                    moveDur: 185,
                    workDur: 270,
                    stageStart: 0
                });
            }

            if (job.coverEl && !job.hasCover) {
                addTask({
                    type: 'drag-cover',
                    job,
                    el: job.coverEl,
                    requiresFrame: true,
                    status: 'queued',
                    moveDur: 155,
                    workDur: 210,
                    stageStart: 0
                });
            }

            if (job.metaEl && !job.hasMeta) {
                addTask({
                    type: 'drag-meta',
                    job,
                    el: job.metaEl,
                    requiresFrame: true,
                    status: 'queued',
                    moveDur: 140,
                    workDur: 165,
                    stageStart: 0
                });
            }

            if (job.titleTyping && !job.hasTitle) {
                addTask({
                    type: 'type-title',
                    job,
                    el: job.titleEl,
                    typing: job.titleTyping,
                    requiresFrame: true,
                    status: 'queued',
                    moveDur: 145,
                    workDur: Math.min(500, Math.max(290, job.titleTyping.fullText.length * 14)),
                    stageStart: 0
                });
            }

            if (job.summaryTyping && !job.hasSummary) {
                addTask({
                    type: 'type-summary',
                    job,
                    el: job.summaryBoxEl || job.summaryTextEl,
                    typing: job.summaryTyping,
                    requiresFrame: true,
                    status: 'queued',
                    moveDur: 155,
                    workDur: Math.min(600, Math.max(340, job.summaryTyping.fullText.length * 8)),
                    stageStart: 0
                });
            }

            job.badgeEls.forEach((badgeEl, badgeIdx) => {
                if (job.builtBadgeSet.has(badgeIdx)) return;
                addTask({
                    type: 'drag-badge',
                    job,
                    el: badgeEl,
                    badgeIdx,
                    requiresFrame: true,
                    status: 'queued',
                    moveDur: 120,
                    workDur: 115,
                    stageStart: 0
                });
            });

            if (job.remainingTasks === 0) {
                completeJobNormally(job);
            }
        }

        function saveJobProgressToCache(job) {
            if (!job || !job.cardKey) return;
            if (job.state === 'completed') {
                cachedPage.completed[job.cardKey] = true;
                delete cachedPage.partial[job.cardKey];
            } else if (job.frameBuilt || job.hasMeta || job.hasCover || job.hasTitle || job.hasSummary || job.builtBadgeSet.size > 0) {
                cachedPage.partial[job.cardKey] = {
                    frameBuilt: job.frameBuilt,
                    hasMeta: job.hasMeta,
                    hasCover: job.hasCover,
                    hasTitle: job.hasTitle,
                    hasSummary: job.hasSummary,
                    hasFooter: job.hasFooter,
                    builtBadges: Array.from(job.builtBadgeSet)
                };
            }
            persistPageProgress(pageKey, cachedPage);
        }

        // Only enqueue cards currently visible in the viewport!
        cardJobs.forEach((job) => {
            if (job.isInViewport && job.state !== 'completed') {
                enqueueTasksForJob(job);
            }
        });

        const hasUnbuiltOffscreen = cardJobs.some((job) => !job.tasksEnqueued && job.state !== 'completed');
        if (taskQueue.length === 0 && !hasUnbuiltOffscreen) {
            return;
        }

        // Create Fixed Viewport Canvas for Swarm Cursors, Drag-Boxes & Barrier Field
        const stageCanvas = document.createElement('canvas');
        stageCanvas.className = 'wb-swarm-stage-canvas';
        stageCanvas.setAttribute('aria-hidden', 'true');
        document.body.appendChild(stageCanvas);
        const ctx = stageCanvas.getContext('2d');

        let stageW = window.innerWidth || 1280;
        let stageH = window.innerHeight || 800;
        const syncStageSize = () => {
            stageW = window.innerWidth || 1280;
            stageH = window.innerHeight || 800;
            const dpr = Math.min(2, window.devicePixelRatio || 1);
            stageCanvas.width = Math.round(stageW * dpr);
            stageCanvas.height = Math.round(stageH * dpr);
            if (ctx) ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        };
        syncStageSize();

        // Spawn 6 colored Swarm Agents cleanly from their exact orbit positions (or subtle top-center fan on PJAX)
        const agentCount = 6;
        const originX = stageW * 0.5;
        const originY = options && options.fromOverlay ? stageH * 0.46 : Math.min(160, stageH * 0.22);

        const agents = [];
        for (let i = 0; i < agentCount; i++) {
            const fromOrbit = orbitScreenCoords[i];
            const angle = (i / agentCount) * Math.PI * 2;
            agents.push({
                id: i,
                name: fromOrbit ? fromOrbit.name : AGENT_LABELS[i % AGENT_LABELS.length],
                color: fromOrbit ? fromOrbit.color : palette[i % palette.length],
                x: fromOrbit ? fromOrbit.x : (originX + Math.cos(angle) * 44),
                y: fromOrbit ? fromOrbit.y : (originY + Math.sin(angle) * 28),
                vx: 0,
                vy: 0,
                scale: 1,
                alpha: 1,
                mode: 'idle', // 'moving' | 'working' | 'idle' | 'startled' | 'approach-gap' | 'gap-sprint' | 'hidden'
                task: null,
                fromX: 0,
                fromY: 0,
                toX: 0,
                toY: 0,
                moveStart: 0,
                moveDur: 180,
                dragBox: null, // { x, y, w, h, color, showSize }
                isLastCarrier: false
            });
        }

        let phase = 'building'; // 'building' | 'scattering' | 'done'
        let scatterStartTime = 0;
        let barrierRingAlpha = 0;
        const thrownClones = [];
        let rafId = 0;

        function getTaskStartPoint(task) {
            const r = task.el.getBoundingClientRect();
            if (task.type === 'type-title' || task.type === 'type-summary' || task.type === 'type-article-block') {
                return { x: r.left + 8, y: r.top + Math.min(18, r.height * 0.5) };
            }
            return { x: r.left, y: r.top };
        }

        function claimNextTask(agent, now) {
            // Find the earliest queued task whose prerequisites are satisfied
            for (let i = 0; i < taskQueue.length; i++) {
                const t = taskQueue[i];
                if (t.status !== 'queued') continue;
                if (t.requiresFrame && !t.job.frameBuilt) continue;

                t.status = 'active';
                t.job.assignedAgent = agent;
                if (t.job.state === 'pending') {
                    t.job.state = 'building';
                }

                const startPt = getTaskStartPoint(t);
                agent.task = t;
                agent.mode = 'moving';
                agent.fromX = agent.x;
                agent.fromY = agent.y;
                agent.toX = startPt.x;
                agent.toY = startPt.y;
                agent.moveStart = now;
                agent.moveDur = t.moveDur;
                agent.dragBox = null;
                return true;
            }
            agent.task = null;
            agent.mode = 'idle';
            agent.dragBox = null;
            return false;
        }

        function completeJobNormally(job) {
            if (!job || job.state === 'completed' || job.state === 'frozen') return;
            job.state = 'completed';
            job.progress = 1;
            restoreZeroClsTypingTarget(job.titleTyping, false);
            restoreZeroClsTypingTarget(job.summaryTyping, false);
            if (job.isArticleBlock) {
                job.cardEl.classList.remove('wb-swarm-block-pending', 'wb-swarm-block-building');
                job.cardEl.classList.add('wb-swarm-block-built');
                return;
            }
            job.badgeEls.forEach((b) => {
                b.classList.remove('wb-swarm-tag-pending', 'wb-swarm-tag-built');
            });
            job.cardEl.classList.remove(
                'wb-swarm-item-pending', 'wb-swarm-item-building',
                'wb-has-meta', 'wb-has-cover', 'wb-has-title', 'wb-has-summary', 'wb-has-footer'
            );
            job.cardEl.classList.add('wb-swarm-item-built');
            job.cardEl.removeAttribute('data-wb-stage');
            job.cardEl.style.removeProperty('min-height');
            job.cardEl.style.removeProperty('--wb-swarm-agent-color');
        }

        function finishTask(agent, task) {
            task.status = 'done';
            agent.dragBox = null;
            const job = task.job;

            if (task.type === 'drag-frame') {
                job.frameBuilt = true;
                job.cardEl.classList.remove('wb-swarm-item-pending');
                job.cardEl.classList.add('wb-swarm-item-building');
            } else if (task.type === 'drag-article-block') {
                job.cardEl.classList.remove('wb-swarm-block-pending', 'wb-swarm-block-building');
                job.cardEl.classList.add('wb-swarm-block-built');
            } else if (task.type === 'type-article-block') {
                job.hasTitle = true;
                job.cardEl.classList.remove('wb-swarm-block-pending', 'wb-swarm-block-building');
                job.cardEl.classList.add('wb-swarm-block-built');
                restoreZeroClsTypingTarget(task.typing, false);
            } else if (task.type === 'drag-cover') {
                job.hasCover = true;
                job.cardEl.classList.add('wb-has-cover');
            } else if (task.type === 'drag-meta') {
                job.hasMeta = true;
                job.cardEl.classList.add('wb-has-meta');
            } else if (task.type === 'type-title') {
                job.hasTitle = true;
                job.cardEl.classList.add('wb-has-title');
                restoreZeroClsTypingTarget(task.typing, false);
            } else if (task.type === 'type-summary') {
                job.hasSummary = true;
                job.cardEl.classList.add('wb-has-summary');
                restoreZeroClsTypingTarget(task.typing, false);
            } else if (task.type === 'drag-badge') {
                job.hasFooter = true;
                if (typeof task.badgeIdx === 'number') {
                    job.builtBadgeSet.add(task.badgeIdx);
                }
                job.cardEl.classList.add('wb-has-footer');
                task.el.classList.remove('wb-swarm-tag-pending');
                task.el.classList.add('wb-swarm-tag-built');
            }

            job.remainingTasks = Math.max(0, job.remainingTasks - 1);
            job.progress = job.totalTasks > 0 ? (1 - job.remainingTasks / job.totalTasks) : 1;
            if (job.remainingTasks === 0) {
                completeJobNormally(job);
            }
            saveJobProgressToCache(job);
        }

        let detached = false;
        let scrollObserver = null;

        function wakeUpSwarmForNewTasks(anchorRect) {
            if (detached) return;
            const now = simNow;
            // Respawn agents cleanly near the newly scrolled-in card if they had hidden after scattering
            const spawnX = anchorRect ? Math.min(stageW - 40, Math.max(40, anchorRect.left + anchorRect.width * 0.5)) : stageW * 0.5;
            const spawnY = anchorRect ? Math.min(stageH - 40, Math.max(40, anchorRect.top - 24)) : stageH * 0.5;
            for (let i = 0; i < agents.length; i++) {
                const a = agents[i];
                if (a.mode === 'hidden' || phase === 'idle-waiting') {
                    const ang = (i / agents.length) * Math.PI * 2;
                    a.x = spawnX + Math.cos(ang) * 36;
                    a.y = spawnY + Math.sin(ang) * 20;
                    a.vx = 0;
                    a.vy = 0;
                    a.scale = 1;
                    a.alpha = 1;
                    a.mode = 'idle';
                    a.task = null;
                    a.dragBox = null;
                    a.isLastCarrier = false;
                }
            }
            phase = 'building';
            if (stageCanvas) {
                stageCanvas.style.display = '';
            }
            for (let i = 0; i < agents.length; i++) {
                if (agents[i].mode === 'idle') {
                    claimNextTask(agents[i], now);
                }
            }
            if (!rafId) {
                lastRealNow = performance.now();
                rafId = window.requestAnimationFrame(tickSwarm);
            }
        }

        // Observe off-screen pending cards so they ONLY render/build when scrolled into view!
        function checkAndEnqueueVisibleOffscreenCards() {
            if (detached) return;
            const vh = window.innerHeight || 800;
            let addedAny = false;
            let firstRect = null;
            for (let i = 0; i < cardJobs.length; i++) {
                const job = cardJobs[i];
                if (job.tasksEnqueued || job.state === 'completed') continue;
                const r = job.cardEl.getBoundingClientRect();
                if (r.height > 0 && r.bottom > -20 && r.top < vh + 20) {
                    if (scrollObserver) scrollObserver.unobserve(job.cardEl);
                    job.isInViewport = true;
                    enqueueTasksForJob(job);
                    if (!firstRect) firstRect = r;
                    addedAny = true;
                }
            }
            if (addedAny) {
                wakeUpSwarmForNewTasks(firstRect);
            }
        }

        let scrollCheckQueued = false;
        function onWindowScrollCheck() {
            if (detached || scrollCheckQueued) return;
            scrollCheckQueued = true;
            window.requestAnimationFrame(() => {
                scrollCheckQueued = false;
                checkAndEnqueueVisibleOffscreenCards();
            });
        }
        window.addEventListener('scroll', onWindowScrollCheck, { passive: true });

        if (typeof IntersectionObserver === 'function') {
            scrollObserver = new IntersectionObserver((entries) => {
                if (detached) return;
                let addedAny = false;
                let firstRect = null;
                entries.forEach((entry) => {
                    if (!entry.isIntersecting) return;
                    const job = cardJobs.find((j) => j.cardEl === entry.target);
                    if (!job || job.tasksEnqueued || job.state === 'completed') {
                        scrollObserver.unobserve(entry.target);
                        return;
                    }
                    scrollObserver.unobserve(entry.target);
                    job.isInViewport = true;
                    enqueueTasksForJob(job);
                    if (!firstRect) firstRect = entry.boundingClientRect;
                    addedAny = true;
                });
                if (addedAny) {
                    wakeUpSwarmForNewTasks(firstRect);
                }
            }, { threshold: 0.04, rootMargin: '24px 0px' });

            cardJobs.forEach((job) => {
                if (!job.tasksEnqueued && job.state !== 'completed') {
                    scrollObserver.observe(job.cardEl);
                }
            });
        }

        // Kick off initial task claims for cards currently in viewport
        const startNow = performance.now();
        if (taskQueue.length > 0) {
            for (let i = 0; i < agents.length; i++) {
                claimNextTask(agents[i], startNow);
            }
        } else {
            phase = 'idle-waiting';
            if (stageCanvas) stageCanvas.style.display = 'none';
        }

        // Solidify a half-built card and create a visual clone to be dragged & thrown by the last leaving cursor
        // While keeping the real card in place (or ready for FLIP transition if clicked!)
        function solidifyAndSpawnHalfBuiltProjectile(job, carrierAgent, triggerMouseX, triggerMouseY) {
            job.state = 'frozen';
            // Solidify the typing state at its current progress ( minimum 35% so it visibly has half-built content )
            if (job.titleTyping && !job.titleTyping.done) {
                const len = Math.max(3, Math.ceil(job.titleTyping.fullText.length * Math.max(0.45, job.progress)));
                job.titleTyping.typedSpan.textContent = job.titleTyping.fullText.slice(0, len);
                restoreZeroClsTypingTarget(job.titleTyping, true);
            }
            if (job.summaryTyping && !job.summaryTyping.done) {
                const len = Math.max(6, Math.ceil(job.summaryTyping.fullText.length * Math.max(0.35, job.progress)));
                job.summaryTyping.typedSpan.textContent = job.summaryTyping.fullText.slice(0, len);
                restoreZeroClsTypingTarget(job.summaryTyping, true);
            }

            const rect = job.cardEl.getBoundingClientRect();

            // Create the solidified half-built floating projectile clone BEFORE restoring the underlying card
            // so the projectile visually captures the exact half-built state!
            if (rect.width > 0 && rect.height > 0 && rect.bottom > -100 && rect.top < stageH + 200) {
                const projectileEl = job.cardEl.cloneNode(true);
                projectileEl.removeAttribute('id');
                projectileEl.removeAttribute('data-article-transition-slug');
                projectileEl.removeAttribute('data-article-transition-card');
                projectileEl.querySelectorAll('[data-article-transition-slug], [data-article-transition-part]').forEach((n) => {
                    n.removeAttribute('data-article-transition-slug');
                    n.removeAttribute('data-article-transition-part');
                });
                projectileEl.className = job.cardEl.className + ' wb-swarm-half-built-projectile';
                // Crucial: strip pending hidden classes so a card clicked during drag-frame is STILL 100% visible when thrown!
                projectileEl.classList.remove('wb-swarm-item-pending', 'wb-swarm-block-pending');
                projectileEl.classList.add('wb-swarm-item-building', 'wb-has-meta', 'wb-has-title');
                projectileEl.style.position = 'fixed';
                projectileEl.style.left = '0px';
                projectileEl.style.top = '0px';
                projectileEl.style.width = rect.width.toFixed(1) + 'px';
                projectileEl.style.height = rect.height.toFixed(1) + 'px';
                projectileEl.style.margin = '0';
                projectileEl.style.opacity = '1';
                projectileEl.style.zIndex = '10002';
                projectileEl.style.pointerEvents = 'none';
                projectileEl.style.transformOrigin = 'center center';
                projectileEl.style.borderColor = carrierAgent ? carrierAgent.color : 'var(--accent)';
                projectileEl.style.transform = `translate3d(${rect.left.toFixed(1)}px, ${rect.top.toFixed(1)}px, 0)`;
                document.body.appendChild(projectileEl);

                // Calculate throw velocity vector towards the edge in the direction of the carrier cursor's departure
                const cx = rect.left + rect.width * 0.5;
                const cy = rect.top + rect.height * 0.5;
                let dirX = triggerMouseX - cx;
                let dirY = triggerMouseY - cy;
                const dLen = Math.hypot(dirX, dirY) || 1;
                dirX /= dLen;
                dirY /= dLen;

                // Ensure strong outward velocity so it reliably reaches a viewport edge
                const launchSpeed = 22 + Math.random() * 6;
                const vx = (Math.abs(dirX) < 0.25 ? (dirX >= 0 ? 0.55 : -0.55) : dirX) * launchSpeed;
                const vy = dirY * launchSpeed - 4;

                thrownClones.push({
                    el: projectileEl,
                    x: rect.left,
                    y: rect.top,
                    w: rect.width,
                    h: rect.height,
                    vx,
                    vy,
                    angle: 0,
                    vAngle: (vx >= 0 ? 1 : -1) * (0.04 + Math.random() * 0.03),
                    gravity: 0.45,
                    firstEdgeHit: null, // 'top' | 'left' | 'right' | 'bottom'
                    smoothTopEject: false,
                    bouncedSide: false,
                    attachFramesLeft: 7, // Carried by the last leaving cursor for the first 7 frames!
                    carrier: carrierAgent
                });
            }

            // Underlying DOM card is immediately restored to clean state so FLIP transition or reading works seamlessly
            restoreZeroClsTypingTarget(job.titleTyping, false);
            restoreZeroClsTypingTarget(job.summaryTyping, false);
            job.badgeEls.forEach((b) => {
                b.classList.remove('wb-swarm-tag-pending', 'wb-swarm-tag-built');
            });
            job.cardEl.classList.remove(
                'wb-swarm-item-pending', 'wb-swarm-item-building',
                'wb-has-meta', 'wb-has-cover', 'wb-has-title', 'wb-has-summary', 'wb-has-footer'
            );
            job.cardEl.style.removeProperty('min-height');
            job.cardEl.style.removeProperty('--wb-swarm-agent-color');
        }

        // Trigger the "Startled" (被吓到) Cursor Convergence + Bottom-Right Gap Barrier + Half-Built Throw
        function triggerStartledScatter(reason, clickedCardEl, clickClientX, clickClientY) {
            if (phase !== 'building') return;
            phase = 'scattering';
            scatterStartTime = simNow;
            barrierRingAlpha = 0.92;

            if (typeof clickClientX === 'number' && typeof clickClientY === 'number') {
                mouseX = clickClientX;
                mouseY = clickClientY;
                hasRealPointer = true;
            } else if (!hasRealPointer) {
                mouseX = stageW * 0.55;
                mouseY = stageH * 0.42;
            }

            // Find the agent furthest from the mouse to be the "Last Leaving Cursor"
            let lastCarrierAgent = agents[agents.length - 1];
            let maxDistSq = -1;
            for (let i = 0; i < agents.length; i++) {
                const a = agents[i];
                const dSq = (a.x - mouseX) ** 2 + (a.y - mouseY) ** 2;
                if (dSq > maxDistSq) {
                    maxDistSq = dSq;
                    lastCarrierAgent = a;
                }
            }
            if (lastCarrierAgent) {
                lastCarrierAgent.isLastCarrier = true;
            }

            // Save all current cards' partial progress into cache BEFORE scattering,
            // so returning to this list via FLIP inherits the exact previous build progress!
            cardJobs.forEach((job) => saveJobProgressToCache(job));

            // ONLY when interrupted by clicking an unfinished card:
            // Solidify & throw AT MOST 1 half-built card (carried by the last leaving cursor),
            // NEVER throw all building cards! And immediately restore the clicked card's real DOM
            // so WindBlog's native FLIP PJAX transition captures a clean, complete card!
            if (reason === 'card-click') {
                const clickedJob = cardJobs.find((j) => j.cardEl === clickedCardEl);
                const carrierJob = (lastCarrierAgent && lastCarrierAgent.task) ? lastCarrierAgent.task.job : null;
                // Prefer throwing the single clicked half-built card's solidified snapshot (or carrier's single half-built card)
                const singleThrowTarget = (clickedJob && clickedJob.state !== 'completed')
                    ? clickedJob
                    : (carrierJob && carrierJob.state === 'building' ? carrierJob : null);

                if (singleThrowTarget) {
                    if (singleThrowTarget.progress <= 0.1) singleThrowTarget.progress = 0.48;
                    solidifyAndSpawnHalfBuiltProjectile(singleThrowTarget, lastCarrierAgent, mouseX, mouseY);
                }

                // Ensure clicked card is 100% restored for FLIP capture right before `click` fires
                if (clickedJob && clickedJob.state !== 'completed') {
                    completeJobNormally(clickedJob);
                    saveJobProgressToCache(clickedJob);
                }
            }

            // Give every cursor a "Startled Jump" impulse (pop outward slightly + exclamation scale)
            for (let i = 0; i < agents.length; i++) {
                const a = agents[i];
                a.mode = 'startled';
                a.task = null;
                a.dragBox = null;
                const dx = a.x - mouseX;
                const dy = a.y - mouseY;
                const dist = Math.max(1, Math.hypot(dx, dy));
                // Startled recoil jump away for ~110ms before rushing to the bottom-right barrier gap
                a.vx = (dx / dist) * 7.5 + (Math.random() - 0.5) * 4;
                a.vy = (dy / dist) * 7.5 - 4.5;
                a.scale = 1.42;
                a.startledUntil = scatterStartTime + 115 + (a.isLastCarrier ? 55 : i * 14);
            }
        }

        // Listen for click on any building/pending card:
        // Do NOT call preventDefault/stopPropagation so WindBlog's native PJAX & FLIP shared-element transition
        // executes simultaneously alongside the startled scatter & half-built throw!
        function onDocumentPointerDown(e) {
            if (phase !== 'building') return;
            const target = e.target instanceof Element ? e.target : null;
            if (!target) return;
            const clickedCard = target.closest(LIST_ITEM_SELECTOR);
            if (clickedCard) {
                const job = cardJobs.find((j) => j.cardEl === clickedCard);
                if (job && (job.state === 'building' || job.state === 'pending')) {
                    triggerStartledScatter('card-click', clickedCard, e.clientX, e.clientY);
                }
            }
        }
        document.addEventListener('pointerdown', onDocumentPointerDown, { capture: true, passive: true });

        function stepProjectiles(dtFactor = 1) {
            let activeProjectiles = 0;
            for (let i = 0; i < thrownClones.length; i++) {
                const p = thrownClones[i];
                if (!p.el) continue;

                // While attached to the last leaving cursor (first few frames), get dragged along with it
                if (p.attachFramesLeft > 0 && p.carrier) {
                    p.attachFramesLeft -= dtFactor;
                    p.x += (p.carrier.vx * 1.35 + p.vx * 0.45) * dtFactor;
                    p.y += (p.carrier.vy * 1.35 + p.vy * 0.45) * dtFactor;
                    p.angle += p.vAngle * 0.6 * dtFactor;
                } else {
                    if (p.smoothTopEject) {
                        // First touched edge was TOP: Smooth Dynamic Compensation (平滑动力补偿)
                        // Boost upward & outward velocity smoothly with zero bounce and zero downward gravity!
                        p.vx *= Math.pow(1.06, dtFactor);
                        p.vy = Math.min(-18, p.vy * Math.pow(1.08, dtFactor) - 1.4 * dtFactor);
                        p.x += p.vx * dtFactor;
                        p.y += p.vy * dtFactor;
                        p.angle += p.vAngle * 1.25 * dtFactor;
                    } else {
                        // Normal / Side-bounce gravity physics
                        p.vy += p.gravity * dtFactor;
                        p.x += p.vx * dtFactor;
                        p.y += p.vy * dtFactor;
                        p.angle += p.vAngle * dtFactor;

                        // Check edge collisions
                        const hitTop = p.y <= 0;
                        const hitLeft = p.x <= 0;
                        const hitRight = p.x + p.w >= stageW;

                        if (!p.firstEdgeHit && hitTop) {
                            // Rule: If the FIRST touched edge is the TOP edge, use smooth dynamic compensation
                            // to throw it straight out without bouncing!
                            p.firstEdgeHit = 'top';
                            p.smoothTopEject = true;
                            p.vy = -Math.max(16, Math.abs(p.vy) * 1.15);
                        } else {
                            if (hitLeft) {
                                if (!p.firstEdgeHit) p.firstEdgeHit = 'left';
                                p.x = 2;
                                p.vx = Math.abs(p.vx) * 0.74;
                                // Increase gravity after edge impact so it bounces off the side and falls into the bottom of the page
                                p.gravity = 1.65;
                                p.vAngle = Math.abs(p.vAngle) * 1.35;
                                p.bouncedSide = true;
                            } else if (hitRight) {
                                if (!p.firstEdgeHit) p.firstEdgeHit = 'right';
                                p.x = stageW - p.w - 2;
                                p.vx = -Math.abs(p.vx) * 0.74;
                                // Increase gravity after edge impact so it bounces off the side and falls into the bottom of the page
                                p.gravity = 1.65;
                                p.vAngle = -Math.abs(p.vAngle) * 1.35;
                                p.bouncedSide = true;
                            }
                        }
                    }
                }

                const scale = p.smoothTopEject ? 0.92 : 0.96;
                p.el.style.transform = `translate3d(${p.x.toFixed(1)}px, ${p.y.toFixed(1)}px, 0) rotate(${p.angle.toFixed(3)}rad) scale(${scale})`;

                // Remove once completely outside bottom (or top for top-ejected)
                const outBottom = p.y > stageH + p.h + 120;
                const outTop = p.smoothTopEject && (p.y + p.h < -140 || p.x + p.w < -200 || p.x > stageW + 200);
                if (outBottom || outTop) {
                    p.el.remove();
                    p.el = null;
                } else {
                    activeProjectiles++;
                }
            }
            return activeProjectiles;
        }

        // Step Startled Cursors with Bottom-Right Gap Barrier Field
        // Barrier geometry centered at (mouseX, mouseY):
        // - Barrier Radius R_barrier = 74px
        // - Gap at Bottom-Right quadrant: angle in [20 deg, 75 deg] (i.e. +X, +Y direction in screen coordinates: 0.35 rad .. 1.30 rad)
        // - Outside gap: solid repulsive barrier wall guides cursors clockwise/counter-clockwise around the barrier perimeter to the bottom-right entry gate
        // - Inside bottom-right gap & interior zone: extreme acceleration sprint straight to (mouseX, mouseY) + scale-down hide animation
        const BARRIER_RADIUS = 72;
        const GAP_CENTER_ANGLE = Math.PI * 0.25; // 45 deg (Bottom-Right in screen coords where +X is right, +Y is down)
        const GAP_HALF_WIDTH = 0.48;             // ~27.5 deg half-cone => [17.5 deg, 72.5 deg]

        function normalizeAngleDiff(a, b) {
            let d = a - b;
            while (d > Math.PI) d -= Math.PI * 2;
            while (d < -Math.PI) d += Math.PI * 2;
            return d;
        }

        function stepStartledAgents(now, dtFactor = 1) {
            let activeAgents = 0;

            for (let i = 0; i < agents.length; i++) {
                const a = agents[i];
                if (a.mode === 'hidden') continue;
                activeAgents++;

                if (a.mode === 'startled') {
                    // Brief startled recoil pop
                    a.x += a.vx * dtFactor;
                    a.y += a.vy * dtFactor;
                    a.vx *= Math.pow(0.88, dtFactor);
                    a.vy *= Math.pow(0.88, dtFactor);
                    a.scale = Math.max(1.1, a.scale * Math.pow(0.97, dtFactor));
                    if (now >= a.startledUntil) {
                        a.mode = 'approach-gap';
                    }
                } else if (a.mode === 'approach-gap') {
                    // Vector from mouse to cursor
                    const relX = a.x - mouseX;
                    const relY = a.y - mouseY;
                    const dist = Math.hypot(relX, relY);
                    const angleFromMouse = Math.atan2(relY, relX);
                    const angleDiffToGap = normalizeAngleDiff(angleFromMouse, GAP_CENTER_ANGLE);
                    const isAlignedWithBottomRightGap = Math.abs(angleDiffToGap) <= GAP_HALF_WIDTH;

                    if (isAlignedWithBottomRightGap && dist <= BARRIER_RADIUS * 1.35) {
                        // Entered the bottom-right gap zone! Switch to extreme acceleration sprint
                        a.mode = 'gap-sprint';
                    } else {
                        // Navigate towards the bottom-right entry waypoint outside the gap
                        // while strictly respecting the circular barrier wall on all other sides
                        const gateX = mouseX + Math.cos(GAP_CENTER_ANGLE) * (BARRIER_RADIUS * 0.92);
                        const gateY = mouseY + Math.sin(GAP_CENTER_ANGLE) * (BARRIER_RADIUS * 0.92);

                        // If cursor is too close to the non-gap side of the barrier, push it out & around tangentially toward the bottom-right gap
                        if (dist < BARRIER_RADIUS + 26 && !isAlignedWithBottomRightGap) {
                            const nx = relX / Math.max(1, dist);
                            const ny = relY / Math.max(1, dist);
                            // Tangential direction that rotates shortest way toward GAP_CENTER_ANGLE
                            const dirSign = angleDiffToGap > 0 ? -1 : 1;
                            const tx = -ny * dirSign;
                            const ty = nx * dirSign;

                            const pushOut = Math.max(0, (BARRIER_RADIUS + 26 - dist) * 0.45);
                            a.vx = a.vx * 0.72 + tx * 6.8 + nx * pushOut;
                            a.vy = a.vy * 0.72 + ty * 6.8 + ny * pushOut;
                        } else {
                            // Steer toward the bottom-right approach point
                            const stagingX = mouseX + Math.cos(GAP_CENTER_ANGLE) * (BARRIER_RADIUS + 18);
                            const stagingY = mouseY + Math.sin(GAP_CENTER_ANGLE) * (BARRIER_RADIUS + 18);
                            const dx = (dist > BARRIER_RADIUS * 1.5 ? stagingX : gateX) - a.x;
                            const dy = (dist > BARRIER_RADIUS * 1.5 ? stagingY : gateY) - a.y;
                            const d = Math.max(1, Math.hypot(dx, dy));
                            const desiredSpeed = Math.min(24, Math.max(11, d * 0.14));
                            a.vx = a.vx * 0.76 + (dx / d) * desiredSpeed * 0.34;
                            a.vy = a.vy * 0.76 + (dy / d) * desiredSpeed * 0.34;
                        }

                        a.x += a.vx * dtFactor;
                        a.y += a.vy * dtFactor;

                        // Hard barrier clamp outside the bottom-right gap so no cursor can ever cross from top/left/bottom-left
                        const postRelX = a.x - mouseX;
                        const postRelY = a.y - mouseY;
                        const postDist = Math.hypot(postRelX, postRelY);
                        const postAngle = Math.atan2(postRelY, postRelX);
                        const postDiff = normalizeAngleDiff(postAngle, GAP_CENTER_ANGLE);
                        if (postDist < BARRIER_RADIUS && Math.abs(postDiff) > GAP_HALF_WIDTH) {
                            const clampR = BARRIER_RADIUS + 2;
                            a.x = mouseX + Math.cos(postAngle) * clampR;
                            a.y = mouseY + Math.sin(postAngle) * clampR;
                        }
                    }
                }

                if (a.mode === 'gap-sprint') {
                    // Inside the bottom-right gap barrier zone: EXTREME ACCELERATION straight to mouse position + shrink to hide
                    const dx = mouseX - a.x;
                    const dy = mouseY - a.y;
                    const dist = Math.hypot(dx, dy);

                    if (dist < 10 || a.scale <= 0.08) {
                        a.x = mouseX;
                        a.y = mouseY;
                        a.scale = 0;
                        a.alpha = 0;
                        a.mode = 'hidden';
                        continue;
                    }

                    // High acceleration homing pursuit
                    const ux = dx / dist;
                    const uy = dy / dist;
                    const accel = 6.5;
                    a.vx = a.vx * 0.65 + ux * (18 + accel);
                    a.vy = a.vy * 0.65 + uy * (18 + accel);

                    // Prevent overshooting mouse
                    const stepMag = Math.hypot(a.vx, a.vy);
                    if (stepMag >= dist) {
                        a.x = mouseX;
                        a.y = mouseY;
                        a.scale = 0;
                        a.alpha = 0;
                        a.mode = 'hidden';
                        continue;
                    }

                    a.x += a.vx * dtFactor;
                    a.y += a.vy * dtFactor;

                    // Shrink smoothly as it sprints from BARRIER_RADIUS -> 0
                    const normDist = Math.max(0, Math.min(1, dist / BARRIER_RADIUS));
                    a.scale = Math.max(0.05, 0.15 + 0.95 * (normDist * normDist));
                    a.alpha = Math.max(0.1, normDist);
                }
            }

            return activeAgents;
        }

        function drawDragBox(box) {
            if (!box || box.w < 2 || box.h < 2) return;
            ctx.save();
            // Subtle translucent tinted fill
            ctx.fillStyle = box.color;
            ctx.globalAlpha = 0.08;
            ctx.fillRect(box.x, box.y, box.w, box.h);

            // Crisp Figma/Swarm selection border
            ctx.strokeStyle = box.color;
            ctx.globalAlpha = 0.92;
            ctx.lineWidth = 1.5;
            ctx.strokeRect(box.x + 0.5, box.y + 0.5, Math.max(0, box.w - 1), Math.max(0, box.h - 1));

            // 4 corner resize handles (for larger boxes)
            if (box.w > 22 && box.h > 14) {
                const hs = 5;
                const half = hs * 0.5;
                const corners = [
                    [box.x, box.y],
                    [box.x + box.w, box.y],
                    [box.x, box.y + box.h],
                    [box.x + box.w, box.y + box.h]
                ];
                ctx.fillStyle = '#ffffff';
                ctx.strokeStyle = box.color;
                ctx.lineWidth = 1.25;
                ctx.globalAlpha = 1;
                for (let c = 0; c < corners.length; c++) {
                    ctx.fillRect(corners[c][0] - half, corners[c][1] - half, hs, hs);
                    ctx.strokeRect(corners[c][0] - half, corners[c][1] - half, hs, hs);
                }
            }

            // Monospace dimension pill (e.g. "640 × 210") on frame/cover drag-boxes
            if (box.showSize && box.w > 56 && box.h > 26) {
                const dimText = Math.round(box.w) + ' \u00D7 ' + Math.round(box.h);
                ctx.font = '600 10px "JetBrains Mono", ui-monospace, monospace';
                const tw = ctx.measureText(dimText).width + 10;
                const bx = box.x + box.w * 0.5 - tw * 0.5;
                const by = box.y + box.h + 6;
                ctx.fillStyle = box.color;
                ctx.globalAlpha = 0.92;
                ctx.beginPath();
                if (typeof ctx.roundRect === 'function') {
                    ctx.roundRect(bx, by, tw, 16, 4);
                } else {
                    ctx.rect(bx, by, tw, 16);
                }
                ctx.fill();
                ctx.fillStyle = '#040810';
                ctx.globalAlpha = 1;
                ctx.fillText(dimText, bx + 5, by + 11.5);
            }
            ctx.restore();
        }

        function drawAgentCursor(a, now) {
            if (a.mode === 'hidden' || a.alpha <= 0.01 || a.scale <= 0.02) return;

            const angle = (a.mode === 'approach-gap' || a.mode === 'gap-sprint')
                ? Math.atan2(a.vy, a.vx)
                : -Math.PI * 0.65;

            ctx.save();
            ctx.translate(a.x, a.y);
            ctx.scale(a.scale, a.scale);

            // Startled exclamation indicator during startled jump
            if (a.mode === 'startled') {
                ctx.font = 'bold 12px "JetBrains Mono", ui-monospace, monospace';
                ctx.fillStyle = a.color;
                ctx.globalAlpha = 1;
                ctx.fillText('!', 8, -10);
            }

            // Cursor arrow body
            ctx.save();
            if (a.mode === 'approach-gap' || a.mode === 'gap-sprint') {
                ctx.rotate(angle);
                ctx.fillStyle = a.color;
                ctx.globalAlpha = a.alpha;
                ctx.beginPath();
                ctx.moveTo(9, 0);
                ctx.lineTo(-6, -5);
                ctx.lineTo(-3, 0);
                ctx.lineTo(-6, 5);
                ctx.closePath();
                ctx.fill();
            } else {
                // Classic Figma/Multiplayer Agent Pointer + Pill Badge
                ctx.fillStyle = a.color;
                ctx.globalAlpha = a.alpha;
                ctx.beginPath();
                ctx.moveTo(0, 0);
                ctx.lineTo(0, 14);
                ctx.lineTo(4.2, 10.5);
                ctx.lineTo(10.5, 10.5);
                ctx.closePath();
                ctx.fill();

                // Agent pill badge
                ctx.font = '600 10px "JetBrains Mono", ui-monospace, monospace';
                const labelW = ctx.measureText(a.name).width + 10;
                ctx.fillStyle = a.color;
                ctx.beginPath();
                if (typeof ctx.roundRect === 'function') {
                    ctx.roundRect(8, 10, labelW, 16, 4);
                } else {
                    ctx.rect(8, 10, labelW, 16);
                }
                ctx.fill();

                ctx.fillStyle = '#040810';
                ctx.fillText(a.name, 13, 21.5);
            }
            ctx.restore();
            ctx.restore();
        }

        function drawBarrierWithBottomRightGap() {
            if (phase !== 'scattering' || barrierRingAlpha <= 0.01) return;
            ctx.save();
            ctx.translate(mouseX, mouseY);
            ctx.beginPath();
            // Draw circular barrier arc everywhere EXCEPT the bottom-right gap [GAP_CENTER_ANGLE - GAP_HALF_WIDTH, GAP_CENTER_ANGLE + GAP_HALF_WIDTH]
            const gapStart = GAP_CENTER_ANGLE + GAP_HALF_WIDTH;
            const gapEnd = GAP_CENTER_ANGLE - GAP_HALF_WIDTH + Math.PI * 2;
            ctx.arc(0, 0, BARRIER_RADIUS, gapStart, gapEnd, false);
            ctx.strokeStyle = isLight ? 'rgba(2, 132, 199, 0.42)' : 'rgba(56, 189, 248, 0.45)';
            ctx.globalAlpha = barrierRingAlpha;
            ctx.lineWidth = 1.75;
            ctx.setLineDash([5, 3]);
            ctx.stroke();

            // Draw subtle entry gate flares at the two edges of the bottom-right gap
            const g1x = Math.cos(gapStart) * BARRIER_RADIUS;
            const g1y = Math.sin(gapStart) * BARRIER_RADIUS;
            const g2x = Math.cos(GAP_CENTER_ANGLE - GAP_HALF_WIDTH) * BARRIER_RADIUS;
            const g2y = Math.sin(GAP_CENTER_ANGLE - GAP_HALF_WIDTH) * BARRIER_RADIUS;
            ctx.setLineDash([]);
            ctx.fillStyle = isLight ? '#0284c7' : '#38bdf8';
            ctx.beginPath();
            ctx.arc(g1x, g1y, 2.6, 0, Math.PI * 2);
            ctx.arc(g2x, g2y, 2.6, 0, Math.PI * 2);
            ctx.fill();

            ctx.restore();
            barrierRingAlpha *= 0.965;
        }

        let lastRealNow = performance.now();
        let simNow = lastRealNow;

        function tickSwarm(realNow) {
            if (!ctx) return;
            const realDt = Math.max(1, Math.min(64, realNow - lastRealNow));
            lastRealNow = realNow;
            simNow += realDt * debugTimeScale;
            const now = simNow;
            const dtFactor = (realDt / 16.6667) * debugTimeScale;

            ctx.clearRect(0, 0, stageW, stageH);

            if (phase === 'building') {
                // Step 1: Advance each agent's SINGLE assigned task cleanly (moving -> working -> finish -> claim next)
                for (let i = 0; i < agents.length; i++) {
                    const a = agents[i];

                    if (a.mode === 'idle') {
                        claimNextTask(a, now);
                    }

                    if (a.mode === 'moving' && a.task) {
                        const moveP = Math.min(1, (now - a.moveStart) / Math.max(1, a.moveDur));
                        const e = easeInOutCubic(moveP);
                        // Re-anchor destination in case of user scroll
                        const liveStart = getTaskStartPoint(a.task);
                        a.toX = liveStart.x;
                        a.toY = liveStart.y;
                        a.x = a.fromX + (a.toX - a.fromX) * e;
                        a.y = a.fromY + (a.toY - a.fromY) * e;

                        if (moveP >= 1) {
                            a.mode = 'working';
                            a.task.stageStart = now;
                            // Prepare typing target visibility right when typing begins
                            if (a.task.type === 'type-title') {
                                a.task.job.cardEl.classList.add('wb-has-title');
                                if (a.task.typing && a.task.typing.caretSpan) {
                                    a.task.typing.caretSpan.style.color = a.color;
                                }
                            } else if (a.task.type === 'type-summary') {
                                a.task.job.cardEl.classList.add('wb-has-summary');
                                if (a.task.typing && a.task.typing.caretSpan) {
                                    a.task.typing.caretSpan.style.color = a.color;
                                }
                            } else if (a.task.type === 'type-article-block') {
                                a.task.job.cardEl.classList.remove('wb-swarm-block-pending');
                                a.task.job.cardEl.classList.add('wb-swarm-block-building');
                                if (a.task.typing && a.task.typing.caretSpan) {
                                    a.task.typing.caretSpan.style.color = a.color;
                                }
                            } else if (a.task.type === 'drag-badge') {
                                a.task.job.cardEl.classList.add('wb-has-footer');
                            }
                        }
                    }

                    if (a.mode === 'working' && a.task) {
                        const t = a.task;
                        const workP = Math.min(1, (now - t.stageStart) / Math.max(1, t.workDur));
                        const r = t.el.getBoundingClientRect();

                        if (
                            t.type === 'drag-frame' ||
                            t.type === 'drag-cover' ||
                            t.type === 'drag-meta' ||
                            t.type === 'drag-badge' ||
                            t.type === 'drag-article-block'
                        ) {
                            // Swarm Rebuild diagonal marquee drag-box (top-left -> bottom-right)
                            const dragE = easeOutCubic(workP);
                            const curW = Math.max(2, r.width * dragE);
                            const curH = Math.max(2, r.height * dragE);
                            a.x = r.left + curW;
                            a.y = r.top + curH;
                            a.dragBox = {
                                x: r.left,
                                y: r.top,
                                w: curW,
                                h: curH,
                                color: a.color,
                                showSize: t.type === 'drag-frame' || t.type === 'drag-cover' || t.type === 'drag-article-block'
                            };
                        } else if (t.type === 'type-title' || t.type === 'type-summary' || t.type === 'type-article-block') {
                            // Crisp Zero-CLS streaming typing with subtle edit bounding box & exact caret tracking
                            if (t.typing && !t.typing.done) {
                                const charCount = Math.floor(t.typing.fullText.length * workP);
                                t.typing.typedSpan.textContent = t.typing.fullText.slice(0, charCount);
                                const cr = t.typing.caretSpan ? t.typing.caretSpan.getBoundingClientRect() : null;
                                if (cr && (cr.width > 0 || cr.height > 0)) {
                                    a.x = cr.left + 8;
                                    a.y = cr.top + cr.height * 0.65;
                                } else {
                                    a.x = r.left + 12 + r.width * workP * 0.8;
                                    a.y = r.top + 14;
                                }
                            }
                            a.dragBox = {
                                x: r.left - 3,
                                y: r.top - 2,
                                w: r.width + 6,
                                h: r.height + 4,
                                color: a.color,
                                showSize: false
                            };
                        }

                        if (workP >= 1) {
                            finishTask(a, t);
                            claimNextTask(a, now);
                        }
                    }
                }

                // Step 2: Draw all active drag-boxes first, then draw crisp cursors on top
                for (let i = 0; i < agents.length; i++) {
                    if (agents[i].dragBox) {
                        drawDragBox(agents[i].dragBox);
                    }
                }
                for (let i = 0; i < agents.length; i++) {
                    drawAgentCursor(agents[i], now);
                }

                // Check if all currently enqueued tasks (visible viewport batch) have completed
                let hasRemainingTasks = false;
                for (let i = 0; i < taskQueue.length; i++) {
                    if (taskQueue[i].status !== 'done') {
                        hasRemainingTasks = true;
                        break;
                    }
                }
                if (!hasRemainingTasks) {
                    // Do NOT force-complete off-screen cards! They stay hidden until scrolled into view.
                    triggerStartledScatter('batch-completed');
                }
            } else if (phase === 'scattering') {
                drawBarrierWithBottomRightGap();
                const activeAgents = stepStartledAgents(now, dtFactor);
                for (let i = 0; i < agents.length; i++) {
                    drawAgentCursor(agents[i], now);
                }
                const activeProjectiles = stepProjectiles(dtFactor);

                if (activeAgents === 0 && activeProjectiles === 0) {
                    const moreOffscreen = !detached && cardJobs.some((j) => !j.tasksEnqueued && j.state !== 'completed');
                    if (moreOffscreen) {
                        // Sleep with 0 CPU until IntersectionObserver sees off-screen cards scroll into view
                        phase = 'idle-waiting';
                        rafId = 0;
                        if (stageCanvas) stageCanvas.style.display = 'none';
                        return;
                    }
                    phase = 'done';
                    cleanupSession();
                    return;
                }
            }

            ctx.globalAlpha = 1;
            rafId = window.requestAnimationFrame(tickSwarm);
        }

        function detachFromPageDom() {
            detached = true;
            if (scrollObserver) {
                scrollObserver.disconnect();
                scrollObserver = null;
            }
            window.removeEventListener('scroll', onWindowScrollCheck);
            document.removeEventListener('pointerdown', onDocumentPointerDown, { capture: true });
        }

        function cleanupSession() {
            const wasDetached = detached;
            detachFromPageDom();
            if (rafId) {
                window.cancelAnimationFrame(rafId);
                rafId = 0;
            }
            window.removeEventListener('resize', syncStageSize);
            thrownClones.forEach((p) => {
                if (p.el) p.el.remove();
            });
            thrownClones.length = 0;
            if (stageCanvas && stageCanvas.parentNode) {
                stageCanvas.width = 0;
                stageCanvas.height = 0;
                stageCanvas.remove();
            }
            if (!wasDetached) {
                cardJobs.forEach((job) => {
                    restoreZeroClsTypingTarget(job.titleTyping, false);
                    restoreZeroClsTypingTarget(job.summaryTyping, false);
                    job.badgeEls.forEach((b) => {
                        b.classList.remove('wb-swarm-tag-pending', 'wb-swarm-tag-built');
                    });
                    job.cardEl.classList.remove(
                        'wb-swarm-item-pending', 'wb-swarm-item-building', 'wb-swarm-item-built',
                        'wb-swarm-block-pending', 'wb-swarm-block-building', 'wb-swarm-block-built',
                        'wb-has-meta', 'wb-has-cover', 'wb-has-title', 'wb-has-summary', 'wb-has-footer'
                    );
                    job.cardEl.style.removeProperty('min-height');
                    job.cardEl.style.removeProperty('--wb-swarm-agent-color');
                });
            }
            if (activeSwarmSession === sessionApi) {
                activeSwarmSession = null;
            }
        }

        window.addEventListener('resize', syncStageSize, { passive: true });
        if (phase === 'building') {
            rafId = window.requestAnimationFrame(tickSwarm);
        }

        const sessionApi = {
            abortImmediately: cleanupSession,
            detachFromPageDom,
            isScattering: () => phase === 'scattering',
            isActive: () => phase === 'building' || phase === 'scattering',
            triggerStartledScatter
        };
        activeSwarmSession = sessionApi;
    }

    // =========================================================================
    // 5. Lifecycle Integration (Initial Load, Progressive Upgrade, PJAX, BFCache)
    // =========================================================================
    function initInitialOverlayAndScheduleDismiss() {
        if (isBfCacheRestore) {
            dismissInlineOverlay(true);
            return;
        }
        const initialTier = getCurrentTier();
        syncOverlayTier(initialTier);

        const onReadyToReveal = () => {
            if (isBfCacheRestore) {
                abortAndRevealAllImmediately();
                return;
            }
            const tier = getCurrentTier();
            const holdMs = tier === 'high' ? 520 : (tier === 'medium' ? 420 : (tier === 'low' ? 240 : 0));
            window.setTimeout(() => {
                const finalTier = getCurrentTier();
                const effectiveHigh = finalTier === 'high' || window.__wbInitialTargetTier === 'high';
                // If high tier, block list items & start Swarm Rebuild right as the overlay dismisses
                if (effectiveHigh && !isBfCacheRestore) {
                    if (finalTier !== 'high') {
                        document.documentElement.dataset.perfTier = 'high';
                    }
                    startSwarmRebuildForPage({ fromOverlay: true });
                } else {
                    document.documentElement.classList.remove('wb-swarm-preblock');
                }
                dismissInlineOverlay(finalTier === 'minimal');
            }, holdMs);
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', onReadyToReveal, { once: true });
        } else {
            onReadyToReveal();
        }
    }

    window.addEventListener('windblog:perf-tier-change', (e) => {
        const nextTier = (e && e.detail && e.detail.tier) ? e.detail.tier : getCurrentTier();
        if (!overlayDismissed) {
            syncOverlayTier(nextTier);
        }
    });

    // Observe theme changes so browser API background color stays synchronized
    if (typeof MutationObserver === 'function') {
        const mo = new MutationObserver(() => {
            if (loaderEl && !overlayDismissed) {
                applyThemeColorsToLoader(loaderEl);
            }
        });
        mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] });
    }

    // Register with PJAX Lifecycle so navigating to any list/article page runs Swarm Rebuild
    let pjaxStartRafId = 0;
    function registerWithPjaxLifecycle() {
        if (!window.WindBlogLifecycle || typeof window.WindBlogLifecycle.register !== 'function') {
            return false;
        }
        window.WindBlogLifecycle.register('inline-loader-swarm', {
            persistent: true,
            mount() {},
            unmount() {
                if (pjaxStartRafId) {
                    window.cancelAnimationFrame(pjaxStartRafId);
                    pjaxStartRafId = 0;
                }
                if (activeSwarmSession) {
                    if (typeof activeSwarmSession.isScattering === 'function' && activeSwarmSession.isScattering()) {
                        // Let the startled cursors + 1 thrown half-built card finish animating
                        // simultaneously alongside the PJAX FLIP transition!
                        activeSwarmSession.detachFromPageDom();
                    } else {
                        activeSwarmSession.abortImmediately();
                    }
                    activeSwarmSession = null;
                }
            },
            onPage(context) {
                if (!context || context.initial) return;
                if (isBfCacheRestore) return;
                if (getCurrentTier() === 'high') {
                    if (pjaxStartRafId) {
                        window.cancelAnimationFrame(pjaxStartRafId);
                    }
                    // Wait 1 frame so rapid PJAX enter/back navigation settles scroll & Tailwind layout first
                    pjaxStartRafId = window.requestAnimationFrame(() => {
                        pjaxStartRafId = 0;
                        startSwarmRebuildForPage({ fromOverlay: false });
                    });
                }
            }
        });
        return true;
    }

    if (!registerWithPjaxLifecycle()) {
        document.addEventListener('DOMContentLoaded', registerWithPjaxLifecycle, { once: true });
    }

    // [临时调试用] 设置慢速播放倍率（传入小数，如 0.25 表示 0.25x 慢放，1.0 恢复正常）
    function setDebugSpeed(multiplier) {
        const val = Number(multiplier);
        if (!Number.isFinite(val) || val <= 0) {
            console.warn('[WindBlog Debug] 请传入大于 0 的数字倍率，例如 wbDebugSpeed(0.25)');
            return debugTimeScale;
        }
        debugTimeScale = Math.max(0.02, Math.min(5.0, val));
        console.info('[WindBlog Debug] 动画速度倍率已设置为:', debugTimeScale + 'x');
        return debugTimeScale;
    }

    // [临时调试用] 立即重新触发当前列表的 Swarm Rebuild 构建动画
    function debugRebuild(optionalSpeedMultiplier) {
        if (optionalSpeedMultiplier !== undefined) {
            setDebugSpeed(optionalSpeedMultiplier);
        }
        abortAndRevealAllImmediately();
        startSwarmRebuildForPage({ fromOverlay: false, debugForce: true });
        console.info('[WindBlog Debug] 已触发 Swarm Rebuild 重新构建 (当前速度倍率: ' + debugTimeScale + 'x)');
    }

    window.WindBlogInlineLoader = {
        syncOverlayTier,
        dismissInlineOverlay,
        startSwarmRebuildForPage,
        abortAndRevealAllImmediately,
        isBusy: () => Boolean(!overlayDismissed || (activeSwarmSession && activeSwarmSession.isActive && activeSwarmSession.isActive())),
        setDebugSpeed,
        debugRebuild
    };

    // [临时调试用] 控制台全局快捷命令（调试完成后可移除）
    window.wbDebugRebuild = debugRebuild;
    window.wbDebugSpeed = setDebugSpeed;

    initInitialOverlayAndScheduleDismiss();
})();
