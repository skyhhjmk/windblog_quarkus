// /assets/js/relax-games.js
/**
 * WindBlog Relax Games Hub (摸鱼小游戏中心)
 * 包含：
 * 1. 🚀 宇宙飞船打 DOM (DOM Space Destroyer) - 三角形飞船跟随鼠标，点击发射子弹，击中 DOM 爆裂删除
 * 2. 🕳️ DOM 引力黑洞 (DOM Gravity Singularity) - 强引力扭曲吸附并吞噬网页 DOM，自带空间坍缩与引力音效
 * 3. 🏓 DOM 弹球粉碎者 (DOM Brick Breaker) - 挡板与弹球反弹撞碎网页组件
 *
 * 核心机制：
 * - 完备的 DOM 框体防护系统 (DOM Frame Protection)：绝对保护页面结构大框体与布局骨架，仅允许击毁内容级叶子元素，防止整页消失
 * - Web Audio API 原生音效合成器，支持用户手势预激活与立体空间合成
 */
(function () {
    'use strict';

    // 避免重复初始化
    if (window.__wbRelaxGamesInitialized) return;
    window.__wbRelaxGamesInitialized = true;

    /* ==========================================================================
       1. 音频合成器 (原生 Web Audio API，无需外部音频资源)
       ========================================================================== */
    class GameAudioSynth {
        constructor() {
            this.ctx = null;
            this.muted = localStorage.getItem('wb_game_sound_muted') === 'true';
            this.lastSwallowTime = 0;
        }

        initContext() {
            if (!this.ctx) {
                const AudioCtx = window.AudioContext || window.webkitAudioContext;
                if (AudioCtx) {
                    this.ctx = new AudioCtx();
                }
            }
            if (this.ctx && this.ctx.state === 'suspended') {
                this.ctx.resume().catch(() => {});
            }
        }

        setMuted(muted) {
            this.muted = muted;
            localStorage.setItem('wb_game_sound_muted', muted ? 'true' : 'false');
        }

        // 激光发射音效 (扫频)
        playLaser() {
            if (this.muted) return;
            this.initContext();
            if (!this.ctx || this.ctx.state !== 'running') return;

            try {
                const now = this.ctx.currentTime;
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();

                osc.type = 'sawtooth';
                osc.frequency.setValueAtTime(850, now);
                osc.frequency.exponentialRampToValueAtTime(140, now + 0.09);

                gain.gain.setValueAtTime(0.16, now);
                gain.gain.exponentialRampToValueAtTime(0.001, now + 0.09);

                osc.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now);
                osc.stop(now + 0.09);
            } catch (e) {}
        }

        // 击中 DOM 爆炸音效
        playExplosion() {
            if (this.muted) return;
            this.initContext();
            if (!this.ctx || this.ctx.state !== 'running') return;

            try {
                const now = this.ctx.currentTime;
                const dur = 0.18;
                const bufferSize = this.ctx.sampleRate * dur;
                const buffer = this.ctx.createBuffer(1, bufferSize, this.ctx.sampleRate);
                const data = buffer.getChannelData(0);

                for (let i = 0; i < bufferSize; i++) {
                    data[i] = Math.random() * 2 - 1;
                }

                const noise = this.ctx.createBufferSource();
                noise.buffer = buffer;

                const filter = this.ctx.createBiquadFilter();
                filter.type = 'lowpass';
                filter.frequency.setValueAtTime(700, now);
                filter.frequency.exponentialRampToValueAtTime(60, now + dur);

                const gain = this.ctx.createGain();
                gain.gain.setValueAtTime(0.3, now);
                gain.gain.exponentialRampToValueAtTime(0.01, now + dur);

                noise.connect(filter);
                filter.connect(gain);
                gain.connect(this.ctx.destination);

                noise.start(now);
                noise.stop(now + dur);
            } catch (e) {}
        }

        // 连击连破音效
        playCombo(combo) {
            if (this.muted) return;
            this.initContext();
            if (!this.ctx || this.ctx.state !== 'running') return;

            try {
                const now = this.ctx.currentTime;
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();

                const notes = [261.6, 293.7, 329.6, 392.0, 440.0, 523.3, 587.3, 659.3, 784.0];
                const noteIndex = Math.min(combo, notes.length - 1);
                const freq = notes[noteIndex];

                osc.type = 'sine';
                osc.frequency.setValueAtTime(freq, now);
                osc.frequency.exponentialRampToValueAtTime(freq * 1.25, now + 0.12);

                gain.gain.setValueAtTime(0.18, now);
                gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);

                osc.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now);
                osc.stop(now + 0.12);
            } catch (e) {}
        }

        // 黑洞激活/生成音效 (重力引力场呼啸)
        playBlackHoleSpawn() {
            if (this.muted) return;
            this.initContext();
            if (!this.ctx) return;

            try {
                const now = this.ctx.currentTime;
                const osc = this.ctx.createOscillator();
                const gain = this.ctx.createGain();
                const filter = this.ctx.createBiquadFilter();

                osc.type = 'sawtooth';
                osc.frequency.setValueAtTime(80, now);
                osc.frequency.exponentialRampToValueAtTime(260, now + 0.35);

                filter.type = 'lowpass';
                filter.frequency.setValueAtTime(320, now);

                gain.gain.setValueAtTime(0.28, now);
                gain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);

                osc.connect(filter);
                filter.connect(gain);
                gain.connect(this.ctx.destination);

                osc.start(now);
                osc.stop(now + 0.35);
            } catch (e) {}
        }

        // 黑洞引力坍缩吞噬音效 (饱满清晰的双层空间坍缩声：低频重音 + 奇点共鸣)
        playBlackHoleSwallow(combo = 1) {
            if (this.muted) return;
            this.initContext();
            if (!this.ctx) return;

            // 限制触发频率，避免一瞬间吞噬多个产生刺耳爆音
            const nowTime = performance.now();
            if (nowTime - this.lastSwallowTime < 45) return;
            this.lastSwallowTime = nowTime;

            try {
                const now = this.ctx.currentTime;

                // 1. 低频坍缩 Thump (扎实的重低音空间坍缩)
                const subOsc = this.ctx.createOscillator();
                const subGain = this.ctx.createGain();
                subOsc.type = 'sine';
                subOsc.frequency.setValueAtTime(260, now);
                subOsc.frequency.exponentialRampToValueAtTime(45, now + 0.16);

                subGain.gain.setValueAtTime(0.38, now);
                subGain.gain.exponentialRampToValueAtTime(0.001, now + 0.16);

                subOsc.connect(subGain);
                subGain.connect(this.ctx.destination);
                subOsc.start(now);
                subOsc.stop(now + 0.16);

                // 2. 清脆奇点谐波 Chirp (中高频吸入提示，随 Combo 爬升)
                const chirpOsc = this.ctx.createOscillator();
                const chirpGain = this.ctx.createGain();
                const chirpFilter = this.ctx.createBiquadFilter();

                const baseFreq = 520 + Math.min(combo * 40, 500);
                chirpOsc.type = 'triangle';
                chirpOsc.frequency.setValueAtTime(baseFreq, now);
                chirpOsc.frequency.exponentialRampToValueAtTime(baseFreq * 0.4, now + 0.13);

                chirpFilter.type = 'bandpass';
                chirpFilter.frequency.setValueAtTime(baseFreq * 0.8, now);
                chirpFilter.Q.setValueAtTime(3.0, now);

                chirpGain.gain.setValueAtTime(0.24, now);
                chirpGain.gain.exponentialRampToValueAtTime(0.001, now + 0.13);

                chirpOsc.connect(chirpFilter);
                chirpFilter.connect(chirpGain);
                chirpGain.connect(this.ctx.destination);

                chirpOsc.start(now);
                chirpOsc.stop(now + 0.13);
            } catch (e) {}
        }
    }

    /* ==========================================================================
       2. DOM 框体防护体系 (防止误伤页面大框架导致整页内容被拔除)
       ========================================================================== */
    const audio = new GameAudioSynth();

    // 绝对禁止作为整体被摧毁或删除的框体结构标签
    const PROTECTED_FRAME_TAGS = new Set([
        'html', 'body', 'head', 'header', 'footer', 'nav', 'main', 'aside',
        'section', 'article', 'form', 'table', 'thead', 'tbody', 'tfoot',
        'tr', 'ul', 'ol', 'dl', 'fieldset', 'script', 'style', 'link', 'meta'
    ]);

    // 允许被击毁/吞噬的内容级叶子标签
    const DESTRUCTIBLE_CONTENT_TAGS = new Set([
        'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'span', 'a', 'button',
        'img', 'pre', 'code', 'li', 'svg', 'path', 'kbd', 'input', 'textarea',
        'label', 'strong', 'em', 'b', 'i', 'time', 'small', 'blockquote',
        'cite', 'sub', 'sup', 'mark', 'td', 'th', 'dd', 'dt', 'picture', 'figure'
    ]);

    function triggerScreenShake() {
        document.body.classList.remove('wb-screen-shake');
        void document.body.offsetWidth;
        document.body.classList.add('wb-screen-shake');
        setTimeout(() => {
            document.body.classList.remove('wb-screen-shake');
        }, 150);
    }

    // 检查元素是否属于框架容器、布局骨架或游戏系统组件
    function isFrameOrSystemElement(el) {
        if (!el || el === document.body || el === document.documentElement) return true;

        // 1. 游戏系统组件与弹窗绝对保护
        if (el.id === 'wb-game-canvas' ||
            el.closest('.wb-game-hud') ||
            el.closest('.wb-game-trigger') ||
            el.closest('.wb-game-menu-panel') ||
            el.closest('.wb-game-modal-overlay') ||
            el.closest('#appModal') ||
            el.closest('#toastContainer')) {
            return true;
        }

        // 2. 正在执行破碎消失动画的元素
        if (el.classList.contains('wb-dom-shattering')) return true;

        const tag = el.tagName.toLowerCase();
        // 结构性骨架标签一律保护
        if (PROTECTED_FRAME_TAGS.has(tag)) return true;

        // 3. 关键业务 ID 保护
        const id = el.id || '';
        if (id === 'pjax-container' ||
            id === 'sidebar-container' ||
            id === 'nav-path-text' ||
            id === 'mobileMenu' ||
            id === 'mobileUserAuth' ||
            id === 'userDropdown' ||
            id === 'languageMenu' ||
            id === 'pjax-progress') {
            return true;
        }

        // 4. 关键布局类名保护
        if (el.classList.contains('max-w-1400') ||
            el.classList.contains('toast-container') ||
            el.hasAttribute('data-wb-ads-config')) {
            return true;
        }

        // 5. 尺寸与面积防护：横贯屏幕的巨大容器或占屏面积过大的元素禁止击毁
        const rect = el.getBoundingClientRect();
        const vw = window.innerWidth;
        const vh = window.innerHeight;

        // 宽度超过 45% 且高度大于 80px，说明是大区块/大分栏容器
        if (rect.width >= vw * 0.45 && rect.height >= 80) return true;

        // 面积超过屏幕 10% 的元素禁止删除
        if (rect.width * rect.height > vw * vh * 0.10) return true;

        // 6. 复合容器防护：如果包含 2 个及以上的直接元素子节点，属于容器包装层（如卡片、列表盒）
        // 绝不可一枪把整个外壳拔除，必须打碎具体的子元素
        if (el.children && el.children.length >= 2) return true;

        // 如果是 div，只要里面还含有子元素，就作为容器保护
        if (tag === 'div' && el.children && el.children.length > 0) return true;

        return false;
    }

    // 检查元素是否是合法的最小内容打击目标
    function isDestructibleContentTarget(el) {
        if (!el || isFrameOrSystemElement(el)) return false;

        const tag = el.tagName.toLowerCase();
        if (DESTRUCTIBLE_CONTENT_TAGS.has(tag)) return true;

        // 如果是没有子元素的叶子 div / 块（例如 badge, pill, divider, icon-box）
        if (el.children && el.children.length === 0) {
            const rect = el.getBoundingClientRect();
            return rect.width >= 3 && rect.height >= 3 && rect.width <= window.innerWidth * 0.5 && rect.height <= window.innerHeight * 0.4;
        }

        return false;
    }

    // 在指定的视口 (x, y) 坐标处寻找最深层的合法内容元素（杜绝误伤大框体）
    function findTargetElementAt(x, y) {
        const elements = document.elementsFromPoint(x, y);
        if (!elements || elements.length === 0) return null;

        for (let i = 0; i < elements.length; i++) {
            const candidate = elements[i];

            // 1. 如果该元素本身就是合法的最小内容目标，直接命中
            if (isDestructibleContentTarget(candidate)) {
                return candidate;
            }

            // 2. 如果命中的是一个包装容器（如卡片或列表），绝不删除该容器，
            // 而是向内部深入寻找正好覆盖 (x, y) 坐标的具体内容子节点
            if (candidate && !isFrameOrSystemElement(candidate) && candidate.children && candidate.children.length > 0) {
                const subItems = candidate.querySelectorAll('p, h1, h2, h3, h4, h5, h6, span, a, button, img, pre, code, li, svg, kbd, strong, em, b, time');
                for (let j = 0; j < subItems.length; j++) {
                    const sub = subItems[j];
                    if (isDestructibleContentTarget(sub)) {
                        const r = sub.getBoundingClientRect();
                        if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) {
                            return sub;
                        }
                    }
                }
            }
        }

        // 如果该点只落在大框体的空白处/边距上，直接返回 null，决不摧毁外壳
        return null;
    }

    /* ==========================================================================
       3. 游戏 1：宇宙飞船打 DOM (DOM Space Destroyer)
       ========================================================================== */
    class SpaceDestroyerGame {
        constructor(manager) {
            this.manager = manager;
            this.name = 'DOM Space Destroyer';
            this.canvas = null;
            this.ctx = null;
            this.animId = null;

            // 飞船状态
            this.ship = {
                x: window.innerWidth / 2,
                y: window.innerHeight / 2,
                vx: 0,
                vy: 0,
                angle: -Math.PI / 2,
                targetAngle: -Math.PI / 2,
                size: 22,
                speed: 0
            };

            // 鼠标位置与交互
            this.mouseX = window.innerWidth / 2;
            this.mouseY = window.innerHeight / 2;
            this.isMouseDown = false;
            this.lastShootTime = 0;
            this.shootInterval = 110; // 连发射速 ms

            // 实体池
            this.bullets = [];
            this.particles = [];
            this.floats = [];

            // 事件绑定引用
            this._onMouseMove = this.onMouseMove.bind(this);
            this._onMouseDown = this.onMouseDown.bind(this);
            this._onMouseUp = this.onMouseUp.bind(this);
            this._onResize = this.onResize.bind(this);
        }

        start() {
            this.canvas = this.manager.getCanvas();
            this.ctx = this.canvas.getContext('2d');
            this.onResize();

            this.ship.x = this.mouseX = window.innerWidth / 2;
            this.ship.y = this.mouseY = window.innerHeight * 0.7;
            this.bullets = [];
            this.particles = [];
            this.floats = [];

            document.body.classList.add('wb-game-active', 'wb-game-cursor-crosshair');
            window.addEventListener('mousemove', this._onMouseMove, { passive: true });
            window.addEventListener('mousedown', this._onMouseDown);
            window.addEventListener('mouseup', this._onMouseUp);
            window.addEventListener('resize', this._onResize);

            this.loop = this.loop.bind(this);
            this.animId = requestAnimationFrame(this.loop);
        }

        stop() {
            if (this.animId) {
                cancelAnimationFrame(this.animId);
                this.animId = null;
            }
            document.body.classList.remove('wb-game-active', 'wb-game-cursor-crosshair');
            window.removeEventListener('mousemove', this._onMouseMove);
            window.removeEventListener('mousedown', this._onMouseDown);
            window.removeEventListener('mouseup', this._onMouseUp);
            window.removeEventListener('resize', this._onResize);

            if (this.ctx && this.canvas) {
                this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            }
        }

        onResize() {
            if (!this.canvas) return;
            const dpr = window.devicePixelRatio || 1;
            this.canvas.width = window.innerWidth * dpr;
            this.canvas.height = window.innerHeight * dpr;
            if (this.ctx) {
                this.ctx.setTransform(1, 0, 0, 1, 0, 0);
                this.ctx.scale(dpr, dpr);
            }
        }

        onMouseMove(e) {
            this.mouseX = e.clientX;
            this.mouseY = e.clientY;
        }

        onMouseDown(e) {
            if (e.button !== 0) return;
            if (e.target && (e.target.closest('.wb-game-hud') || e.target.closest('.wb-game-trigger'))) return;
            audio.initContext();
            this.isMouseDown = true;
            this.shoot();
        }

        onMouseUp(e) {
            if (e.button === 0) {
                this.isMouseDown = false;
            }
        }

        shoot() {
            const now = performance.now();
            if (now - this.lastShootTime < this.shootInterval) return;
            this.lastShootTime = now;

            // 从飞船机头尖端发射
            const tipDist = this.ship.size * 1.1;
            const bx = this.ship.x + Math.cos(this.ship.angle) * tipDist;
            const by = this.ship.y + Math.sin(this.ship.angle) * tipDist;

            const bSpeed = 26;
            this.bullets.push({
                x: bx,
                y: by,
                vx: Math.cos(this.ship.angle) * bSpeed,
                vy: Math.sin(this.ship.angle) * bSpeed,
                life: 60,
                color: '#38bdf8'
            });

            // 飞船后坐力粒子
            for (let i = 0; i < 3; i++) {
                const spread = (Math.random() - 0.5) * 0.5;
                this.particles.push({
                    x: bx,
                    y: by,
                    vx: -Math.cos(this.ship.angle + spread) * (Math.random() * 3 + 1),
                    vy: -Math.sin(this.ship.angle + spread) * (Math.random() * 3 + 1),
                    size: Math.random() * 2.5 + 1.5,
                    life: 15,
                    maxLife: 15,
                    color: '#67e8f9'
                });
            }

            audio.playLaser();
        }

        loop() {
            this.update();
            this.render();
            this.animId = requestAnimationFrame(this.loop);
        }

        update() {
            const now = performance.now();
            if (this.isMouseDown && now - this.lastShootTime >= this.shootInterval) {
                this.shoot();
            }

            // 1. 飞船移动平滑跟随鼠标
            const dx = this.mouseX - this.ship.x;
            const dy = this.mouseY - this.ship.y;
            const dist = Math.hypot(dx, dy);

            // 平滑插值靠近鼠标
            const lerpSpeed = 0.28;
            this.ship.vx = dx * lerpSpeed;
            this.ship.vy = dy * lerpSpeed;
            this.ship.x += this.ship.vx;
            this.ship.y += this.ship.vy;
            this.ship.speed = dist;

            // 飞船朝向：当有移动时朝向移动方向，静止时朝向鼠标
            if (dist > 2) {
                this.ship.targetAngle = Math.atan2(dy, dx);
            }
            // 角度插值
            let angleDiff = this.ship.targetAngle - this.ship.angle;
            while (angleDiff < -Math.PI) angleDiff += Math.PI * 2;
            while (angleDiff > Math.PI) angleDiff -= Math.PI * 2;
            this.ship.angle += angleDiff * 0.35;

            // 尾部离子推进器粒子
            if (dist > 3) {
                const tailDist = this.ship.size * 0.8;
                const tailX = this.ship.x - Math.cos(this.ship.angle) * tailDist;
                const tailY = this.ship.y - Math.sin(this.ship.angle) * tailDist;
                for (let i = 0; i < 2; i++) {
                    const spread = (Math.random() - 0.5) * 0.8;
                    this.particles.push({
                        x: tailX,
                        y: tailY,
                        vx: -Math.cos(this.ship.angle + spread) * (Math.random() * 4 + 2),
                        vy: -Math.sin(this.ship.angle + spread) * (Math.random() * 4 + 2),
                        size: Math.random() * 3 + 2,
                        life: 18,
                        maxLife: 18,
                        color: Math.random() > 0.4 ? '#f59e0b' : '#ef4444'
                    });
                }
            }

            // 2. 子弹更新与碰撞检测 (通过严格的 DOM 框体防护)
            for (let i = this.bullets.length - 1; i >= 0; i--) {
                const b = this.bullets[i];
                b.x += b.vx;
                b.y += b.vy;
                b.life--;

                // 出界移除
                if (b.life <= 0 || b.x < 0 || b.x > window.innerWidth || b.y < 0 || b.y > window.innerHeight) {
                    this.bullets.splice(i, 1);
                    continue;
                }

                // 精准 DOM 击中检测
                const hitEl = findTargetElementAt(b.x, b.y);
                if (hitEl) {
                    this.onHitDOM(hitEl, b.x, b.y, b.vx, b.vy);
                    this.bullets.splice(i, 1);
                }
            }

            // 3. 粒子系统更新
            for (let i = this.particles.length - 1; i >= 0; i--) {
                const p = this.particles[i];
                p.x += p.vx;
                p.y += p.vy;
                p.vx *= 0.94;
                p.vy *= 0.94;
                p.life--;
                if (p.life <= 0) {
                    this.particles.splice(i, 1);
                }
            }

            // 4. 浮动文字更新
            for (let i = this.floats.length - 1; i >= 0; i--) {
                const f = this.floats[i];
                f.y += f.vy;
                f.life--;
                if (f.life <= 0) {
                    this.floats.splice(i, 1);
                }
            }
        }

        // 击中 DOM 元素
        onHitDOM(el, x, y, vx, vy) {
            const rect = el.getBoundingClientRect();
            const color = window.getComputedStyle(el).color || '#6366f1';

            // 生成击碎碎片粒子
            const count = Math.min(24, Math.max(12, Math.floor((rect.width * rect.height) / 1200)));
            for (let i = 0; i < count; i++) {
                const angle = Math.random() * Math.PI * 2;
                const speed = Math.random() * 8 + 3;
                this.particles.push({
                    x: x,
                    y: y,
                    vx: Math.cos(angle) * speed + vx * 0.1,
                    vy: Math.sin(angle) * speed + vy * 0.1,
                    size: Math.random() * 3.5 + 2,
                    life: Math.floor(Math.random() * 20 + 20),
                    maxLife: 40,
                    color: Math.random() > 0.5 ? color : '#38bdf8'
                });
            }

            // 元素物理抖动位移方向
            const rX = (Math.random() - 0.5) * 18;
            const rY = (Math.random() - 0.5) * 18;
            const rot = (Math.random() - 0.5) * 20;
            el.style.setProperty('--wb-vx', `${rX}px`);
            el.style.setProperty('--wb-vy', `${rY}px`);
            el.style.setProperty('--wb-rot', `${rot}deg`);

            // 添加碎裂动画并删除该具体内容 DOM 元素
            el.classList.add('wb-dom-shattering');
            setTimeout(() => {
                if (el.parentNode) {
                    el.remove();
                }
            }, 180);

            // 飘字加分
            const combo = this.manager.addHit();
            const text = combo > 1 ? `💥 x${combo} COMBO!` : '💥 CRUSHED!';
            this.floats.push({
                x: x,
                y: y - 10,
                vy: -1.2,
                text: text,
                color: combo > 1 ? '#f59e0b' : '#38bdf8',
                life: 35,
                maxLife: 35
            });

            audio.playExplosion();
            if (combo > 1) {
                audio.playCombo(combo);
            }
            triggerScreenShake();
        }

        render() {
            if (!this.ctx) return;
            this.ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);

            // 1. 绘制粒子
            for (let i = 0; i < this.particles.length; i++) {
                const p = this.particles[i];
                const alpha = Math.max(0, p.life / p.maxLife);
                this.ctx.fillStyle = p.color;
                this.ctx.globalAlpha = alpha;
                this.ctx.beginPath();
                this.ctx.arc(p.x, p.y, p.size * alpha, 0, Math.PI * 2);
                this.ctx.fill();
            }

            // 2. 绘制子弹 (发光激光束)
            this.ctx.globalAlpha = 1;
            for (let i = 0; i < this.bullets.length; i++) {
                const b = this.bullets[i];
                const len = 16;
                const angle = Math.atan2(b.vy, b.vx);

                this.ctx.save();
                this.ctx.translate(b.x, b.y);
                this.ctx.rotate(angle);

                // 外发光
                this.ctx.shadowColor = '#38bdf8';
                this.ctx.shadowBlur = 12;
                this.ctx.strokeStyle = '#38bdf8';
                this.ctx.lineWidth = 3.5;
                this.ctx.lineCap = 'round';
                this.ctx.beginPath();
                this.ctx.moveTo(-len, 0);
                this.ctx.lineTo(0, 0);
                this.ctx.stroke();

                // 核心白光
                this.ctx.shadowBlur = 0;
                this.ctx.strokeStyle = '#ffffff';
                this.ctx.lineWidth = 1.5;
                this.ctx.beginPath();
                this.ctx.moveTo(-len * 0.7, 0);
                this.ctx.lineTo(0, 0);
                this.ctx.stroke();

                this.ctx.restore();
            }

            // 3. 绘制三角形样式的飞船
            this.ctx.save();
            this.ctx.translate(this.ship.x, this.ship.y);
            this.ctx.rotate(this.ship.angle);

            const s = this.ship.size;

            // 飞船发光外壳
            this.ctx.shadowColor = '#6366f1';
            this.ctx.shadowBlur = 16;
            this.ctx.lineWidth = 2.5;
            this.ctx.strokeStyle = '#818cf8';
            this.ctx.fillStyle = 'rgba(15, 23, 42, 0.85)';

            // 绘制经典的锐利几何等腰三角飞船
            this.ctx.beginPath();
            this.ctx.moveTo(s * 1.25, 0);          // 机头尖端
            this.ctx.lineTo(-s * 0.7, -s * 0.75); // 左机翼
            this.ctx.lineTo(-s * 0.35, 0);        // 尾部内凹
            this.ctx.lineTo(-s * 0.7, s * 0.75);  // 右机翼
            this.ctx.closePath();
            this.ctx.fill();
            this.ctx.stroke();

            // 飞船内构线与能量核心
            this.ctx.shadowBlur = 6;
            this.ctx.shadowColor = '#38bdf8';
            this.ctx.strokeStyle = '#38bdf8';
            this.ctx.lineWidth = 1.5;
            this.ctx.beginPath();
            this.ctx.moveTo(s * 0.5, 0);
            this.ctx.lineTo(-s * 0.1, -s * 0.25);
            this.ctx.lineTo(-s * 0.1, s * 0.25);
            this.ctx.closePath();
            this.ctx.stroke();

            // 机头准星导引指示
            this.ctx.beginPath();
            this.ctx.arc(0, 0, 2.5, 0, Math.PI * 2);
            this.ctx.fillStyle = '#67e8f9';
            this.ctx.fill();

            this.ctx.restore();

            // 4. 绘制准星光圈 (跟随真实鼠标位置)
            this.ctx.save();
            this.ctx.translate(this.mouseX, this.mouseY);
            this.ctx.strokeStyle = 'rgba(56, 189, 248, 0.6)';
            this.ctx.lineWidth = 1.2;
            this.ctx.beginPath();
            this.ctx.arc(0, 0, 10, 0, Math.PI * 2);
            this.ctx.stroke();

            this.ctx.beginPath();
            this.ctx.moveTo(-14, 0); this.ctx.lineTo(-6, 0);
            this.ctx.moveTo(6, 0);   this.ctx.lineTo(14, 0);
            this.ctx.moveTo(0, -14); this.ctx.lineTo(0, -6);
            this.ctx.moveTo(0, 6);   this.ctx.lineTo(0, 14);
            this.ctx.stroke();
            this.ctx.restore();

            // 5. 绘制浮动计分文字
            for (let i = 0; i < this.floats.length; i++) {
                const f = this.floats[i];
                const alpha = Math.max(0, f.life / f.maxLife);
                this.ctx.save();
                this.ctx.font = 'bold 14px ui-monospace, SFMono-Regular, monospace';
                this.ctx.fillStyle = f.color;
                this.ctx.globalAlpha = alpha;
                this.ctx.shadowColor = '#000000';
                this.ctx.shadowBlur = 4;
                this.ctx.fillText(f.text, f.x, f.y);
                this.ctx.restore();
            }
        }
    }

    /* ==========================================================================
       4. 游戏 2：DOM 引力黑洞 (DOM Gravity Singularity)
       ========================================================================== */
    class BlackHoleGame {
        constructor(manager) {
            this.manager = manager;
            this.name = 'DOM Black Hole';
            this.canvas = null;
            this.ctx = null;
            this.animId = null;

            this.mouseX = window.innerWidth / 2;
            this.mouseY = window.innerHeight / 2;
            this.holeRadius = 24;
            this.gravRadius = 180;
            this.angle = 0;
            this.diskParticles = [];

            this._onMouseMove = (e) => {
                this.mouseX = e.clientX;
                this.mouseY = e.clientY;
            };
            this._onResize = this.onResize.bind(this);
        }

        start() {
            this.canvas = this.manager.getCanvas();
            this.ctx = this.canvas.getContext('2d');
            this.onResize();

            // 激活音频并播放黑洞启动音效
            audio.initContext();
            audio.playBlackHoleSpawn();

            this.mouseX = window.innerWidth / 2;
            this.mouseY = window.innerHeight / 2;
            this.holeRadius = 24;
            this.diskParticles = [];
            for (let i = 0; i < 40; i++) {
                this.diskParticles.push({
                    dist: Math.random() * (this.gravRadius - 20) + 20,
                    angle: Math.random() * Math.PI * 2,
                    speed: (Math.random() * 0.04 + 0.02) * (Math.random() > 0.5 ? 1 : -1),
                    size: Math.random() * 2.5 + 1,
                    color: Math.random() > 0.5 ? '#a855f7' : '#ec4899'
                });
            }

            document.body.classList.add('wb-game-active', 'wb-game-cursor-none');
            window.addEventListener('mousemove', this._onMouseMove, { passive: true });
            window.addEventListener('resize', this._onResize);

            this.loop = this.loop.bind(this);
            this.animId = requestAnimationFrame(this.loop);
        }

        stop() {
            if (this.animId) {
                cancelAnimationFrame(this.animId);
                this.animId = null;
            }
            document.body.classList.remove('wb-game-active', 'wb-game-cursor-none');
            window.removeEventListener('mousemove', this._onMouseMove);
            window.removeEventListener('resize', this._onResize);

            if (this.ctx && this.canvas) {
                this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            }
        }

        onResize() {
            if (!this.canvas) return;
            const dpr = window.devicePixelRatio || 1;
            this.canvas.width = window.innerWidth * dpr;
            this.canvas.height = window.innerHeight * dpr;
            if (this.ctx) {
                this.ctx.setTransform(1, 0, 0, 1, 0, 0);
                this.ctx.scale(dpr, dpr);
            }
        }

        loop() {
            this.update();
            this.render();
            this.animId = requestAnimationFrame(this.loop);
        }

        update() {
            this.angle += 0.05;

            // 吸积盘粒子运动
            for (let i = 0; i < this.diskParticles.length; i++) {
                const p = this.diskParticles[i];
                p.angle += p.speed;
                p.dist -= 0.35;
                if (p.dist < this.holeRadius) {
                    p.dist = this.gravRadius + Math.random() * 20;
                }
            }

            // 引力捕获 DOM (经过 DOM 框体防护，只吸引具体的叶子内容节点)
            const sampleCount = 10;
            for (let i = 0; i < sampleCount; i++) {
                const a = (Math.PI * 2 / sampleCount) * i + this.angle;
                const r = Math.random() * this.gravRadius;
                const sx = this.mouseX + Math.cos(a) * r;
                const sy = this.mouseY + Math.sin(a) * r;
                const el = findTargetElementAt(sx, sy);
                if (el && isDestructibleContentTarget(el)) {
                    this.attractDOM(el);
                }
            }
        }

        attractDOM(el) {
            // 双重安全防护：若属于框体则绝不吸引
            if (!isDestructibleContentTarget(el)) return;

            const rect = el.getBoundingClientRect();
            const cx = rect.left + rect.width / 2;
            const cy = rect.top + rect.height / 2;
            const dx = this.mouseX - cx;
            const dy = this.mouseY - cy;
            const dist = Math.hypot(dx, dy);

            if (dist < 36) {
                // 距离过近，直接吞噬
                el.classList.add('wb-dom-shattering');
                el.style.transition = 'all 0.16s cubic-bezier(0.5, 0, 0.75, 0)';
                el.style.transform = `scale(0.01) rotate(180deg)`;
                setTimeout(() => {
                    if (el.parentNode) el.remove();
                }, 160);

                const combo = this.manager.addHit();
                audio.playBlackHoleSwallow(combo);
                this.holeRadius = Math.min(60, this.holeRadius + 0.3);
                triggerScreenShake();
            } else if (dist < this.gravRadius) {
                // 受到引力拉扯扭曲
                const pullFactor = (1 - dist / this.gravRadius) * 12;
                const pullX = (dx / dist) * pullFactor;
                const pullY = (dy / dist) * pullFactor;
                el.style.transform = `translate(${pullX}px, ${pullY}px) scale(${Math.max(0.7, 1 - pullFactor * 0.02)})`;
                el.style.filter = `brightness(1.2)`;
            }
        }

        render() {
            if (!this.ctx) return;
            this.ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);

            const x = this.mouseX;
            const y = this.mouseY;

            // 1. 引力透镜光圈
            const grad = this.ctx.createRadialGradient(x, y, this.holeRadius * 0.8, x, y, this.gravRadius);
            grad.addColorStop(0, 'rgba(168, 85, 247, 0.45)');
            grad.addColorStop(0.3, 'rgba(236, 72, 153, 0.15)');
            grad.addColorStop(1, 'rgba(0, 0, 0, 0)');
            this.ctx.fillStyle = grad;
            this.ctx.beginPath();
            this.ctx.arc(x, y, this.gravRadius, 0, Math.PI * 2);
            this.ctx.fill();

            // 2. 吸积盘粒子
            for (let i = 0; i < this.diskParticles.length; i++) {
                const p = this.diskParticles[i];
                const px = x + Math.cos(p.angle) * p.dist;
                const py = y + Math.sin(p.angle) * p.dist * 0.4; // 椭圆倾角吸积盘
                this.ctx.fillStyle = p.color;
                this.ctx.globalAlpha = Math.min(1, (p.dist - this.holeRadius) / 40);
                this.ctx.beginPath();
                this.ctx.arc(px, py, p.size, 0, Math.PI * 2);
                this.ctx.fill();
            }

            // 3. 黑洞奇点视界 (纯黑核心 + 霓虹紫粉光圈)
            this.ctx.globalAlpha = 1;
            this.ctx.shadowColor = '#d946ef';
            this.ctx.shadowBlur = 24;
            this.ctx.fillStyle = '#000000';
            this.ctx.beginPath();
            this.ctx.arc(x, y, this.holeRadius, 0, Math.PI * 2);
            this.ctx.fill();

            this.ctx.strokeStyle = '#f472b6';
            this.ctx.lineWidth = 2.5;
            this.ctx.stroke();
            this.ctx.shadowBlur = 0;
        }
    }

    /* ==========================================================================
       5. 游戏 3：DOM 弹球粉碎者 (DOM Brick Breaker)
       ========================================================================== */
    class BrickBreakerGame {
        constructor(manager) {
            this.manager = manager;
            this.name = 'DOM Brick Breaker';
            this.canvas = null;
            this.ctx = null;
            this.animId = null;

            this.paddle = {
                w: 120,
                h: 14,
                x: window.innerWidth / 2 - 60,
                y: window.innerHeight - 40
            };

            this.ball = {
                x: window.innerWidth / 2,
                y: window.innerHeight - 80,
                vx: 6,
                vy: -7,
                r: 8
            };

            this._onMouseMove = (e) => {
                this.paddle.x = Math.max(0, Math.min(window.innerWidth - this.paddle.w, e.clientX - this.paddle.w / 2));
            };
            this._onResize = this.onResize.bind(this);
        }

        start() {
            this.canvas = this.manager.getCanvas();
            this.ctx = this.canvas.getContext('2d');
            this.onResize();

            audio.initContext();

            this.paddle.x = window.innerWidth / 2 - this.paddle.w / 2;
            this.paddle.y = window.innerHeight - 45;
            this.ball.x = window.innerWidth / 2;
            this.ball.y = this.paddle.y - 20;
            this.ball.vx = (Math.random() > 0.5 ? 1 : -1) * (Math.random() * 2 + 5);
            this.ball.vy = -7;

            document.body.classList.add('wb-game-active');
            window.addEventListener('mousemove', this._onMouseMove, { passive: true });
            window.addEventListener('resize', this._onResize);

            this.loop = this.loop.bind(this);
            this.animId = requestAnimationFrame(this.loop);
        }

        stop() {
            if (this.animId) {
                cancelAnimationFrame(this.animId);
                this.animId = null;
            }
            document.body.classList.remove('wb-game-active');
            window.removeEventListener('mousemove', this._onMouseMove);
            window.removeEventListener('resize', this._onResize);

            if (this.ctx && this.canvas) {
                this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
            }
        }

        onResize() {
            if (!this.canvas) return;
            const dpr = window.devicePixelRatio || 1;
            this.canvas.width = window.innerWidth * dpr;
            this.canvas.height = window.innerHeight * dpr;
            if (this.ctx) {
                this.ctx.setTransform(1, 0, 0, 1, 0, 0);
                this.ctx.scale(dpr, dpr);
            }
        }

        loop() {
            this.update();
            this.render();
            this.animId = requestAnimationFrame(this.loop);
        }

        update() {
            const b = this.ball;
            b.x += b.vx;
            b.y += b.vy;

            // 视口边界反弹
            if (b.x - b.r < 0) {
                b.x = b.r;
                b.vx = -b.vx;
                audio.playLaser();
            } else if (b.x + b.r > window.innerWidth) {
                b.x = window.innerWidth - b.r;
                b.vx = -b.vx;
                audio.playLaser();
            }

            if (b.y - b.r < 0) {
                b.y = b.r;
                b.vy = -b.vy;
                audio.playLaser();
            } else if (b.y > window.innerHeight) {
                // 掉出底边重置
                b.x = this.paddle.x + this.paddle.w / 2;
                b.y = this.paddle.y - 20;
                b.vy = -7;
                b.vx = (Math.random() - 0.5) * 8;
            }

            // 挡板碰撞
            const p = this.paddle;
            if (b.y + b.r >= p.y && b.y - b.r <= p.y + p.h && b.x >= p.x && b.x <= p.x + p.w && b.vy > 0) {
                b.y = p.y - b.r;
                const hitPos = (b.x - (p.x + p.w / 2)) / (p.w / 2);
                b.vx = hitPos * 8;
                b.vy = -Math.max(5, Math.abs(b.vy));
                audio.playLaser();
            }

            // 碰到 DOM 内容元素反弹击碎
            const hitEl = findTargetElementAt(b.x, b.y);
            if (hitEl && isDestructibleContentTarget(hitEl)) {
                b.vy = -b.vy;
                hitEl.classList.add('wb-dom-shattering');
                setTimeout(() => {
                    if (hitEl.parentNode) hitEl.remove();
                }, 180);

                const combo = this.manager.addHit();
                audio.playExplosion();
                triggerScreenShake();
            }
        }

        render() {
            if (!this.ctx) return;
            this.ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);

            // 1. 绘制挡板
            this.ctx.shadowColor = '#6366f1';
            this.ctx.shadowBlur = 12;
            this.ctx.fillStyle = '#6366f1';
            this.ctx.beginPath();
            this.ctx.roundRect(this.paddle.x, this.paddle.y, this.paddle.w, this.paddle.h, 7);
            this.ctx.fill();

            // 2. 绘制发光弹球
            this.ctx.shadowColor = '#38bdf8';
            this.ctx.shadowBlur = 16;
            this.ctx.fillStyle = '#38bdf8';
            this.ctx.beginPath();
            this.ctx.arc(this.ball.x, this.ball.y, this.ball.r, 0, Math.PI * 2);
            this.ctx.fill();
            this.ctx.shadowBlur = 0;
        }
    }

    /* ==========================================================================
       6. 摸鱼游戏中心总管理器 (RelaxGameManager)
       ========================================================================== */
    class RelaxGameManager {
        constructor() {
            this.activeGame = null;
            this.isMenuOpen = false;
            this.canvas = null;
            this.destroyedCount = 0;
            this.combo = 0;
            this.comboTimer = null;

            this.games = {
                'space-destroyer': new SpaceDestroyerGame(this),
                'black-hole': new BlackHoleGame(this),
                'brick-breaker': new BrickBreakerGame(this)
            };
        }

        init() {
            this.createCanvas();
            this.createDOM();
            this.bindEvents();
        }

        getCanvas() {
            if (!this.canvas) {
                this.createCanvas();
            }
            return this.canvas;
        }

        createCanvas() {
            if (document.getElementById('wb-game-canvas')) {
                this.canvas = document.getElementById('wb-game-canvas');
                return;
            }
            this.canvas = document.createElement('canvas');
            this.canvas.id = 'wb-game-canvas';
            document.body.appendChild(this.canvas);
        }

        createDOM() {
            // 1. 左下角悬浮按钮
            const trigger = document.createElement('button');
            trigger.type = 'button';
            trigger.className = 'wb-game-trigger';
            trigger.id = 'wbGameTrigger';
            trigger.setAttribute('aria-label', window.wbT ? window.wbT('js.relax_arcade', '摸鱼游戏中心') : '摸鱼游戏中心');
            trigger.innerHTML = `
                <i class="fas fa-gamepad wb-game-trigger-icon"></i>
                <span class="wb-game-trigger-badge"></span>
                <span class="wb-game-trigger-tooltip">${window.wbT ? window.wbT('js.take_a_break', '摸鱼小游戏') : '摸鱼小游戏'}</span>
            `;
            document.body.appendChild(trigger);

            // 2. 菜单遮罩与弹出卡片
            const overlay = document.createElement('div');
            overlay.className = 'wb-game-modal-overlay';
            overlay.id = 'wbGameModalOverlay';

            const menu = document.createElement('div');
            menu.className = 'wb-game-menu-panel';
            menu.id = 'wbGameMenuPanel';
            menu.innerHTML = `
                <div class="wb-game-menu-header">
                    <div class="wb-game-menu-title-wrap">
                        <div class="wb-game-menu-title-icon"><i class="fas fa-ghost"></i></div>
                        <div>
                            <div class="wb-game-menu-title">${window.wbT ? window.wbT('js.arcade_title', '摸鱼游戏厅') : '摸鱼游戏厅'}</div>
                            <div class="wb-game-menu-subtitle">${window.wbT ? window.wbT('js.arcade_subtitle', '与当前网页元素趣味互动破坏') : '与当前网页元素趣味互动破坏'}</div>
                        </div>
                    </div>
                    <button type="button" class="wb-game-menu-close" id="wbGameMenuClose" title="关闭"><i class="fas fa-times"></i></button>
                </div>

                <div class="wb-game-cards-list">
                    <!-- 游戏 1：宇宙飞船打 DOM -->
                    <div class="wb-game-card" data-game-key="space-destroyer">
                        <div class="wb-game-card-top">
                            <div class="wb-game-card-header">
                                <span class="wb-game-card-icon">🚀</span>
                                <span class="wb-game-card-name">网页太空战机</span>
                            </div>
                            <span class="wb-game-card-badge">强力破坏</span>
                        </div>
                        <div class="wb-game-card-desc">
                            操控三角太空战机跟随鼠标翱翔，点击发射高能光束，把网页上的文字、按钮和卡片通通轰得粉碎！
                        </div>
                        <div class="wb-game-card-footer">
                            <span class="wb-game-card-controls">移动瞄准 · 左键连射</span>
                            <button type="button" class="wb-game-card-btn"><i class="fas fa-play"></i> 启动战机</button>
                        </div>
                    </div>

                    <!-- 游戏 2：DOM 引力黑洞 -->
                    <div class="wb-game-card" data-game-key="black-hole">
                        <div class="wb-game-card-top">
                            <div class="wb-game-card-header">
                                <span class="wb-game-card-icon">🕳️</span>
                                <span class="wb-game-card-name">DOM 引力黑洞</span>
                            </div>
                            <span class="wb-game-card-badge">解压撕裂</span>
                        </div>
                        <div class="wb-game-card-desc">
                            化身贪婪奇点，引力场将撕裂周围所有的网页组件，旋转缩小并彻底吸入虚无！
                        </div>
                        <div class="wb-game-card-footer">
                            <span class="wb-game-card-controls">移动光标 · 引力吸附</span>
                            <button type="button" class="wb-game-card-btn"><i class="fas fa-play"></i> 释放黑洞</button>
                        </div>
                    </div>

                    <!-- 游戏 3：DOM 弹球消除者 -->
                    <div class="wb-game-card" data-game-key="brick-breaker">
                        <div class="wb-game-card-top">
                            <div class="wb-game-card-header">
                                <span class="wb-game-card-icon">🏓</span>
                                <span class="wb-game-card-name">DOM 弹球消除者</span>
                            </div>
                            <span class="wb-game-card-badge">街机碰撞</span>
                        </div>
                        <div class="wb-game-card-desc">
                            把整个网页当成打砖块！移动底部挡板反弹小球，将网页组件击碎消除。
                        </div>
                        <div class="wb-game-card-footer">
                            <span class="wb-game-card-controls">移动挡板 · 反弹粉碎</span>
                            <button type="button" class="wb-game-card-btn"><i class="fas fa-play"></i> 开始弹球</button>
                        </div>
                    </div>
                </div>

                <div class="wb-game-menu-footer">
                    <button type="button" class="wb-game-footer-btn" id="wbGameSoundBtn">
                        <i class="fas fa-volume-up" id="wbGameSoundIcon"></i>
                        <span id="wbGameSoundText">${audio.muted ? '静音中' : '8-bit 音效'}</span>
                    </button>
                    <button type="button" class="wb-game-footer-btn" id="wbGameResetPageBtn">
                        <i class="fas fa-undo"></i> 复原网页
                    </button>
                </div>
            `;
            document.body.appendChild(overlay);
            document.body.appendChild(menu);

            // 3. 屏幕顶部 HUD 条
            const hud = document.createElement('div');
            hud.className = 'wb-game-hud';
            hud.id = 'wbGameHud';
            hud.innerHTML = `
                <div class="wb-game-hud-stat">
                    <span>已击碎 DOM:</span>
                    <span class="wb-game-hud-stat-value" id="wbGameDestroyedCount">0</span>
                </div>
                <div class="wb-game-hud-combo" id="wbGameCombo">x0 COMBO!</div>
                <div class="wb-game-hud-divider"></div>
                <span class="wb-game-hud-hint">点击射击 / 按 ESC 退出</span>
                <button type="button" class="wb-game-hud-btn wb-game-hud-btn-restore" id="wbGameHudRestoreBtn" title="复原被击毁的页面组件">
                    <i class="fas fa-undo"></i> 复原
                </button>
                <button type="button" class="wb-game-hud-btn" id="wbGameHudExitBtn" title="退出游戏">
                    <i class="fas fa-times"></i> 退出
                </button>
            `;
            document.body.appendChild(hud);
        }

        bindEvents() {
            const trigger = document.getElementById('wbGameTrigger');
            const overlay = document.getElementById('wbGameModalOverlay');
            const closeBtn = document.getElementById('wbGameMenuClose');
            const soundBtn = document.getElementById('wbGameSoundBtn');
            const soundIcon = document.getElementById('wbGameSoundIcon');
            const soundText = document.getElementById('wbGameSoundText');
            const resetBtn = document.getElementById('wbGameResetPageBtn');
            const hudRestoreBtn = document.getElementById('wbGameHudRestoreBtn');
            const hudExitBtn = document.getElementById('wbGameHudExitBtn');

            // 只要与游戏组件发生点击交互，就立刻预激活 AudioContext
            const activateAudio = () => audio.initContext();
            trigger?.addEventListener('click', activateAudio);
            overlay?.addEventListener('click', activateAudio);

            // 切换菜单展开/收起
            trigger?.addEventListener('click', () => {
                if (this.activeGame) {
                    this.stopGame();
                } else {
                    this.toggleMenu();
                }
            });

            overlay?.addEventListener('click', () => this.closeMenu());
            closeBtn?.addEventListener('click', () => this.closeMenu());

            // 选择游戏卡片
            document.querySelectorAll('.wb-game-card').forEach(card => {
                card.addEventListener('click', (e) => {
                    audio.initContext();
                    const key = card.getAttribute('data-game-key');
                    this.startGame(key);
                });
            });

            // 音效切换
            soundBtn?.addEventListener('click', () => {
                audio.initContext();
                const isMuted = !audio.muted;
                audio.setMuted(isMuted);
                soundIcon.className = isMuted ? 'fas fa-volume-mute' : 'fas fa-volume-up';
                soundText.textContent = isMuted ? '静音中' : '8-bit 音效';
                if (!isMuted) audio.playLaser();
            });

            // 复原页面
            resetBtn?.addEventListener('click', () => this.restorePage());
            hudRestoreBtn?.addEventListener('click', () => this.restorePage());

            // 退出游戏
            hudExitBtn?.addEventListener('click', () => this.stopGame());

            // 快捷键 ESC 退出
            window.addEventListener('keydown', (e) => {
                audio.initContext();
                if (e.key === 'Escape') {
                    if (this.activeGame) {
                        this.stopGame();
                    } else if (this.isMenuOpen) {
                        this.closeMenu();
                    }
                }
            });

            // 页面 PJAX 路由切换时优雅停止
            document.addEventListener('pjax:start', () => {
                if (this.activeGame) {
                    this.stopGame();
                }
            });
            document.addEventListener('page:ready', () => {
                if (this.activeGame) {
                    this.stopGame();
                }
            });
        }

        toggleMenu() {
            if (this.isMenuOpen) this.closeMenu();
            else this.openMenu();
        }

        openMenu() {
            audio.initContext();
            this.isMenuOpen = true;
            document.getElementById('wbGameMenuPanel')?.classList.add('active');
            document.getElementById('wbGameModalOverlay')?.classList.add('active');
        }

        closeMenu() {
            this.isMenuOpen = false;
            document.getElementById('wbGameMenuPanel')?.classList.remove('active');
            document.getElementById('wbGameModalOverlay')?.classList.remove('active');
        }

        startGame(key) {
            const game = this.games[key];
            if (!game) return;

            // 确保在用户交互的当前栈内激活 AudioContext
            audio.initContext();

            if (this.activeGame) {
                this.activeGame.stop();
            }

            this.closeMenu();
            this.activeGame = game;
            this.combo = 0;
            this.updateHud();

            document.getElementById('wbGameHud')?.classList.add('active');
            game.start();
        }

        stopGame() {
            if (!this.activeGame) return;
            this.activeGame.stop();
            this.activeGame = null;
            document.getElementById('wbGameHud')?.classList.remove('active');
        }

        addHit() {
            this.destroyedCount++;
            this.combo++;

            clearTimeout(this.comboTimer);
            this.comboTimer = setTimeout(() => {
                this.combo = 0;
                this.updateHud();
            }, 1800);

            this.updateHud();
            return this.combo;
        }

        updateHud() {
            const countEl = document.getElementById('wbGameDestroyedCount');
            const comboEl = document.getElementById('wbGameCombo');
            if (countEl) countEl.textContent = this.destroyedCount;
            if (comboEl) {
                if (this.combo > 1) {
                    comboEl.textContent = `x${this.combo} COMBO!`;
                    comboEl.classList.add('active');
                } else {
                    comboEl.classList.remove('active');
                }
            }
        }

        // 复原网页 DOM
        restorePage() {
            this.stopGame();
            if (window.toast) {
                window.toast.info('正在复原网页...');
            }
            // 优先使用 PJAX 无刷新复原当前网页
            const currentUrl = window.location.href;
            if (typeof window.pjaxNavigate === 'function') {
                window.pjaxNavigate(currentUrl);
            } else {
                window.location.reload();
            }
        }
    }

    // 页面加载完成后自动启动管理器
    function init() {
        const manager = new RelaxGameManager();
        manager.init();
        window.wbRelaxGameManager = manager;
    }

    if (document.readyState !== 'loading') {
        init();
    } else {
        document.addEventListener('DOMContentLoaded', init);
    }
})();
