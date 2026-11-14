// /assets/js/pow.js
// Standalone, reusable Proof-of-Work client for WindBlog (login, register, comments, reactions).
(function () {
    'use strict';

    const DEFAULT_WORKER_URL = '/assets/js/comment-pow-worker.js?v=pow-20261114';
    const DEFAULT_SOLVE_TIMEOUT_MS = 30000;
    const DEFAULT_FETCH_TIMEOUT_MS = 10000;
    const DEFAULT_MAX_AGE_MS = 240000;

    function solve(challenge, difficulty, options) {
        const opts = options || {};
        const workerUrl = opts.workerUrl || DEFAULT_WORKER_URL;
        const timeoutMs = Number(opts.timeoutMs) > 0 ? Number(opts.timeoutMs) : DEFAULT_SOLVE_TIMEOUT_MS;
        const signal = opts.signal;

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
                settled = true;
                cleanup();
                const data = event && event.data ? event.data : {};
                if (data.ok && data.nonce !== undefined) {
                    resolve(String(data.nonce));
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
                    const nonce = await solve(challengeId, difficulty, {
                        timeoutMs: solveTimeoutMs,
                        signal: controller ? controller.signal : undefined
                    });
                    if (currentGen !== generation) {
                        throw new Error('pow aborted');
                    }

                    cached = {
                        challengeId: challengeId,
                        powNonce: nonce,
                        difficulty: difficulty,
                        expiresAt: expiresAt,
                        loadedAt: Date.now()
                    };
                    onStatus('ready', cached);
                    return {
                        challengeId: cached.challengeId,
                        powNonce: cached.powNonce,
                        difficulty: cached.difficulty
                    };
                } catch (error) {
                    if (currentGen === generation) {
                        cached = null;
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
            hasFresh: hasFresh
        };
    }

    window.WindBlogPow = {
        solve: solve,
        createChallengeManager: createChallengeManager
    };
})();
