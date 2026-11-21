// /assets/js/perf-tier.js
(function () {
    'use strict';

    const STORAGE_KEY = 'wb_perf_tier_v1';
    const OVERRIDE_KEY = 'wb_perf_tier_override';
    const CACHE_TTL_MS = 30 * 60 * 1000;
    const VALID_TIERS = new Set(['minimal', 'low', 'medium', 'high']);
    const TIER_RANK = { minimal: 0, low: 1, medium: 2, high: 3 };

    const reducedMotionMedia = window.matchMedia
        ? window.matchMedia('(prefers-reduced-motion: reduce)')
        : null;
    const mobileMedia = window.matchMedia
        ? window.matchMedia('(max-width: 1023px), (hover: none), (pointer: coarse)')
        : null;

    function isMobileDevice() {
        return Boolean(mobileMedia && mobileMedia.matches);
    }

    let cachedGpu = null;
    function getGpuInfo() {
        if (cachedGpu) return cachedGpu;
        try {
            const canvas = document.createElement('canvas');
            const gl = canvas.getContext('webgl') || canvas.getContext('experimental-webgl');
            if (!gl) {
                cachedGpu = { supported: false, vendor: null, renderer: null, isSoftware: false };
                return cachedGpu;
            }
            const dbg = gl.getExtension('WEBGL_debug_renderer_info');
            const vendor = dbg ? gl.getParameter(dbg.UNMASKED_VENDOR_WEBGL) : gl.getParameter(gl.VENDOR);
            const renderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
            const isSoftware = /swiftshader|llvmpipe|software|microsoft basic/i.test(renderer || '');
            cachedGpu = { supported: true, vendor, renderer, isSoftware };
            return cachedGpu;
        } catch (_) {
            cachedGpu = { supported: false, vendor: null, renderer: null, isSoftware: false };
            return cachedGpu;
        }
    }

    function getHardwareSnapshot() {
        const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection;
        return {
            cores: typeof navigator.hardwareConcurrency === 'number' ? navigator.hardwareConcurrency : null,
            memoryGB: typeof navigator.deviceMemory === 'number' ? navigator.deviceMemory : null,
            dpr: window.devicePixelRatio || 1,
            reducedMotion: Boolean(reducedMotionMedia && reducedMotionMedia.matches),
            saveData: Boolean(conn && conn.saveData),
            effectiveType: conn && conn.effectiveType ? conn.effectiveType : null,
            gpu: getGpuInfo()
        };
    }

    function readOverride() {
        try {
            const val = localStorage.getItem(OVERRIDE_KEY);
            return VALID_TIERS.has(val) ? val : null;
        } catch (_) {
            return null;
        }
    }

    function readSessionCache() {
        try {
            const raw = sessionStorage.getItem(STORAGE_KEY);
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            if (!parsed || !VALID_TIERS.has(parsed.tier)) return null;
            if (typeof parsed.ts === 'number' && Date.now() - parsed.ts > CACHE_TTL_MS) return null;
            return parsed;
        } catch (_) {
            return null;
        }
    }

    function writeSessionCache(report) {
        try {
            sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
                tier: report.tier,
                source: report.source,
                bench: report.bench || null,
                ts: Date.now()
            }));
        } catch (_) {}
    }

    function classifyStatic(hw) {
        if (hw.reducedMotion || hw.saveData || hw.gpu.isSoftware) {
            return 'minimal';
        }
        if (hw.cores !== null && hw.cores <= 2 && hw.memoryGB !== null && hw.memoryGB <= 2) {
            return 'minimal';
        }
        if ((hw.cores !== null && hw.cores <= 2) || (hw.memoryGB !== null && hw.memoryGB <= 2)) {
            return 'low';
        }
        if (hw.cores !== null && hw.cores >= 8 && (hw.memoryGB === null || hw.memoryGB >= 8)) {
            return 'high';
        }
        return 'medium';
    }

    // Calibrated against 100%-smooth desktop baseline:
    // 16 cores / 32GB / RTX 3070 / 165Hz => 300k trig ops = 11.6ms (~5.8ms for 150k),
    // compositeAnim avg = 6.05ms, p95 = 6.1ms, jankFrames = 0.
    function classifyWithBench(hw, bench) {
        if (hw.reducedMotion || hw.saveData || hw.gpu.isSoftware) {
            return 'minimal';
        }
        if (hw.cores !== null && hw.cores <= 2 && hw.memoryGB !== null && hw.memoryGB <= 2) {
            return 'minimal';
        }
        if ((hw.cores !== null && hw.cores <= 2) || (hw.memoryGB !== null && hw.memoryGB <= 2)) {
            return 'low';
        }
        if (
            bench.jsCpuMs > 85 ||
            bench.compositeAnim.jankFrames >= 7 ||
            bench.compositeAnim.p95Ms > 55
        ) {
            return 'minimal';
        }
        if (
            bench.jsCpuMs > 55 ||
            bench.compositeAnim.jankFrames >= 4 ||
            bench.compositeAnim.p95Ms > 36
        ) {
            return 'low';
        }
        const coresOk = hw.cores === null ? bench.jsCpuMs <= 18 : hw.cores >= 6;
        const memOk = hw.memoryGB === null || hw.memoryGB >= 8;
        if (
            coresOk &&
            memOk &&
            bench.jsCpuMs <= 22 &&
            bench.compositeAnim.jankFrames <= 1 &&
            bench.compositeAnim.p95Ms <= 22
        ) {
            return 'high';
        }
        return 'medium';
    }

    function measureRaf(frames, onFrame) {
        return new Promise((resolve) => {
            const deltas = [];
            let last = performance.now();
            let count = 0;
            function step(now) {
                if (count > 0) deltas.push(now - last);
                last = now;
                if (onFrame) onFrame(count);
                count++;
                if (count <= frames) {
                    window.requestAnimationFrame(step);
                } else {
                    resolve(deltas);
                }
            }
            window.requestAnimationFrame(step);
        });
    }

    async function runMicroBenchmark() {
        const t0 = performance.now();
        let acc = 0;
        for (let i = 1; i <= 150000; i++) {
            acc += Math.sin(i * 0.001) * Math.cos(i * 0.0007);
        }
        const jsCpuMs = Number((performance.now() - t0).toFixed(2));
        if (acc === Number.NEGATIVE_INFINITY) return null;

        const idleDeltas = await measureRaf(10);
        const avgIdle = idleDeltas.reduce((s, v) => s + v, 0) / Math.max(1, idleDeltas.length);

        const host = document.createElement('div');
        host.style.cssText = 'position:fixed;inset:0;pointer-events:none;opacity:0.01;z-index:-1;overflow:hidden;';
        const frost = document.createElement('div');
        frost.style.cssText = 'position:absolute;right:0;top:0;width:56px;height:100%;backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);background:rgba(128,128,128,0.1);';
        host.appendChild(frost);

        const items = [];
        for (let i = 0; i < 24; i++) {
            const el = document.createElement('div');
            el.style.cssText = 'position:absolute;right:8px;top:0;width:24px;height:2px;background:#888;will-change:transform;';
            host.appendChild(el);
            items.push(el);
        }
        document.body.appendChild(host);

        let compositeAnim;
        try {
            const animDeltas = await measureRaf(12, (frame) => {
                for (let i = 0; i < items.length; i++) {
                    const y = (frame * 17 + i * 23) % 600;
                    const s = 1 + ((frame + i) % 5) * 0.05;
                    items[i].style.transform = `translate3d(0, ${y}px, 0) scaleX(${s})`;
                }
            });
            const sortedAnim = [...animDeltas].sort((a, b) => a - b);
            const avgAnim = animDeltas.reduce((s, v) => s + v, 0) / Math.max(1, animDeltas.length);
            const jankFrames = animDeltas.filter((d) => d > avgIdle * 1.5 + 4).length;
            compositeAnim = {
                avgMs: Number(avgAnim.toFixed(2)),
                p95Ms: Number(sortedAnim[Math.floor(sortedAnim.length * 0.95)].toFixed(2)),
                jankFrames
            };
        } finally {
            host.remove();
        }

        return {
            jsCpuMs,
            idleRafAvgMs: Number(avgIdle.toFixed(2)),
            compositeAnim
        };
    }

    let currentReport = null;

    let progressiveUpgradeTimers = [];

    function clearProgressiveTimers() {
        while (progressiveUpgradeTimers.length > 0) {
            window.clearTimeout(progressiveUpgradeTimers.pop());
        }
    }

    function applyTierImmediate(tier, source, bench) {
        const htmlEl = document.documentElement;
        const prevTier = htmlEl.dataset.perfTier || null;
        htmlEl.dataset.perfTier = tier;
        htmlEl.dataset.deviceClass = isMobileDevice() ? 'mobile' : 'desktop';
        currentReport = {
            tier,
            source,
            mobile: isMobileDevice(),
            hardware: getHardwareSnapshot(),
            bench: bench || null
        };
        if (prevTier !== tier) {
            window.dispatchEvent(new CustomEvent('windblog:perf-tier-change', {
                detail: { tier, prevTier, source }
            }));
        }
    }

    // Smoothly step upward through intermediate tiers (minimal -> low -> medium -> high)
    // so the inline loader and UI transition progressively without skipping visual stages.
    function applyTier(targetTier, source, bench) {
        clearProgressiveTimers();
        const htmlEl = document.documentElement;
        const currentTier = htmlEl.dataset.perfTier || 'minimal';
        const currentRank = typeof TIER_RANK[currentTier] === 'number' ? TIER_RANK[currentTier] : 0;
        const targetRank = typeof TIER_RANK[targetTier] === 'number' ? TIER_RANK[targetTier] : 0;

        if (targetRank <= currentRank || source === 'override') {
            applyTierImmediate(targetTier, source, bench);
            return;
        }

        const orderedTiers = ['minimal', 'low', 'medium', 'high'];
        let stepDelay = 0;
        for (let r = currentRank + 1; r <= targetRank; r++) {
            const stepTier = orderedTiers[r];
            const isFinal = r === targetRank;
            if (stepDelay === 0) {
                applyTierImmediate(stepTier, isFinal ? source : 'progressive-step', isFinal ? bench : null);
            } else {
                const timerId = window.setTimeout(() => {
                    applyTierImmediate(stepTier, isFinal ? source : 'progressive-step', isFinal ? bench : null);
                }, stepDelay);
                progressiveUpgradeTimers.push(timerId);
            }
            stepDelay += 130;
        }
    }

    function init() {
        const override = readOverride();
        if (override) {
            applyTier(override, 'override', null);
            return;
        }

        const hw = getHardwareSnapshot();
        const cached = readSessionCache();
        if (cached) {
            const effectiveTier = (hw.reducedMotion || hw.saveData) ? 'minimal' : cached.tier;
            applyTier(effectiveTier, 'session-cache', cached.bench);
            return;
        }

        const staticTier = classifyStatic(hw);
        applyTier(staticTier, 'static', null);

        if (hw.reducedMotion || hw.saveData || hw.gpu.isSoftware) {
            writeSessionCache(currentReport);
            return;
        }

        const scheduleBench = () => {
            if (document.hidden) return;
            runMicroBenchmark().then((bench) => {
                if (!bench || readOverride()) return;
                const freshHw = getHardwareSnapshot();
                const finalTier = classifyWithBench(freshHw, bench);
                applyTier(finalTier, 'benchmark', bench);
                writeSessionCache(currentReport);
            }).catch(() => {});
        };

        if (typeof window.requestIdleCallback === 'function') {
            window.requestIdleCallback(() => window.setTimeout(scheduleBench, 120), { timeout: 1500 });
        } else {
            window.setTimeout(scheduleBench, 450);
        }
    }

    if (reducedMotionMedia && typeof reducedMotionMedia.addEventListener === 'function') {
        reducedMotionMedia.addEventListener('change', () => {
            if (!readOverride()) {
                init();
            }
        });
    }

    if (mobileMedia && typeof mobileMedia.addEventListener === 'function') {
        mobileMedia.addEventListener('change', () => {
            document.documentElement.dataset.deviceClass = isMobileDevice() ? 'mobile' : 'desktop';
        });
    }

    window.WindBlogPerf = {
        isMobile() {
            return isMobileDevice();
        },
        getTier() {
            return document.documentElement.dataset.perfTier || (currentReport ? currentReport.tier : 'minimal');
        },
        getTierRank(tier) {
            const t = tier || this.getTier();
            return typeof TIER_RANK[t] === 'number' ? TIER_RANK[t] : 0;
        },
        isHigh() {
            return this.getTier() === 'high';
        },
        isMedium() {
            return this.getTier() === 'medium';
        },
        isLow() {
            const t = this.getTier();
            return t === 'low' || t === 'minimal';
        },
        isMinimal() {
            return this.getTier() === 'minimal';
        },
        shouldReduceMotion() {
            return Boolean(reducedMotionMedia && reducedMotionMedia.matches) || this.isMinimal();
        },
        shouldShowRuler() {
            return !this.isLow() && !this.isMobile();
        },
        isRulerStatic() {
            return Boolean(reducedMotionMedia && reducedMotionMedia.matches) || this.isMedium();
        },
        getReport() {
            return currentReport;
        },
        setTierOverride(tier) {
            try {
                if (tier && VALID_TIERS.has(tier)) {
                    localStorage.setItem(OVERRIDE_KEY, tier);
                    applyTier(tier, 'override', currentReport ? currentReport.bench : null);
                } else {
                    localStorage.removeItem(OVERRIDE_KEY);
                    init();
                }
            } catch (_) {}
        },
        recalibrate() {
            try {
                sessionStorage.removeItem(STORAGE_KEY);
            } catch (_) {}
            init();
        }
    };

    init();
})();
