(function () {
    'use strict';

    function formatBytes(bytes) {
        if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
    }

    function hexToRgb(hex) {
        const clean = String(hex || '#ffffff').replace('#', '').trim();
        const full = clean.length === 3
            ? clean[0] + clean[0] + clean[1] + clean[1] + clean[2] + clean[2]
            : clean.padEnd(6, 'f');
        const num = parseInt(full.slice(0, 6), 16);
        if (Number.isNaN(num)) return [255, 255, 255];
        return [(num >> 16) & 255, (num >> 8) & 255, num & 255];
    }

    function getExtensionForMime(mime) {
        switch (mime) {
            case 'image/webp': return 'webp';
            case 'image/jpeg': return 'jpg';
            case 'image/avif': return 'avif';
            case 'image/bmp': return 'bmp';
            case 'image/x-icon': return 'ico';
            case 'image/svg+xml': return 'svg';
            default: return 'png';
        }
    }

    function detectInputFormatLabel(filename, mime) {
        const ext = (String(filename || '').split('.').pop() || '').toUpperCase();
        if (ext && ext.length <= 5 && ext !== String(filename || '').toUpperCase()) {
            return ext === 'JPEG' ? 'JPG' : ext;
        }
        if (mime && mime.includes('/')) {
            return mime.split('/')[1].split('+')[0].toUpperCase();
        }
        return 'IMG';
    }

    // =========================================================================
    // 1. PURE JS BMP, MULTI-SIZE ICO & SVG ENCODERS
    // =========================================================================
    function encodeCanvasToBmpBlob(canvas, bgHex) {
        const w = canvas.width;
        const h = canvas.height;
        const ctx = canvas.getContext('2d');
        const imgData = ctx.getImageData(0, 0, w, h).data;
        const bg = hexToRgb(bgHex || '#ffffff');
        const rowStride = Math.floor((24 * w + 31) / 32) * 4;
        const pixelArraySize = rowStride * h;
        const fileSize = 54 + pixelArraySize;
        const buf = new ArrayBuffer(fileSize);
        const view = new DataView(buf);
        const u8 = new Uint8Array(buf);

        // BITMAPFILEHEADER
        view.setUint8(0, 0x42); // 'B'
        view.setUint8(1, 0x4D); // 'M'
        view.setUint32(2, fileSize, true);
        view.setUint32(6, 0, true);
        view.setUint32(10, 54, true);

        // BITMAPINFOHEADER
        view.setUint32(14, 40, true);
        view.setInt32(18, w, true);
        view.setInt32(22, -h, true); // top-down DIB
        view.setUint16(26, 1, true);
        view.setUint16(28, 24, true);
        view.setUint32(30, 0, true);
        view.setUint32(34, pixelArraySize, true);
        view.setInt32(38, 2835, true);
        view.setInt32(42, 2835, true);
        view.setUint32(46, 0, true);
        view.setUint32(50, 0, true);

        for (let y = 0; y < h; y++) {
            const dstRow = 54 + y * rowStride;
            const srcRow = y * w * 4;
            for (let x = 0; x < w; x++) {
                const s = srcRow + x * 4;
                const d = dstRow + x * 3;
                const a = imgData[s + 3] / 255;
                const r = Math.round(imgData[s] * a + bg[0] * (1 - a));
                const g = Math.round(imgData[s + 1] * a + bg[1] * (1 - a));
                const b = Math.round(imgData[s + 2] * a + bg[2] * (1 - a));
                u8[d] = b;
                u8[d + 1] = g;
                u8[d + 2] = r;
            }
        }
        return new Blob([buf], { type: 'image/bmp' });
    }

    async function encodeCanvasToIcoBlob(sourceCanvas, sizes) {
        const iconSizes = Array.isArray(sizes) && sizes.length > 0 ? sizes : [16, 32, 48, 64];
        const pngBuffers = [];

        for (let i = 0; i < iconSizes.length; i++) {
            const sz = Math.max(16, Math.min(256, iconSizes[i]));
            const tmp = document.createElement('canvas');
            tmp.width = sz;
            tmp.height = sz;
            const tctx = tmp.getContext('2d');
            tctx.imageSmoothingEnabled = true;
            tctx.imageSmoothingQuality = 'high';
            tctx.drawImage(sourceCanvas, 0, 0, sz, sz);
            const blob = await new Promise((resolve) => tmp.toBlob(resolve, 'image/png'));
            const ab = await blob.arrayBuffer();
            pngBuffers.push({ size: sz, bytes: new Uint8Array(ab) });
        }

        const count = pngBuffers.length;
        const headerSize = 6 + count * 16;
        let totalSize = headerSize;
        for (let i = 0; i < count; i++) totalSize += pngBuffers[i].bytes.length;

        const out = new ArrayBuffer(totalSize);
        const view = new DataView(out);
        const u8 = new Uint8Array(out);

        view.setUint16(0, 0, true);
        view.setUint16(2, 1, true);
        view.setUint16(4, count, true);

        let offset = headerSize;
        for (let i = 0; i < count; i++) {
            const entry = pngBuffers[i];
            const dirPos = 6 + i * 16;
            view.setUint8(dirPos, entry.size >= 256 ? 0 : entry.size);
            view.setUint8(dirPos + 1, entry.size >= 256 ? 0 : entry.size);
            view.setUint8(dirPos + 2, 0);
            view.setUint8(dirPos + 3, 0);
            view.setUint16(dirPos + 4, 1, true);
            view.setUint16(dirPos + 6, 32, true);
            view.setUint32(dirPos + 8, entry.bytes.length, true);
            view.setUint32(dirPos + 12, offset, true);
            u8.set(entry.bytes, offset);
            offset += entry.bytes.length;
        }
        return new Blob([out], { type: 'image/x-icon' });
    }

    async function encodeCanvasToSvgBlob(canvas) {
        const pngDataUrl = canvas.toDataURL('image/png');
        const w = canvas.width;
        const h = canvas.height;
        const svg = [
            '<?xml version="1.0" encoding="UTF-8"?>',
            '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + w + ' ' + h + '" width="' + w + '" height="' + h + '">',
            '  <image width="' + w + '" height="' + h + '" href="' + pngDataUrl + '" />',
            '</svg>'
        ].join('\n');
        return new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    }

    // =========================================================================
    // 2. SAMPLE TEST IMAGE GENERATOR (Zero-dependency Canvas Graphic)
    // =========================================================================
    async function createDemoSampleFile() {
        const c = document.createElement('canvas');
        c.width = 960;
        c.height = 540;
        const ctx = c.getContext('2d');

        // Rounded transparent card with vibrant gradient & geometric rings
        const grad = ctx.createLinearGradient(0, 0, c.width, c.height);
        grad.addColorStop(0, '#0f172a');
        grad.addColorStop(0.5, '#4f46e5');
        grad.addColorStop(1, '#10b981');

        ctx.beginPath();
        if (ctx.roundRect) ctx.roundRect(24, 24, c.width - 48, c.height - 48, 36);
        else ctx.rect(24, 24, c.width - 48, c.height - 48);
        ctx.fillStyle = grad;
        ctx.fill();

        // Decorative translucent circles
        ctx.fillStyle = 'rgba(255, 255, 255, 0.12)';
        ctx.beginPath();
        ctx.arc(760, 160, 140, 0, Math.PI * 2);
        ctx.fill();
        ctx.beginPath();
        ctx.arc(200, 400, 110, 0, Math.PI * 2);
        ctx.fill();

        // Typography
        ctx.fillStyle = '#ffffff';
        ctx.font = 'bold 42px system-ui, -apple-system, sans-serif';
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('WindBlog Local Image Transcoder', c.width / 2, c.height / 2 - 26);

        ctx.fillStyle = 'rgba(226, 232, 240, 0.9)';
        ctx.font = 'bold 20px monospace';
        ctx.fillText('960×540 Transparent PNG Sample · 100% Free & Local', c.width / 2, c.height / 2 + 32);

        const blob = await new Promise((r) => c.toBlob(r, 'image/png'));
        return new File([blob], 'windblog-sample-960x540.png', { type: 'image/png' });
    }

    // =========================================================================
    // 3. MAIN IMAGE CONVERTER CONTROLLER
    // =========================================================================
    let activeCleanup = null;

    function mountImageConverter() {
        const app = document.getElementById('imageConverterToolApp');
        if (!app) return;
        if (activeCleanup) activeCleanup();

        const targetFmtEl = document.getElementById('icTargetFormat');
        const qualSlider = document.getElementById('icQualitySlider');
        const qualLabel = document.getElementById('icQualityLabel');
        const scaleModeEl = document.getElementById('icScaleMode');
        const customDimWrap = document.getElementById('icCustomDimWrap');
        const customWidthEl = document.getElementById('icCustomWidth');
        const customHeightEl = document.getElementById('icCustomHeight');
        const bgColorEl = document.getElementById('icBgFillColor');
        const bgPresetEl = document.getElementById('icBgFillPreset');
        const queueListEl = document.getElementById('icQueueList');
        const pairBannerEl = document.getElementById('icActivePairBanner');

        let queueItems = [];
        const objectUrls = [];

        function computeTargetDimensions(srcW, srcH, mode) {
            if (!mode || mode === 'original' || mode === 'ico-multi') {
                return [srcW, srcH];
            }
            if (mode.startsWith('max-')) {
                const maxEdge = parseInt(mode.slice(4), 10) || 1920;
                if (Math.max(srcW, srcH) <= maxEdge) return [srcW, srcH];
                const ratio = maxEdge / Math.max(srcW, srcH);
                return [Math.max(1, Math.round(srcW * ratio)), Math.max(1, Math.round(srcH * ratio))];
            }
            if (mode.startsWith('pct-')) {
                const pct = (parseInt(mode.slice(4), 10) || 100) / 100;
                return [Math.max(1, Math.round(srcW * pct)), Math.max(1, Math.round(srcH * pct))];
            }
            if (mode === 'custom') {
                const cw = Math.max(8, Math.min(8192, parseInt(customWidthEl ? customWidthEl.value : '800', 10) || 800));
                const ch = Math.max(8, Math.min(8192, parseInt(customHeightEl ? customHeightEl.value : '600', 10) || 600));
                return [cw, ch];
            }
            if (mode.includes('x')) {
                const p = mode.split('x');
                return [parseInt(p[0], 10) || 32, parseInt(p[1], 10) || 32];
            }
            return [srcW, srcH];
        }

        async function transcodeEntry(entry) {
            const fmt = targetFmtEl ? targetFmtEl.value : 'image/webp';
            const qual = qualSlider ? Number(qualSlider.value) : 86;
            const scaleMode = scaleModeEl ? scaleModeEl.value : 'original';
            const bgHex = bgColorEl ? bgColorEl.value : '#ffffff';

            const [outW, outH] = computeTargetDimensions(entry.srcWidth, entry.srcHeight, scaleMode);
            const work = document.createElement('canvas');
            work.width = outW;
            work.height = outH;
            const wctx = work.getContext('2d');
            wctx.imageSmoothingEnabled = true;
            wctx.imageSmoothingQuality = 'high';

            if (fmt === 'image/jpeg') {
                wctx.fillStyle = bgHex;
                wctx.fillRect(0, 0, outW, outH);
            }
            wctx.drawImage(entry.sourceCanvas, 0, 0, outW, outH);

            let blob = null;
            let actualMime = fmt;
            if (fmt === 'image/bmp') {
                blob = encodeCanvasToBmpBlob(work, bgHex);
            } else if (fmt === 'image/x-icon') {
                const icoSizes = scaleMode === 'ico-multi'
                    ? [16, 32, 48, 64]
                    : [Math.min(256, Math.max(16, outW))];
                blob = await encodeCanvasToIcoBlob(work, icoSizes);
            } else if (fmt === 'image/svg+xml') {
                blob = await encodeCanvasToSvgBlob(work);
            } else {
                const q = Math.max(0.05, Math.min(1.0, qual / 100));
                blob = await new Promise((resolve) => work.toBlob(resolve, fmt, q));
                if (!blob || (fmt === 'image/avif' && blob.type !== 'image/avif')) {
                    // Fallback to WebP if current browser does not have AVIF encoder enabled
                    blob = await new Promise((resolve) => work.toBlob(resolve, 'image/webp', q));
                    actualMime = blob ? blob.type : 'image/webp';
                } else {
                    actualMime = blob.type || fmt;
                }
            }

            if (entry.outUrl) URL.revokeObjectURL(entry.outUrl);
            entry.outBlob = blob;
            entry.outUrl = URL.createObjectURL(blob);
            objectUrls.push(entry.outUrl);
            entry.outWidth = scaleMode === 'ico-multi' && fmt === 'image/x-icon' ? 64 : outW;
            entry.outHeight = scaleMode === 'ico-multi' && fmt === 'image/x-icon' ? 64 : outH;
            entry.outExt = getExtensionForMime(actualMime);
            entry.isMultiIco = scaleMode === 'ico-multi' && fmt === 'image/x-icon';
        }

        function updateDashboardStats() {
            const countEl = document.getElementById('icStatCount');
            const origEl = document.getElementById('icStatOrigSize');
            const newEl = document.getElementById('icStatNewSize');
            const savedEl = document.getElementById('icStatSaved');

            let totalOrig = 0;
            let totalNew = 0;
            queueItems.forEach((it) => {
                totalOrig += it.origSize || 0;
                totalNew += it.outBlob ? it.outBlob.size : 0;
            });

            if (countEl) countEl.textContent = queueItems.length + ' 张';
            if (origEl) origEl.textContent = formatBytes(totalOrig);
            if (newEl) newEl.textContent = formatBytes(totalNew);
            if (savedEl) {
                if (totalOrig <= 0 || queueItems.length === 0) {
                    savedEl.textContent = '0%';
                } else {
                    const diff = totalNew - totalOrig;
                    const pct = Math.round((diff / totalOrig) * 100);
                    if (diff <= 0) {
                        savedEl.textContent = '省 ' + formatBytes(Math.abs(diff)) + ' (' + Math.abs(pct) + '%)';
                        savedEl.className = 'text-sm font-extrabold font-mono text-emerald-400 mt-0.5';
                    } else {
                        savedEl.textContent = '+' + formatBytes(diff) + ' (+' + pct + '%)';
                        savedEl.className = 'text-sm font-extrabold font-mono text-amber-400 mt-0.5';
                    }
                }
            }
        }

        function renderQueueDom() {
            if (!queueListEl) return;
            queueListEl.textContent = '';
            updateDashboardStats();

            if (queueItems.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'text-center py-8 text-xs font-mono text-meta';
                empty.textContent = '暂无待转码图片。拖入本地图片或点击「一键载入测试样张体验」立即查看毫秒级转码效果。';
                queueListEl.appendChild(empty);
                return;
            }

            queueItems.forEach((item) => {
                const card = document.createElement('div');
                card.className = 'flex flex-col sm:flex-row sm:items-center justify-between gap-3 p-3 rounded-xl bg-input-bg/70 border border-border hover:border-accent/40 transition-colors';

                // Left: Thumbnail + Info
                const left = document.createElement('div');
                left.className = 'flex items-center gap-3 min-w-0 flex-1';

                const thumbWrap = document.createElement('div');
                thumbWrap.className = 'ic-checker w-12 h-12 rounded-lg border border-border overflow-hidden flex items-center justify-center shrink-0';
                const thumbImg = document.createElement('img');
                thumbImg.className = 'max-w-full max-h-full object-contain';
                thumbImg.alt = item.name;
                thumbImg.src = item.thumbDataUrl || '';
                thumbWrap.appendChild(thumbImg);

                const info = document.createElement('div');
                info.className = 'min-w-0 flex-1';

                const titleRow = document.createElement('div');
                titleRow.className = 'flex items-center gap-2';

                const badge = document.createElement('span');
                badge.className = 'px-1.5 py-0.5 rounded bg-accent/15 border border-accent/30 text-[10px] font-mono font-bold text-accent shrink-0';
                badge.textContent = item.inFormat + ' → ' + (item.outExt || 'webp').toUpperCase();

                const fileNameEl = document.createElement('span');
                fileNameEl.className = 'text-xs font-bold text-main truncate';
                const baseName = item.name.replace(/\.[^.]+$/, '');
                const outFileName = baseName + '.' + (item.outExt || 'webp');
                fileNameEl.textContent = outFileName;

                titleRow.appendChild(badge);
                titleRow.appendChild(fileNameEl);

                const metaRow = document.createElement('div');
                metaRow.className = 'text-[11px] font-mono text-meta mt-1 truncate';
                const outSize = item.outBlob ? item.outBlob.size : 0;
                const diffPct = item.origSize > 0 ? Math.round(((outSize - item.origSize) / item.origSize) * 100) : 0;
                const ratioStr = diffPct <= 0 ? ('节省 ' + Math.abs(diffPct) + '%') : ('体积 +' + diffPct + '%');
                const dimStr = item.isMultiIco
                    ? '16/32/48/64px 多尺寸复合'
                    : (item.outWidth + '×' + item.outHeight + 'px');
                metaRow.textContent = dimStr + ' · 原始 ' + formatBytes(item.origSize) + ' → ' + formatBytes(outSize) + ' (' + ratioStr + ' · ' + item.elapsedMs + 'ms)';

                info.appendChild(titleRow);
                info.appendChild(metaRow);
                left.appendChild(thumbWrap);
                left.appendChild(info);

                // Right: Action Buttons
                const actions = document.createElement('div');
                actions.className = 'flex items-center gap-1.5 shrink-0';

                const copyB64Btn = document.createElement('button');
                copyB64Btn.type = 'button';
                copyB64Btn.className = 'px-2.5 py-1.5 rounded-lg bg-card border border-border text-[11px] font-mono text-meta hover:text-main hover:border-accent/40 cursor-pointer';
                copyB64Btn.textContent = '复制 Base64';
                copyB64Btn.addEventListener('click', () => {
                    if (!item.outBlob || !navigator.clipboard) return;
                    const reader = new FileReader();
                    reader.onloadend = () => {
                        navigator.clipboard.writeText(String(reader.result || '')).then(() => {
                            copyB64Btn.textContent = '✓ 已复制';
                            setTimeout(() => { copyB64Btn.textContent = '复制 Base64'; }, 1200);
                        }).catch(() => {});
                    };
                    reader.readAsDataURL(item.outBlob);
                });

                const dlLink = document.createElement('a');
                dlLink.className = 'px-3 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] text-[11px] font-mono font-bold hover:opacity-90 transition-opacity';
                dlLink.href = item.outUrl || '#';
                dlLink.download = outFileName;
                dlLink.textContent = '下载 .' + (item.outExt || 'webp').toUpperCase();

                const delBtn = document.createElement('button');
                delBtn.type = 'button';
                delBtn.className = 'px-2 py-1.5 rounded-lg bg-card border border-border text-[11px] text-meta hover:text-rose-400 cursor-pointer';
                delBtn.title = '移除此项';
                const trashIcon = document.createElement('i');
                trashIcon.className = 'fa-solid fa-xmark';
                delBtn.appendChild(trashIcon);
                delBtn.addEventListener('click', () => {
                    if (item.outUrl) URL.revokeObjectURL(item.outUrl);
                    queueItems = queueItems.filter((x) => x !== item);
                    renderQueueDom();
                });

                actions.appendChild(copyB64Btn);
                actions.appendChild(dlLink);
                actions.appendChild(delBtn);

                card.appendChild(left);
                card.appendChild(actions);
                queueListEl.appendChild(card);
            });
        }

        async function addImageFiles(fileList) {
            const files = Array.from(fileList || []);
            for (let i = 0; i < files.length; i++) {
                const file = files[i];
                await new Promise((resolve) => {
                    const url = URL.createObjectURL(file);
                    const img = new Image();
                    img.onload = async () => {
                        URL.revokeObjectURL(url);
                        const t0 = performance.now();
                        const w = img.naturalWidth || img.width || 512;
                        const h = img.naturalHeight || img.height || 512;
                        const sc = document.createElement('canvas');
                        sc.width = w;
                        sc.height = h;
                        sc.getContext('2d').drawImage(img, 0, 0, w, h);

                        // Small thumbnail data URL for queue display
                        const tc = document.createElement('canvas');
                        tc.width = 64;
                        tc.height = 64;
                        const tctx = tc.getContext('2d');
                        const scale = Math.min(64 / w, 64 / h);
                        const dw = w * scale, dh = h * scale;
                        tctx.drawImage(sc, (64 - dw) / 2, (64 - dh) / 2, dw, dh);

                        const entry = {
                            name: file.name || 'image.png',
                            inFormat: detectInputFormatLabel(file.name, file.type),
                            origSize: file.size || Math.round(w * h * 1.5),
                            srcWidth: w,
                            srcHeight: h,
                            sourceCanvas: sc,
                            thumbDataUrl: tc.toDataURL('image/png'),
                            outBlob: null,
                            outUrl: null,
                            outWidth: w,
                            outHeight: h,
                            outExt: 'webp',
                            elapsedMs: '0.0'
                        };
                        await transcodeEntry(entry);
                        entry.elapsedMs = (performance.now() - t0).toFixed(1);
                        queueItems.unshift(entry);
                        renderQueueDom();
                        resolve();
                    };
                    img.onerror = () => {
                        URL.revokeObjectURL(url);
                        resolve();
                    };
                    img.src = url;
                });
            }
        }

        async function reconvertAll() {
            for (let i = 0; i < queueItems.length; i++) {
                const t0 = performance.now();
                await transcodeEntry(queueItems[i]);
                queueItems[i].elapsedMs = (performance.now() - t0).toFixed(1);
            }
            renderQueueDom();
        }

        // Bind Quick Conversion Pair Pills
        const pairBar = document.getElementById('icQuickPairBar');
        if (pairBar) {
            pairBar.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-pair]');
                if (!btn) return;
                Array.from(pairBar.querySelectorAll('[data-pair]')).forEach((b) => {
                    b.setAttribute('data-active', b === btn ? 'true' : 'false');
                });
                const fmt = btn.getAttribute('data-fmt') || 'image/webp';
                const qual = btn.getAttribute('data-qual') || '86';
                const scale = btn.getAttribute('data-scale') || 'original';

                if (targetFmtEl) targetFmtEl.value = fmt;
                if (qualSlider) qualSlider.value = qual;
                if (qualLabel) qualLabel.textContent = qual + '%';
                if (scaleModeEl) scaleModeEl.value = scale;
                if (customDimWrap) customDimWrap.classList.toggle('hidden', scale !== 'custom');
                if (pairBannerEl) pairBannerEl.textContent = '当前模式：' + btn.textContent.trim();

                reconvertAll();
            });
        }

        if (targetFmtEl) targetFmtEl.addEventListener('change', reconvertAll);
        if (scaleModeEl) {
            scaleModeEl.addEventListener('change', () => {
                if (customDimWrap) customDimWrap.classList.toggle('hidden', scaleModeEl.value !== 'custom');
                reconvertAll();
            });
        }
        if (qualSlider) {
            qualSlider.addEventListener('input', () => {
                if (qualLabel) qualLabel.textContent = qualSlider.value + '%';
            });
            qualSlider.addEventListener('change', reconvertAll);
        }
        if (bgPresetEl && bgColorEl) {
            bgPresetEl.addEventListener('change', () => {
                if (bgPresetEl.value !== 'custom') {
                    bgColorEl.value = bgPresetEl.value;
                    reconvertAll();
                }
            });
            bgColorEl.addEventListener('input', () => {
                bgPresetEl.value = 'custom';
            });
            bgColorEl.addEventListener('change', reconvertAll);
        }

        const reconvertBtn = document.getElementById('icReconvertAllBtn');
        if (reconvertBtn) reconvertBtn.addEventListener('click', reconvertAll);

        // Dropzone & File Input
        const fileInput = document.getElementById('icFileInput');
        const dropzone = document.getElementById('icDropzone');
        if (fileInput) {
            fileInput.addEventListener('change', () => {
                if (fileInput.files && fileInput.files.length) {
                    addImageFiles(fileInput.files);
                }
            });
        }
        if (dropzone) {
            ['dragenter', 'dragover'].forEach((ev) => dropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                dropzone.classList.add('ic-dragover');
            }));
            ['dragleave', 'drop'].forEach((ev) => dropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                dropzone.classList.remove('ic-dragover');
            }));
            dropzone.addEventListener('drop', (e) => {
                if (e.dataTransfer && e.dataTransfer.files) {
                    addImageFiles(e.dataTransfer.files);
                }
            });
        }

        // Sample Image Button, Clear & Download All
        const sampleBtn = document.getElementById('icLoadSampleBtn');
        if (sampleBtn) {
            sampleBtn.addEventListener('click', async () => {
                const sample = await createDemoSampleFile();
                await addImageFiles([sample]);
            });
        }

        const clearBtn = document.getElementById('icClearQueueBtn');
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                queueItems.forEach((it) => { if (it.outUrl) URL.revokeObjectURL(it.outUrl); });
                queueItems = [];
                renderQueueDom();
            });
        }

        const dlAllBtn = document.getElementById('icDownloadAllBtn');
        if (dlAllBtn) {
            dlAllBtn.addEventListener('click', () => {
                queueItems.forEach((it, idx) => {
                    if (!it.outUrl) return;
                    setTimeout(() => {
                        const a = document.createElement('a');
                        a.href = it.outUrl;
                        a.download = it.name.replace(/\.[^.]+$/, '') + '.' + (it.outExt || 'webp');
                        document.body.appendChild(a);
                        a.click();
                        a.remove();
                    }, idx * 150);
                });
            });
        }

        // Base64 Reverse Decoder
        const b64Input = document.getElementById('icBase64Input');
        const b64DecodeBtn = document.getElementById('icBase64DecodeBtn');
        const b64Status = document.getElementById('icBase64Status');
        if (b64DecodeBtn && b64Input) {
            b64DecodeBtn.addEventListener('click', () => {
                let raw = (b64Input.value || '').trim();
                if (!raw) {
                    if (b64Status) b64Status.textContent = '请先粘贴 Base64 编码字符串';
                    return;
                }
                if (!raw.startsWith('data:image/')) {
                    raw = 'data:image/png;base64,' + raw.replace(/^base64,/, '');
                }
                fetch(raw)
                    .then((res) => res.blob())
                    .then((blob) => {
                        const f = new File([blob], 'base64-decoded-' + Date.now().toString().slice(-4) + '.png', { type: blob.type || 'image/png' });
                        addImageFiles([f]);
                        if (b64Status) b64Status.textContent = '✓ Base64 解码成功，已加入右侧转码队列';
                    })
                    .catch(() => {
                        if (b64Status) b64Status.textContent = '✕ Base64 格式无效，请检查内容是否完整';
                    });
            });
        }

        // Clipboard Paste
        const onPaste = (e) => {
            if (!e.clipboardData || !e.clipboardData.items) return;
            const files = [];
            for (let i = 0; i < e.clipboardData.items.length; i++) {
                const item = e.clipboardData.items[i];
                if (item.type && item.type.indexOf('image/') === 0) {
                    const f = item.getAsFile();
                    if (f) files.push(f);
                }
            }
            if (files.length > 0) addImageFiles(files);
        };
        window.addEventListener('paste', onPaste);

        activeCleanup = function () {
            window.removeEventListener('paste', onPaste);
            objectUrls.forEach((u) => URL.revokeObjectURL(u));
            objectUrls.length = 0;
        };
    }

    function unmountImageConverter() {
        if (activeCleanup) {
            activeCleanup();
            activeCleanup = null;
        }
    }

    window.WindBlogImageConverter = {
        mount: mountImageConverter,
        unmount: unmountImageConverter
    };
})();
