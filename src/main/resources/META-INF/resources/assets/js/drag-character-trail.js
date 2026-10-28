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
    function resolveTrailColor() {
        try {
            const val = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
            if (val) return val;
        } catch (_) {}
        return '#0ea5e9';
    }
    if (!window.matchMedia) return;
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');

    if (reducedMotion.matches ||
        window.matchMedia('(max-width: 767px), (hover: none), (pointer: coarse)').matches ||
        document.getElementById(canvasId)) {
        return;
    }

    const canvas = document.createElement('canvas');
    canvas.id = canvasId;
    canvas.setAttribute('aria-hidden', 'true');
    document.body.insertBefore(canvas, document.body.firstChild);

    const context = canvas.getContext('2d');
    if (!context) {
        canvas.remove();
        return;
    }

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
    let animationRunning = false;

    function resize() {
        width = Math.round(window.innerWidth);
        height = Math.round(window.innerHeight);
        if (width === 0 || height === 0) return;

        deviceScale = Math.max(1, window.devicePixelRatio || 1);
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
        if (width === 0 || height === 0) return;

        context.clearRect(0, 0, width, height);
        context.font = `${fontSize}px 'Apercu Mono Pro', 'JetBrains Mono', ui-monospace, monospace`;
        context.textAlign = 'center';
        context.textBaseline = 'middle';

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
            context.fillStyle = resolveTrailColor();
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
            draw();
        }
    }

    function startAnimation() {
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
        if (dx * dx + dy * dy > minimumPointDistance * minimumPointDistance) {
            points.push({x, y, birth: frame});
            if (points.length > maximumPoints) points.shift();
            previousX = x;
            previousY = y;
            startAnimation();
        }
    }

    function resetPointerPath() {
        previousX = -9000;
        previousY = -9000;
    }

    resize();
    window.addEventListener('pointermove', handlePointerMove, {passive: true});
    window.addEventListener('pointerleave', resetPointerPath, {passive: true});
    window.addEventListener('blur', resetPointerPath, {passive: true});
    reducedMotion.addEventListener('change', (event) => {
        if (!event.matches) return;
        window.cancelAnimationFrame(animationFrame);
        animationRunning = false;
        points.length = 0;
        resetPointerPath();
        context.clearRect(0, 0, width, height);
    });

    window.addEventListener('resize', resize, {passive: true});
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            window.cancelAnimationFrame(animationFrame);
            animationRunning = false;
            points.length = 0;
            resetPointerPath();
            context.clearRect(0, 0, width, height);
        }
    });
})();
