// /assets/js/asciinema-block.js
// WindBlog Asciinema Player Integration
// Supports automatic theme adaptation, idleTimeLimit: 2, pauseOnMarkers: true,
// manual play/pause/seek progress control, "查看最终结果" (jump to final output),
// playback speed controller, marker navigation, and responsive width/mobile scaling.
(function () {
    'use strict';

    const SPEED_OPTIONS = [0.5, 1, 1.5, 2, 4];
    const activeInstances = new Map();
    let themeObserver = null;
    let bodyMutationObserver = null;
    let lastKnownLightMode = null;

    function isLightThemeActive() {
        return document.documentElement.classList.contains('light');
    }

    function resolveThemeName(configuredTheme) {
        const raw = (configuredTheme || 'auto').trim().toLowerCase();
        if (raw && raw !== 'auto') {
            return raw;
        }
        return isLightThemeActive() ? 'windblog-light' : 'windblog-dark';
    }

    function formatTime(seconds) {
        if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds < 0) {
            return '00:00';
        }
        const total = Math.floor(seconds);
        const mins = Math.floor(total / 60);
        const secs = total % 60;
        return String(mins).padStart(2, '0') + ':' + String(secs).padStart(2, '0');
    }

    function parseMarkersAttr(rawMarkers) {
        if (!rawMarkers || typeof rawMarkers !== 'string') return [];
        const trimmed = rawMarkers.trim();
        if (!trimmed) return [];

        if (trimmed.startsWith('[')) {
            try {
                const parsed = JSON.parse(trimmed);
                if (Array.isArray(parsed)) {
                    const out = [];
                    parsed.forEach(item => {
                        if (typeof item === 'number' && Number.isFinite(item)) {
                            out.push([item, '']);
                        } else if (Array.isArray(item) && Number.isFinite(Number(item[0]))) {
                            out.push([Number(item[0]), String(item[1] ?? '')]);
                        }
                    });
                    return out;
                }
            } catch (_) {
                // Fall back to string delimiter parsing
            }
        }

        const result = [];
        trimmed.split(/[;|]/).forEach(part => {
            const entry = part.trim();
            if (!entry) return;
            const colonIdx = entry.indexOf(':');
            if (colonIdx > 0) {
                const time = parseFloat(entry.substring(0, colonIdx).trim());
                const label = entry.substring(colonIdx + 1).trim();
                if (Number.isFinite(time) && time >= 0) {
                    result.push([time, label]);
                }
            } else {
                const time = parseFloat(entry);
                if (Number.isFinite(time) && time >= 0) {
                    result.push([time, '']);
                }
            }
        });
        return result;
    }

    function parseCastTextMetadata(castText, idleTimeLimit) {
        const markers = [];
        let duration = 0;
        if (!castText || typeof castText !== 'string') {
            return { markers, duration };
        }
        const lines = castText.split(/\r?\n/);
        if (lines.length === 0) return { markers, duration };

        let version = 2;
        let headerIdleLimit = null;
        try {
            const firstLine = lines[0].trim();
            if (firstLine.startsWith('{')) {
                const header = JSON.parse(firstLine);
                if (typeof header.version === 'number') {
                    version = header.version;
                }
                if (typeof header.idle_time_limit === 'number') {
                    headerIdleLimit = header.idle_time_limit;
                }
            }
        } catch (_) {
            // Ignore malformed header
        }

        const effectiveLimit = (typeof idleTimeLimit === 'number' && idleTimeLimit > 0)
            ? idleTimeLimit
            : headerIdleLimit;

        let rawAbsTime = 0;
        let effectiveTime = 0;
        let prevRawTime = 0;

        for (let i = 1; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line || line[0] !== '[') continue;
            try {
                const ev = JSON.parse(line);
                if (!Array.isArray(ev) || typeof ev[0] !== 'number') continue;
                let delta = 0;
                if (version === 3) {
                    delta = Math.max(0, ev[0]);
                    rawAbsTime += delta;
                } else {
                    const t = Math.max(0, ev[0]);
                    delta = Math.max(0, t - prevRawTime);
                    prevRawTime = t;
                    rawAbsTime = t;
                }
                if (typeof effectiveLimit === 'number' && effectiveLimit > 0 && delta > effectiveLimit) {
                    delta = effectiveLimit;
                }
                effectiveTime += delta;
                if (ev[1] === 'm') {
                    markers.push([effectiveTime, String(ev[2] || '')]);
                }
            } catch (_) {
                // Skip invalid line
            }
        }
        duration = effectiveTime;
        return { markers, duration };
    }

    function mergeMarkers(attrMarkers, castMarkers) {
        const combined = [];
        const seen = new Set();
        const add = (time, label) => {
            if (typeof time !== 'number' || !Number.isFinite(time) || time < 0) return;
            const key = time.toFixed(2) + ':' + (label || '');
            if (seen.has(key)) return;
            seen.add(key);
            combined.push([time, label || ('标记 #' + (combined.length + 1))]);
        };
        (castMarkers || []).forEach(m => add(m[0], m[1]));
        (attrMarkers || []).forEach(m => add(m[0], m[1]));
        combined.sort((a, b) => a[0] - b[0]);
        return combined;
    }

    function createCardScaffold(cardEl, config) {
        cardEl.innerHTML = '';

        // 1. Header Bar
        const headerEl = document.createElement('div');
        headerEl.className = 'wb-asciinema-header';
        headerEl.innerHTML = `
            <div class="wb-asciinema-title-group">
                <span class="wb-asciinema-dots" aria-hidden="true">
                    <span class="wb-asciinema-dot wb-asciinema-dot-red"></span>
                    <span class="wb-asciinema-dot wb-asciinema-dot-yellow"></span>
                    <span class="wb-asciinema-dot wb-asciinema-dot-green"></span>
                </span>
                <span class="wb-asciinema-badge">ASCIINEMA</span>
                <span class="wb-asciinema-title"></span>
            </div>
            <div class="wb-asciinema-header-actions">
                <button type="button" class="wb-asciinema-btn wb-asciinema-btn-copy" title="复制当前终端文本">
                    <i class="fa-regular fa-copy"></i>
                    <span>复制输出</span>
                </button>
                <button type="button" class="wb-asciinema-btn wb-asciinema-btn-final" title="直接跳转到录屏末尾查看最终执行结果">
                    <i class="fa-solid fa-forward-fast"></i>
                    <span>查看最终结果</span>
                </button>
            </div>
        `;
        const titleSpan = headerEl.querySelector('.wb-asciinema-title');
        if (titleSpan) titleSpan.textContent = config.title || 'Terminal Recording';

        // 2. Markers Bar
        const markersBarEl = document.createElement('div');
        markersBarEl.className = 'wb-asciinema-markers-bar';
        markersBarEl.hidden = true;

        // 3. Pause on Marker Notice Banner
        const markerNoticeEl = document.createElement('div');
        markerNoticeEl.className = 'wb-asciinema-marker-notice';
        markerNoticeEl.hidden = true;
        markerNoticeEl.innerHTML = `
            <span class="wb-asciinema-marker-notice-text"></span>
            <button type="button" class="wb-asciinema-btn wb-asciinema-btn-primary wb-asciinema-btn-resume">
                <i class="fa-solid fa-play"></i>
                <span>继续播放</span>
            </button>
        `;

        // 4. Player Stage
        const stageEl = document.createElement('div');
        stageEl.className = 'wb-asciinema-stage';
        const mountEl = document.createElement('div');
        mountEl.className = 'wb-asciinema-mount';
        stageEl.appendChild(mountEl);

        // 5. Custom Control Bar
        const controlsEl = document.createElement('div');
        controlsEl.className = 'wb-asciinema-controls';
        controlsEl.innerHTML = `
            <div class="wb-asciinema-progress-row">
                <span class="wb-asciinema-time wb-asciinema-time-current">00:00</span>
                <input type="range" class="wb-asciinema-scrubber" min="0" max="100" step="0.05" value="0" aria-label="播放进度" />
                <span class="wb-asciinema-time wb-asciinema-time-total">00:00</span>
            </div>
            <div class="wb-asciinema-actions-row">
                <div class="wb-asciinema-left-controls">
                    <button type="button" class="wb-asciinema-btn wb-asciinema-btn-primary wb-asciinema-btn-play" aria-label="播放或暂停">
                        <i class="fa-solid fa-play"></i>
                        <span class="wb-asciinema-play-label">播放</span>
                    </button>
                    <button type="button" class="wb-asciinema-btn wb-asciinema-btn-restart" title="从头开始播放">
                        <i class="fa-solid fa-rotate-left"></i>
                        <span>重播</span>
                    </button>
                    <button type="button" class="wb-asciinema-btn wb-asciinema-btn-prev-marker" title="上一个标记 ([)" hidden>
                        <i class="fa-solid fa-backward-step"></i>
                    </button>
                    <button type="button" class="wb-asciinema-btn wb-asciinema-btn-next-marker" title="下一个标记 (])" hidden>
                        <i class="fa-solid fa-forward-step"></i>
                    </button>
                </div>
                <div class="wb-asciinema-right-controls">
                    <div class="wb-asciinema-speed-group" role="group" aria-label="倍速控制"></div>
                </div>
            </div>
        `;

        cardEl.appendChild(headerEl);
        cardEl.appendChild(markersBarEl);
        cardEl.appendChild(markerNoticeEl);
        cardEl.appendChild(stageEl);
        cardEl.appendChild(controlsEl);

        return {
            headerEl,
            markersBarEl,
            markerNoticeEl,
            stageEl,
            mountEl,
            controlsEl,
            scrubberEl: controlsEl.querySelector('.wb-asciinema-scrubber'),
            currentTimeEl: controlsEl.querySelector('.wb-asciinema-time-current'),
            totalTimeEl: controlsEl.querySelector('.wb-asciinema-time-total'),
            playBtn: controlsEl.querySelector('.wb-asciinema-btn-play'),
            playLabel: controlsEl.querySelector('.wb-asciinema-play-label'),
            playIcon: controlsEl.querySelector('.wb-asciinema-btn-play i'),
            restartBtn: controlsEl.querySelector('.wb-asciinema-btn-restart'),
            prevMarkerBtn: controlsEl.querySelector('.wb-asciinema-btn-prev-marker'),
            nextMarkerBtn: controlsEl.querySelector('.wb-asciinema-btn-next-marker'),
            speedGroupEl: controlsEl.querySelector('.wb-asciinema-speed-group'),
            finalBtn: headerEl.querySelector('.wb-asciinema-btn-final'),
            copyBtn: headerEl.querySelector('.wb-asciinema-btn-copy'),
            resumeBtn: markerNoticeEl.querySelector('.wb-asciinema-btn-resume'),
            noticeTextEl: markerNoticeEl.querySelector('.wb-asciinema-marker-notice-text')
        };
    }

    async function mountAsciinemaCard(cardEl) {
        if (!cardEl || activeInstances.has(cardEl)) return;
        if (typeof window.AsciinemaPlayer === 'undefined' || typeof window.AsciinemaPlayer.create !== 'function') {
            return;
        }

        const src = (cardEl.dataset.src || '').trim();
        if (!src) return;

        const idleTimeLimitRaw = parseFloat(cardEl.dataset.idleTimeLimit || '2');
        const idleTimeLimit = Number.isFinite(idleTimeLimitRaw) && idleTimeLimitRaw > 0 ? idleTimeLimitRaw : 2;
        const initialSpeedRaw = parseFloat(cardEl.dataset.speed || '1');
        const initialSpeed = Number.isFinite(initialSpeedRaw) && initialSpeedRaw > 0 ? initialSpeedRaw : 1;
        const pauseOnMarkers = (cardEl.dataset.pauseOnMarkers || 'true').toLowerCase() !== 'false';
        const controls = (cardEl.dataset.controls || 'true').toLowerCase() !== 'false';
        const autoPlay = (cardEl.dataset.autoPlay || 'false').toLowerCase() === 'true';
        const loop = (cardEl.dataset.loop || 'false').toLowerCase() === 'true';
        const cols = parseInt(cardEl.dataset.cols || '', 10);
        const rows = parseInt(cardEl.dataset.rows || '', 10);
        const configuredTheme = cardEl.dataset.theme || 'auto';
        const attrMarkers = parseMarkersAttr(cardEl.dataset.markers || '');
        const title = cardEl.dataset.title || src.split('/').pop() || 'demo.cast';

        const config = {
            src,
            title,
            idleTimeLimit,
            pauseOnMarkers,
            controls,
            autoPlay,
            loop,
            cols: Number.isFinite(cols) && cols > 0 ? cols : undefined,
            rows: Number.isFinite(rows) && rows > 0 ? rows : undefined,
            configuredTheme,
            attrMarkers
        };

        const ui = createCardScaffold(cardEl, config);

        const state = {
            cardEl,
            config,
            ui,
            player: null,
            castText: null,
            markers: [...attrMarkers],
            duration: 0,
            currentTime: 0,
            speed: initialSpeed,
            isPlaying: false,
            isAtEnd: false,
            isScrubbing: false,
            progressTimer: null,
            disposed: false
        };
        activeInstances.set(cardEl, state);

        // Fetch cast file text once so we can inspect markers/duration and re-create player instantaneously on speed/theme changes
        try {
            const response = await fetch(src, { credentials: 'same-origin' });
            if (response.ok) {
                state.castText = await response.text();
                const parsedMeta = parseCastTextMetadata(state.castText, idleTimeLimit);
                state.markers = mergeMarkers(attrMarkers, parsedMeta.markers);
                if (parsedMeta.duration > 0) {
                    state.duration = parsedMeta.duration;
                }
            }
        } catch (_) {
            // If fetch fails, AsciinemaPlayer.create will still try loading src directly
        }

        if (state.disposed) return;

        renderSpeedButtons(state);
        renderMarkersBar(state);
        bindCardEvents(state);
        createOrRecreatePlayer(state, { startAt: 0, autoPlay: config.autoPlay });
    }

    function renderSpeedButtons(state) {
        const { speedGroupEl } = state.ui;
        if (!speedGroupEl) return;
        speedGroupEl.innerHTML = '';

        const speeds = SPEED_OPTIONS.includes(state.speed)
            ? SPEED_OPTIONS
            : [...SPEED_OPTIONS, state.speed].sort((a, b) => a - b);

        speeds.forEach(spd => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'wb-asciinema-speed-btn' + (Math.abs(spd - state.speed) < 0.01 ? ' is-active' : '');
            btn.textContent = spd + 'x';
            btn.title = '切换播放倍速为 ' + spd + 'x';
            btn.addEventListener('click', () => {
                if (Math.abs(spd - state.speed) < 0.01) return;
                changePlaybackSpeed(state, spd);
            });
            speedGroupEl.appendChild(btn);
        });
    }

    function renderMarkersBar(state) {
        const { markersBarEl, prevMarkerBtn, nextMarkerBtn } = state.ui;
        if (!markersBarEl) return;

        if (!state.markers || state.markers.length === 0) {
            markersBarEl.hidden = true;
            if (prevMarkerBtn) prevMarkerBtn.hidden = true;
            if (nextMarkerBtn) nextMarkerBtn.hidden = true;
            return;
        }

        markersBarEl.hidden = false;
        if (prevMarkerBtn) prevMarkerBtn.hidden = false;
        if (nextMarkerBtn) nextMarkerBtn.hidden = false;

        markersBarEl.innerHTML = `<span class="wb-asciinema-markers-label"><i class="fa-solid fa-bookmark"></i><span>关键标记:</span></span>`;
        state.markers.forEach((marker, idx) => {
            const [time, label] = marker;
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'wb-asciinema-marker-chip';
            chip.dataset.markerIndex = String(idx);
            chip.innerHTML = `<span class="wb-asciinema-marker-time">${formatTime(time)}</span><span>${escapeHtml(label || ('#' + (idx + 1)))}</span>`;
            chip.addEventListener('click', () => {
                jumpToMarker(state, idx, time);
            });
            markersBarEl.appendChild(chip);
        });
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    function updatePlayButtonUI(state) {
        const { playLabel, playIcon } = state.ui;
        if (!playLabel || !playIcon) return;
        if (state.isPlaying) {
            playLabel.textContent = '暂停';
            playIcon.className = 'fa-solid fa-pause';
        } else {
            playLabel.textContent = '播放';
            playIcon.className = 'fa-solid fa-play';
        }
    }

    function updateProgressUI(state) {
        const { scrubberEl, currentTimeEl, totalTimeEl, finalBtn } = state.ui;
        if (state.player && typeof state.player.getDuration === 'function') {
            const playerDur = state.player.getDuration();
            if (typeof playerDur === 'number' && Number.isFinite(playerDur) && playerDur > 0) {
                state.duration = playerDur;
            }
        }
        if (state.player && !state.isScrubbing && typeof state.player.getCurrentTime === 'function') {
            const playerCur = state.player.getCurrentTime();
            if (typeof playerCur === 'number' && Number.isFinite(playerCur) && playerCur >= 0) {
                state.currentTime = playerCur;
            }
        }

        const total = state.duration > 0 ? state.duration : Math.max(state.currentTime, 1);
        const cur = Math.min(Math.max(0, state.currentTime), total);

        if (scrubberEl && !state.isScrubbing) {
            scrubberEl.max = String(total);
            scrubberEl.value = String(cur);
        }
        if (currentTimeEl) {
            currentTimeEl.textContent = formatTime(cur);
        }
        if (totalTimeEl) {
            totalTimeEl.textContent = formatTime(state.duration);
        }
        if (finalBtn) {
            finalBtn.classList.toggle('is-active', state.isAtEnd);
        }
    }

    function startProgressPolling(state) {
        stopProgressPolling(state);
        state.progressTimer = window.setInterval(() => {
            if (state.disposed) {
                stopProgressPolling(state);
                return;
            }
            updateProgressUI(state);
        }, 120);
    }

    function stopProgressPolling(state) {
        if (state.progressTimer !== null) {
            window.clearInterval(state.progressTimer);
            state.progressTimer = null;
        }
    }

    function createOrRecreatePlayer(state, options) {
        const opts = options || {};
        if (state.player && typeof state.player.dispose === 'function') {
            try {
                state.player.dispose();
            } catch (_) {}
            state.player = null;
        }
        state.ui.mountEl.innerHTML = '';

        const themeName = resolveThemeName(state.config.configuredTheme);
        const source = state.castText
            ? { data: state.castText, parser: 'asciicast' }
            : state.config.src;

        const playerOptions = {
            controls: state.config.controls,
            pauseOnMarkers: state.config.pauseOnMarkers,
            idleTimeLimit: state.config.idleTimeLimit,
            speed: state.speed,
            fit: 'width',
            preload: true,
            autoPlay: Boolean(opts.autoPlay),
            loop: state.config.loop,
            theme: themeName
        };

        if (typeof opts.startAt === 'number' && opts.startAt > 0) {
            playerOptions.startAt = opts.startAt;
        }
        if (state.config.cols) {
            playerOptions.cols = state.config.cols;
        }
        if (state.config.rows) {
            playerOptions.rows = state.config.rows;
        }
        if (state.config.attrMarkers && state.config.attrMarkers.length > 0) {
            playerOptions.markers = state.config.attrMarkers;
        }

        const player = window.AsciinemaPlayer.create(source, state.ui.mountEl, playerOptions);
        state.player = player;

        player.addEventListener('play', () => {
            state.isPlaying = true;
            state.isAtEnd = false;
            state.ui.markerNoticeEl.hidden = true;
            updatePlayButtonUI(state);
            startProgressPolling(state);
        });

        player.addEventListener('playing', () => {
            state.isPlaying = true;
            state.isAtEnd = false;
            state.ui.markerNoticeEl.hidden = true;
            updatePlayButtonUI(state);
            updateProgressUI(state);
            startProgressPolling(state);
        });

        player.addEventListener('pause', () => {
            state.isPlaying = false;
            updatePlayButtonUI(state);
            updateProgressUI(state);
            stopProgressPolling(state);
        });

        player.addEventListener('ended', () => {
            state.isPlaying = false;
            state.isAtEnd = true;
            if (state.duration > 0) {
                state.currentTime = state.duration;
            }
            updatePlayButtonUI(state);
            updateProgressUI(state);
            stopProgressPolling(state);
        });

        player.addEventListener('marker', (ev) => {
            updateProgressUI(state);
            if (state.config.pauseOnMarkers && state.ui.markerNoticeEl && state.ui.noticeTextEl) {
                const label = (ev && ev.label) ? ev.label : ('标记 #' + ((ev && ev.index !== undefined ? ev.index : 0) + 1));
                state.ui.noticeTextEl.textContent = '已在关键标记处自动暂停：「' + label + '」— 可直接选中终端命令复制';
                state.ui.markerNoticeEl.hidden = false;
            }
        });

        if (opts.seekToEnd) {
            void viewFinalResult(state);
        } else if (typeof opts.startAt === 'number' && opts.startAt > 0 && !opts.autoPlay) {
            state.ui.stageEl.classList.add('wb-asciinema-seeked');
            void Promise.resolve(player.seek(opts.startAt)).then(() => {
                state.currentTime = opts.startAt;
                updateProgressUI(state);
            }).catch(() => {});
        } else {
            updateProgressUI(state);
        }
    }

    async function viewFinalResult(state) {
        if (!state.player) return;
        state.ui.stageEl.classList.add('wb-asciinema-seeked');
        state.ui.markerNoticeEl.hidden = true;
        try {
            await state.player.pause();
            await state.player.seek('100%');
            state.isPlaying = false;
            state.isAtEnd = true;
            if (typeof state.player.getDuration === 'function') {
                const d = state.player.getDuration();
                if (typeof d === 'number' && d > 0) state.duration = d;
            }
            state.currentTime = state.duration;
            updatePlayButtonUI(state);
            updateProgressUI(state);
        } catch (_) {}
    }

    async function jumpToMarker(state, markerIndex, markerTime) {
        if (!state.player) return;
        state.ui.stageEl.classList.add('wb-asciinema-seeked');
        state.ui.markerNoticeEl.hidden = true;
        try {
            await state.player.seek(typeof markerTime === 'number' ? markerTime : { marker: markerIndex });
            state.currentTime = typeof markerTime === 'number' ? markerTime : state.currentTime;
            state.isAtEnd = false;
            updateProgressUI(state);
        } catch (_) {}
    }

    function changePlaybackSpeed(state, newSpeed) {
        const wasPlaying = state.isPlaying;
        const wasAtEnd = state.isAtEnd;
        let currentPos = state.currentTime;
        if (state.player && typeof state.player.getCurrentTime === 'function') {
            const t = state.player.getCurrentTime();
            if (typeof t === 'number' && Number.isFinite(t) && t >= 0) {
                currentPos = t;
            }
        }
        state.speed = newSpeed;
        renderSpeedButtons(state);
        createOrRecreatePlayer(state, {
            startAt: wasAtEnd ? 0 : currentPos,
            autoPlay: wasPlaying,
            seekToEnd: wasAtEnd && !wasPlaying
        });
    }

    async function copyTerminalOutput(state) {
        const termPre = state.ui.mountEl.querySelector('pre.ap-term-text');
        if (!termPre) return;
        const lines = Array.from(termPre.querySelectorAll('.ap-line')).map(lineEl => lineEl.textContent || '');
        while (lines.length > 0 && lines[lines.length - 1].trim() === '') {
            lines.pop();
        }
        const text = lines.join('\n');
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                await navigator.clipboard.writeText(text);
            } else {
                const ta = document.createElement('textarea');
                ta.value = text;
                ta.style.position = 'fixed';
                ta.style.opacity = '0';
                document.body.appendChild(ta);
                ta.select();
                document.execCommand('copy');
                ta.remove();
            }
            const span = state.ui.copyBtn.querySelector('span');
            if (span) {
                const prev = span.textContent;
                span.textContent = '已复制';
                window.setTimeout(() => {
                    span.textContent = prev;
                }, 1500);
            }
        } catch (_) {}
    }

    function bindCardEvents(state) {
        const {
            playBtn,
            restartBtn,
            prevMarkerBtn,
            nextMarkerBtn,
            scrubberEl,
            finalBtn,
            copyBtn,
            resumeBtn
        } = state.ui;

        if (playBtn) {
            playBtn.addEventListener('click', async () => {
                if (!state.player) return;
                state.ui.stageEl.classList.add('wb-asciinema-seeked');
                if (state.isPlaying) {
                    await state.player.pause();
                } else {
                    if (state.isAtEnd) {
                        await state.player.seek(0);
                        state.isAtEnd = false;
                    }
                    await state.player.play();
                }
            });
        }

        if (restartBtn) {
            restartBtn.addEventListener('click', async () => {
                if (!state.player) return;
                state.ui.stageEl.classList.add('wb-asciinema-seeked');
                state.ui.markerNoticeEl.hidden = true;
                state.isAtEnd = false;
                await state.player.seek(0);
                await state.player.play();
            });
        }

        if (prevMarkerBtn) {
            prevMarkerBtn.addEventListener('click', async () => {
                if (!state.player) return;
                state.ui.stageEl.classList.add('wb-asciinema-seeked');
                await state.player.seek({ marker: 'prev' });
                updateProgressUI(state);
            });
        }

        if (nextMarkerBtn) {
            nextMarkerBtn.addEventListener('click', async () => {
                if (!state.player) return;
                state.ui.stageEl.classList.add('wb-asciinema-seeked');
                await state.player.seek({ marker: 'next' });
                updateProgressUI(state);
            });
        }

        if (finalBtn) {
            finalBtn.addEventListener('click', () => {
                void viewFinalResult(state);
            });
        }

        if (copyBtn) {
            copyBtn.addEventListener('click', () => {
                void copyTerminalOutput(state);
            });
        }

        if (resumeBtn) {
            resumeBtn.addEventListener('click', async () => {
                if (!state.player) return;
                state.ui.markerNoticeEl.hidden = true;
                await state.player.play();
            });
        }

        if (scrubberEl) {
            scrubberEl.addEventListener('input', () => {
                state.isScrubbing = true;
                const val = parseFloat(scrubberEl.value);
                if (Number.isFinite(val)) {
                    state.currentTime = val;
                    if (state.ui.currentTimeEl) {
                        state.ui.currentTimeEl.textContent = formatTime(val);
                    }
                }
            });

            const commitScrub = async () => {
                if (!state.isScrubbing) return;
                const val = parseFloat(scrubberEl.value);
                state.isScrubbing = false;
                if (!state.player || !Number.isFinite(val)) return;
                state.ui.stageEl.classList.add('wb-asciinema-seeked');
                state.ui.markerNoticeEl.hidden = true;
                state.isAtEnd = state.duration > 0 && val >= state.duration - 0.05;
                await state.player.seek(val);
                updateProgressUI(state);
            };

            scrubberEl.addEventListener('change', commitScrub);
            scrubberEl.addEventListener('mouseup', commitScrub);
            scrubberEl.addEventListener('touchend', commitScrub);
        }
    }

    function handleThemeChanged() {
        const currentLight = isLightThemeActive();
        if (lastKnownLightMode === currentLight) return;
        lastKnownLightMode = currentLight;

        activeInstances.forEach(state => {
            const rawTheme = (state.config.configuredTheme || 'auto').trim().toLowerCase();
            if (rawTheme && rawTheme !== 'auto') return;
            const wasPlaying = state.isPlaying;
            const wasAtEnd = state.isAtEnd;
            let currentPos = state.currentTime;
            if (state.player && typeof state.player.getCurrentTime === 'function') {
                const t = state.player.getCurrentTime();
                if (typeof t === 'number' && Number.isFinite(t) && t >= 0) {
                    currentPos = t;
                }
            }
            createOrRecreatePlayer(state, {
                startAt: wasAtEnd ? 0 : currentPos,
                autoPlay: wasPlaying,
                seekToEnd: wasAtEnd && !wasPlaying
            });
        });
    }

    function ensureThemeObserver() {
        if (themeObserver) return;
        lastKnownLightMode = isLightThemeActive();
        themeObserver = new MutationObserver(() => {
            handleThemeChanged();
        });
        themeObserver.observe(document.documentElement, {
            attributes: true,
            attributeFilter: ['class']
        });
    }

    function scanAndMountAll(root) {
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        const cards = scope.querySelectorAll('.asciinema-player-card[data-asciinema-player="true"]');
        cards.forEach(card => {
            void mountAsciinemaCard(card);
        });
    }

    function observeDynamicPostContent() {
        if (bodyMutationObserver) {
            bodyMutationObserver.disconnect();
            bodyMutationObserver = null;
        }
        const postSection = document.getElementById('post-content-section');
        if (!postSection) return;
        bodyMutationObserver = new MutationObserver(() => {
            scanAndMountAll(postSection);
        });
        bodyMutationObserver.observe(postSection, {
            childList: true,
            subtree: true
        });
    }

    function mount() {
        ensureThemeObserver();
        scanAndMountAll(document);
        observeDynamicPostContent();
    }

    function unmount() {
        if (bodyMutationObserver) {
            bodyMutationObserver.disconnect();
            bodyMutationObserver = null;
        }
        activeInstances.forEach(state => {
            state.disposed = true;
            stopProgressPolling(state);
            if (state.player && typeof state.player.dispose === 'function') {
                try {
                    state.player.dispose();
                } catch (_) {}
            }
        });
        activeInstances.clear();
    }

    function refresh() {
        scanAndMountAll(document);
    }

    if (window.WindBlogLifecycle && typeof window.WindBlogLifecycle.register === 'function') {
        window.WindBlogLifecycle.register('asciinema-block', {
            mount: mount,
            unmount: unmount,
            refresh: refresh
        });
    } else {
        document.addEventListener('DOMContentLoaded', mount, { once: true });
    }
})();
