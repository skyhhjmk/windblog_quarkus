// /assets/js/pow.js
// Standalone, reusable Proof-of-Work client for WindBlog (login, register, comments, reactions).
(function () {
    'use strict';

    const DEFAULT_WORKER_URL = '/assets/js/comment-pow-worker.js?v=pow-visual-20261117';
    const DEFAULT_SOLVE_TIMEOUT_MS = 30000;
    const DEFAULT_FETCH_TIMEOUT_MS = 10000;
    const DEFAULT_MAX_AGE_MS = 240000;
    const HASH_REFRESH_INTERVAL_MS = 500;
    const VERIFIED_HOLD_MS = 2000;

    function formatHash(hash) {
        if (!hash || typeof hash !== 'string') {
            return '00000000…0000';
        }
        const clean = hash.trim();
        if (clean.length <= 14) {
            return clean;
        }
        return clean.slice(0, 8) + '…' + clean.slice(-4);
    }

    function escapeHtml(value) {
        return String(value || '')
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function t(key, fallback) {
        return window.wbT ? window.wbT(key, fallback) : fallback;
    }

    function renderSubmitButtonHtml(options) {
        const opts = options || {};
        const idAttr = opts.id ? ` id="${escapeHtml(opts.id)}"` : '';
        const typeAttr = escapeHtml(opts.type || 'submit');
        const cssClass = opts.cssClass ? `pow-submit-btn ${opts.cssClass}` : 'pow-submit-btn';
        const goalAttr = opts.seerayGoal ? ` data-seeray-goal="${escapeHtml(opts.seerayGoal)}"` : '';
        const extraAttrs = opts.extraAttrs ? ` ${opts.extraAttrs}` : '';
        const label = escapeHtml(opts.label || '提交');
        const verifyingLabel = escapeHtml(t('js.pow_verifying', 'PoW 验证中'));
        return `<button type="${typeAttr}"${idAttr}${goalAttr}${extraAttrs} class="${escapeHtml(cssClass)}" data-pow-visual-button="true" data-pow-state="idle" disabled>`
            + `<span class="pow-btn-progress" aria-hidden="true"></span>`
            + `<span class="pow-btn-visual" aria-live="polite">`
            + `<span class="pow-btn-badge">${verifyingLabel}</span>`
            + `<span class="pow-btn-difficulty hidden"></span>`
            + `<span class="pow-btn-hash">00000000…0000</span>`
            + `</span>`
            + `<span class="btn-text">${label}</span>`
            + `<span class="spinner hidden"></span>`
            + `</button>`;
    }

    function ensureButtonStructure(button) {
        if (!button) return null;
        if (!button.classList.contains('pow-submit-btn')) {
            button.classList.add('pow-submit-btn');
        }
        button.dataset.powVisualButton = 'true';

        let progressEl = button.querySelector(':scope > .pow-btn-progress');
        let visualEl = button.querySelector(':scope > .pow-btn-visual');
        let textEl = button.querySelector(':scope > .btn-text');
        let spinnerEl = button.querySelector(':scope > .spinner');

        if (!textEl) {
            textEl = document.createElement('span');
            textEl.className = 'btn-text';
            const nodesToMove = Array.from(button.childNodes).filter(node => {
                if (node === progressEl || node === visualEl || node === spinnerEl) return false;
                if (node.nodeType === 1 && node.dataset && node.dataset.shortcutHint) return false;
                return true;
            });
            nodesToMove.forEach(node => textEl.appendChild(node));
            button.appendChild(textEl);
        }

        if (!progressEl) {
            progressEl = document.createElement('span');
            progressEl.className = 'pow-btn-progress';
            progressEl.setAttribute('aria-hidden', 'true');
            button.insertBefore(progressEl, button.firstChild);
        }

        if (!visualEl) {
            visualEl = document.createElement('span');
            visualEl.className = 'pow-btn-visual';
            visualEl.setAttribute('aria-live', 'polite');
            const badge = document.createElement('span');
            badge.className = 'pow-btn-badge';
            badge.textContent = t('js.pow_verifying', 'PoW 验证中');
            const diffSpan = document.createElement('span');
            diffSpan.className = 'pow-btn-difficulty hidden';
            const hashSpan = document.createElement('span');
            hashSpan.className = 'pow-btn-hash';
            hashSpan.textContent = '00000000…0000';
            visualEl.appendChild(badge);
            visualEl.appendChild(diffSpan);
            visualEl.appendChild(hashSpan);
            button.insertBefore(visualEl, textEl);
        }

        let diffEl = visualEl.querySelector('.pow-btn-difficulty');
        if (!diffEl) {
            diffEl = document.createElement('span');
            diffEl.className = 'pow-btn-difficulty hidden';
            const hashEl = visualEl.querySelector('.pow-btn-hash');
            visualEl.insertBefore(diffEl, hashEl || null);
        }

        if (!spinnerEl) {
            spinnerEl = document.createElement('span');
            spinnerEl.className = 'spinner hidden';
            button.appendChild(spinnerEl);
        }

        return {
            progressEl: progressEl,
            visualEl: visualEl,
            badgeEl: visualEl.querySelector('.pow-btn-badge'),
            difficultyEl: diffEl,
            hashEl: visualEl.querySelector('.pow-btn-hash'),
            textEl: textEl,
            spinnerEl: spinnerEl
        };
    }

    function attachButton(button, options) {
        if (!button) return null;
        const opts = options || {};
        const els = ensureButtonStructure(button);
        const canEnable = typeof opts.canEnable === 'function' ? opts.canEnable : () => true;
        const onReady = typeof opts.onReady === 'function' ? opts.onReady : null;
        const onRetry = typeof opts.onRetry === 'function' ? opts.onRetry : null;
        const compact = Boolean(opts.compact);
        if (compact) {
            button.classList.add('pow-submit-btn--compact');
        }

        let state = button.dataset.powState || 'idle';
        if (els.badgeEl && state === 'idle') {
            els.badgeEl.textContent = t('js.pow_verifying', 'PoW 验证中');
        }
        let latestHash = '';
        let hasRenderedFirstHash = false;
        let solveStartedAt = 0;
        let hashInterval = null;
        let progressInterval = null;
        let verifiedTimer = null;

        function clearTimers() {
            if (hashInterval) {
                window.clearInterval(hashInterval);
                hashInterval = null;
            }
            if (progressInterval) {
                window.clearInterval(progressInterval);
                progressInterval = null;
            }
            if (verifiedTimer) {
                window.clearTimeout(verifiedTimer);
                verifiedTimer = null;
            }
        }

        function setProgress(percent) {
            const clamped = Math.max(0, Math.min(100, percent));
            els.progressEl.style.width = clamped.toFixed(1) + '%';
        }

        function renderHash(hash) {
            const formatted = formatHash(hash);
            els.hashEl.textContent = formatted;
            if (hash) {
                els.hashEl.title = hash;
            }
        }

        function setDifficulty(difficulty) {
            if (!els.difficultyEl) return;
            const num = Number(difficulty);
            if (Number.isFinite(num) && num > 0) {
                const label = window.wbTf
                    ? window.wbTf('js.pow_difficulty', num, '难度 %s')
                    : ('难度 ' + num);
                els.difficultyEl.textContent = label;
                els.difficultyEl.classList.remove('hidden');
            } else {
                els.difficultyEl.textContent = '';
                els.difficultyEl.classList.add('hidden');
            }
        }

        function setState(nextState) {
            state = nextState;
            button.dataset.powState = nextState;
        }

        function start(difficulty) {
            clearTimers();
            latestHash = '';
            hasRenderedFirstHash = false;
            solveStartedAt = Date.now();
            setState('solving');
            button.disabled = true;
            button.setAttribute('aria-busy', 'true');
            if (els.badgeEl) {
                els.badgeEl.textContent = t('js.pow_verifying', 'PoW 验证中');
            }
            setDifficulty(difficulty);
            renderHash('');
            setProgress(8);

            // Fake progress bar: smoothly approaches 92% until PoW completes.
            progressInterval = window.setInterval(() => {
                if (state !== 'solving') return;
                const elapsed = Date.now() - solveStartedAt;
                const pct = 8 + 84 * (1 - Math.exp(-elapsed / 2200));
                setProgress(pct);
            }, 80);

            // Refresh displayed hash every 0.5s.
            hashInterval = window.setInterval(() => {
                if (state !== 'solving') return;
                if (latestHash) {
                    renderHash(latestHash);
                }
            }, HASH_REFRESH_INTERVAL_MS);
        }

        function updateHash(hash) {
            if (!hash) return;
            latestHash = hash;
            if (!hasRenderedFirstHash && state === 'solving') {
                hasRenderedFirstHash = true;
                renderHash(hash);
            }
        }

        function complete(finalHash) {
            clearTimers();
            if (finalHash) {
                latestHash = finalHash;
            }
            setState('verified');
            button.disabled = true;
            button.setAttribute('aria-busy', 'false');
            setProgress(100);
            if (els.badgeEl) {
                els.badgeEl.textContent = '✓ ' + t('js.pow_verified', 'PoW 验证通过');
            }
            if (latestHash) {
                renderHash(latestHash);
            }

            verifiedTimer = window.setTimeout(() => {
                verifiedTimer = null;
                if (state !== 'verified') return;
                setState('ready');
                button.removeAttribute('aria-busy');
                button.disabled = !canEnable();
                if (onReady) {
                    onReady();
                }
            }, VERIFIED_HOLD_MS);
        }

        function fail(message) {
            clearTimers();
            setState('error');
            button.removeAttribute('aria-busy');
            setProgress(0);
            setDifficulty(null);
            if (els.badgeEl) {
                els.badgeEl.textContent = '!';
            }
            els.hashEl.textContent = message || (window.wbT
                ? window.wbT('js.pow_retry', '校验失败，点击重试')
                : '校验失败，点击重试');
            button.disabled = !onRetry;
        }

        function reset() {
            clearTimers();
            setState('idle');
            button.removeAttribute('aria-busy');
            setProgress(0);
            setDifficulty(null);
            if (els.badgeEl) {
                els.badgeEl.textContent = t('js.pow_verifying', 'PoW 验证中');
            }
            renderHash('');
            button.disabled = true;
        }

        function syncDisabled() {
            if (state === 'ready') {
                button.disabled = !canEnable();
            } else if (state === 'error') {
                button.disabled = !onRetry;
            } else {
                button.disabled = true;
            }
        }

        const onClick = (event) => {
            if (state === 'error' && onRetry) {
                event.preventDefault();
                event.stopPropagation();
                onRetry();
            }
        };
        button.addEventListener('click', onClick);

        function destroy() {
            clearTimers();
            button.removeEventListener('click', onClick);
        }

        return {
            start: start,
            setDifficulty: setDifficulty,
            updateHash: updateHash,
            complete: complete,
            fail: fail,
            reset: reset,
            syncDisabled: syncDisabled,
            destroy: destroy,
            isReady: () => state === 'ready',
            getState: () => state
        };
    }

    function solve(challenge, difficulty, options) {
        const opts = options || {};
        const workerUrl = opts.workerUrl || DEFAULT_WORKER_URL;
        const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : DEFAULT_SOLVE_TIMEOUT_MS;
        const signal = opts.signal;
        const onProgress = typeof opts.onProgress === 'function' ? opts.onProgress : null;
        const onSolved = typeof opts.onSolved === 'function' ? opts.onSolved : null;

        return new Promise((resolve, reject) => {
            if (!challenge || !Number.isFinite(Number(difficulty)) || Number(difficulty) < 1) {
                reject(new Error('invalid pow challenge'));
                return;
            }
            if (typeof Worker === 'undefined') {
                reject(new Error('worker unsupported'));
                return;
            }
            if (signal && signal.aborted) {
                reject(new Error('pow aborted'));
                return;
            }

            let worker;
            try {
                worker = new Worker(workerUrl);
            } catch (error) {
                reject(error);
                return;
            }

            let settled = false;
            const cleanup = () => {
                window.clearTimeout(timer);
                if (signal && onAbort) {
                    signal.removeEventListener('abort', onAbort);
                }
                try {
                    worker.terminate();
                } catch (_) {
                    // Ignore worker termination errors.
                }
            };

            const onAbort = () => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(new Error('pow aborted'));
            };

            if (signal) {
                signal.addEventListener('abort', onAbort, { once: true });
            }

            const timer = window.setTimeout(() => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(new Error('pow timeout'));
            }, timeoutMs);

            worker.onmessage = (event) => {
                if (settled) return;
                const data = event && event.data ? event.data : {};
                if (data.type === 'progress') {
                    if (onProgress && data.hash) {
                        onProgress({ hash: data.hash, nonce: data.nonce });
                    }
                    return;
                }
                settled = true;
                cleanup();
                if (data.ok && data.nonce !== undefined) {
                    const nonceStr = String(data.nonce);
                    if (onSolved) {
                        onSolved({ nonce: nonceStr, hash: data.hash || '' });
                    }
                    resolve(nonceStr);
                    return;
                }
                reject(new Error(data.reason || 'pow failed'));
            };

            worker.onerror = () => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(new Error('pow error'));
            };

            worker.postMessage({ challenge: challenge, difficulty: Number(difficulty) });
        });
    }

    function createChallengeManager(options) {
        const opts = options || {};
        const challengeUrl = opts.challengeUrl;
        const fetchTimeoutMs = Number(opts.fetchTimeoutMs) > 0 ? Number(opts.fetchTimeoutMs) : DEFAULT_FETCH_TIMEOUT_MS;
        const solveTimeoutMs = Number(opts.solveTimeoutMs) > 0 ? Number(opts.solveTimeoutMs) : DEFAULT_SOLVE_TIMEOUT_MS;
        const maxAgeMs = Number(opts.maxAgeMs) > 0 ? Number(opts.maxAgeMs) : DEFAULT_MAX_AGE_MS;
        const onStatus = typeof opts.onStatus === 'function' ? opts.onStatus : function () {};

        let cached = null;
        let inflightPromise = null;
        let abortController = null;
        let generation = 0;

        let buttonCtrl = null;
        if (opts.button) {
            buttonCtrl = attachButton(opts.button, {
                canEnable: opts.canEnable,
                onReady: opts.onReady,
                onRetry: () => {
                    refresh().catch(() => {});
                }
            });
        }

        function hasFresh() {
            if (!cached || !cached.challengeId || !cached.powNonce) {
                return false;
            }
            const nowMs = Date.now();
            if (nowMs - cached.loadedAt >= maxAgeMs) {
                return false;
            }
            if (cached.expiresAt && (nowMs / 1000) >= cached.expiresAt - 10) {
                return false;
            }
            return true;
        }

        function invalidate() {
            generation += 1;
            cached = null;
            inflightPromise = null;
            if (abortController) {
                try {
                    abortController.abort();
                } catch (_) {
                    // Ignore abort errors.
                }
                abortController = null;
            }
            if (buttonCtrl) {
                buttonCtrl.reset();
            }
        }

        function ensure() {
            if (hasFresh()) {
                return Promise.resolve({
                    challengeId: cached.challengeId,
                    powNonce: cached.powNonce,
                    difficulty: cached.difficulty
                });
            }
            if (inflightPromise) {
                return inflightPromise;
            }

            const currentGen = generation;
            const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
            abortController = controller;
            onStatus('preparing');
            if (buttonCtrl) {
                buttonCtrl.start();
            }

            inflightPromise = (async () => {
                try {
                    const fetchOptions = {
                        cache: 'no-store',
                        credentials: 'same-origin',
                        headers: { 'Accept': 'application/json' }
                    };
                    if (controller) {
                        fetchOptions.signal = typeof AbortSignal !== 'undefined' && typeof AbortSignal.any === 'function'
                            ? AbortSignal.any([controller.signal, AbortSignal.timeout(fetchTimeoutMs)])
                            : controller.signal;
                    } else if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
                        fetchOptions.signal = AbortSignal.timeout(fetchTimeoutMs);
                    }

                    const response = await fetch(challengeUrl, fetchOptions);
                    const result = await response.json().catch(() => null);
                    if (currentGen !== generation) {
                        throw new Error('pow aborted');
                    }
                    if (!response.ok || !result?.success || !result.data?.id) {
                        const message = result?.message || ('安全校验服务返回 HTTP ' + response.status);
                        const err = new Error(message);
                        err.status = response.status;
                        err.errorCode = result?.errorCode || response.headers.get('X-WindBlog-Error-Code');
                        throw err;
                    }

                    const challengeId = result.data.id;
                    const difficulty = Number(result.data.difficulty) || 16;
                    const expiresAt = Number(result.data.expiresAt) || 0;
                    if (buttonCtrl) {
                        buttonCtrl.setDifficulty(difficulty);
                    }
                    let solvedHash = '';
                    const nonce = await solve(challengeId, difficulty, {
                        timeoutMs: solveTimeoutMs,
                        signal: controller ? controller.signal : undefined,
                        onProgress: (info) => {
                            if (currentGen === generation && buttonCtrl && info && info.hash) {
                                buttonCtrl.updateHash(info.hash);
                            }
                        },
                        onSolved: (info) => {
                            if (info && info.hash) {
                                solvedHash = info.hash;
                            }
                        }
                    });
                    if (currentGen !== generation) {
                        throw new Error('pow aborted');
                    }

                    cached = {
                        challengeId: challengeId,
                        powNonce: nonce,
                        hash: solvedHash,
                        difficulty: difficulty,
                        expiresAt: expiresAt,
                        loadedAt: Date.now()
                    };
                    if (buttonCtrl) {
                        buttonCtrl.complete(solvedHash);
                    }
                    onStatus('ready', cached);
                    return {
                        challengeId: cached.challengeId,
                        powNonce: cached.powNonce,
                        difficulty: cached.difficulty
                    };
                } catch (error) {
                    if (currentGen === generation) {
                        cached = null;
                        if (buttonCtrl && error.message !== 'pow aborted') {
                            buttonCtrl.fail();
                        }
                        onStatus('error', error);
                    }
                    throw error;
                } finally {
                    if (currentGen === generation) {
                        inflightPromise = null;
                        if (abortController === controller) {
                            abortController = null;
                        }
                    }
                }
            })();

            return inflightPromise;
        }

        async function acquire() {
            const proof = await ensure();
            cached = null;
            return proof;
        }

        function refresh() {
            invalidate();
            return ensure();
        }

        return {
            ensure: ensure,
            acquire: acquire,
            invalidate: invalidate,
            refresh: refresh,
            hasFresh: hasFresh,
            getButtonController: () => buttonCtrl
        };
    }

    window.WindBlogPow = {
        solve: solve,
        createChallengeManager: createChallengeManager,
        attachButton: attachButton,
        renderSubmitButtonHtml: renderSubmitButtonHtml,
        formatHash: formatHash
    };
})();
