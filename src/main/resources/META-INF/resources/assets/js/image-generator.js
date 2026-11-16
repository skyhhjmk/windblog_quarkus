(function () {
    'use strict';

    // =========================================================================
    // 1. DETERMINISTIC PRNG, HASH & 2D PERLIN / SIMPLEX NOISE ENGINE
    // =========================================================================
    function fnv1aHash(str) {
        const s = String(str || 'windblog-2026');
        let h = 0x811c9dc5;
        for (let i = 0; i < s.length; i++) {
            h ^= s.charCodeAt(i);
            h = Math.imul(h, 0x01000193);
        }
        return h >>> 0;
    }

    function mulberry32(seedInt) {
        let a = seedInt >>> 0;
        return function () {
            let t = (a += 0x6D2B79F5);
            t = Math.imul(t ^ (t >>> 15), t | 1);
            t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
            return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
        };
    }

    class Perlin2D {
        constructor(seedStr) {
            const rand = mulberry32(fnv1aHash(seedStr));
            this.p = new Uint8Array(512);
            const perm = new Uint8Array(256);
            for (let i = 0; i < 256; i++) perm[i] = i;
            for (let i = 255; i > 0; i--) {
                const j = Math.floor(rand() * (i + 1));
                const tmp = perm[i];
                perm[i] = perm[j];
                perm[j] = tmp;
            }
            for (let i = 0; i < 512; i++) this.p[i] = perm[i & 255];
        }

        static fade(t) {
            return t * t * t * (t * (t * 6 - 15) + 10);
        }

        static lerp(t, a, b) {
            return a + t * (b - a);
        }

        static grad(hash, x, y) {
            const h = hash & 7;
            const u = h < 4 ? x : y;
            const v = h < 4 ? y : x;
            return ((h & 1) ? -u : u) + ((h & 2) ? -2.0 * v : 2.0 * v);
        }

        noise(x, y) {
            const X = Math.floor(x) & 255;
            const Y = Math.floor(y) & 255;
            x -= Math.floor(x);
            y -= Math.floor(y);
            const u = Perlin2D.fade(x);
            const v = Perlin2D.fade(y);
            const p = this.p;
            const A = p[X] + Y;
            const B = p[X + 1] + Y;
            const res = Perlin2D.lerp(
                v,
                Perlin2D.lerp(u, Perlin2D.grad(p[A], x, y), Perlin2D.grad(p[B], x - 1, y)),
                Perlin2D.lerp(u, Perlin2D.grad(p[A + 1], x, y - 1), Perlin2D.grad(p[B + 1], x - 1, y - 1))
            );
            return res * 0.45; // approximately [-1, 1]
        }

        fbm(x, y, octaves, persistence, lacunarity, mode) {
            let total = 0;
            let frequency = 1;
            let amplitude = 1;
            let maxValue = 0;
            for (let i = 0; i < octaves; i++) {
                let n = this.noise(x * frequency, y * frequency);
                if (mode === 1) {
                    // Ridged multifractal
                    n = 1.0 - Math.abs(n);
                    n = n * n;
                } else if (mode === 2) {
                    // Turbulence
                    n = Math.abs(n);
                } else {
                    n = n * 0.5 + 0.5;
                }
                total += n * amplitude;
                maxValue += amplitude;
                amplitude *= persistence;
                frequency *= lacunarity;
            }
            return maxValue > 0 ? Math.max(0, Math.min(1, total / maxValue)) : 0;
        }
    }

    // =========================================================================
    // 2. CURATED PALETTES & COLOR UTILITIES
    // =========================================================================
    const PALETTE_PRESETS = [
        { id: 'cyberpunk', name: '赛博霓虹', stops: ['#090d16', '#1e1b4b', '#7c3aed', '#ec4899', '#38bdf8'] },
        { id: 'aurora', name: '极光翡翠', stops: ['#04131a', '#064e3b', '#10b981', '#34d399', '#a7f3d0'] },
        { id: 'sunset', name: '落日熔金', stops: ['#180b20', '#4c1d95', '#be185d', '#f97316', '#fde047'] },
        { id: 'oceanic', name: '深海蓝洞', stops: ['#030712', '#0c4a6e', '#0284c7', '#38bdf8', '#e0f2fe'] },
        { id: 'vaporwave', name: '蒸汽波复古', stops: ['#1a103c', '#5b21b6', '#f472b6', '#2dd4bf', '#fef08a'] },
        { id: 'crimson', name: '黑金烈焰', stops: ['#0a0a0a', '#450a0a', '#dc2626', '#f59e0b', '#fef3c7'] },
        { id: 'nordic', name: '北欧冷杉', stops: ['#1e293b', '#334155', '#64748b', '#94a3b8', '#f1f5f9'] },
        { id: 'matrix', name: '黑客矩阵', stops: ['#020a04', '#052e16', '#15803d', '#22c55e', '#bbf7d0'] },
        { id: 'pastel', name: '莫兰迪糖果', stops: ['#312e81', '#818cf8', '#f472b6', '#fbbf24', '#ecfeff'] },
        { id: 'blueprint', name: '工程制图蓝', stops: ['#071936', '#0d326e', '#1d4ed8', '#60a5fa', '#ffffff'] },
        { id: 'espresso', name: '焦糖拿铁', stops: ['#1c130e', '#452719', '#8c5332', '#d49b6a', '#f7e6d4'] },
        { id: 'monochrome', name: '极简银灰', stops: ['#09090b', '#27272a', '#71717a', '#d4d4d8', '#ffffff'] }
    ];

    const RETRO_PALETTES = {
        '1bit-bw': [[12, 12, 16], [245, 245, 247]],
        'gameboy': [[15, 56, 15], [48, 98, 48], [139, 172, 15], [155, 188, 15]],
        'cga': [[0, 0, 0], [85, 255, 255], [255, 85, 255], [255, 255, 255]],
        'pico8': [
            [0, 0, 0], [29, 43, 83], [126, 37, 83], [0, 135, 81],
            [171, 82, 54], [95, 87, 79], [194, 195, 199], [255, 241, 232],
            [255, 0, 77], [255, 163, 0], [255, 236, 39], [0, 228, 54],
            [41, 173, 255], [131, 118, 156], [255, 119, 168], [255, 204, 170]
        ],
        'rgb-3bit': [
            [0, 0, 0], [255, 0, 0], [0, 255, 0], [0, 0, 255],
            [255, 255, 0], [255, 0, 255], [0, 255, 255], [255, 255, 255]
        ]
    };

    const ALGO_META = {
        'domain-warping': {
            desc: '多重分形柏林噪声对空间坐标递归扭曲 f(p + f(p))，生成丝绸与液态大理石纹理',
            labels: ['基础流场频率 (Scale)', '分形叠加阶数 (Octaves)', '递归扭曲幅度 (Warp)', '纹理脉络锐度 (Contrast)'],
            defaults: [42, 60, 62, 55]
        },
        'perlin-fbm': {
            desc: '经典多层分形布朗运动 (fBm) 与山脊分形噪声，适合云层、地形高度图与抽象雾气',
            labels: ['噪声空间频率 (Scale)', '细节分形层数 (Octaves)', '高频振幅保持 (Persistence)', '对比度与阈值挤压 (Contrast)'],
            defaults: [48, 65, 55, 50]
        },
        'flow-field': {
            desc: '基于无散度旋度噪声场 (Curl Flow Field) 的数千粒子运动轨迹积分渲染',
            labels: ['流场涡旋频率 (Field Scale)', '粒子投放密度 (Particles)', '轨迹步长与曲率 (Step/Curl)', '线条辉光粗细 (Stroke)'],
            defaults: [40, 65, 58, 45]
        },
        'worley-cellular': {
            desc: '沃利晶胞距离场与 Voronoi 泰森多边形晶格 (支持 F1、F2 及 F2-F1 晶界模式)',
            labels: ['晶胞网格密度 (Cells)', '晶核随机抖动度 (Jitter)', '晶界棱线锐度 (Edge Power)', '内部光度衰减 (Falloff)'],
            defaults: [38, 85, 55, 50]
        },
        'low-poly': {
            desc: '德劳内三角剖分风格低多边形晶格网络，结合三维平行光源平面着色与棱线刻画',
            labels: ['多边形网格密度 (Mesh Density)', '顶点有机扰动 (Vertex Jitter)', '三维立体光影反差 (Lighting)', '晶格棱线可见度 (Wireframe)'],
            defaults: [45, 78, 60, 25]
        },
        'mesh-gradient': {
            desc: '多控制点高斯径向弥散与双向流体插值，打造 Apple / Stripe 风格极光背景',
            labels: ['色团锚点数量 (Blobs)', '径向弥散半径 (Spread)', '空间流体扭曲 (Fluid Warp)', '色彩混合平滑度 (Smoothness)'],
            defaults: [50, 68, 45, 60]
        },
        'isometric-cubes': {
            desc: '三维等距轴测立体体素矩阵 (Isometric Voxels)，高度受正弦/柏林波面调制',
            labels: ['体素网格尺寸 (Cube Size)', '波面起伏高度 (Wave Height)', '随机镂空概率 (Void Ratio)', '三面明暗立体感 (Shading)'],
            defaults: [45, 60, 20, 65]
        },
        'bauhaus-memphis': {
            desc: '包豪斯主义与孟菲斯几何构成艺术，融合瑞士网格圆弧、同心圆、斜纹与波点矩阵',
            labels: ['网格划分密度 (Grid)', '几何复杂度 (Complexity)', '线条与色块比例 (Stroke/Fill)', '构图留白率 (Whitespace)'],
            defaults: [45, 65, 50, 25]
        },
        'truchet-tiles': {
            desc: '多尺度递归 Smith 圆弧与对角线 Truchet 迷宫镶嵌图案，形成无限连通拓扑路径',
            labels: ['基础瓷砖密度 (Tile Grid)', '多尺度细分概率 (Multi-Scale)', '迷宫轨道线宽 (Track Width)', '双色填充对比 (Fill Mode)'],
            defaults: [45, 55, 48, 50]
        },
        'mandelbrot-julia': {
            desc: '复平面曼德博集合与朱利亚集逃逸时间分形，采用平滑对数势能函数消除色带断层',
            labels: ['视野缩放倍率 (Zoom)', '朱利亚实部偏移 (Julia Cr)', '朱利亚虚部偏移 (Julia Ci)', '逃逸迭代上限 (Max Iterations)'],
            defaults: [35, 38, 62, 60]
        },
        'clifford-attractor': {
            desc: '克利福德/德容混沌奇异吸引子，累积数十万次非线性动力系统轨迹点的对数密度分布',
            labels: ['混沌参数 A (sin ay)', '混沌参数 B (sin bx)', '混沌参数 C/D 耦合', '曝光辉光增益 (HDR Exposure)'],
            defaults: [56, 42, 68, 65]
        },
        'reaction-diffusion': {
            desc: '阿兰·图灵 Gray-Scott 反应扩散偏微分方程模拟，自然涌现珊瑚、迷宫与细胞分裂斑图',
            labels: ['反应扩散尺度 (Scale)', '进料速率 (Feed Rate f)', '消亡速率 (Kill Rate k)', '演化迭代深度 (Steps)'],
            defaults: [50, 52, 58, 65]
        },
        'phyllotaxis-spiro': {
            desc: '黄金分割角 (137.50776°) 费马螺线向日葵叶序与内摆线万花尺谐波轨迹',
            labels: ['节点/谐波密度 (Count)', '黄金角微调偏振 (Angle Shift)', '几何花瓣尺寸 (Node Radius)', '外环万花尺叠加 (Spirograph)'],
            defaults: [65, 50, 52, 60]
        },
        'layered-waves': {
            desc: '多层视差剪影山脉与流体海浪，内置天体烈日/弯月、星空粒子与大气透视雾效',
            labels: ['视差山海层数 (Layers)', '山脊/波浪起伏度 (Amplitude)', '地形粗糙细节 (Roughness)', '天体烈日尺寸 (Sun Size)'],
            defaults: [55, 55, 50, 55]
        },
        'synthwave-grid': {
            desc: '80年代复古未来主义 Outrun 赛博地平线透视网格、扫描线落日与远山霓虹',
            labels: ['透视网格密度 (Grid Density)', '地平线高度与山峰 (Horizon)', '扫描线落日大小 (Sun Scale)', '霓虹扫描辉光 (Neon Glow)'],
            defaults: [55, 52, 65, 60]
        },
        'halftone-op-art': {
            desc: '波普艺术旋转半调网点矩阵 (CMYK Halftone) 与欧普视觉摩尔纹干涉波场',
            labels: ['网点/波纹频率 (Frequency)', '干涉波源距离 (Interference)', '网点调制深度 (Modulation)', '旋转角度偏移 (Angle)'],
            defaults: [50, 55, 65, 40]
        },
        'identicon-avatar': {
            desc: '基于输入种子 SHA/FNV 哈希的确定性镜像对称几何/像素极客头像生成器',
            labels: ['对称矩阵阶数 (Grid 5×5~11×11)', '块体圆角率 (Corner Radius)', '填充密度阈值 (Density)', '外框内边距 (Padding)'],
            defaults: [40, 35, 55, 30]
        },
        'blueprint-checker': {
            desc: '工程师精密蓝图坐标网格、3D 建模 UV 展开测试棋盘格与 SMPTE 广电色彩校准卡',
            labels: ['主网格跨度尺寸 (Grid Step)', '子刻度细分密度 (Subdivisions)', '坐标标尺与十字准星 (HUD)', '标注对比强度 (Line Opacity)'],
            defaults: [50, 50, 75, 65]
        }
    };

    function hexToRgb(hex) {
        const clean = String(hex || '#000000').replace('#', '').trim();
        const full = clean.length === 3
            ? clean[0] + clean[0] + clean[1] + clean[1] + clean[2] + clean[2]
            : clean.padEnd(6, '0');
        const num = parseInt(full.slice(0, 6), 16) || 0;
        return [(num >> 16) & 255, (num >> 8) & 255, num & 255];
    }

    function rgbToHex(r, g, b) {
        const toHex = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, '0');
        return ('#' + toHex(r) + toHex(g) + toHex(b)).toUpperCase();
    }

    function rgbToHsl(r, g, b) {
        r /= 255; g /= 255; b /= 255;
        const max = Math.max(r, g, b), min = Math.min(r, g, b);
        let h = 0, s = 0;
        const l = (max + min) / 2;
        if (max !== min) {
            const d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            switch (max) {
                case r: h = (g - b) / d + (g < b ? 6 : 0); break;
                case g: h = (b - r) / d + 2; break;
                case b: h = (r - g) / d + 4; break;
            }
            h /= 6;
        }
        return [Math.round(h * 360), Math.round(s * 100), Math.round(l * 100)];
    }

    function hslToRgb(h, s, l) {
        h = ((h % 360) + 360) % 360 / 360;
        s = Math.max(0, Math.min(1, s));
        l = Math.max(0, Math.min(1, l));
        if (s === 0) {
            const v = Math.round(l * 255);
            return [v, v, v];
        }
        const hue2rgb = (p, q, t) => {
            if (t < 0) t += 1;
            if (t > 1) t -= 1;
            if (t < 1 / 6) return p + (q - p) * 6 * t;
            if (t < 1 / 2) return q;
            if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
            return p;
        };
        const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        const p = 2 * l - q;
        return [
            Math.round(hue2rgb(p, q, h + 1 / 3) * 255),
            Math.round(hue2rgb(p, q, h) * 255),
            Math.round(hue2rgb(p, q, h - 1 / 3) * 255)
        ];
    }

    function buildColorLut(hexStops) {
        const rgbStops = hexStops.map(hexToRgb);
        const lut = new Uint8Array(256 * 3);
        const segs = rgbStops.length - 1;
        for (let i = 0; i < 256; i++) {
            const t = i / 255;
            const pos = t * segs;
            const idx = Math.min(segs - 1, Math.floor(pos));
            const frac = pos - idx;
            const c0 = rgbStops[idx];
            const c1 = rgbStops[idx + 1];
            lut[i * 3] = Math.round(c0[0] + (c1[0] - c0[0]) * frac);
            lut[i * 3 + 1] = Math.round(c0[1] + (c1[1] - c0[1]) * frac);
            lut[i * 3 + 2] = Math.round(c0[2] + (c1[2] - c0[2]) * frac);
        }
        return lut;
    }

    function sampleLutCss(lut, t, alpha) {
        const idx = Math.max(0, Math.min(255, Math.round(t * 255))) * 3;
        if (alpha !== undefined && alpha < 1) {
            return 'rgba(' + lut[idx] + ',' + lut[idx + 1] + ',' + lut[idx + 2] + ',' + alpha.toFixed(3) + ')';
        }
        return 'rgb(' + lut[idx] + ',' + lut[idx + 1] + ',' + lut[idx + 2] + ')';
    }

    function formatBytes(bytes) {
        if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
    }

    // =========================================================================
    // 3. BMP & MULTI-SIZE ICO BINARY ENCODERS (Pure JS, Zero Dependencies)
    // =========================================================================
    function encodeCanvasToBmpBlob(canvas) {
        const w = canvas.width;
        const h = canvas.height;
        const ctx = canvas.getContext('2d');
        const imgData = ctx.getImageData(0, 0, w, h).data;
        const rowStride = Math.floor((24 * w + 31) / 32) * 4;
        const pixelArraySize = rowStride * h;
        const fileSize = 54 + pixelArraySize;
        const buf = new ArrayBuffer(fileSize);
        const view = new DataView(buf);
        const u8 = new Uint8Array(buf);

        // BITMAPFILEHEADER (14 bytes)
        view.setUint8(0, 0x42); // 'B'
        view.setUint8(1, 0x4D); // 'M'
        view.setUint32(2, fileSize, true);
        view.setUint32(6, 0, true);
        view.setUint32(10, 54, true);

        // BITMAPINFOHEADER (40 bytes)
        view.setUint32(14, 40, true);
        view.setInt32(18, w, true);
        view.setInt32(22, -h, true); // top-down DIB
        view.setUint16(26, 1, true);
        view.setUint16(28, 24, true); // 24-bit BGR
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
                u8[d] = Math.round(imgData[s + 2] * a);     // B
                u8[d + 1] = Math.round(imgData[s + 1] * a); // G
                u8[d + 2] = Math.round(imgData[s] * a);     // R
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

        // ICONDIR header
        view.setUint16(0, 0, true);
        view.setUint16(2, 1, true); // 1 = ICO
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
    // 4. 18 PROCEDURAL IMAGE ALGORITHMS IMPLEMENTATION
    // =========================================================================
    function renderProceduralAlgorithm(canvas, state) {
        const w = canvas.width;
        const h = canvas.height;
        const ctx = canvas.getContext('2d');
        const algo = state.algo;
        const seed = state.seed;
        const rand = mulberry32(fnv1aHash(seed));
        const perlin = new Perlin2D(seed);
        const lut = buildColorLut(state.colorStops);
        const p1 = state.param1 / 100;
        const p2 = state.param2 / 100;
        const p3 = state.param3 / 100;
        const p4 = state.param4 / 100;
        const variant = Number(state.variant) || 0;

        ctx.save();
        ctx.clearRect(0, 0, w, h);

        // Pixel-shader style algorithms (use an internal render buffer scaled for interactive speed, then bilinear/bicubic upscale)
        const pixelAlgos = new Set([
            'domain-warping', 'perlin-fbm', 'worley-cellular',
            'mandelbrot-julia', 'clifford-attractor', 'reaction-diffusion'
        ]);

        if (pixelAlgos.has(algo)) {
            renderPixelFieldAlgo(ctx, w, h, algo, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'flow-field') {
            renderFlowField(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'low-poly') {
            renderLowPoly(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'mesh-gradient') {
            renderMeshGradient(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'isometric-cubes') {
            renderIsometricCubes(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'bauhaus-memphis') {
            renderBauhausMemphis(ctx, w, h, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'truchet-tiles') {
            renderTruchetTiles(ctx, w, h, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'phyllotaxis-spiro') {
            renderPhyllotaxisSpiro(ctx, w, h, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'layered-waves') {
            renderLayeredWaves(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'synthwave-grid') {
            renderSynthwaveGrid(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'halftone-op-art') {
            renderHalftoneOpArt(ctx, w, h, rand, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'identicon-avatar') {
            renderIdenticonAvatar(ctx, w, h, seed, lut, p1, p2, p3, p4, variant);
        } else if (algo === 'blueprint-checker') {
            renderBlueprintChecker(ctx, w, h, lut, p1, p2, p3, p4, variant);
        }

        // Apply post-generator Vignette & Film Grain if set
        if (state.vignette > 0) {
            const vigAlpha = (state.vignette / 100) * 0.85;
            const cx = w / 2, cy = h / 2;
            const rMax = Math.hypot(cx, cy);
            const g = ctx.createRadialGradient(cx, cy, rMax * 0.25, cx, cy, rMax);
            g.addColorStop(0, 'rgba(0,0,0,0)');
            g.addColorStop(1, 'rgba(0,0,0,' + vigAlpha.toFixed(3) + ')');
            ctx.fillStyle = g;
            ctx.fillRect(0, 0, w, h);
        }

        if (state.grain > 0) {
            applyQuickFilmGrain(ctx, w, h, state.grain / 100, rand);
        }

        // Apply Cover Typography Overlay if configured
        if (state.overlayTitle || state.overlaySubtitle || state.overlayBadge) {
            renderCoverTypographyOverlay(ctx, w, h, state, lut);
        }

        ctx.restore();
    }

    function renderPixelFieldAlgo(ctx, w, h, algo, perlin, rand, lut, p1, p2, p3, p4, variant) {
        // Compute at high-speed internal resolution up to 640px on long edge, then smooth upscale
        const maxInternal = algo === 'reaction-diffusion' ? 220 : 560;
        const scaleDown = Math.max(1, Math.max(w, h) / maxInternal);
        const rw = Math.max(32, Math.round(w / scaleDown));
        const rh = Math.max(32, Math.round(h / scaleDown));

        const off = document.createElement('canvas');
        off.width = rw;
        off.height = rh;
        const octx = off.getContext('2d');
        const img = octx.createImageData(rw, rh);
        const data = img.data;

        const contrastPow = 0.35 + (1 - p4) * 1.6;

        if (algo === 'domain-warping' || algo === 'perlin-fbm') {
            const freq = (1.2 + p1 * 7.5) / Math.min(rw, rh);
            const octaves = Math.max(1, Math.min(7, Math.round(1 + p2 * 6)));
            const warpStrength = algo === 'domain-warping' ? (p3 * 3.8) : 0;
            const persistence = algo === 'perlin-fbm' ? (0.25 + p3 * 0.55) : 0.52;

            for (let y = 0; y < rh; y++) {
                for (let x = 0; x < rw; x++) {
                    let nx = x * freq;
                    let ny = y * freq;
                    let val;
                    if (algo === 'domain-warping') {
                        const qx = perlin.fbm(nx + 0.1, ny + 0.2, 3, 0.5, 2.0, 0);
                        const qy = perlin.fbm(nx + 5.2, ny + 1.3, 3, 0.5, 2.0, 0);
                        const rx = perlin.fbm(nx + warpStrength * qx + 1.7, ny + warpStrength * qy + 9.2, octaves, 0.5, 2.0, variant);
                        const ry = perlin.fbm(nx + warpStrength * qx + 8.3, ny + warpStrength * qy + 2.8, octaves, 0.5, 2.0, variant);
                        val = perlin.fbm(nx + warpStrength * rx, ny + warpStrength * ry, octaves, 0.55, 2.0, variant);
                    } else {
                        val = perlin.fbm(nx, ny, octaves, persistence, 2.05, variant);
                    }
                    val = Math.pow(Math.max(0, Math.min(1, val)), contrastPow);
                    const cIdx = Math.max(0, Math.min(255, Math.round(val * 255))) * 3;
                    const idx = (y * rw + x) * 4;
                    data[idx] = lut[cIdx];
                    data[idx + 1] = lut[cIdx + 1];
                    data[idx + 2] = lut[cIdx + 2];
                    data[idx + 3] = 255;
                }
            }
        } else if (algo === 'worley-cellular') {
            const cells = Math.max(2, Math.round(3 + p1 * 18));
            const jitter = p2;
            const edgePower = 0.4 + p3 * 2.5;
            const ptsX = new Float32Array(cells * cells);
            const ptsY = new Float32Array(cells * cells);
            for (let cy = 0; cy < cells; cy++) {
                for (let cx = 0; cx < cells; cx++) {
                    const i = cy * cells + cx;
                    ptsX[i] = cx + 0.5 + (rand() - 0.5) * jitter;
                    ptsY[i] = cy + 0.5 + (rand() - 0.5) * jitter;
                }
            }
            const aspect = rw / rh;
            for (let y = 0; y < rh; y++) {
                const gy = (y / rh) * cells;
                const cy0 = Math.floor(gy);
                for (let x = 0; x < rw; x++) {
                    const gx = (x / rw) * cells * aspect;
                    const cx0 = Math.floor(gx);
                    let d1 = 999, d2 = 999;
                    for (let dy = -1; dy <= 1; dy++) {
                        const ny = ((cy0 + dy) % cells + cells) % cells;
                        for (let dx = -1; dx <= 1; dx++) {
                            const nx = ((cx0 + dx) % cells + cells) % cells;
                            const idxPt = ny * cells + nx;
                            const px = (cx0 + dx) + (ptsX[idxPt] - nx);
                            const py = (cy0 + dy) + (ptsY[idxPt] - ny);
                            const ax = Math.abs(gx - px);
                            const ay = Math.abs(gy - py);
                            const d = variant === 2 ? (ax + ay) : Math.hypot(ax, ay);
                            if (d < d1) {
                                d2 = d1;
                                d1 = d;
                            } else if (d < d2) {
                                d2 = d;
                            }
                        }
                    }
                    let val = variant === 1 ? (d2 - d1) : (variant === 2 ? d2 * 0.7 : 1.0 - d1 * 0.95);
                    val = Math.pow(Math.max(0, Math.min(1, val)), edgePower);
                    const cIdx = Math.max(0, Math.min(255, Math.round(val * 255))) * 3;
                    const idx = (y * rw + x) * 4;
                    data[idx] = lut[cIdx];
                    data[idx + 1] = lut[cIdx + 1];
                    data[idx + 2] = lut[cIdx + 2];
                    data[idx + 3] = 255;
                }
            }
        } else if (algo === 'mandelbrot-julia') {
            const maxIter = Math.round(36 + p4 * 140);
            const zoom = Math.pow(1.06, p1 * 55);
            const cr = -0.85 + p2 * 1.3;
            const ci = -0.65 + p3 * 1.3;
            const isJulia = variant !== 1;
            const aspect = rw / rh;
            const scale = (isJulia ? 3.0 : 2.6) / zoom;
            const centerX = isJulia ? 0 : -0.743643887037151;
            const centerY = isJulia ? 0 : 0.13182590420533;

            for (let y = 0; y < rh; y++) {
                const y0 = (y / rh - 0.5) * scale + centerY;
                for (let x = 0; x < rw; x++) {
                    const x0 = (x / rw - 0.5) * scale * aspect + centerX;
                    let zx = isJulia ? x0 : 0;
                    let zy = isJulia ? y0 : 0;
                    const cx = isJulia ? cr : x0;
                    const cy = isJulia ? ci : y0;
                    let iter = 0;
                    let zx2 = zx * zx, zy2 = zy * zy;
                    while (zx2 + zy2 <= 16.0 && iter < maxIter) {
                        zy = 2 * zx * zy + cy;
                        zx = zx2 - zy2 + cx;
                        zx2 = zx * zx;
                        zy2 = zy * zy;
                        iter++;
                    }
                    let t = 0;
                    if (iter < maxIter) {
                        const logZn = Math.log(zx2 + zy2) / 2;
                        const nu = Math.log(logZn / Math.LN2) / Math.LN2;
                        const smooth = iter + 1 - nu;
                        t = (Math.sin(smooth * 0.14 + (variant === 2 ? Math.PI : 0)) * 0.5 + 0.5);
                    }
                    const cIdx = Math.max(0, Math.min(255, Math.round(t * 255))) * 3;
                    const idx = (y * rw + x) * 4;
                    data[idx] = lut[cIdx];
                    data[idx + 1] = lut[cIdx + 1];
                    data[idx + 2] = lut[cIdx + 2];
                    data[idx + 3] = 255;
                }
            }
        } else if (algo === 'clifford-attractor') {
            const density = new Uint32Array(rw * rh);
            const a = -2.1 + p1 * 0.9;
            const b = -2.0 + p2 * 0.8;
            const c = 1.1 + p3 * 0.9;
            const d = 0.7 + (1 - p3) * 0.9;
            let x = 0.1, y = 0.1;
            const totalPts = 220000;
            let maxHits = 1;
            for (let i = 0; i < totalPts; i++) {
                const nx = Math.sin(a * y) + c * Math.cos(a * x);
                const ny = Math.sin(b * x) + d * Math.cos(b * y);
                x = nx;
                y = ny;
                const px = Math.floor(((x + 2.8) / 5.6) * rw);
                const py = Math.floor(((y + 2.8) / 5.6) * rh);
                if (px >= 0 && px < rw && py >= 0 && py < rh) {
                    const idx = py * rw + px;
                    const v = ++density[idx];
                    if (v > maxHits) maxHits = v;
                }
            }
            const logMax = Math.log(1 + maxHits);
            const gain = 0.6 + p4 * 1.4;
            for (let i = 0; i < rw * rh; i++) {
                const hits = density[i];
                const t = hits > 0 ? Math.min(1, Math.pow(Math.log(1 + hits) / logMax, 0.55) * gain) : 0;
                const cIdx = Math.max(0, Math.min(255, Math.round(t * 255))) * 3;
                const idx = i * 4;
                data[idx] = lut[cIdx];
                data[idx + 1] = lut[cIdx + 1];
                data[idx + 2] = lut[cIdx + 2];
                data[idx + 3] = 255;
            }
        } else if (algo === 'reaction-diffusion') {
            const N = rw * rh;
            let u = new Float32Array(N).fill(1.0);
            let v = new Float32Array(N).fill(0.0);
            let uNext = new Float32Array(N);
            let vNext = new Float32Array(N);

            const seedsCount = Math.round(8 + p1 * 28);
            for (let s = 0; s < seedsCount; s++) {
                const sx = Math.floor(rand() * (rw - 12)) + 6;
                const sy = Math.floor(rand() * (rh - 12)) + 6;
                const rad = Math.floor(2 + rand() * 5);
                for (let dy = -rad; dy <= rad; dy++) {
                    for (let dx = -rad; dx <= rad; dx++) {
                        const idx = (sy + dy) * rw + (sx + dx);
                        u[idx] = 0.5;
                        v[idx] = 0.25 + rand() * 0.1;
                    }
                }
            }

            const f = 0.028 + p2 * 0.032;
            const k = 0.055 + p3 * 0.012;
            const steps = Math.round(90 + p4 * 150);

            for (let step = 0; step < steps; step++) {
                for (let y = 1; y < rh - 1; y++) {
                    const row = y * rw;
                    for (let x = 1; x < rw - 1; x++) {
                        const i = row + x;
                        const ui = u[i], vi = v[i];
                        const lapU = u[i - 1] + u[i + 1] + u[i - rw] + u[i + rw] - 4 * ui;
                        const lapV = v[i - 1] + v[i + 1] + v[i - rw] + v[i + rw] - 4 * vi;
                        const uvv = ui * vi * vi;
                        uNext[i] = Math.max(0, Math.min(1, ui + (0.2097 * lapU - uvv + f * (1 - ui))));
                        vNext[i] = Math.max(0, Math.min(1, vi + (0.105 * lapV + uvv - (f + k) * vi)));
                    }
                }
                const tmpU = u; u = uNext; uNext = tmpU;
                const tmpV = v; v = vNext; vNext = tmpV;
            }

            for (let i = 0; i < N; i++) {
                let val = Math.max(0, Math.min(1, (u[i] - v[i] * 1.6)));
                if (variant === 1) val = 1.0 - val;
                const cIdx = Math.max(0, Math.min(255, Math.round(val * 255))) * 3;
                const idx = i * 4;
                data[idx] = lut[cIdx];
                data[idx + 1] = lut[cIdx + 1];
                data[idx + 2] = lut[cIdx + 2];
                data[idx + 3] = 255;
            }
        }

        octx.putImageData(img, 0, 0);
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(off, 0, 0, w, h);
    }

    function renderFlowField(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        ctx.fillStyle = sampleLutCss(lut, 0.04);
        ctx.fillRect(0, 0, w, h);

        const freq = (1.0 + p1 * 5.0) / Math.min(w, h);
        const particles = Math.round(350 + p2 * 1650);
        const steps = Math.round(25 + p3 * 65);
        const stepLen = Math.max(2, Math.min(w, h) * 0.006);
        const lineW = 0.6 + p4 * 3.2;

        ctx.lineCap = 'round';
        ctx.lineWidth = lineW;

        for (let i = 0; i < particles; i++) {
            let x = rand() * w;
            let y = rand() * h;
            const colorT = 0.18 + rand() * 0.82;
            ctx.strokeStyle = sampleLutCss(lut, colorT, variant === 1 ? 0.85 : 0.48);
            ctx.beginPath();
            ctx.moveTo(x, y);
            for (let s = 0; s < steps; s++) {
                const n = perlin.fbm(x * freq, y * freq, 3, 0.5, 2.0, 0);
                const angle = n * Math.PI * (2.5 + p3 * 4.0) + (variant === 2 ? Math.atan2(y - h / 2, x - w / 2) : 0);
                x += Math.cos(angle) * stepLen;
                y += Math.sin(angle) * stepLen;
                ctx.lineTo(x, y);
            }
            ctx.stroke();
        }
    }

    function renderLowPoly(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        const cols = Math.round(6 + p1 * 22);
        const rows = Math.max(4, Math.round(cols * (h / w)));
        const cellW = w / cols;
        const cellH = h / rows;
        const jitter = p2 * 0.82;

        const pts = [];
        for (let r = -1; r <= rows + 1; r++) {
            const rowPts = [];
            for (let c = -1; c <= cols + 1; c++) {
                const isEdge = r <= 0 || r >= rows || c <= 0 || c >= cols;
                const jx = isEdge ? 0 : (rand() - 0.5) * cellW * jitter;
                const jy = isEdge ? 0 : (rand() - 0.5) * cellH * jitter;
                rowPts.push({ x: c * cellW + jx, y: r * cellH + jy });
            }
            pts.push(rowPts);
        }

        const drawTri = (a, b, c) => {
            const cx = (a.x + b.x + c.x) / 3;
            const cy = (a.y + b.y + c.y) / 3;
            const baseT = (cx / w) * 0.55 + (cy / h) * 0.45;
            const n = perlin.noise(cx * 0.004, cy * 0.004) * p3 * 0.45;
            const shade = (rand() - 0.5) * p3 * 0.22;
            const t = Math.max(0, Math.min(1, baseT + n + shade));
            const fill = sampleLutCss(lut, t);

            ctx.beginPath();
            ctx.moveTo(a.x, a.y);
            ctx.lineTo(b.x, b.y);
            ctx.lineTo(c.x, c.y);
            ctx.closePath();
            if (variant !== 1) {
                ctx.fillStyle = fill;
                ctx.fill();
            } else {
                ctx.fillStyle = sampleLutCss(lut, 0.05);
                ctx.fill();
            }
            ctx.lineWidth = variant === 1 ? 1.4 : (0.5 + p4 * 1.5);
            ctx.strokeStyle = p4 > 0.08 || variant === 1
                ? sampleLutCss(lut, variant === 1 ? t : Math.min(1, t + 0.15), Math.max(0.15, p4))
                : fill;
            ctx.stroke();
        };

        for (let r = 0; r < rows + 2; r++) {
            for (let c = 0; c < cols + 2; c++) {
                const p00 = pts[r][c];
                const p10 = pts[r][c + 1];
                const p01 = pts[r + 1][c];
                const p11 = pts[r + 1][c + 1];
                if (rand() > 0.5) {
                    drawTri(p00, p10, p11);
                    drawTri(p00, p11, p01);
                } else {
                    drawTri(p00, p10, p01);
                    drawTri(p10, p11, p01);
                }
            }
        }
    }

    function renderMeshGradient(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        const bgGrad = ctx.createLinearGradient(0, 0, w, h);
        bgGrad.addColorStop(0, sampleLutCss(lut, 0.05));
        bgGrad.addColorStop(0.5, sampleLutCss(lut, 0.35));
        bgGrad.addColorStop(1, sampleLutCss(lut, 0.15));
        ctx.fillStyle = bgGrad;
        ctx.fillRect(0, 0, w, h);

        const blobs = Math.round(4 + p1 * 12);
        const radiusBase = Math.max(w, h) * (0.28 + p2 * 0.45);
        ctx.globalCompositeOperation = variant === 1 ? 'screen' : 'source-over';

        for (let i = 0; i < blobs; i++) {
            const bx = rand() * w;
            const by = rand() * h;
            const r = radiusBase * (0.6 + rand() * 0.7);
            const t = 0.2 + (i / Math.max(1, blobs - 1)) * 0.8;
            const g = ctx.createRadialGradient(bx, by, 0, bx, by, r);
            g.addColorStop(0, sampleLutCss(lut, t, 0.78));
            g.addColorStop(0.5, sampleLutCss(lut, Math.max(0, t - 0.15), 0.35));
            g.addColorStop(1, sampleLutCss(lut, t, 0));
            ctx.fillStyle = g;
            ctx.beginPath();
            ctx.arc(bx, by, r, 0, Math.PI * 2);
            ctx.fill();
        }
        ctx.globalCompositeOperation = 'source-over';

        if (p3 > 0.15) {
            // Subtle organic contour ribbons
            ctx.lineWidth = 1.2;
            const ribbons = Math.round(6 + p3 * 16);
            for (let r = 0; r < ribbons; r++) {
                ctx.strokeStyle = sampleLutCss(lut, 0.65 + (r / ribbons) * 0.35, 0.18 * p4);
                ctx.beginPath();
                const baseY = (r / ribbons) * h;
                for (let x = 0; x <= w; x += 16) {
                    const y = baseY + perlin.noise(x * 0.003, r * 0.4) * h * 0.18 * p3;
                    if (x === 0) ctx.moveTo(x, y);
                    else ctx.lineTo(x, y);
                }
                ctx.stroke();
            }
        }
    }

    function renderIsometricCubes(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        ctx.fillStyle = sampleLutCss(lut, 0.04);
        ctx.fillRect(0, 0, w, h);

        const size = Math.max(18, Math.round((1.15 - p1) * Math.min(w, h) * 0.12 + 16));
        const cos30 = Math.cos(Math.PI / 6);
        const sin30 = 0.5;
        const dx = size * cos30;
        const dy = size * sin30;
        const cols = Math.ceil(w / (dx * 2)) + 4;
        const rows = Math.ceil(h / (dy * 1.5)) + 6;

        const drawCube = (cx, cy, heightMod, colorT) => {
            const ch = size * (0.6 + heightMod * 1.8);
            // Top face
            ctx.beginPath();
            ctx.moveTo(cx, cy - ch);
            ctx.lineTo(cx + dx, cy - ch + dy);
            ctx.lineTo(cx, cy - ch + dy * 2);
            ctx.lineTo(cx - dx, cy - ch + dy);
            ctx.closePath();
            ctx.fillStyle = sampleLutCss(lut, Math.min(1, colorT + 0.22 * p4));
            ctx.fill();

            // Left face
            ctx.beginPath();
            ctx.moveTo(cx - dx, cy - ch + dy);
            ctx.lineTo(cx, cy - ch + dy * 2);
            ctx.lineTo(cx, cy + dy * 2);
            ctx.lineTo(cx - dx, cy + dy);
            ctx.closePath();
            ctx.fillStyle = sampleLutCss(lut, colorT);
            ctx.fill();

            // Right face
            ctx.beginPath();
            ctx.moveTo(cx, cy - ch + dy * 2);
            ctx.lineTo(cx + dx, cy - ch + dy);
            ctx.lineTo(cx + dx, cy + dy);
            ctx.lineTo(cx, cy + dy * 2);
            ctx.closePath();
            ctx.fillStyle = sampleLutCss(lut, Math.max(0, colorT - 0.22 * p4));
            ctx.fill();

            if (variant === 1) {
                ctx.strokeStyle = sampleLutCss(lut, 0.95, 0.45);
                ctx.lineWidth = 1;
                ctx.stroke();
            }
        };

        for (let r = -2; r < rows; r++) {
            for (let c = -2; c < cols; c++) {
                if (rand() < p3 * 0.45) continue;
                const offsetX = (r & 1) ? dx : 0;
                const cx = c * dx * 2 + offsetX;
                const cy = r * dy * 1.5;
                const n = perlin.noise(c * 0.18, r * 0.18) * 0.5 + 0.5;
                const wave = Math.sin(c * 0.45 + r * 0.35) * 0.5 + 0.5;
                const hMod = (n * 0.6 + wave * 0.4) * p2;
                const colorT = 0.2 + n * 0.7;
                drawCube(cx, cy, hMod, colorT);
            }
        }
    }

    function renderBauhausMemphis(ctx, w, h, rand, lut, p1, p2, p3, p4, variant) {
        ctx.fillStyle = sampleLutCss(lut, variant === 2 ? 0.92 : 0.06);
        ctx.fillRect(0, 0, w, h);

        const cols = Math.max(3, Math.round(3 + p1 * 9));
        const cell = w / cols;
        const rows = Math.ceil(h / cell);

        for (let r = 0; r < rows; r++) {
            for (let c = 0; c < cols; c++) {
                const x = c * cell;
                const y = r * cell;
                const bgT = 0.12 + rand() * 0.35;
                if (rand() > p4 * 0.5) {
                    ctx.fillStyle = sampleLutCss(lut, bgT, 0.35);
                    ctx.fillRect(x, y, cell, cell);
                }

                const shapeType = Math.floor(rand() * 6);
                const fgT = 0.35 + rand() * 0.65;
                ctx.fillStyle = sampleLutCss(lut, fgT);
                ctx.strokeStyle = sampleLutCss(lut, fgT);
                ctx.lineWidth = Math.max(2, cell * 0.08 * (0.5 + p3));

                ctx.save();
                ctx.beginPath();
                ctx.rect(x, y, cell, cell);
                ctx.clip();

                if (shapeType === 0) {
                    // Quarter circle arc
                    const corner = Math.floor(rand() * 4);
                    const ax = x + ((corner === 1 || corner === 2) ? cell : 0);
                    const ay = y + ((corner >= 2) ? cell : 0);
                    ctx.beginPath();
                    ctx.moveTo(ax, ay);
                    ctx.arc(ax, ay, cell, 0, Math.PI * 2);
                    ctx.fill();
                } else if (shapeType === 1) {
                    // Concentric circles
                    ctx.beginPath();
                    ctx.arc(x + cell / 2, y + cell / 2, cell * 0.42, 0, Math.PI * 2);
                    ctx.fill();
                    ctx.fillStyle = sampleLutCss(lut, 1 - fgT);
                    ctx.beginPath();
                    ctx.arc(x + cell / 2, y + cell / 2, cell * 0.2, 0, Math.PI * 2);
                    ctx.fill();
                } else if (shapeType === 2) {
                    // Diagonal triangle
                    ctx.beginPath();
                    ctx.moveTo(x, y);
                    ctx.lineTo(x + cell, y);
                    ctx.lineTo(x, y + cell);
                    ctx.closePath();
                    ctx.fill();
                } else if (shapeType === 3) {
                    // Bauhaus diagonal stripes
                    const stripes = Math.max(3, Math.round(3 + p2 * 5));
                    const step = (cell * 2) / stripes;
                    for (let s = -cell; s < cell * 2; s += step) {
                        ctx.beginPath();
                        ctx.moveTo(x + s, y);
                        ctx.lineTo(x + s + cell, y + cell);
                        ctx.stroke();
                    }
                } else if (shapeType === 4) {
                    // Dot matrix
                    const dots = 3;
                    const gap = cell / (dots + 1);
                    for (let dy = 1; dy <= dots; dy++) {
                        for (let dx = 1; dx <= dots; dx++) {
                            ctx.beginPath();
                            ctx.arc(x + dx * gap, y + dy * gap, cell * 0.06, 0, Math.PI * 2);
                            ctx.fill();
                        }
                    }
                } else {
                    // Pill / semicircle split
                    ctx.beginPath();
                    ctx.arc(x + cell / 2, y, cell / 2, 0, Math.PI);
                    ctx.fill();
                    ctx.fillStyle = sampleLutCss(lut, Math.min(1, fgT + 0.25));
                    ctx.beginPath();
                    ctx.arc(x + cell / 2, y + cell, cell / 2, Math.PI, Math.PI * 2);
                    ctx.fill();
                }
                ctx.restore();
            }
        }
    }

    function renderTruchetTiles(ctx, w, h, rand, lut, p1, p2, p3, p4, variant) {
        ctx.fillStyle = sampleLutCss(lut, 0.05);
        ctx.fillRect(0, 0, w, h);

        const cols = Math.max(4, Math.round(5 + p1 * 18));
        const baseSize = w / cols;
        const rows = Math.ceil(h / baseSize);

        const drawTile = (x, y, sz, depth) => {
            if (depth === 0 && rand() < p2 * 0.55 && sz > 28) {
                const half = sz / 2;
                drawTile(x, y, half, 1);
                drawTile(x + half, y, half, 1);
                drawTile(x, y + half, half, 1);
                drawTile(x + half, y + half, half, 1);
                return;
            }
            const flip = rand() > 0.5;
            const r = sz / 2;
            const colorT = 0.35 + (depth * 0.25) + rand() * 0.4;
            ctx.strokeStyle = sampleLutCss(lut, colorT);
            ctx.lineWidth = Math.max(2, sz * (0.08 + p3 * 0.22));
            ctx.lineCap = 'round';

            if (variant === 1) {
                // Diagonal labyrinth
                ctx.beginPath();
                if (flip) {
                    ctx.moveTo(x, y);
                    ctx.lineTo(x + sz, y + sz);
                } else {
                    ctx.moveTo(x + sz, y);
                    ctx.lineTo(x, y + sz);
                }
                ctx.stroke();
            } else {
                // Smith quarter-circle arcs
                ctx.beginPath();
                if (flip) {
                    ctx.arc(x, y, r, 0, Math.PI / 2);
                    ctx.stroke();
                    ctx.beginPath();
                    ctx.arc(x + sz, y + sz, r, Math.PI, Math.PI * 1.5);
                    ctx.stroke();
                } else {
                    ctx.arc(x + sz, y, r, Math.PI / 2, Math.PI);
                    ctx.stroke();
                    ctx.beginPath();
                    ctx.arc(x, y + sz, r, Math.PI * 1.5, Math.PI * 2);
                    ctx.stroke();
                }
            }
        };

        for (let r = 0; r < rows; r++) {
            for (let c = 0; c < cols; c++) {
                drawTile(c * baseSize, r * baseSize, baseSize, 0);
            }
        }
    }

    function renderPhyllotaxisSpiro(ctx, w, h, rand, lut, p1, p2, p3, p4, variant) {
        const cx = w / 2, cy = h / 2;
        const maxR = Math.min(w, h) * 0.46;
        const bg = ctx.createRadialGradient(cx, cy, 0, cx, cy, maxR * 1.4);
        bg.addColorStop(0, sampleLutCss(lut, 0.12));
        bg.addColorStop(1, sampleLutCss(lut, 0.02));
        ctx.fillStyle = bg;
        ctx.fillRect(0, 0, w, h);

        // 1. Spirograph Hypotrochoid harmonic web
        if (p4 > 0.05) {
            const R = maxR * 0.92;
            const r = R * (0.22 + p2 * 0.52);
            const d = r * (0.4 + p4 * 1.2);
            ctx.lineWidth = 1.3;
            ctx.strokeStyle = sampleLutCss(lut, 0.75, 0.42);
            ctx.beginPath();
            const totalSteps = 1800;
            for (let i = 0; i <= totalSteps; i++) {
                const t = (i / totalSteps) * Math.PI * 42;
                const x = cx + (R - r) * Math.cos(t) + d * Math.cos(((R - r) / r) * t);
                const y = cy + (R - r) * Math.sin(t) - d * Math.sin(((R - r) / r) * t);
                if (i === 0) ctx.moveTo(x, y);
                else ctx.lineTo(x, y);
            }
            ctx.stroke();
        }

        // 2. Golden Angle Phyllotaxis nodes
        const count = Math.round(180 + p1 * 900);
        const goldenAngle = (137.507764 + (p2 - 0.5) * 1.8) * (Math.PI / 180);
        const dotBase = Math.max(2, (Math.min(w, h) / 140) * (0.4 + p3 * 1.4));

        for (let i = 1; i < count; i++) {
            const frac = i / count;
            const radius = Math.sqrt(frac) * maxR * 0.92;
            const theta = i * goldenAngle;
            const x = cx + radius * Math.cos(theta);
            const y = cy + radius * Math.sin(theta);
            const rNode = dotBase * (0.4 + frac * 1.1);
            ctx.fillStyle = sampleLutCss(lut, 0.25 + frac * 0.75, 0.88);
            ctx.beginPath();
            ctx.arc(x, y, rNode, 0, Math.PI * 2);
            ctx.fill();
        }
    }

    function renderLayeredWaves(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        // Sky gradient
        const sky = ctx.createLinearGradient(0, 0, 0, h * 0.75);
        sky.addColorStop(0, sampleLutCss(lut, 0.06));
        sky.addColorStop(0.55, sampleLutCss(lut, 0.38));
        sky.addColorStop(1, sampleLutCss(lut, 0.85));
        ctx.fillStyle = sky;
        ctx.fillRect(0, 0, w, h);

        // Stars in upper sky
        const stars = Math.round(40 + p3 * 90);
        for (let s = 0; s < stars; s++) {
            const sx = rand() * w;
            const sy = rand() * h * 0.45;
            ctx.fillStyle = 'rgba(255,255,255,' + (0.2 + rand() * 0.7).toFixed(2) + ')';
            ctx.fillRect(sx, sy, rand() > 0.8 ? 2 : 1.2, rand() > 0.8 ? 2 : 1.2);
        }

        // Celestial Sun / Moon disc
        if (p4 > 0.05) {
            const sunR = Math.min(w, h) * (0.06 + p4 * 0.22);
            const sunX = w * (0.28 + rand() * 0.44);
            const sunY = h * 0.32;
            const glow = ctx.createRadialGradient(sunX, sunY, sunR * 0.2, sunX, sunY, sunR * 2.2);
            glow.addColorStop(0, sampleLutCss(lut, 0.98, 0.95));
            glow.addColorStop(0.45, sampleLutCss(lut, 0.82, 0.45));
            glow.addColorStop(1, sampleLutCss(lut, 0.7, 0));
            ctx.fillStyle = glow;
            ctx.beginPath();
            ctx.arc(sunX, sunY, sunR * 2.2, 0, Math.PI * 2);
            ctx.fill();

            ctx.fillStyle = sampleLutCss(lut, 0.98);
            ctx.beginPath();
            ctx.arc(sunX, sunY, sunR, 0, Math.PI * 2);
            ctx.fill();
        }

        // Layered mountains / waves from back to front
        const layers = Math.max(3, Math.round(4 + p1 * 8));
        for (let l = 0; l < layers; l++) {
            const depth = l / (layers - 1);
            const baseY = h * (0.38 + depth * 0.52);
            const amp = h * (0.04 + p2 * 0.16) * (1.1 - depth * 0.45);
            const freq = (1.5 + p3 * 4.5) / w;
            const octs = variant === 1 ? 2 : 5;

            ctx.beginPath();
            ctx.moveTo(0, h);
            for (let x = 0; x <= w; x += 6) {
                const n = perlin.fbm(x * freq + l * 7.3, l * 2.1, octs, 0.52, 2.1, variant === 2 ? 1 : 0);
                const y = baseY - (n - 0.5) * amp * 2.2;
                ctx.lineTo(x, y);
            }
            ctx.lineTo(w, h);
            ctx.closePath();

            // Far layers lighter, front layers darker silhouette
            const colorT = Math.max(0.03, 0.72 - depth * 0.68);
            ctx.fillStyle = sampleLutCss(lut, colorT);
            ctx.fill();
        }
    }

    function renderSynthwaveGrid(ctx, w, h, perlin, rand, lut, p1, p2, p3, p4, variant) {
        const horizonY = h * (0.42 + (p2 - 0.5) * 0.18);

        // Night sky
        const sky = ctx.createLinearGradient(0, 0, 0, horizonY);
        sky.addColorStop(0, sampleLutCss(lut, 0.02));
        sky.addColorStop(0.65, sampleLutCss(lut, 0.32));
        sky.addColorStop(1, sampleLutCss(lut, 0.75));
        ctx.fillStyle = sky;
        ctx.fillRect(0, 0, w, horizonY);

        // Starfield
        for (let i = 0; i < 90; i++) {
            ctx.fillStyle = 'rgba(255,255,255,' + (0.25 + rand() * 0.75).toFixed(2) + ')';
            ctx.fillRect(rand() * w, rand() * horizonY * 0.75, 1.5, 1.5);
        }

        // Outrun Segmented Neon Sun
        const sunR = Math.min(w, h) * (0.12 + p3 * 0.22);
        const sunX = w / 2;
        const sunY = horizonY - sunR * 0.15;
        ctx.save();
        ctx.beginPath();
        ctx.arc(sunX, sunY, sunR, 0, Math.PI * 2);
        ctx.clip();

        const sunGrad = ctx.createLinearGradient(0, sunY - sunR, 0, sunY + sunR);
        sunGrad.addColorStop(0, sampleLutCss(lut, 0.98));
        sunGrad.addColorStop(0.55, sampleLutCss(lut, 0.75));
        sunGrad.addColorStop(1, sampleLutCss(lut, 0.45));
        ctx.fillStyle = sunGrad;
        ctx.fillRect(sunX - sunR, sunY - sunR, sunR * 2, sunR * 2);

        // Horizontal scanline cutouts on lower half of sun
        ctx.fillStyle = sampleLutCss(lut, 0.12);
        for (let s = 0; s < 8; s++) {
            const sy = sunY + (s / 8) * sunR;
            const sh = 1.5 + s * (sunR * 0.022);
            ctx.fillRect(sunX - sunR, sy, sunR * 2, sh);
        }
        ctx.restore();

        // Low-poly mountain silhouette along horizon
        ctx.beginPath();
        ctx.moveTo(0, horizonY);
        for (let x = 0; x <= w; x += 12) {
            const distCenter = Math.abs(x - w / 2) / (w / 2);
            const mHeight = perlin.fbm(x * 0.006, 1.5, 4, 0.5, 2.0, 1) * h * 0.18 * (0.25 + distCenter * 0.95);
            ctx.lineTo(x, horizonY - mHeight);
        }
        ctx.lineTo(w, horizonY);
        ctx.closePath();
        ctx.fillStyle = sampleLutCss(lut, 0.06);
        ctx.fill();
        ctx.strokeStyle = sampleLutCss(lut, 0.85, 0.7);
        ctx.lineWidth = 1.5;
        ctx.stroke();

        // Ground plane & perspective grid
        const ground = ctx.createLinearGradient(0, horizonY, 0, h);
        ground.addColorStop(0, sampleLutCss(lut, 0.18));
        ground.addColorStop(1, sampleLutCss(lut, 0.02));
        ctx.fillStyle = ground;
        ctx.fillRect(0, horizonY, w, h - horizonY);

        ctx.save();
        ctx.beginPath();
        ctx.rect(0, horizonY, w, h - horizonY);
        ctx.clip();

        const vLines = Math.round(12 + p1 * 28);
        ctx.strokeStyle = sampleLutCss(lut, 0.88, 0.35 + p4 * 0.55);
        ctx.lineWidth = 1.2 + p4 * 1.2;

        // Perspective radiating lines
        for (let i = -vLines; i <= vLines; i++) {
            const bx = w / 2 + (i / vLines) * w * 2.4;
            ctx.beginPath();
            ctx.moveTo(w / 2 + (i / vLines) * (w * 0.08), horizonY);
            ctx.lineTo(bx, h);
            ctx.stroke();
        }

        // Perspective horizontal Z lines
        const hLines = Math.round(10 + p1 * 18);
        for (let j = 1; j <= hLines; j++) {
            const t = Math.pow(j / hLines, 2.2);
            const y = horizonY + t * (h - horizonY);
            ctx.beginPath();
            ctx.moveTo(0, y);
            ctx.lineTo(w, y);
            ctx.stroke();
        }
        ctx.restore();
    }

    function renderHalftoneOpArt(ctx, w, h, rand, lut, p1, p2, p3, p4, variant) {
        ctx.fillStyle = sampleLutCss(lut, 0.05);
        ctx.fillRect(0, 0, w, h);

        const step = Math.max(8, Math.round((1.15 - p1) * 28 + 7));
        const angle = (p4 - 0.5) * Math.PI * 0.5;
        const cosA = Math.cos(angle), sinA = Math.sin(angle);
        const cx1 = w * (0.5 - p2 * 0.28), cy1 = h * 0.5;
        const cx2 = w * (0.5 + p2 * 0.28), cy2 = h * 0.5;
        const diag = Math.hypot(w, h);

        for (let gy = -diag * 0.6; gy <= diag * 0.6; gy += step) {
            for (let gx = -diag * 0.6; gx <= diag * 0.6; gx += step) {
                const x = w / 2 + gx * cosA - gy * sinA;
                const y = h / 2 + gx * sinA + gy * cosA;
                if (x < -step || x > w + step || y < -step || y > h + step) continue;

                const d1 = Math.hypot(x - cx1, y - cy1);
                const d2 = Math.hypot(x - cx2, y - cy2);
                const wave = (Math.sin(d1 * 0.045) + Math.cos(d2 * 0.045)) * 0.25 + 0.5;
                const r = step * 0.52 * (0.15 + wave * p3 * 0.95);

                ctx.fillStyle = sampleLutCss(lut, 0.25 + wave * 0.75);
                ctx.beginPath();
                if (variant === 1) {
                    ctx.rect(x - r, y - r, r * 2, r * 2);
                } else {
                    ctx.arc(x, y, r, 0, Math.PI * 2);
                }
                ctx.fill();
            }
        }
    }

    function renderIdenticonAvatar(ctx, w, h, seed, lut, p1, p2, p3, p4, variant) {
        const rand = mulberry32(fnv1aHash(seed));
        ctx.fillStyle = sampleLutCss(lut, 0.06);
        ctx.fillRect(0, 0, w, h);

        const gridN = (Math.round(2 + p1 * 4) * 2) + 1; // Odd symmetric: 5, 7, 9, 11
        const boxSize = Math.min(w, h) * (1 - (0.08 + p4 * 0.28));
        const cellSize = boxSize / gridN;
        const startX = (w - boxSize) / 2;
        const startY = (h - boxSize) / 2;
        const radius = cellSize * 0.48 * p2;
        const halfCols = Math.ceil(gridN / 2);

        for (let r = 0; r < gridN; r++) {
            for (let c = 0; c < halfCols; c++) {
                const active = rand() < (0.35 + p3 * 0.45);
                if (!active) continue;
                const colorT = 0.4 + rand() * 0.58;
                ctx.fillStyle = sampleLutCss(lut, colorT);

                const drawCell = (colIdx) => {
                    const x = startX + colIdx * cellSize;
                    const y = startY + r * cellSize;
                    ctx.beginPath();
                    if (variant === 1) {
                        ctx.arc(x + cellSize / 2, y + cellSize / 2, cellSize * 0.44, 0, Math.PI * 2);
                    } else if (ctx.roundRect && radius > 0.5) {
                        ctx.roundRect(x + 1, y + 1, cellSize - 2, cellSize - 2, radius);
                    } else {
                        ctx.rect(x, y, cellSize + 0.5, cellSize + 0.5);
                    }
                    ctx.fill();
                };

                drawCell(c);
                if (c !== gridN - 1 - c) {
                    drawCell(gridN - 1 - c);
                }
            }
        }
    }

    function renderBlueprintChecker(ctx, w, h, lut, p1, p2, p3, p4, variant) {
        const majorStep = Math.max(40, Math.round((1.2 - p1) * 140 + 40));
        const subDivs = Math.max(2, Math.round(2 + p2 * 6));
        const minorStep = majorStep / subDivs;

        if (variant === 1) {
            // UV Checkerboard Mode
            const cols = Math.ceil(w / majorStep);
            const rows = Math.ceil(h / majorStep);
            ctx.font = 'bold ' + Math.max(10, Math.round(majorStep * 0.22)) + 'px monospace';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            for (let r = 0; r < rows; r++) {
                for (let c = 0; c < cols; c++) {
                    const isEven = (r + c) % 2 === 0;
                    const t = ((c / Math.max(1, cols)) * 0.6 + (r / Math.max(1, rows)) * 0.4);
                    ctx.fillStyle = isEven ? sampleLutCss(lut, 0.12 + t * 0.25) : sampleLutCss(lut, 0.35 + t * 0.55);
                    ctx.fillRect(c * majorStep, r * majorStep, majorStep, majorStep);
                    if (p3 > 0.3) {
                        ctx.fillStyle = 'rgba(255,255,255,0.75)';
                        ctx.fillText(String.fromCharCode(65 + (r % 26)) + c, (c + 0.5) * majorStep, (r + 0.5) * majorStep);
                    }
                }
            }
        } else if (variant === 2) {
            // SMPTE Broadcast Calibration Test Pattern
            const bars = ['#c0c0c0', '#c0c000', '#00c0c0', '#00c000', '#c000c0', '#c00000', '#0000c0'];
            const bw = w / bars.length;
            for (let i = 0; i < bars.length; i++) {
                ctx.fillStyle = bars[i];
                ctx.fillRect(i * bw, 0, bw + 1, h * 0.68);
            }
            for (let x = 0; x < w; x++) {
                const t = x / w;
                ctx.fillStyle = sampleLutCss(lut, t);
                ctx.fillRect(x, h * 0.68, 1.5, h * 0.16);
            }
            ctx.fillStyle = '#090d16';
            ctx.fillRect(0, h * 0.84, w, h * 0.16);
        } else {
            // Engineering Blueprint Mode
            ctx.fillStyle = sampleLutCss(lut, 0.08);
            ctx.fillRect(0, 0, w, h);
        }

        // Grid Overlay
        const lineAlpha = 0.18 + p4 * 0.65;
        ctx.strokeStyle = sampleLutCss(lut, 0.75, lineAlpha * 0.35);
        ctx.lineWidth = 1;
        ctx.beginPath();
        for (let x = 0; x <= w; x += minorStep) {
            ctx.moveTo(x, 0); ctx.lineTo(x, h);
        }
        for (let y = 0; y <= h; y += minorStep) {
            ctx.moveTo(0, y); ctx.lineTo(w, y);
        }
        ctx.stroke();

        ctx.strokeStyle = sampleLutCss(lut, 0.92, lineAlpha * 0.8);
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        for (let x = 0; x <= w; x += majorStep) {
            ctx.moveTo(x, 0); ctx.lineTo(x, h);
        }
        for (let y = 0; y <= h; y += majorStep) {
            ctx.moveTo(0, y); ctx.lineTo(w, y);
        }
        ctx.stroke();

        // Center Reticle & Resolution HUD
        if (p3 > 0.2) {
            const cx = w / 2, cy = h / 2;
            const r = Math.min(w, h) * 0.18;
            ctx.strokeStyle = sampleLutCss(lut, 0.98, 0.85);
            ctx.lineWidth = 2;
            ctx.beginPath();
            ctx.arc(cx, cy, r, 0, Math.PI * 2);
            ctx.moveTo(cx - r * 1.4, cy); ctx.lineTo(cx + r * 1.4, cy);
            ctx.moveTo(cx, cy - r * 1.4); ctx.lineTo(cx, cy + r * 1.4);
            ctx.stroke();

            ctx.fillStyle = 'rgba(9, 13, 22, 0.78)';
            ctx.fillRect(cx - 110, cy + r + 14, 220, 32);
            ctx.strokeRect(cx - 110, cy + r + 14, 220, 32);
            ctx.fillStyle = '#ffffff';
            ctx.font = 'bold 13px monospace';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(w + ' × ' + h + ' PX · 1:1 CALIBRATED', cx, cy + r + 30);
        }
    }

    function applyQuickFilmGrain(ctx, w, h, strength, rand) {
        const img = ctx.getImageData(0, 0, w, h);
        const d = img.data;
        const amp = strength * 75;
        let s = (rand() * 0xffffffff) >>> 0;
        for (let i = 0; i < d.length; i += 4) {
            s = (Math.imul(s, 1664525) + 1013904223) >>> 0;
            const n = ((s >>> 24) / 255 - 0.5) * amp;
            d[i] = Math.max(0, Math.min(255, d[i] + n));
            d[i + 1] = Math.max(0, Math.min(255, d[i + 1] + n));
            d[i + 2] = Math.max(0, Math.min(255, d[i + 2] + n));
        }
        ctx.putImageData(img, 0, 0);
    }

    function renderCoverTypographyOverlay(ctx, w, h, state, lut) {
        const title = (state.overlayTitle || '').trim();
        const sub = (state.overlaySubtitle || '').trim();
        const badge = (state.overlayBadge || '').trim();
        const scale = (state.overlayFontScale || 100) / 100;
        const dim = (state.overlayBackdropDim || 40) / 100;
        const layout = state.overlayLayout || 'center-glass';

        ctx.save();
        if (dim > 0) {
            ctx.fillStyle = 'rgba(8, 10, 18, ' + (dim * 0.65).toFixed(3) + ')';
            ctx.fillRect(0, 0, w, h);
        }

        const baseTitlePx = Math.max(22, Math.round(Math.min(w, h) * 0.068 * scale));
        const baseSubPx = Math.max(13, Math.round(baseTitlePx * 0.42));
        const baseBadgePx = Math.max(11, Math.round(baseTitlePx * 0.32));

        if (layout === 'center-glass' || layout === 'terminal-frame') {
            const cardW = Math.min(w * 0.84, Math.max(w * 0.56, 560));
            const cardH = Math.min(h * 0.68, baseTitlePx * 3.4);
            const cardX = (w - cardW) / 2;
            const cardY = (h - cardH) / 2;

            ctx.fillStyle = 'rgba(12, 15, 26, 0.72)';
            ctx.strokeStyle = sampleLutCss(lut, 0.85, 0.55);
            ctx.lineWidth = 2;
            ctx.beginPath();
            if (ctx.roundRect) ctx.roundRect(cardX, cardY, cardW, cardH, 20);
            else ctx.rect(cardX, cardY, cardW, cardH);
            ctx.fill();
            ctx.stroke();

            if (layout === 'terminal-frame') {
                // macOS / Linux terminal window dots
                const dotY = cardY + 20;
                ['#ef4444', '#f59e0b', '#10b981'].forEach((c, idx) => {
                    ctx.fillStyle = c;
                    ctx.beginPath();
                    ctx.arc(cardX + 24 + idx * 18, dotY, 5.5, 0, Math.PI * 2);
                    ctx.fill();
                });
            }
        }

        const isLeft = layout === 'bottom-left';
        ctx.textAlign = isLeft ? 'left' : 'center';
        ctx.textBaseline = 'middle';
        const anchorX = isLeft ? w * 0.08 : w / 2;
        const anchorY = isLeft ? h * 0.76 : h / 2;

        if (badge) {
            ctx.font = 'bold ' + baseBadgePx + 'px monospace';
            const bw = ctx.measureText(badge).width + 24;
            const bh = baseBadgePx * 1.9;
            const bx = isLeft ? anchorX : anchorX - bw / 2;
            const by = anchorY - baseTitlePx * 1.05 - bh / 2;
            ctx.fillStyle = sampleLutCss(lut, 0.75, 0.28);
            ctx.strokeStyle = sampleLutCss(lut, 0.9, 0.7);
            ctx.lineWidth = 1.2;
            ctx.beginPath();
            if (ctx.roundRect) ctx.roundRect(bx, by, bw, bh, bh / 2);
            else ctx.rect(bx, by, bw, bh);
            ctx.fill();
            ctx.stroke();

            ctx.fillStyle = '#ffffff';
            ctx.fillText(badge, isLeft ? bx + 12 : anchorX, by + bh / 2);
        }

        if (title) {
            ctx.font = '800 ' + baseTitlePx + 'px system-ui, -apple-system, sans-serif';
            ctx.shadowColor = 'rgba(0,0,0,0.65)';
            ctx.shadowBlur = 16;
            ctx.fillStyle = '#ffffff';
            ctx.fillText(title, anchorX, anchorY);
        }

        if (sub) {
            ctx.font = '500 ' + baseSubPx + 'px monospace, system-ui';
            ctx.shadowBlur = 8;
            ctx.fillStyle = 'rgba(226, 232, 240, 0.88)';
            ctx.fillText(sub, anchorX, anchorY + baseTitlePx * 0.85);
        }

        ctx.restore();
    }

    // =========================================================================
    // 5. NON-DESTRUCTIVE IMAGE PROCESSING PIPELINE ENGINE
    // =========================================================================
    function applyImageProcessingPipeline(sourceCanvas, targetCanvas, procState, compareSplitPct) {
        if (!sourceCanvas || sourceCanvas.width === 0) return;

        // 1. Geometry: Crop, Rotate, Flip, Scale, Padding, Border Radius
        const srcW = sourceCanvas.width;
        const srcH = sourceCanvas.height;
        const cxPct = Math.max(0, Math.min(90, Number(procState.cropX) || 0)) / 100;
        const cyPct = Math.max(0, Math.min(90, Number(procState.cropY) || 0)) / 100;
        const cwPct = Math.max(0.1, Math.min(1 - cxPct, (Number(procState.cropW) || 100) / 100));
        const chPct = Math.max(0.1, Math.min(1 - cyPct, (Number(procState.cropH) || 100) / 100));

        const sx = Math.round(srcW * cxPct);
        const sy = Math.round(srcH * cyPct);
        const sw = Math.max(8, Math.round(srcW * cwPct));
        const sh = Math.max(8, Math.round(srcH * chPct));

        const scaleFactor = Math.max(0.1, Math.min(2.0, (Number(procState.scalePct) || 100) / 100));
        const rotQuad = ((procState.rot90 || 0) % 4 + 4) % 4;
        const swapped = rotQuad === 1 || rotQuad === 3;
        const baseOutW = Math.max(8, Math.round((swapped ? sh : sw) * scaleFactor));
        const baseOutH = Math.max(8, Math.round((swapped ? sw : sh) * scaleFactor));
        const pad = Math.max(0, Math.round(Number(procState.padding) || 0));

        const finalW = baseOutW + pad * 2;
        const finalH = baseOutH + pad * 2;

        // Prepare geometric canvas (unfiltered source after geometry)
        const geoCanvas = document.createElement('canvas');
        geoCanvas.width = finalW;
        geoCanvas.height = finalH;
        const gctx = geoCanvas.getContext('2d');

        gctx.save();
        if (procState.borderRadius > 0) {
            const rad = Math.min(baseOutW, baseOutH) * (procState.borderRadius / 100);
            gctx.beginPath();
            if (gctx.roundRect) {
                gctx.roundRect(pad, pad, baseOutW, baseOutH, rad);
            } else {
                gctx.rect(pad, pad, baseOutW, baseOutH);
            }
            gctx.clip();
        }

        gctx.translate(finalW / 2, finalH / 2);
        const totalRad = (rotQuad * 90 + (Number(procState.rotateFine) || 0)) * (Math.PI / 180);
        gctx.rotate(totalRad);
        gctx.scale(procState.flipH ? -1 : 1, procState.flipV ? -1 : 1);

        const drawW = swapped ? baseOutH : baseOutW;
        const drawH = swapped ? baseOutW : baseOutH;
        gctx.drawImage(sourceCanvas, sx, sy, sw, sh, -drawW / 2, -drawH / 2, drawW, drawH);
        gctx.restore();

        // 2. Create working canvas for pixel pipeline
        const workCanvas = document.createElement('canvas');
        workCanvas.width = finalW;
        workCanvas.height = finalH;
        const wctx = workCanvas.getContext('2d');

        // Apply Pixelate block first if enabled
        const pxBlock = Math.max(1, Math.round(Number(procState.pixelate) || 1));
        if (pxBlock > 1) {
            const pw = Math.max(4, Math.floor(finalW / pxBlock));
            const ph = Math.max(4, Math.floor(finalH / pxBlock));
            const tmpP = document.createElement('canvas');
            tmpP.width = pw;
            tmpP.height = ph;
            tmpP.getContext('2d').drawImage(geoCanvas, 0, 0, pw, ph);
            wctx.imageSmoothingEnabled = false;
            wctx.drawImage(tmpP, 0, 0, pw, ph, 0, 0, finalW, finalH);
            wctx.imageSmoothingEnabled = true;
        } else {
            wctx.drawImage(geoCanvas, 0, 0);
        }

        // Apply Gaussian Blur via Canvas filter or separable box blur
        const blurPx = Number(procState.blur) || 0;
        if (blurPx > 0) {
            const blurTmp = document.createElement('canvas');
            blurTmp.width = finalW;
            blurTmp.height = finalH;
            const bctx = blurTmp.getContext('2d');
            bctx.filter = 'blur(' + blurPx + 'px)';
            bctx.drawImage(workCanvas, 0, 0);
            wctx.clearRect(0, 0, finalW, finalH);
            wctx.drawImage(blurTmp, 0, 0);
        }

        // 3. Extract ImageData for per-pixel Color Grading, Kernel, Dither, FX & Chroma Key
        const imgData = wctx.getImageData(0, 0, finalW, finalH);
        const data = imgData.data;

        // Auto Contrast Levels pre-pass if checked
        let minLum = 0, maxLum = 255;
        if (procState.autoLevels) {
            minLum = 255; maxLum = 0;
            for (let i = 0; i < data.length; i += 16) {
                if (data[i + 3] < 16) continue;
                const lum = Math.round(0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2]);
                if (lum < minLum) minLum = lum;
                if (lum > maxLum) maxLum = lum;
            }
            if (maxLum <= minLum + 4) { minLum = 0; maxLum = 255; }
        }

        // Color grading parameters
        const bright = (Number(procState.brightness) || 0) * 2.2;
        const contrastVal = Number(procState.contrast) || 0;
        const contrastFactor = (259 * (contrastVal + 255)) / (255 * (259 - contrastVal));
        const satFactor = 1 + (Number(procState.saturation) || 0) / 100;
        const hueShift = Number(procState.hue) || 0;
        const temp = (Number(procState.temp) || 0) * 0.85;
        const tint = (Number(procState.tint) || 0) * 0.85;
        const high = (Number(procState.highlights) || 0) / 100;
        const shad = (Number(procState.shadows) || 0) / 100;
        const gamma = Math.max(0.2, (Number(procState.gamma) || 100) / 100);
        const invGamma = 1.0 / gamma;
        const sepiaAmt = (Number(procState.sepia) || 0) / 100;
        const posterizeLvls = Number(procState.posterize) || 32;
        const doPosterize = posterizeLvls < 32;
        const duotoneOn = Boolean(procState.duotoneEnable);
        const duoDark = hexToRgb(procState.duotoneDark || '#1e1b4b');
        const duoLight = hexToRgb(procState.duotoneLight || '#10b981');
        const chromaKeyOn = Boolean(procState.chromaKeyEnable);
        const ckRgb = hexToRgb(procState.chromaKeyColor || '#ffffff');
        const ckTol = (Number(procState.chromaKeyTol) || 25) * 2.2;
        const ckFeather = Math.max(1, (Number(procState.chromaKeyFeather) || 15) * 2.2);

        const needColorLoop = bright !== 0 || contrastVal !== 0 || satFactor !== 1 || hueShift !== 0
            || temp !== 0 || tint !== 0 || high !== 0 || shad !== 0 || gamma !== 1.0 || sepiaAmt > 0
            || procState.invert || procState.solarize || procState.autoLevels || doPosterize
            || duotoneOn || chromaKeyOn;

        if (needColorLoop) {
            const levelScale = 255 / Math.max(1, maxLum - minLum);
            for (let i = 0; i < data.length; i += 4) {
                let r = data[i], g = data[i + 1], b = data[i + 2], a = data[i + 3];
                if (a === 0) continue;

                // Chroma key check on original pixel color
                if (chromaKeyOn) {
                    const dist = Math.hypot(r - ckRgb[0], g - ckRgb[1], b - ckRgb[2]);
                    if (dist <= ckTol) {
                        data[i + 3] = 0;
                        continue;
                    } else if (dist < ckTol + ckFeather) {
                        a = Math.round(a * ((dist - ckTol) / ckFeather));
                        data[i + 3] = a;
                    }
                }

                if (procState.autoLevels) {
                    r = (r - minLum) * levelScale;
                    g = (g - minLum) * levelScale;
                    b = (b - minLum) * levelScale;
                }

                // Exposure / Brightness & Contrast
                r = contrastFactor * (r + bright - 128) + 128;
                g = contrastFactor * (g + bright - 128) + 128;
                b = contrastFactor * (b + bright - 128) + 128;

                // Temperature (R/B) & Tint (G/M)
                r += temp + tint * 0.35;
                g -= tint * 0.7;
                b -= temp - tint * 0.35;

                // Highlights & Shadows
                if (high !== 0 || shad !== 0) {
                    const lumNorm = Math.max(0, Math.min(1, (0.299 * r + 0.587 * g + 0.114 * b) / 255));
                    const sWeight = Math.pow(1 - lumNorm, 1.8) * shad * 85;
                    const hWeight = Math.pow(lumNorm, 1.8) * high * 85;
                    const delta = sWeight + hWeight;
                    r += delta; g += delta; b += delta;
                }

                // Saturation & Hue Shift
                if (hueShift !== 0) {
                    const hsl = rgbToHsl(
                        Math.max(0, Math.min(255, r)),
                        Math.max(0, Math.min(255, g)),
                        Math.max(0, Math.min(255, b))
                    );
                    const rgb = hslToRgb(hsl[0] + hueShift, (hsl[1] / 100) * satFactor, hsl[2] / 100);
                    r = rgb[0]; g = rgb[1]; b = rgb[2];
                } else if (satFactor !== 1) {
                    const gray = 0.299 * r + 0.587 * g + 0.114 * b;
                    r = gray + (r - gray) * satFactor;
                    g = gray + (g - gray) * satFactor;
                    b = gray + (b - gray) * satFactor;
                }

                // Gamma
                if (gamma !== 1.0) {
                    r = 255 * Math.pow(Math.max(0, Math.min(255, r)) / 255, invGamma);
                    g = 255 * Math.pow(Math.max(0, Math.min(255, g)) / 255, invGamma);
                    b = 255 * Math.pow(Math.max(0, Math.min(255, b)) / 255, invGamma);
                }

                // Sepia
                if (sepiaAmt > 0) {
                    const sr = Math.min(255, r * 0.393 + g * 0.769 + b * 0.189);
                    const sg = Math.min(255, r * 0.349 + g * 0.686 + b * 0.168);
                    const sb = Math.min(255, r * 0.272 + g * 0.534 + b * 0.131);
                    r = r + (sr - r) * sepiaAmt;
                    g = g + (sg - g) * sepiaAmt;
                    b = b + (sb - b) * sepiaAmt;
                }

                if (procState.solarize) {
                    if (r > 127) r = 255 - r;
                    if (g > 127) g = 255 - g;
                    if (b > 127) b = 255 - b;
                }

                if (procState.invert) {
                    r = 255 - r;
                    g = 255 - g;
                    b = 255 - b;
                }

                if (duotoneOn) {
                    const luma = Math.max(0, Math.min(1, (0.299 * r + 0.587 * g + 0.114 * b) / 255));
                    r = duoDark[0] + (duoLight[0] - duoDark[0]) * luma;
                    g = duoDark[1] + (duoLight[1] - duoDark[1]) * luma;
                    b = duoDark[2] + (duoLight[2] - duoDark[2]) * luma;
                }

                if (doPosterize) {
                    const step = 255 / (posterizeLvls - 1);
                    r = Math.round(Math.max(0, Math.min(255, r)) / step) * step;
                    g = Math.round(Math.max(0, Math.min(255, g)) / step) * step;
                    b = Math.round(Math.max(0, Math.min(255, b)) / step) * step;
                }

                data[i] = Math.max(0, Math.min(255, Math.round(r)));
                data[i + 1] = Math.max(0, Math.min(255, Math.round(g)));
                data[i + 2] = Math.max(0, Math.min(255, Math.round(b)));
            }
        }

        // 4. Convolution Kernel / Sharpen / Sobel Edge Detection
        const sharpenAmt = (Number(procState.sharpen) || 0) / 100;
        const kernelMode = procState.kernelPreset || 'none';
        if (kernelMode === 'sobel-color' || kernelMode === 'sobel-mono') {
            applySobelOperator(data, finalW, finalH, kernelMode === 'sobel-mono');
        } else if (kernelMode !== 'none' || sharpenAmt > 0) {
            let k = [0, 0, 0, 0, 1, 0, 0, 0, 0];
            let bias = 0;
            if (kernelMode === 'laplacian') {
                k = [-1, -1, -1, -1, 8, -1, -1, -1, -1];
            } else if (kernelMode === 'emboss') {
                k = [-2, -1, 0, -1, 1, 1, 0, 1, 2];
                bias = 40;
            } else if (kernelMode === 'edge-enhance') {
                k = [-1, -1, -1, -1, 9, -1, -1, -1, -1];
            } else if (kernelMode === 'box-blur') {
                k = [1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9, 1 / 9];
            } else if (kernelMode === 'custom') {
                k = procState.customKernel.slice();
                if (procState.kernelNormalize) {
                    const sum = k.reduce((a, b) => a + b, 0);
                    if (Math.abs(sum) > 0.001) k = k.map((v) => v / sum);
                }
            } else if (sharpenAmt > 0) {
                const s = sharpenAmt * 1.6;
                k = [0, -s, 0, -s, 1 + 4 * s, -s, 0, -s, 0];
            }
            apply3x3Convolution(data, finalW, finalH, k, bias);
        }

        // 5. Chromatic Aberration Shift
        const chromaPx = Math.round(Number(procState.chroma) || 0);
        if (chromaPx > 0) {
            const copy = new Uint8ClampedArray(data);
            for (let y = 0; y < finalH; y++) {
                const row = y * finalW;
                for (let x = 0; x < finalW; x++) {
                    const xr = Math.min(finalW - 1, Math.max(0, x - chromaPx));
                    const xb = Math.min(finalW - 1, Math.max(0, x + chromaPx));
                    const i = (row + x) * 4;
                    data[i] = copy[(row + xr) * 4];
                    data[i + 2] = copy[(row + xb) * 4 + 2];
                }
            }
        }

        // 6. Retro Dithering & Palette Quantization
        if (procState.ditherAlgo && procState.ditherAlgo !== 'none') {
            applyDithering(data, finalW, finalH, procState.ditherAlgo, procState.ditherPalette || '1bit-bw');
        }

        // 7. Film Grain & Vignette in Processor
        const noiseAmt = (Number(procState.noise) || 0) / 100;
        const vigAmt = (Number(procState.vignette) || 0) / 100;
        if (noiseAmt > 0 || vigAmt > 0) {
            const cx = finalW / 2, cy = finalH / 2;
            const maxD = Math.hypot(cx, cy);
            let rng = 0x12345678;
            for (let y = 0; y < finalH; y++) {
                for (let x = 0; x < finalW; x++) {
                    const i = (y * finalW + x) * 4;
                    let r = data[i], g = data[i + 1], b = data[i + 2];
                    if (noiseAmt > 0) {
                        rng = (Math.imul(rng, 1664525) + 1013904223) >>> 0;
                        const n = ((rng >>> 24) / 255 - 0.5) * noiseAmt * 95;
                        r += n; g += n; b += n;
                    }
                    if (vigAmt > 0) {
                        const d = Math.hypot(x - cx, y - cy) / maxD;
                        const v = 1 - Math.pow(d, 1.8) * vigAmt * 0.95;
                        r *= v; g *= v; b *= v;
                    }
                    data[i] = Math.max(0, Math.min(255, r));
                    data[i + 1] = Math.max(0, Math.min(255, g));
                    data[i + 2] = Math.max(0, Math.min(255, b));
                }
            }
        }

        wctx.putImageData(imgData, 0, 0);

        // 8. Glitch Slice Displacement & Scanlines
        const glitchAmt = (Number(procState.glitch) || 0) / 100;
        if (glitchAmt > 0) {
            const rand = mulberry32(0x9E3779B9);
            const slices = Math.round(4 + glitchAmt * 22);
            for (let s = 0; s < slices; s++) {
                const sySlice = Math.floor(rand() * finalH);
                const shSlice = Math.max(2, Math.floor(rand() * (finalH * 0.08 * glitchAmt)));
                const shiftX = (rand() - 0.5) * finalW * 0.16 * glitchAmt;
                wctx.drawImage(workCanvas, 0, sySlice, finalW, shSlice, shiftX, sySlice, finalW, shSlice);
            }
            // Subtle CRT scanlines
            wctx.fillStyle = 'rgba(0,0,0,' + (0.18 * glitchAmt).toFixed(2) + ')';
            for (let y = 0; y < finalH; y += 3) {
                wctx.fillRect(0, y, finalW, 1);
            }
        }

        // 9. Anti-Theft Watermark Stamp
        const wmText = (procState.watermarkText || '').trim();
        if (wmText) {
            renderWatermarkOverlay(wctx, finalW, finalH, wmText, procState);
        }

        // 10. Output to targetCanvas (with optional Before/After Split Comparison)
        targetCanvas.width = finalW;
        targetCanvas.height = finalH;
        const tctx = targetCanvas.getContext('2d');
        tctx.clearRect(0, 0, finalW, finalH);
        tctx.drawImage(workCanvas, 0, 0);

        const split = Math.max(0, Math.min(100, Number(compareSplitPct) || 0));
        if (split > 0) {
            const splitX = Math.round((split / 100) * finalW);
            tctx.save();
            tctx.beginPath();
            tctx.rect(0, 0, splitX, finalH);
            tctx.clip();
            tctx.clearRect(0, 0, splitX, finalH);
            tctx.drawImage(geoCanvas, 0, 0);
            tctx.restore();

            // Divider line
            tctx.strokeStyle = '#a855f7';
            tctx.lineWidth = 2;
            tctx.beginPath();
            tctx.moveTo(splitX, 0);
            tctx.lineTo(splitX, finalH);
            tctx.stroke();
        }
    }

    function apply3x3Convolution(data, w, h, k, bias) {
        const src = new Uint8ClampedArray(data);
        for (let y = 1; y < h - 1; y++) {
            for (let x = 1; x < w - 1; x++) {
                let r = 0, g = 0, b = 0;
                for (let ky = -1; ky <= 1; ky++) {
                    const row = (y + ky) * w;
                    const kRow = (ky + 1) * 3;
                    for (let kx = -1; kx <= 1; kx++) {
                        const weight = k[kRow + (kx + 1)];
                        if (weight === 0) continue;
                        const idx = (row + (x + kx)) * 4;
                        r += src[idx] * weight;
                        g += src[idx + 1] * weight;
                        b += src[idx + 2] * weight;
                    }
                }
                const dst = (y * w + x) * 4;
                data[dst] = Math.max(0, Math.min(255, r + bias));
                data[dst + 1] = Math.max(0, Math.min(255, g + bias));
                data[dst + 2] = Math.max(0, Math.min(255, b + bias));
            }
        }
    }

    function applySobelOperator(data, w, h, mono) {
        const src = new Uint8ClampedArray(data);
        for (let y = 1; y < h - 1; y++) {
            for (let x = 1; x < w - 1; x++) {
                const idx = (r, c) => (r * w + c) * 4;
                for (let ch = 0; ch < 3; ch++) {
                    const gx =
                        -src[idx(y - 1, x - 1) + ch] + src[idx(y - 1, x + 1) + ch]
                        - 2 * src[idx(y, x - 1) + ch] + 2 * src[idx(y, x + 1) + ch]
                        - src[idx(y + 1, x - 1) + ch] + src[idx(y + 1, x + 1) + ch];
                    const gy =
                        -src[idx(y - 1, x - 1) + ch] - 2 * src[idx(y - 1, x) + ch] - src[idx(y - 1, x + 1) + ch]
                        + src[idx(y + 1, x - 1) + ch] + 2 * src[idx(y + 1, x) + ch] + src[idx(y + 1, x + 1) + ch];
                    data[idx(y, x) + ch] = Math.min(255, Math.hypot(gx, gy));
                }
                if (mono) {
                    const p = idx(y, x);
                    const v = Math.round(0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2]);
                    data[p] = data[p + 1] = data[p + 2] = v;
                }
            }
        }
    }

    function findNearestPaletteColor(r, g, b, pal) {
        let best = pal[0];
        let bestDist = Infinity;
        for (let i = 0; i < pal.length; i++) {
            const c = pal[i];
            const dr = r - c[0], dg = g - c[1], db = b - c[2];
            const d = dr * dr * 0.3 + dg * dg * 0.59 + db * db * 0.11;
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    function applyDithering(data, w, h, algo, paletteId) {
        const pal = RETRO_PALETTES[paletteId] || RETRO_PALETTES['1bit-bw'];
        const bayer4 = [
            [0, 8, 2, 10],
            [12, 4, 14, 6],
            [3, 11, 1, 9],
            [15, 7, 13, 5]
        ];

        if (algo === 'bayer-4x4' || algo === 'bayer-8x8' || algo === 'threshold') {
            const spread = algo === 'threshold' ? 0 : 48;
            for (let y = 0; y < h; y++) {
                for (let x = 0; x < w; x++) {
                    const i = (y * w + x) * 4;
                    const m = (bayer4[y & 3][x & 3] / 15 - 0.5) * spread;
                    const near = findNearestPaletteColor(data[i] + m, data[i + 1] + m, data[i + 2] + m, pal);
                    data[i] = near[0];
                    data[i + 1] = near[1];
                    data[i + 2] = near[2];
                }
            }
            return;
        }

        // Error diffusion (Floyd-Steinberg or Atkinson)
        const buf = new Float32Array(data.length);
        for (let i = 0; i < data.length; i++) buf[i] = data[i];

        const addErr = (x, y, er, eg, eb, weight) => {
            if (x < 0 || x >= w || y < 0 || y >= h) return;
            const idx = (y * w + x) * 4;
            buf[idx] += er * weight;
            buf[idx + 1] += eg * weight;
            buf[idx + 2] += eb * weight;
        };

        for (let y = 0; y < h; y++) {
            for (let x = 0; x < w; x++) {
                const i = (y * w + x) * 4;
                const oldR = buf[i], oldG = buf[i + 1], oldB = buf[i + 2];
                const near = findNearestPaletteColor(oldR, oldG, oldB, pal);
                data[i] = near[0];
                data[i + 1] = near[1];
                data[i + 2] = near[2];

                const er = oldR - near[0];
                const eg = oldG - near[1];
                const eb = oldB - near[2];

                if (algo === 'atkinson') {
                    const w8 = 1 / 8;
                    addErr(x + 1, y, er, eg, eb, w8);
                    addErr(x + 2, y, er, eg, eb, w8);
                    addErr(x - 1, y + 1, er, eg, eb, w8);
                    addErr(x, y + 1, er, eg, eb, w8);
                    addErr(x + 1, y + 1, er, eg, eb, w8);
                    addErr(x, y + 2, er, eg, eb, w8);
                } else {
                    // Floyd-Steinberg
                    addErr(x + 1, y, er, eg, eb, 7 / 16);
                    addErr(x - 1, y + 1, er, eg, eb, 3 / 16);
                    addErr(x, y + 1, er, eg, eb, 5 / 16);
                    addErr(x + 1, y + 1, er, eg, eb, 1 / 16);
                }
            }
        }
    }

    function renderWatermarkOverlay(ctx, w, h, text, procState) {
        const opacity = (Number(procState.watermarkOpacity) || 28) / 100;
        const sizeScale = (Number(procState.watermarkSize) || 50) / 50;
        const rgb = hexToRgb(procState.watermarkColor || '#ffffff');
        const mode = procState.watermarkMode || 'tiled';
        const fontSize = Math.max(12, Math.round(Math.min(w, h) * 0.032 * sizeScale));

        ctx.save();
        ctx.font = 'bold ' + fontSize + 'px system-ui, -apple-system, sans-serif';
        ctx.fillStyle = 'rgba(' + rgb[0] + ',' + rgb[1] + ',' + rgb[2] + ',' + opacity.toFixed(3) + ')';
        ctx.shadowColor = 'rgba(0,0,0,' + (opacity * 0.6).toFixed(3) + ')';
        ctx.shadowBlur = 4;

        if (mode === 'bottom-right') {
            ctx.textAlign = 'right';
            ctx.textBaseline = 'bottom';
            ctx.fillText(text, w - fontSize, h - fontSize * 0.8);
        } else if (mode === 'center') {
            ctx.font = 'bold ' + Math.round(fontSize * 1.8) + 'px system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.translate(w / 2, h / 2);
            ctx.rotate(-Math.PI / 8);
            ctx.fillText(text, 0, 0);
        } else {
            // Tiled diagonal anti-theft stamp
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.translate(w / 2, h / 2);
            ctx.rotate(-Math.PI / 6);
            const diag = Math.hypot(w, h);
            const stepX = Math.max(fontSize * 6, ctx.measureText(text).width + fontSize * 3.5);
            const stepY = Math.max(fontSize * 3.5, 90 * sizeScale);
            let rowIdx = 0;
            for (let y = -diag; y <= diag; y += stepY) {
                const offset = (rowIdx++ % 2) * (stepX / 2);
                for (let x = -diag; x <= diag; x += stepX) {
                    ctx.fillText(text, x + offset, y);
                }
            }
        }
        ctx.restore();
    }

    // =========================================================================
    // 6. RGB + LUMINANCE HISTOGRAM & EYEDROPPER RENDERER
    // =========================================================================
    function updateHistogramAndStats(mainCanvas, histCanvas, statsEl) {
        if (!mainCanvas || !histCanvas) return;
        const w = mainCanvas.width, h = mainCanvas.height;
        if (w === 0 || h === 0) return;

        const ctx = mainCanvas.getContext('2d');
        const data = ctx.getImageData(0, 0, w, h).data;
        const rBins = new Uint32Array(256);
        const gBins = new Uint32Array(256);
        const bBins = new Uint32Array(256);
        const lBins = new Uint32Array(256);

        const stride = Math.max(4, Math.floor((w * h) / 24000) * 4);
        let sumLum = 0, count = 0, minL = 255, maxL = 0;

        for (let i = 0; i < data.length; i += stride) {
            if (data[i + 3] < 16) continue;
            const r = data[i], g = data[i + 1], b = data[i + 2];
            const lum = Math.min(255, Math.round(0.299 * r + 0.587 * g + 0.114 * b));
            rBins[r]++;
            gBins[g]++;
            bBins[b]++;
            lBins[lum]++;
            sumLum += lum;
            count++;
            if (lum < minL) minL = lum;
            if (lum > maxL) maxL = lum;
        }

        let maxCount = 1;
        for (let i = 1; i < 255; i++) {
            maxCount = Math.max(maxCount, rBins[i], gBins[i], bBins[i], lBins[i]);
        }

        const hw = histCanvas.width;
        const hh = histCanvas.height;
        const hctx = histCanvas.getContext('2d');
        hctx.clearRect(0, 0, hw, hh);

        const drawChannel = (bins, fillStyle, strokeStyle) => {
            hctx.beginPath();
            hctx.moveTo(0, hh);
            for (let i = 0; i < 256; i++) {
                const x = (i / 255) * hw;
                const val = Math.min(1, Math.pow(bins[i] / maxCount, 0.65));
                const y = hh - val * (hh - 4);
                hctx.lineTo(x, y);
            }
            hctx.lineTo(hw, hh);
            hctx.closePath();
            hctx.fillStyle = fillStyle;
            hctx.fill();
            hctx.strokeStyle = strokeStyle;
            hctx.lineWidth = 1.1;
            hctx.stroke();
        };

        drawChannel(rBins, 'rgba(239, 68, 68, 0.22)', 'rgba(239, 68, 68, 0.75)');
        drawChannel(gBins, 'rgba(16, 185, 129, 0.22)', 'rgba(16, 185, 129, 0.75)');
        drawChannel(bBins, 'rgba(59, 130, 246, 0.22)', 'rgba(59, 130, 246, 0.75)');
        drawChannel(lBins, 'rgba(255, 255, 255, 0.12)', 'rgba(226, 232, 240, 0.85)');

        if (statsEl && count > 0) {
            const avg = Math.round(sumLum / count);
            statsEl.textContent = '平均亮度: ' + avg + ' · 动态跨度: ' + Math.max(0, maxL - minL);
        }
    }

    // =========================================================================
    // 7. STUDIO CONTROLLER & LIFECYCLE MOUNT
    // =========================================================================
    let activeCleanup = null;

    function mountImageGenerator() {
        const app = document.getElementById('imageGeneratorToolApp');
        if (!app) return;
        if (activeCleanup) activeCleanup();

        const mainCanvas = document.getElementById('igMainCanvas');
        const histCanvas = document.getElementById('igHistogramCanvas');
        const swatchCanvas = document.getElementById('igColorSwatchCanvas');
        const generatedBufferCanvas = document.createElement('canvas');
        const processorSourceCanvas = document.createElement('canvas');

        const genState = {
            algo: 'domain-warping',
            width: 1200,
            height: 630,
            seed: 'windblog-2026',
            param1: 42,
            param2: 60,
            param3: 62,
            param4: 55,
            variant: 0,
            grain: 12,
            vignette: 20,
            paletteId: 'cyberpunk',
            colorStops: PALETTE_PRESETS[0].stops.slice(),
            overlayTitle: '',
            overlaySubtitle: '',
            overlayBadge: '',
            overlayLayout: 'center-glass',
            overlayFontScale: 100,
            overlayBackdropDim: 40
        };

        const defaultProcState = () => ({
            brightness: 0, contrast: 0, saturation: 0, hue: 0,
            temp: 0, tint: 0, highlights: 0, shadows: 0,
            gamma: 100, sepia: 0, invert: false, solarize: false, autoLevels: false,
            rot90: 0, rotateFine: 0, flipH: false, flipV: false,
            scalePct: 100, borderRadius: 0, padding: 0,
            cropX: 0, cropY: 0, cropW: 100, cropH: 100,
            blur: 0, sharpen: 0, kernelPreset: 'none',
            customKernel: [0, -1, 0, -1, 5, -1, 0, -1, 0], kernelNormalize: true,
            pixelate: 1, posterize: 32, ditherAlgo: 'none', ditherPalette: '1bit-bw',
            chroma: 0, glitch: 0, noise: 0, vignette: 0,
            duotoneEnable: false, duotoneDark: '#1e1b4b', duotoneLight: '#10b981',
            chromaKeyEnable: false, chromaKeyColor: '#ffffff', chromaKeyTol: 25, chromaKeyFeather: 15,
            watermarkText: '', watermarkMode: 'tiled', watermarkOpacity: 28, watermarkSize: 50, watermarkColor: '#ffffff'
        });

        let procState = defaultProcState();
        const procHistory = [];
        let activeMode = 'generate'; // 'generate' | 'process' | 'transcode'
        let hasCustomUploadedSource = false;
        let currentInspectHex = '#7C3AED';
        let batchItems = [];
        const objectUrls = [];

        function pushProcHistory() {
            procHistory.push(JSON.stringify(procState));
            if (procHistory.length > 25) procHistory.shift();
        }

        function setSwatch(r, g, b) {
            if (!swatchCanvas) return;
            const sctx = swatchCanvas.getContext('2d');
            sctx.fillStyle = 'rgb(' + r + ',' + g + ',' + b + ')';
            sctx.fillRect(0, 0, swatchCanvas.width, swatchCanvas.height);
        }

        // Sync color stop inputs with genState.colorStops
        function syncColorStopInputs() {
            for (let i = 0; i < 5; i++) {
                const el = document.getElementById('igColorStop' + i);
                if (el && genState.colorStops[i]) el.value = genState.colorStops[i];
            }
        }

        // Build Palette Preset Buttons using safe DOM creation
        const paletteContainer = document.getElementById('igPalettePresets');
        if (paletteContainer) {
            paletteContainer.textContent = '';
            PALETTE_PRESETS.forEach((pal) => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.className = 'ig-palette-chip p-2 rounded-xl border border-border bg-input-bg/70 hover:border-accent/40 transition-all cursor-pointer text-left';
                btn.setAttribute('data-palette-id', pal.id);
                btn.setAttribute('data-active', pal.id === genState.paletteId ? 'true' : 'false');

                const title = document.createElement('div');
                title.className = 'text-[11px] font-bold text-main mb-1 truncate';
                title.textContent = pal.name;

                const bar = document.createElement('div');
                bar.className = 'flex h-2.5 rounded-full overflow-hidden border border-black/20';
                pal.stops.forEach((hex) => {
                    const seg = document.createElement('span');
                    seg.className = 'flex-1 h-full';
                    seg.style.backgroundColor = hex;
                    bar.appendChild(seg);
                });

                btn.appendChild(title);
                btn.appendChild(bar);
                btn.addEventListener('click', () => {
                    genState.paletteId = pal.id;
                    genState.colorStops = pal.stops.slice();
                    Array.from(paletteContainer.querySelectorAll('[data-palette-id]')).forEach((b) => {
                        b.setAttribute('data-active', b.getAttribute('data-palette-id') === pal.id ? 'true' : 'false');
                    });
                    syncColorStopInputs();
                    scheduleRender();
                });
                paletteContainer.appendChild(btn);
            });
        }

        // Render scheduling
        let renderPending = false;
        function scheduleRender() {
            if (renderPending) return;
            renderPending = true;
            requestAnimationFrame(() => {
                renderPending = false;
                performRender();
            });
        }

        function performRender() {
            const t0 = performance.now();
            if (activeMode === 'generate' || !hasCustomUploadedSource) {
                generatedBufferCanvas.width = genState.width;
                generatedBufferCanvas.height = genState.height;
                renderProceduralAlgorithm(generatedBufferCanvas, genState);
                if (!hasCustomUploadedSource) {
                    processorSourceCanvas.width = generatedBufferCanvas.width;
                    processorSourceCanvas.height = generatedBufferCanvas.height;
                    processorSourceCanvas.getContext('2d').drawImage(generatedBufferCanvas, 0, 0);
                }
            }

            if (activeMode === 'process') {
                const splitEl = document.getElementById('igCompareSplit');
                const splitPct = splitEl ? Number(splitEl.value) || 0 : 0;
                applyImageProcessingPipeline(processorSourceCanvas, mainCanvas, procState, splitPct);
            } else if (activeMode === 'generate') {
                mainCanvas.width = generatedBufferCanvas.width;
                mainCanvas.height = generatedBufferCanvas.height;
                mainCanvas.getContext('2d').drawImage(generatedBufferCanvas, 0, 0);
            }

            const elapsed = (performance.now() - t0).toFixed(1);
            const badge = document.getElementById('igCanvasInfoBadge');
            if (badge) {
                badge.textContent = mainCanvas.width + '×' + mainCanvas.height + ' · ' + elapsed + 'ms';
            }

            updateHistogramAndStats(mainCanvas, histCanvas, document.getElementById('igHistogramStats'));
            if (activeMode === 'transcode') {
                updateCurrentTranscodePreview();
            }
        }

        // Mode Switching
        function switchMode(mode) {
            activeMode = mode;
            ['generate', 'process', 'transcode'].forEach((m) => {
                const tabBtn = document.querySelector('[data-ig-mode="' + m + '"]');
                if (tabBtn) tabBtn.setAttribute('data-active', m === mode ? 'true' : 'false');
            });
            const pGen = document.getElementById('igPanelGenerate');
            const pProc = document.getElementById('igPanelProcess');
            const pTrans = document.getElementById('igPanelTranscode');
            const compBar = document.getElementById('igCompareBar');

            if (pGen) pGen.classList.toggle('hidden', mode !== 'generate');
            if (pProc) pProc.classList.toggle('hidden', mode !== 'process');
            if (pTrans) pTrans.classList.toggle('hidden', mode !== 'transcode');
            if (compBar) compBar.classList.toggle('hidden', mode !== 'process');

            scheduleRender();
        }

        Array.from(app.querySelectorAll('[data-ig-mode]')).forEach((btn) => {
            btn.addEventListener('click', () => switchMode(btn.getAttribute('data-ig-mode')));
        });

        // Algorithm Category Filter & Algorithm Selection
        const algoCatBar = document.getElementById('igAlgoCategoryBar');
        const algoGrid = document.getElementById('igAlgoGrid');
        if (algoCatBar && algoGrid) {
            algoCatBar.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-algo-cat]');
                if (!btn) return;
                const cat = btn.getAttribute('data-algo-cat');
                Array.from(algoCatBar.querySelectorAll('[data-algo-cat]')).forEach((b) => {
                    b.setAttribute('data-active', b === btn ? 'true' : 'false');
                });
                Array.from(algoGrid.querySelectorAll('[data-algo]')).forEach((card) => {
                    const match = cat === 'all' || card.getAttribute('data-cat') === cat;
                    card.classList.toggle('hidden', !match);
                });
            });

            algoGrid.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-algo]');
                if (!btn) return;
                selectAlgorithm(btn.getAttribute('data-algo'));
            });
        }

        function selectAlgorithm(algoId) {
            if (!ALGO_META[algoId]) return;
            genState.algo = algoId;
            if (algoGrid) {
                Array.from(algoGrid.querySelectorAll('[data-algo]')).forEach((b) => {
                    b.setAttribute('data-active', b.getAttribute('data-algo') === algoId ? 'true' : 'false');
                });
            }
            const meta = ALGO_META[algoId];
            const descEl = document.getElementById('igCurrentAlgoDesc');
            if (descEl) descEl.textContent = meta.desc;
            for (let i = 0; i < 4; i++) {
                const lbl = document.getElementById('igParam' + (i + 1) + 'Label');
                const inp = document.getElementById('igParam' + (i + 1));
                const valEl = document.getElementById('igParam' + (i + 1) + 'Val');
                if (lbl) lbl.textContent = meta.labels[i];
                if (inp) {
                    inp.value = meta.defaults[i];
                    genState['param' + (i + 1)] = meta.defaults[i];
                }
                if (valEl) valEl.textContent = String(meta.defaults[i]);
            }
            scheduleRender();
        }

        // Bind Generator Parameters
        const resPreset = document.getElementById('igResPreset');
        const wInput = document.getElementById('igCanvasWidth');
        const hInput = document.getElementById('igCanvasHeight');
        if (resPreset && wInput && hInput) {
            resPreset.addEventListener('change', () => {
                if (resPreset.value === 'custom') return;
                const parts = resPreset.value.split('x');
                genState.width = parseInt(parts[0], 10) || 1200;
                genState.height = parseInt(parts[1], 10) || 630;
                wInput.value = genState.width;
                hInput.value = genState.height;
                scheduleRender();
            });
            const onDimChange = () => {
                genState.width = Math.max(16, Math.min(4096, parseInt(wInput.value, 10) || 1200));
                genState.height = Math.max(16, Math.min(4096, parseInt(hInput.value, 10) || 630));
                resPreset.value = 'custom';
                scheduleRender();
            };
            wInput.addEventListener('change', onDimChange);
            hInput.addEventListener('change', onDimChange);
        }

        const seedInput = document.getElementById('igSeedInput');
        if (seedInput) {
            seedInput.addEventListener('input', () => {
                genState.seed = seedInput.value || 'windblog';
                scheduleRender();
            });
        }

        function rerollSeed() {
            const newSeed = 'wb-' + Math.random().toString(36).slice(2, 8) + '-' + (Date.now() % 10000);
            genState.seed = newSeed;
            if (seedInput) seedInput.value = newSeed;
            scheduleRender();
        }

        function randomizePalette() {
            const baseHue = Math.floor(Math.random() * 360);
            const harmonyOffsets = [0, 28, 75, 145, 210];
            genState.colorStops = harmonyOffsets.map((off, idx) => {
                const l = 0.08 + idx * 0.21;
                const s = 0.65 + (idx === 2 || idx === 3 ? 0.25 : 0);
                const rgb = hslToRgb(baseHue + off, s, l);
                return rgbToHex(rgb[0], rgb[1], rgb[2]);
            });
            syncColorStopInputs();
            scheduleRender();
        }

        ['igRerollSeedBtn', 'igRandomSeedBtn'].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.addEventListener('click', rerollSeed);
        });
        ['igRandomHarmonyBtn', 'igRandomPaletteBtn'].forEach((id) => {
            const el = document.getElementById(id);
            if (el) el.addEventListener('click', randomizePalette);
        });

        const revPalBtn = document.getElementById('igReversePaletteBtn');
        if (revPalBtn) {
            revPalBtn.addEventListener('click', () => {
                genState.colorStops.reverse();
                syncColorStopInputs();
                scheduleRender();
            });
        }

        const randAlgoBtn = document.getElementById('igRandomAlgoBtn');
        const surpriseBtn = document.getElementById('igSurpriseMeBtn');
        const algoKeys = Object.keys(ALGO_META);
        if (randAlgoBtn) {
            randAlgoBtn.addEventListener('click', () => {
                const pick = algoKeys[Math.floor(Math.random() * algoKeys.length)];
                selectAlgorithm(pick);
            });
        }
        if (surpriseBtn) {
            surpriseBtn.addEventListener('click', () => {
                const pick = algoKeys[Math.floor(Math.random() * algoKeys.length)];
                selectAlgorithm(pick);
                randomizePalette();
                rerollSeed();
            });
        }

        for (let i = 1; i <= 4; i++) {
            const inp = document.getElementById('igParam' + i);
            const valEl = document.getElementById('igParam' + i + 'Val');
            if (inp) {
                inp.addEventListener('input', () => {
                    genState['param' + i] = Number(inp.value);
                    if (valEl) valEl.textContent = inp.value;
                    scheduleRender();
                });
            }
        }

        const variantSel = document.getElementById('igAlgoVariant');
        if (variantSel) {
            variantSel.addEventListener('change', () => {
                genState.variant = Number(variantSel.value) || 0;
                scheduleRender();
            });
        }

        const grainInp = document.getElementById('igGenGrain');
        const grainVal = document.getElementById('igGenGrainVal');
        if (grainInp) {
            grainInp.addEventListener('input', () => {
                genState.grain = Number(grainInp.value) || 0;
                if (grainVal) grainVal.textContent = grainInp.value + '%';
                scheduleRender();
            });
        }

        const vigInp = document.getElementById('igGenVignette');
        const vigVal = document.getElementById('igGenVignetteVal');
        if (vigInp) {
            vigInp.addEventListener('input', () => {
                genState.vignette = Number(vigInp.value) || 0;
                if (vigVal) vigVal.textContent = vigInp.value + '%';
                scheduleRender();
            });
        }

        for (let i = 0; i < 5; i++) {
            const stopEl = document.getElementById('igColorStop' + i);
            if (stopEl) {
                stopEl.addEventListener('input', () => {
                    genState.colorStops[i] = stopEl.value;
                    scheduleRender();
                });
            }
        }

        // Overlay Typography bindings
        const bindGenInput = (id, key) => {
            const el = document.getElementById(id);
            if (!el) return;
            el.addEventListener('input', () => {
                genState[key] = el.type === 'range' ? Number(el.value) : el.value;
                scheduleRender();
            });
        };
        bindGenInput('igOverlayTitle', 'overlayTitle');
        bindGenInput('igOverlaySubtitle', 'overlaySubtitle');
        bindGenInput('igOverlayBadge', 'overlayBadge');
        bindGenInput('igOverlayLayout', 'overlayLayout');
        bindGenInput('igOverlayFontScale', 'overlayFontScale');
        bindGenInput('igOverlayBackdropDim', 'overlayBackdropDim');

        // =====================================================================
        // MODE 2: IMAGE PROCESSOR CONTROLS & PRESETS
        // =====================================================================
        const procSubTabs = document.getElementById('igProcSubTabs');
        const procTabIds = {
            color: 'igProcTabColor',
            geometry: 'igProcTabGeometry',
            kernel: 'igProcTabKernel',
            dither: 'igProcTabDither',
            fx: 'igProcTabFx',
            watermark: 'igProcTabWatermark'
        };
        if (procSubTabs) {
            procSubTabs.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-proc-tab]');
                if (!btn) return;
                const target = btn.getAttribute('data-proc-tab');
                Array.from(procSubTabs.querySelectorAll('[data-proc-tab]')).forEach((b) => {
                    b.setAttribute('data-active', b === btn ? 'true' : 'false');
                });
                Object.keys(procTabIds).forEach((k) => {
                    const panel = document.getElementById(procTabIds[k]);
                    if (panel) panel.classList.toggle('hidden', k !== target);
                });
            });
        }

        function syncProcessorInputsFromState() {
            const setVal = (id, v, lblId, fmt) => {
                const el = document.getElementById(id);
                if (el) {
                    if (el.type === 'checkbox') el.checked = Boolean(v);
                    else el.value = String(v);
                }
                if (lblId) {
                    const l = document.getElementById(lblId);
                    if (l) l.textContent = fmt ? fmt(v) : String(v);
                }
            };
            setVal('igProcBrightness', procState.brightness, 'igValBrightness');
            setVal('igProcContrast', procState.contrast, 'igValContrast');
            setVal('igProcSaturation', procState.saturation, 'igValSaturation');
            setVal('igProcHue', procState.hue, 'igValHue', (v) => v + '°');
            setVal('igProcTemp', procState.temp, 'igValTemp');
            setVal('igProcTint', procState.tint, 'igValTint');
            setVal('igProcHighlights', procState.highlights, 'igValHighlights');
            setVal('igProcShadows', procState.shadows, 'igValShadows');
            setVal('igProcGamma', procState.gamma, 'igValGamma', (v) => (v / 100).toFixed(2));
            setVal('igProcSepia', procState.sepia, 'igValSepia', (v) => v + '%');
            setVal('igProcInvert', procState.invert);
            setVal('igProcSolarize', procState.solarize);
            setVal('igProcAutoLevels', procState.autoLevels);
            setVal('igProcRotateFine', procState.rotateFine, 'igValRotateFine', (v) => v + '°');
            setVal('igProcScalePct', procState.scalePct, 'igValScalePct', (v) => v + '%');
            setVal('igProcBorderRadius', procState.borderRadius, 'igValBorderRadius', (v) => v + '%');
            setVal('igProcPadding', procState.padding, 'igValPadding', (v) => v + 'px');
            setVal('igCropX', procState.cropX);
            setVal('igCropY', procState.cropY);
            setVal('igCropW', procState.cropW);
            setVal('igCropH', procState.cropH);
            setVal('igProcBlur', procState.blur, 'igValBlur', (v) => v + 'px');
            setVal('igProcSharpen', procState.sharpen, 'igValSharpen', (v) => v + '%');
            setVal('igProcKernelPreset', procState.kernelPreset);
            setVal('igProcPixelate', procState.pixelate, 'igValPixelate', (v) => v > 1 ? v + 'px' : '1px (关闭)');
            setVal('igProcPosterize', procState.posterize, 'igValPosterize', (v) => v >= 32 ? '256 (无压缩)' : v + ' 阶');
            setVal('igProcDitherAlgo', procState.ditherAlgo);
            setVal('igProcDitherPalette', procState.ditherPalette);
            setVal('igProcChroma', procState.chroma, 'igValChroma', (v) => v + 'px');
            setVal('igProcGlitch', procState.glitch, 'igValGlitch', (v) => v + '%');
            setVal('igProcNoise', procState.noise, 'igValNoise', (v) => v + '%');
            setVal('igProcVignette', procState.vignette, 'igValVignette', (v) => v + '%');
            setVal('igProcDuotoneEnable', procState.duotoneEnable);
            setVal('igProcDuotoneDark', procState.duotoneDark);
            setVal('igProcDuotoneLight', procState.duotoneLight);
            setVal('igChromaKeyEnable', procState.chromaKeyEnable);
            setVal('igChromaKeyColor', procState.chromaKeyColor);
            setVal('igChromaKeyTol', procState.chromaKeyTol, 'igValChromaTol');
            setVal('igChromaKeyFeather', procState.chromaKeyFeather, 'igValChromaFeather');
            setVal('igWatermarkText', procState.watermarkText);
            setVal('igWatermarkMode', procState.watermarkMode);
            setVal('igWatermarkOpacity', procState.watermarkOpacity, 'igValWmOpacity', (v) => v + '%');
            setVal('igWatermarkSize', procState.watermarkSize, 'igValWmSize');
            setVal('igWatermarkColor', procState.watermarkColor);
        }

        const bindProcControl = (id, key, lblId, fmt) => {
            const el = document.getElementById(id);
            if (!el) return;
            el.addEventListener('change', () => pushProcHistory());
            el.addEventListener('input', () => {
                if (el.type === 'checkbox') procState[key] = el.checked;
                else if (el.type === 'range' || el.type === 'number') procState[key] = Number(el.value);
                else procState[key] = el.value;
                if (lblId) {
                    const l = document.getElementById(lblId);
                    if (l) l.textContent = fmt ? fmt(procState[key]) : String(procState[key]);
                }
                scheduleRender();
            });
        };

        bindProcControl('igProcBrightness', 'brightness', 'igValBrightness');
        bindProcControl('igProcContrast', 'contrast', 'igValContrast');
        bindProcControl('igProcSaturation', 'saturation', 'igValSaturation');
        bindProcControl('igProcHue', 'hue', 'igValHue', (v) => v + '°');
        bindProcControl('igProcTemp', 'temp', 'igValTemp');
        bindProcControl('igProcTint', 'tint', 'igValTint');
        bindProcControl('igProcHighlights', 'highlights', 'igValHighlights');
        bindProcControl('igProcShadows', 'shadows', 'igValShadows');
        bindProcControl('igProcGamma', 'gamma', 'igValGamma', (v) => (v / 100).toFixed(2));
        bindProcControl('igProcSepia', 'sepia', 'igValSepia', (v) => v + '%');
        bindProcControl('igProcInvert', 'invert');
        bindProcControl('igProcSolarize', 'solarize');
        bindProcControl('igProcAutoLevels', 'autoLevels');
        bindProcControl('igProcRotateFine', 'rotateFine', 'igValRotateFine', (v) => v + '°');
        bindProcControl('igProcScalePct', 'scalePct', 'igValScalePct', (v) => v + '%');
        bindProcControl('igProcBorderRadius', 'borderRadius', 'igValBorderRadius', (v) => v + '%');
        bindProcControl('igProcPadding', 'padding', 'igValPadding', (v) => v + 'px');
        bindProcControl('igCropX', 'cropX');
        bindProcControl('igCropY', 'cropY');
        bindProcControl('igCropW', 'cropW');
        bindProcControl('igCropH', 'cropH');
        bindProcControl('igProcBlur', 'blur', 'igValBlur', (v) => v + 'px');
        bindProcControl('igProcSharpen', 'sharpen', 'igValSharpen', (v) => v + '%');
        bindProcControl('igProcKernelPreset', 'kernelPreset');
        bindProcControl('igKernelNormalize', 'kernelNormalize');
        bindProcControl('igProcPixelate', 'pixelate', 'igValPixelate', (v) => v > 1 ? v + 'px' : '1px (关闭)');
        bindProcControl('igProcPosterize', 'posterize', 'igValPosterize', (v) => v >= 32 ? '256 (无压缩)' : v + ' 阶');
        bindProcControl('igProcDitherAlgo', 'ditherAlgo');
        bindProcControl('igProcDitherPalette', 'ditherPalette');
        bindProcControl('igProcChroma', 'chroma', 'igValChroma', (v) => v + 'px');
        bindProcControl('igProcGlitch', 'glitch', 'igValGlitch', (v) => v + '%');
        bindProcControl('igProcNoise', 'noise', 'igValNoise', (v) => v + '%');
        bindProcControl('igProcVignette', 'vignette', 'igValVignette', (v) => v + '%');
        bindProcControl('igProcDuotoneEnable', 'duotoneEnable');
        bindProcControl('igProcDuotoneDark', 'duotoneDark');
        bindProcControl('igProcDuotoneLight', 'duotoneLight');
        bindProcControl('igChromaKeyEnable', 'chromaKeyEnable');
        bindProcControl('igChromaKeyColor', 'chromaKeyColor');
        bindProcControl('igChromaKeyTol', 'chromaKeyTol', 'igValChromaTol');
        bindProcControl('igChromaKeyFeather', 'chromaKeyFeather', 'igValChromaFeather');
        bindProcControl('igWatermarkText', 'watermarkText');
        bindProcControl('igWatermarkMode', 'watermarkMode');
        bindProcControl('igWatermarkOpacity', 'watermarkOpacity', 'igValWmOpacity', (v) => v + '%');
        bindProcControl('igWatermarkSize', 'watermarkSize', 'igValWmSize');
        bindProcControl('igWatermarkColor', 'watermarkColor');

        // Custom 3x3 Kernel Matrix cells
        Array.from(app.querySelectorAll('.ig-kernel-cell')).forEach((cell) => {
            cell.addEventListener('input', () => {
                const idx = Number(cell.getAttribute('data-kidx'));
                procState.customKernel[idx] = parseFloat(cell.value) || 0;
                procState.kernelPreset = 'custom';
                const sel = document.getElementById('igProcKernelPreset');
                if (sel) sel.value = 'custom';
                scheduleRender();
            });
        });

        // Geometry Quick Buttons
        const bindClick = (id, fn) => {
            const el = document.getElementById(id);
            if (el) el.addEventListener('click', fn);
        };
        bindClick('igRotLeftBtn', () => { pushProcHistory(); procState.rot90 = (procState.rot90 + 3) % 4; scheduleRender(); });
        bindClick('igRotRightBtn', () => { pushProcHistory(); procState.rot90 = (procState.rot90 + 1) % 4; scheduleRender(); });
        bindClick('igFlipHBtn', () => { pushProcHistory(); procState.flipH = !procState.flipH; scheduleRender(); });
        bindClick('igFlipVBtn', () => { pushProcHistory(); procState.flipV = !procState.flipV; scheduleRender(); });
        bindClick('igCropResetBtn', () => {
            pushProcHistory();
            procState.cropX = 0; procState.cropY = 0; procState.cropW = 100; procState.cropH = 100;
            syncProcessorInputsFromState();
            scheduleRender();
        });
        bindClick('igCropAspect11Btn', () => {
            pushProcHistory();
            const w = processorSourceCanvas.width || 1200;
            const h = processorSourceCanvas.height || 630;
            if (w > h) {
                const wp = Math.round((h / w) * 100);
                procState.cropX = Math.round((100 - wp) / 2);
                procState.cropY = 0;
                procState.cropW = wp;
                procState.cropH = 100;
            } else {
                const hp = Math.round((w / h) * 100);
                procState.cropX = 0;
                procState.cropY = Math.round((100 - hp) / 2);
                procState.cropW = 100;
                procState.cropH = hp;
            }
            syncProcessorInputsFromState();
            scheduleRender();
        });
        bindClick('igCropAspect169Btn', () => {
            pushProcHistory();
            procState.cropX = 0; procState.cropY = 5; procState.cropW = 100; procState.cropH = 90;
            syncProcessorInputsFromState();
            scheduleRender();
        });

        // Undo & Reset
        bindClick('igProcUndoBtn', () => {
            if (procHistory.length === 0) return;
            procState = JSON.parse(procHistory.pop());
            syncProcessorInputsFromState();
            scheduleRender();
        });
        bindClick('igProcResetBtn', () => {
            pushProcHistory();
            procState = defaultProcState();
            syncProcessorInputsFromState();
            scheduleRender();
        });

        // One-Click Style Presets
        const presetBar = document.getElementById('igQuickFilterPresets');
        if (presetBar) {
            presetBar.addEventListener('click', (e) => {
                const btn = e.target.closest('[data-proc-preset]');
                if (!btn) return;
                pushProcHistory();
                const p = btn.getAttribute('data-proc-preset');
                const next = defaultProcState();
                // Keep existing geometry/watermark
                next.rot90 = procState.rot90;
                next.watermarkText = procState.watermarkText;
                if (p === 'kodak-film') {
                    next.contrast = 18; next.saturation = 16; next.temp = 24; next.sepia = 18; next.noise = 16; next.vignette = 28;
                } else if (p === 'cyber-neon') {
                    next.contrast = 28; next.saturation = 45; next.hue = -18; next.chroma = 7; next.vignette = 30;
                } else if (p === 'noir-bw') {
                    next.saturation = -100; next.contrast = 52; next.noise = 28; next.vignette = 45;
                } else if (p === 'gameboy-8bit') {
                    next.pixelate = 4; next.ditherAlgo = 'bayer-4x4'; next.ditherPalette = 'gameboy';
                } else if (p === 'mac-atkinson') {
                    next.contrast = 20; next.ditherAlgo = 'atkinson'; next.ditherPalette = '1bit-bw';
                } else if (p === 'sobel-neon') {
                    next.kernelPreset = 'sobel-color'; next.saturation = 40; next.contrast = 25;
                } else if (p === 'vhs-glitch') {
                    next.chroma = 9; next.glitch = 48; next.noise = 22; next.saturation = 20;
                } else if (p === 'duotone-spotify') {
                    next.duotoneEnable = true; next.duotoneDark = '#1e1b4b'; next.duotoneLight = '#10b981'; next.contrast = 15;
                }
                procState = next;
                syncProcessorInputsFromState();
                scheduleRender();
            });
        }

        // Compare Split Slider
        const compareSplit = document.getElementById('igCompareSplit');
        const compareLabel = document.getElementById('igCompareSplitLabel');
        if (compareSplit) {
            compareSplit.addEventListener('input', () => {
                const v = Number(compareSplit.value) || 0;
                if (compareLabel) compareLabel.textContent = v === 0 ? '100% 精修' : ('原图 ' + v + '%');
                scheduleRender();
            });
        }

        // Load Image into Processor Source
        function loadFileIntoProcessor(file) {
            if (!file) return;
            const url = URL.createObjectURL(file);
            const img = new Image();
            img.onload = () => {
                URL.revokeObjectURL(url);
                hasCustomUploadedSource = true;
                processorSourceCanvas.width = img.naturalWidth || img.width || 800;
                processorSourceCanvas.height = img.naturalHeight || img.height || 600;
                processorSourceCanvas.getContext('2d').drawImage(img, 0, 0);
                const metaEl = document.getElementById('igProcSourceMeta');
                if (metaEl) {
                    metaEl.textContent = '已载入本地图像：' + file.name + ' (' + processorSourceCanvas.width + '×' + processorSourceCanvas.height + ' · ' + formatBytes(file.size) + ')';
                }
                switchMode('process');
            };
            img.src = url;
        }

        const procFileInput = document.getElementById('igProcFileInput');
        const procDropzone = document.getElementById('igProcDropzone');
        if (procFileInput) {
            procFileInput.addEventListener('change', () => {
                if (procFileInput.files && procFileInput.files[0]) {
                    loadFileIntoProcessor(procFileInput.files[0]);
                }
            });
        }
        if (procDropzone) {
            ['dragenter', 'dragover'].forEach((ev) => procDropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                procDropzone.classList.add('ig-dragover');
            }));
            ['dragleave', 'drop'].forEach((ev) => procDropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                procDropzone.classList.remove('ig-dragover');
            }));
            procDropzone.addEventListener('drop', (e) => {
                const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
                if (f) loadFileIntoProcessor(f);
            });
        }

        // Send Generated Image to Processor
        ['igUseGeneratedSourceBtn', 'igSendToProcessorBtn'].forEach((id) => {
            bindClick(id, () => {
                hasCustomUploadedSource = false;
                processorSourceCanvas.width = generatedBufferCanvas.width;
                processorSourceCanvas.height = generatedBufferCanvas.height;
                processorSourceCanvas.getContext('2d').drawImage(generatedBufferCanvas, 0, 0);
                const metaEl = document.getElementById('igProcSourceMeta');
                if (metaEl) {
                    metaEl.textContent = '当前源：程序化画布实时源 (' + processorSourceCanvas.width + '×' + processorSourceCanvas.height + ')';
                }
                switchMode('process');
            });
        });

        // Clipboard Paste Handler
        const onPaste = (e) => {
            if (!e.clipboardData || !e.clipboardData.items) return;
            for (let i = 0; i < e.clipboardData.items.length; i++) {
                const item = e.clipboardData.items[i];
                if (item.type && item.type.indexOf('image/') === 0) {
                    const f = item.getAsFile();
                    if (f) loadFileIntoProcessor(f);
                    break;
                }
            }
        };
        window.addEventListener('paste', onPaste);

        // =====================================================================
        // MODE 3: LOCAL TRANSCODER & MULTI-FILE BATCH QUEUE
        // =====================================================================
        async function transcodeCanvas(sourceCanvas, format, qualityPct, scaleOverride) {
            let targetCanvas = sourceCanvas;
            if (scaleOverride && scaleOverride !== 'original' && scaleOverride !== 'ico-multi') {
                let tw = sourceCanvas.width, th = sourceCanvas.height;
                if (scaleOverride === 'half') {
                    tw = Math.max(8, Math.round(tw / 2));
                    th = Math.max(8, Math.round(th / 2));
                } else if (scaleOverride.includes('x')) {
                    const p = scaleOverride.split('x');
                    tw = parseInt(p[0], 10) || 64;
                    th = parseInt(p[1], 10) || 64;
                }
                const scaled = document.createElement('canvas');
                scaled.width = tw;
                scaled.height = th;
                const sctx = scaled.getContext('2d');
                sctx.imageSmoothingEnabled = true;
                sctx.imageSmoothingQuality = 'high';
                sctx.drawImage(sourceCanvas, 0, 0, tw, th);
                targetCanvas = scaled;
            }

            if (format === 'image/bmp') {
                return encodeCanvasToBmpBlob(targetCanvas);
            }
            if (format === 'image/x-icon') {
                const icoSizes = scaleOverride === 'ico-multi'
                    ? [16, 32, 48, 64]
                    : [Math.min(256, Math.max(16, targetCanvas.width))];
                return encodeCanvasToIcoBlob(targetCanvas, icoSizes);
            }
            if (format === 'image/svg+xml') {
                return encodeCanvasToSvgBlob(targetCanvas);
            }

            // If JPEG, composite over dark/solid background so alpha isn't black-artefacted
            let encCanvas = targetCanvas;
            if (format === 'image/jpeg') {
                encCanvas = document.createElement('canvas');
                encCanvas.width = targetCanvas.width;
                encCanvas.height = targetCanvas.height;
                const jctx = encCanvas.getContext('2d');
                jctx.fillStyle = '#090d16';
                jctx.fillRect(0, 0, encCanvas.width, encCanvas.height);
                jctx.drawImage(targetCanvas, 0, 0);
            }

            const q = Math.max(0.1, Math.min(1.0, (Number(qualityPct) || 88) / 100));
            const blob = await new Promise((resolve) => encCanvas.toBlob(resolve, format, q));
            if (blob) return blob;
            return new Promise((resolve) => encCanvas.toBlob(resolve, 'image/png'));
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

        async function updateCurrentTranscodePreview() {
            const fmtEl = document.getElementById('igExportFormat');
            const qualEl = document.getElementById('igExportQuality');
            const scaleEl = document.getElementById('igExportScaleOverride');
            const badgeEl = document.getElementById('igTranscodeSizeEstimate');
            const dataOut = document.getElementById('igDataUrlOutput');
            if (!fmtEl) return;

            const fmt = fmtEl.value;
            const qual = qualEl ? Number(qualEl.value) : 88;
            const scale = scaleEl ? scaleEl.value : 'original';

            const blob = await transcodeCanvas(mainCanvas, fmt, qual, scale);
            if (!blob) return;

            const ext = getExtensionForMime(blob.type || fmt);
            if (badgeEl) {
                badgeEl.textContent = ext.toUpperCase() + ' · 编码后大小: ' + formatBytes(blob.size) + ' (已剥离 EXIF)';
            }
            if (dataOut) {
                const reader = new FileReader();
                reader.onloadend = () => {
                    dataOut.value = String(reader.result || '');
                };
                reader.readAsDataURL(blob);
            }
        }

        const exportFmtEl = document.getElementById('igExportFormat');
        const exportQualEl = document.getElementById('igExportQuality');
        const exportQualVal = document.getElementById('igExportQualityVal');
        const exportScaleEl = document.getElementById('igExportScaleOverride');
        if (exportFmtEl) exportFmtEl.addEventListener('change', updateCurrentTranscodePreview);
        if (exportScaleEl) exportScaleEl.addEventListener('change', updateCurrentTranscodePreview);
        if (exportQualEl) {
            exportQualEl.addEventListener('input', () => {
                if (exportQualVal) exportQualVal.textContent = exportQualEl.value + '%';
                updateCurrentTranscodePreview();
            });
        }

        async function triggerCanvasDownload(defaultFormat) {
            const fmtEl = document.getElementById('igExportFormat');
            const qualEl = document.getElementById('igExportQuality');
            const scaleEl = document.getElementById('igExportScaleOverride');
            const fmt = defaultFormat || (fmtEl ? fmtEl.value : 'image/png');
            const qual = qualEl ? Number(qualEl.value) : 90;
            const scale = scaleEl ? scaleEl.value : 'original';
            const blob = await transcodeCanvas(mainCanvas, fmt, qual, scale);
            if (!blob) return;
            const ext = getExtensionForMime(blob.type || fmt);
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'windblog-' + genState.algo + '-' + Date.now().toString().slice(-5) + '.' + ext;
            document.body.appendChild(a);
            a.click();
            a.remove();
            setTimeout(() => URL.revokeObjectURL(url), 3000);
        }

        bindClick('igExportDownloadBtn', () => triggerCanvasDownload());
        bindClick('igQuickDownloadBtn', () => triggerCanvasDownload('image/png'));

        function flashBtnText(btnId, msg) {
            const btn = document.getElementById(btnId);
            if (!btn) return;
            const prev = btn.textContent;
            btn.textContent = msg;
            setTimeout(() => { btn.textContent = prev; }, 1400);
        }

        bindClick('igCopyDataUrlBtn', async () => {
            await updateCurrentTranscodePreview();
            const out = document.getElementById('igDataUrlOutput');
            if (out && out.value && navigator.clipboard) {
                navigator.clipboard.writeText(out.value);
                flashBtnText('igCopyDataUrlBtn', '✓ 已复制 DataURL');
            }
        });
        bindClick('igCopyHtmlTagBtn', async () => {
            await updateCurrentTranscodePreview();
            const out = document.getElementById('igDataUrlOutput');
            if (out && out.value && navigator.clipboard) {
                const tag = '<img src="' + out.value + '" width="' + mainCanvas.width + '" height="' + mainCanvas.height + '" alt="Generated Image" loading="lazy" />';
                navigator.clipboard.writeText(tag);
                flashBtnText('igCopyHtmlTagBtn', '✓ 已复制 <img>');
            }
        });
        bindClick('igCopyCssBgBtn', async () => {
            await updateCurrentTranscodePreview();
            const out = document.getElementById('igDataUrlOutput');
            if (out && out.value && navigator.clipboard) {
                const css = 'background-image: url("' + out.value + '");\nbackground-size: cover;\nbackground-position: center;';
                navigator.clipboard.writeText(css);
                flashBtnText('igCopyCssBgBtn', '✓ 已复制 CSS');
            }
        });

        bindClick('igCopyClipboardBtn', () => {
            if (!navigator.clipboard || !window.ClipboardItem) return;
            mainCanvas.toBlob((blob) => {
                if (!blob) return;
                navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]).then(() => {
                    flashBtnText('igCopyClipboardBtn', '✓ 已复制到剪贴板');
                }).catch(() => {});
            }, 'image/png');
        });

        // Batch Transcoder Queue
        const batchQueueEl = document.getElementById('igBatchQueueList');
        const batchFormatEl = document.getElementById('igBatchFormat');
        const batchQualEl = document.getElementById('igBatchQuality');
        const batchQualLbl = document.getElementById('igBatchQualityLabel');
        const batchMaxDimEl = document.getElementById('igBatchMaxDim');

        async function processBatchEntry(entry) {
            const targetMime = batchFormatEl ? batchFormatEl.value : 'image/webp';
            const qual = batchQualEl ? Number(batchQualEl.value) : 85;
            const maxEdge = batchMaxDimEl ? Number(batchMaxDimEl.value) : 0;

            let workW = entry.sourceCanvas.width;
            let workH = entry.sourceCanvas.height;
            if (maxEdge > 0 && Math.max(workW, workH) > maxEdge) {
                const ratio = maxEdge / Math.max(workW, workH);
                workW = Math.max(1, Math.round(workW * ratio));
                workH = Math.max(1, Math.round(workH * ratio));
            }
            const c = document.createElement('canvas');
            c.width = workW;
            c.height = workH;
            const cctx = c.getContext('2d');
            cctx.imageSmoothingEnabled = true;
            cctx.imageSmoothingQuality = 'high';
            cctx.drawImage(entry.sourceCanvas, 0, 0, workW, workH);

            const blob = await transcodeCanvas(c, targetMime, qual, 'original');
            if (entry.blobUrl) URL.revokeObjectURL(entry.blobUrl);
            entry.blob = blob;
            entry.blobUrl = URL.createObjectURL(blob);
            objectUrls.push(entry.blobUrl);
            entry.outWidth = workW;
            entry.outHeight = workH;
            entry.ext = getExtensionForMime(blob.type || targetMime);
            renderBatchQueueDom();
        }

        function renderBatchQueueDom() {
            if (!batchQueueEl) return;
            batchQueueEl.textContent = '';
            if (batchItems.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'text-center py-4 text-xs font-mono text-meta';
                empty.textContent = '队列暂无文件，拖入任意图片或点击「将右侧画布加入队列」即可体验毫秒级本地转码。';
                batchQueueEl.appendChild(empty);
                return;
            }

            batchItems.forEach((item) => {
                const row = document.createElement('div');
                row.className = 'flex flex-wrap items-center justify-between gap-3 p-3 rounded-xl bg-input-bg/70 border border-border';

                const left = document.createElement('div');
                left.className = 'min-w-0 flex-1';

                const title = document.createElement('div');
                title.className = 'text-xs font-bold text-main truncate';
                const baseName = item.name.replace(/\.[^.]+$/, '');
                const outName = baseName + '-converted.' + (item.ext || 'webp');
                title.textContent = outName;

                const meta = document.createElement('div');
                meta.className = 'text-[11px] font-mono text-meta mt-0.5';
                const outSize = item.blob ? item.blob.size : 0;
                const diffPct = item.origSize > 0
                    ? Math.round(((outSize - item.origSize) / item.origSize) * 100)
                    : 0;
                const diffStr = diffPct <= 0 ? ('节省 ' + Math.abs(diffPct) + '%') : ('+' + diffPct + '%');
                meta.textContent = item.outWidth + '×' + item.outHeight + ' · 原始 ' + formatBytes(item.origSize) + ' → 转码后 ' + formatBytes(outSize) + ' (' + diffStr + ')';

                left.appendChild(title);
                left.appendChild(meta);

                const dlLink = document.createElement('a');
                dlLink.className = 'px-3 py-1.5 rounded-lg bg-accent/15 border border-accent/30 text-accent text-xs font-mono font-bold hover:bg-accent/25 transition-colors shrink-0';
                dlLink.href = item.blobUrl || '#';
                dlLink.download = outName;
                dlLink.textContent = '下载 .' + (item.ext || 'webp').toUpperCase();

                row.appendChild(left);
                row.appendChild(dlLink);
                batchQueueEl.appendChild(row);
            });
        }

        async function reprocessAllBatch() {
            for (let i = 0; i < batchItems.length; i++) {
                await processBatchEntry(batchItems[i]);
            }
        }

        if (batchFormatEl) batchFormatEl.addEventListener('change', reprocessAllBatch);
        if (batchMaxDimEl) batchMaxDimEl.addEventListener('change', reprocessAllBatch);
        if (batchQualEl) {
            batchQualEl.addEventListener('input', () => {
                if (batchQualLbl) batchQualLbl.textContent = batchQualEl.value + '%';
            });
            batchQualEl.addEventListener('change', reprocessAllBatch);
        }

        function addFilesToBatch(files) {
            Array.from(files || []).forEach((file) => {
                const url = URL.createObjectURL(file);
                const img = new Image();
                img.onload = async () => {
                    URL.revokeObjectURL(url);
                    const sc = document.createElement('canvas');
                    sc.width = img.naturalWidth || img.width || 512;
                    sc.height = img.naturalHeight || img.height || 512;
                    sc.getContext('2d').drawImage(img, 0, 0);
                    const entry = {
                        name: file.name || 'image.png',
                        origSize: file.size || 0,
                        sourceCanvas: sc,
                        blob: null,
                        blobUrl: null,
                        outWidth: sc.width,
                        outHeight: sc.height,
                        ext: 'webp'
                    };
                    batchItems.unshift(entry);
                    await processBatchEntry(entry);
                };
                img.src = url;
            });
        }

        async function addCurrentCanvasToBatch() {
            const sc = document.createElement('canvas');
            sc.width = mainCanvas.width;
            sc.height = mainCanvas.height;
            sc.getContext('2d').drawImage(mainCanvas, 0, 0);
            const pngBlob = await new Promise((r) => sc.toBlob(r, 'image/png'));
            const entry = {
                name: 'canvas-' + genState.algo + '-' + Date.now().toString().slice(-4) + '.png',
                origSize: pngBlob ? pngBlob.size : sc.width * sc.height * 3,
                sourceCanvas: sc,
                blob: null,
                blobUrl: null,
                outWidth: sc.width,
                outHeight: sc.height,
                ext: 'webp'
            };
            batchItems.unshift(entry);
            await processBatchEntry(entry);
            switchMode('transcode');
        }

        bindClick('igBatchAddCanvasBtn', addCurrentCanvasToBatch);
        bindClick('igSendToTranscoderBtn', addCurrentCanvasToBatch);
        bindClick('igBatchClearBtn', () => {
            batchItems.forEach((it) => { if (it.blobUrl) URL.revokeObjectURL(it.blobUrl); });
            batchItems = [];
            renderBatchQueueDom();
        });
        bindClick('igBatchDownloadAllBtn', () => {
            batchItems.forEach((it, idx) => {
                if (!it.blobUrl) return;
                setTimeout(() => {
                    const a = document.createElement('a');
                    a.href = it.blobUrl;
                    a.download = it.name.replace(/\.[^.]+$/, '') + '-converted.' + it.ext;
                    document.body.appendChild(a);
                    a.click();
                    a.remove();
                }, idx * 160);
            });
        });

        const batchFileInput = document.getElementById('igBatchFileInput');
        const batchDropzone = document.getElementById('igBatchDropzone');
        if (batchFileInput) {
            batchFileInput.addEventListener('change', () => {
                if (batchFileInput.files && batchFileInput.files.length) {
                    addFilesToBatch(batchFileInput.files);
                }
            });
        }
        if (batchDropzone) {
            ['dragenter', 'dragover'].forEach((ev) => batchDropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                batchDropzone.classList.add('ig-dragover');
            }));
            ['dragleave', 'drop'].forEach((ev) => batchDropzone.addEventListener(ev, (e) => {
                e.preventDefault();
                batchDropzone.classList.remove('ig-dragover');
            }));
            batchDropzone.addEventListener('drop', (e) => {
                if (e.dataTransfer && e.dataTransfer.files) {
                    addFilesToBatch(e.dataTransfer.files);
                }
            });
        }

        // =====================================================================
        // ZOOM TOGGLE & PIXEL EYEDROPPER INSPECTOR
        // =====================================================================
        const zoomBtn = document.getElementById('igToggleZoomBtn');
        if (zoomBtn && mainCanvas) {
            zoomBtn.addEventListener('click', () => {
                const isPixel = mainCanvas.classList.toggle('ig-zoom-pixel');
                const span = zoomBtn.querySelector('span');
                if (span) span.textContent = isPixel ? '1:1 像素' : '自适应';
            });
        }

        function inspectCanvasAt(clientX, clientY, pinToChromaKey) {
            const rect = mainCanvas.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return;
            const x = Math.max(0, Math.min(mainCanvas.width - 1, Math.floor(((clientX - rect.left) / rect.width) * mainCanvas.width)));
            const y = Math.max(0, Math.min(mainCanvas.height - 1, Math.floor(((clientY - rect.top) / rect.height) * mainCanvas.height)));
            const p = mainCanvas.getContext('2d').getImageData(x, y, 1, 1).data;
            const hex = rgbToHex(p[0], p[1], p[2]);
            const hsl = rgbToHsl(p[0], p[1], p[2]);
            currentInspectHex = hex;
            setSwatch(p[0], p[1], p[2]);

            const hexEl = document.getElementById('igInspectHex');
            const coordEl = document.getElementById('igInspectCoord');
            if (hexEl) hexEl.textContent = hex + ' · RGB(' + p[0] + ', ' + p[1] + ', ' + p[2] + ')';
            if (coordEl) coordEl.textContent = '坐标 (' + x + ', ' + y + ') · HSL(' + hsl[0] + '°, ' + hsl[1] + '%, ' + hsl[2] + '%)';

            if (pinToChromaKey) {
                if (navigator.clipboard) navigator.clipboard.writeText(hex).catch(() => {});
                if (activeMode === 'process' && procState.chromaKeyEnable) {
                    procState.chromaKeyColor = hex;
                    const ckInp = document.getElementById('igChromaKeyColor');
                    if (ckInp) ckInp.value = hex;
                    scheduleRender();
                }
            }
        }

        if (mainCanvas) {
            mainCanvas.addEventListener('mousemove', (e) => inspectCanvasAt(e.clientX, e.clientY, false));
            mainCanvas.addEventListener('click', (e) => inspectCanvasAt(e.clientX, e.clientY, true));
        }

        bindClick('igCopyHexBtn', () => {
            if (navigator.clipboard) {
                navigator.clipboard.writeText(currentInspectHex);
                flashBtnText('igCopyHexBtn', '已复制');
            }
        });

        // Initial swatch & first render
        setSwatch(124, 58, 237);
        syncColorStopInputs();
        performRender();

        activeCleanup = function () {
            window.removeEventListener('paste', onPaste);
            objectUrls.forEach((u) => URL.revokeObjectURL(u));
            objectUrls.length = 0;
        };
    }

    function unmountImageGenerator() {
        if (activeCleanup) {
            activeCleanup();
            activeCleanup = null;
        }
    }

    window.WindBlogImageGenerator = {
        mount: mountImageGenerator,
        unmount: unmountImageGenerator
    };
})();
