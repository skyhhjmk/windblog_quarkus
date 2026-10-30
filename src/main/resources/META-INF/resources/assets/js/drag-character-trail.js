(() => {
    'use strict';

    const canvasId = 'windblog-drag-character-trail';
    const glyphs = '/ > < | _ : . 0 1 { }';
    const cellSize = 15;
    const fontSize = 12;
    const radius = 140;
    const lifetimeFrames = 70;
    const minimumPointDistance = 40;
    const maximumPoints = 60;
    // A full-viewport canvas at DPR 2 costs ~32 MiB of backing store; 1.5 is
    // visually indistinguishable for 12px glyphs and a third of the memory.
    const maximumDeviceScale = 1.5;
    const idleReleaseDelay = 4000;

    if (!window.matchMedia) return;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const coarsePointer = window.matchMedia('(max-width: 767px), (hover: none), (pointer: coarse)');
    if (reducedMotion.matches || coarsePointer.matches || document.getElementById(canvasId)) {
        return;
    }

    let accentColor = '#0ea5e9';
    function refreshAccentColor() {
        try {
            const value = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
            if (value) accentColor = value;
        } catch (_) {}
    }
    refreshAccentColor();

    let canvas = null;
    let context = null;
    let deviceScale = 1;
    let width = 0;
    let height = 0;
    let columns = 0;
    let rows = 0;
    let cellCount = 0;
    let cellX = new Float32Array();
    let cellY = new Float32Array();
    let baseGlyph = new Uint8Array();
    const points = [];
    let previousX = -9000;
    let previousY = -9000;
    let frame = 0;
    let animationFrame = 0;
    let idleTimer = 0;
    let animationRunning = false;
    let resizeQueued = false;

    function releaseCanvas() {
        window.clearTimeout(idleTimer);
        idleTimer = 0;
        if (animationFrame) {
            window.cancelAnimationFrame(animationFrame);
        }
        animationFrame = 0;
        animationRunning = false;
        points.length = 0;
        if (canvas) {
            // Freeing the backing store is what actually returns the memory;
            // merely hiding a full-viewport canvas keeps it allocated.
            canvas.width = 0;
            canvas.height = 0;
            canvas.remove();
            canvas = null;
        }
        context = null;
        width = 0;
        height = 0;
        cellCount = 0;
    }

    function scheduleIdleRelease() {
        window.clearTimeout(idleTimer);
        idleTimer = window.setTimeout(releaseCanvas, idleReleaseDelay);
    }

    function ensureCanvas() {
        if (canvas && context) {
            return true;
        }
        const element = document.createElement('canvas');
        element.id = canvasId;
        element.setAttribute('aria-hidden', 'true');
        document.body.insertBefore(element, document.body.firstChild);
        const ctx = element.getContext('2d');
        if (!ctx) {
            element.remove();
            return false;
        }
        canvas = element;
        context = ctx;
        refreshAccentColor();
        resize();
        return width > 0 && height > 0;
    }

    function resize() {
        if (!canvas || !context) return;
        width = Math.round(window.innerWidth);
        height = Math.round(window.innerHeight);
        if (width === 0 || height === 0) return;

        deviceScale = Math.max(1, Math.min(maximumDeviceScale, window.devicePixelRatio || 1));
        canvas.width = Math.round(width * deviceScale);
        canvas.height = Math.round(height * deviceScale);
        context.setTransform(deviceScale, 0, 0, deviceScale, 0, 0);

        columns = Math.ceil(width / cellSize) + 1;
        rows = Math.ceil(height / cellSize) + 1;
        cellCount = columns * rows;
        cellX = new Float32Array(cellCount);
        cellY = new Float32Array(cellCount);
        baseGlyph = new Uint8Array(cellCount);

        for (let row = 0; row < rows; row++) {
            for (let column = 0; column < columns; column++) {
                const index = row * columns + column;
                cellX[index] = column * cellSize + cellSize * 0.5;
                cellY[index] = row * cellSize + cellSize * 0.5;
                baseGlyph[index] = Math.random() * glyphs.length | 0;
            }
        }
        points.length = 0;
        context.clearRect(0, 0, width, height);
    }

    function draw() {
        if (!context || width === 0 || height === 0) return;

        context.clearRect(0, 0, width, height);
        context.font = `${fontSize}px 'JetBrains Mono', ui-monospace, monospace`;
        context.textAlign = 'center';
        context.textBaseline = 'middle';
        context.fillStyle = accentColor;

        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;
        for (const point of points) {
            if (frame - point.birth > lifetimeFrames) continue;
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        if (minX === Infinity) return;

        minX -= radius;
        minY -= radius;
        maxX += radius;
        maxY += radius;

        const radiusSquared = radius * radius;
        const pointCount = points.length;
        for (let index = 0; index < cellCount; index++) {
            const x = cellX[index];
            const y = cellY[index];
            if (x < minX || x > maxX || y < minY || y > maxY) continue;

            let intensity = 0;
            for (let pointIndex = 0; pointIndex < pointCount; pointIndex++) {
                const point = points[pointIndex];
                const age = frame - point.birth;
                if (age > lifetimeFrames) continue;

                const dx = x - point.x;
                const dy = y - point.y;
                const distanceSquared = dx * dx + dy * dy;
                if (distanceSquared > radiusSquared) continue;

                const distanceRatio = Math.sqrt(distanceSquared) / radius;
                const ageRatio = age / lifetimeFrames;
                const influence = (1 - distanceRatio) ** (1 + ageRatio * 5) * (1 - ageRatio * ageRatio);
                if (influence > intensity) intensity = influence;
            }

            if (intensity < 0.03) continue;
            const glyphIndex = intensity > 0.25
                ? (baseGlyph[index] + ((frame * 0.06 + intensity * 2 | 0) & 32767)) % glyphs.length
                : baseGlyph[index];
            const glyph = glyphs[glyphIndex];
            if (glyph === ' ') continue;

            context.globalAlpha = intensity > 0.25 ? intensity * 0.55 : intensity * 0.4;
            context.fillText(glyph, x, y);
        }
        context.globalAlpha = 1;
    }

    function animate() {
        frame++;
        while (points.length > 0 && frame - points[0].birth > lifetimeFrames) {
            points.shift();
        }

        draw();
        if (points.length > 0) {
            animationFrame = window.requestAnimationFrame(animate);
        } else {
            animationRunning = false;
            animationFrame = 0;
            scheduleIdleRelease();
        }
    }

    function startAnimation() {
        window.clearTimeout(idleTimer);
        idleTimer = 0;
        if (animationRunning) return;
        animationRunning = true;
        animationFrame = window.requestAnimationFrame(animate);
    }

    function handlePointerMove(event) {
        if (reducedMotion.matches) return;
        const x = event.clientX;
        const y = event.clientY;
        const dx = x - previousX;
        const dy = y - previousY;
        if (dx * dx + dy * dy <= minimumPointDistance * minimumPointDistance) {
            return;
        }
        if (!ensureCanvas()) return;
        points.push({x, y, birth: frame});
        if (points.length > maximumPoints) points.shift();
        previousX = x;
        previousY = y;
        startAnimation();
    }

    function resetPointerPath() {
        previousX = -9000;
        previousY = -9000;
    }

    function queueResize() {
        if (resizeQueued || !canvas) return;
        resizeQueued = true;
        window.requestAnimationFrame(() => {
            resizeQueued = false;
            if (canvas) resize();
        });
    }

    window.addEventListener('pointermove', handlePointerMove, {passive: true});
    window.addEventListener('pointerleave', resetPointerPath, {passive: true});
    window.addEventListener('blur', resetPointerPath, {passive: true});
    window.addEventListener('resize', queueResize, {passive: true});

    reducedMotion.addEventListener('change', (event) => {
        if (!event.matches) return;
        releaseCanvas();
        resetPointerPath();
    });

    // The accent is read from the theme once and refreshed only when it changes,
    // instead of calling getComputedStyle for every glyph of every frame.
    const themeObserver = new MutationObserver(refreshAccentColor);
    themeObserver.observe(document.documentElement, {attributes: true, attributeFilter: ['class']});
    const colorScheme = window.matchMedia('(prefers-color-scheme: light)');
    colorScheme.addEventListener?.('change', refreshAccentColor);

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            releaseCanvas();
            resetPointerPath();
        }
    });
})();
