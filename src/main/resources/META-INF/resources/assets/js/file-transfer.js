(function () {
    'use strict';

    const assetVersionMeta = typeof document !== 'undefined'
        ? document.querySelector('meta[name="windblog-asset-version"]')
        : null;
    const assetVersion = assetVersionMeta && assetVersionMeta.content ? assetVersionMeta.content.trim() : '20261115';
    const WASM_URL = '/assets/vendor/file-transfer-wasm/file_transfer_ecc.wasm?v=' + encodeURIComponent(assetVersion);
    const QR_GEN_URL = '/assets/vendor/qrcode/qrcode.js?v=2.0.4';
    const QR_SCAN_URL = '/assets/vendor/qrcode/jsQR.js?v=1.4.0';

    const CHUNK_SIZE = 16384; // 16 KB per shard
    const MAGIC_BYTE = 0x57;  // 'W'
    const ENC_FRAME_TAG = 0xE1;
    const HEADER_SIZE = 17;

    const ECC_PROFILES = {
        off: { k: 16, m: 0, label: '关闭 (零冗余)' },
        std: { k: 16, m: 3, label: '标准 (16+3)' },
        high: { k: 12, m: 4, label: '高冗余 (12+4)' }
    };

    const PUBLIC_ICE_SERVERS = [
        { urls: 'stun:stun.qq.com:3478' },
        { urls: 'stun:stun.miwifi.com:3478' },
        { urls: 'stun:stun.l.google.com:19302' },
        { urls: 'stun:stun1.l.google.com:19302' }
    ];

    const SAS_EMOJIS = ['🔐', '🛡️', '⚡', '✨', '🌊', '🔥', '🚀', '🛰️', '🧭', '💎', '🦊', '🐳', '🍀', '🎯', '🔔', '🔑'];

    // --- Script / WASM Loaders ---
    const loadedScripts = new Map();
    function loadScript(src) {
        if (loadedScripts.has(src)) return loadedScripts.get(src);
        const p = new Promise((resolve, reject) => {
            const existing = document.querySelector(`script[src="${src}"]`);
            if (existing && existing.dataset.loaded === '1') {
                resolve();
                return;
            }
            const s = document.createElement('script');
            s.src = src;
            s.async = true;
            s.onload = () => {
                s.dataset.loaded = '1';
                resolve();
            };
            s.onerror = () => reject(new Error('脚本加载失败: ' + src));
            document.head.appendChild(s);
        });
        loadedScripts.set(src, p);
        return p;
    }

    // --- Pure JS CRC32 + GF(2^8) Fallback ---
    const jsCrcTable = (() => {
        const t = new Uint32Array(256);
        for (let i = 0; i < 256; i++) {
            let c = i;
            for (let j = 0; j < 8; j++) {
                c = (c & 1) ? (0xEDB88320 ^ (c >>> 1)) : (c >>> 1);
            }
            t[i] = c >>> 0;
        }
        return t;
    })();

    function jsCrc32(u8) {
        let crc = 0xFFFFFFFF;
        for (let i = 0; i < u8.length; i++) {
            crc = jsCrcTable[(crc ^ u8[i]) & 0xFF] ^ (crc >>> 8);
        }
        return (crc ^ 0xFFFFFFFF) >>> 0;
    }

    // --- Rust WASM ECC Engine ---
    let wasmEccPromise = null;
    function getEccEngine() {
        if (wasmEccPromise) return wasmEccPromise;
        wasmEccPromise = (async () => {
            try {
                let instance;
                if (typeof WebAssembly.instantiateStreaming === 'function') {
                    const resp = await fetch(WASM_URL);
                    if (!resp.ok) throw new Error('HTTP ' + resp.status);
                    try {
                        ({ instance } = await WebAssembly.instantiateStreaming(resp.clone(), {}));
                    } catch (_) {
                        const buf = await resp.arrayBuffer();
                        ({ instance } = await WebAssembly.instantiate(buf, {}));
                    }
                } else {
                    const resp = await fetch(WASM_URL);
                    if (!resp.ok) throw new Error('HTTP ' + resp.status);
                    const buf = await resp.arrayBuffer();
                    ({ instance } = await WebAssembly.instantiate(buf, {}));
                }

                const exp = instance.exports;
                const mem = () => new Uint8Array(exp.memory.buffer);

                return {
                    isWasm: true,
                    crc32(u8) {
                        if (!u8 || u8.length === 0) return 0;
                        const ptr = exp.alloc(u8.length);
                        mem().set(u8, ptr);
                        const res = exp.crc32(ptr, u8.length) >>> 0;
                        exp.dealloc(ptr, u8.length);
                        return res;
                    },
                    rsEncode(dataU8, k, m, shardLen) {
                        if (m === 0) return new Uint8Array(0);
                        const dataLen = k * shardLen;
                        const parityLen = m * shardLen;
                        const dataPtr = exp.alloc(dataLen);
                        const parityPtr = exp.alloc(parityLen);
                        mem().set(dataU8, dataPtr);
                        const rc = exp.rs_encode(dataPtr, k, m, shardLen, parityPtr);
                        const out = rc === 0 ? mem().slice(parityPtr, parityPtr + parityLen) : null;
                        exp.dealloc(dataPtr, dataLen);
                        exp.dealloc(parityPtr, parityLen);
                        if (rc !== 0) throw new Error('WASM rs_encode failed');
                        return out;
                    },
                    rsReconstruct(shardsU8, presentU8, k, m, shardLen) {
                        const n = k + m;
                        const totalBytes = n * shardLen;
                        const shardsPtr = exp.alloc(totalBytes);
                        const presentPtr = exp.alloc(n);
                        mem().set(shardsU8, shardsPtr);
                        mem().set(presentU8, presentPtr);
                        const recovered = exp.rs_reconstruct(shardsPtr, presentPtr, k, m, shardLen);
                        if (recovered >= 0) {
                            shardsU8.set(mem().subarray(shardsPtr, shardsPtr + totalBytes));
                        }
                        exp.dealloc(shardsPtr, totalBytes);
                        exp.dealloc(presentPtr, n);
                        return recovered;
                    }
                };
            } catch (err) {
                console.warn('[FileTransfer] WASM load failed, using JS fallback:', err);
                return createJsEccFallback();
            }
        })();
        return wasmEccPromise;
    }

    function createJsEccFallback() {
        const gfExp = new Uint8Array(512);
        const gfLog = new Uint8Array(256);
        let x = 1;
        for (let i = 0; i < 255; i++) {
            gfExp[i] = x;
            gfExp[i + 255] = x;
            gfLog[x] = i;
            x <<= 1;
            if (x & 0x100) x ^= 0x11d;
        }
        const gfMul = (a, b) => (a === 0 || b === 0) ? 0 : gfExp[gfLog[a] + gfLog[b]];
        const gfInv = (a) => (a === 0) ? 0 : gfExp[255 - gfLog[a]];
        const entry = (r, c, k) => (r < k) ? (r === c ? 1 : 0) : gfInv(r ^ c);

        return {
            isWasm: false,
            crc32: jsCrc32,
            rsEncode(dataU8, k, m, shardLen) {
                const parity = new Uint8Array(m * shardLen);
                for (let p = 0; p < m; p++) {
                    const pOff = p * shardLen;
                    for (let c = 0; c < k; c++) {
                        const coeff = entry(k + p, c, k);
                        if (coeff === 0) continue;
                        const dOff = c * shardLen;
                        for (let b = 0; b < shardLen; b++) {
                            parity[pOff + b] ^= gfMul(coeff, dataU8[dOff + b]);
                        }
                    }
                }
                return parity;
            },
            rsReconstruct(shardsU8, presentU8, k, m, shardLen) {
                const missing = [];
                for (let i = 0; i < k; i++) if (!presentU8[i]) missing.push(i);
                if (missing.length === 0) return 0;
                const used = [];
                for (let i = 0; i < k + m && used.length < k; i++) if (presentU8[i]) used.push(i);
                if (used.length < k) return -1;

                const mat = new Uint8Array(k * k);
                const inv = new Uint8Array(k * k);
                for (let r = 0; r < k; r++) {
                    inv[r * k + r] = 1;
                    for (let c = 0; c < k; c++) mat[r * k + c] = entry(used[r], c, k);
                }
                for (let col = 0; col < k; col++) {
                    let piv = col;
                    while (piv < k && mat[piv * k + col] === 0) piv++;
                    if (piv === k) return -1;
                    if (piv !== col) {
                        for (let c = 0; c < k; c++) {
                            [mat[col * k + c], mat[piv * k + c]] = [mat[piv * k + c], mat[col * k + c]];
                            [inv[col * k + c], inv[piv * k + c]] = [inv[piv * k + c], inv[col * k + c]];
                        }
                    }
                    const d = mat[col * k + col];
                    if (d !== 1) {
                        const id = gfInv(d);
                        for (let c = 0; c < k; c++) {
                            mat[col * k + c] = gfMul(mat[col * k + c], id);
                            inv[col * k + c] = gfMul(inv[col * k + c], id);
                        }
                    }
                    for (let r = 0; r < k; r++) {
                        if (r === col) continue;
                        const f = mat[r * k + col];
                        if (f !== 0) {
                            for (let c = 0; c < k; c++) {
                                mat[r * k + c] ^= gfMul(f, mat[col * k + c]);
                                inv[r * k + c] ^= gfMul(f, inv[col * k + c]);
                            }
                        }
                    }
                }
                const rec = new Uint8Array(missing.length * shardLen);
                for (let mi = 0; mi < missing.length; mi++) {
                    const di = missing[mi];
                    const dstOff = mi * shardLen;
                    for (let j = 0; j < k; j++) {
                        const coeff = inv[di * k + j];
                        if (coeff === 0) continue;
                        const srcOff = used[j] * shardLen;
                        for (let b = 0; b < shardLen; b++) {
                            rec[dstOff + b] ^= gfMul(coeff, shardsU8[srcOff + b]);
                        }
                    }
                }
                for (let mi = 0; mi < missing.length; mi++) {
                    shardsU8.set(rec.subarray(mi * shardLen, (mi + 1) * shardLen), missing[mi] * shardLen);
                }
                return missing.length;
            }
        };
    }

    // --- Incremental SHA-256 (O(1) memory for GB+ files) ---
    class IncrementalSha256 {
        constructor() {
            this.h = new Uint32Array([
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
            ]);
            this.k = new Uint32Array([
                0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
                0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
                0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
                0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
                0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
            ]);
            this.buf = new Uint8Array(64);
            this.bufLen = 0;
            this.totalBytes = 0;
            this.w = new Uint32Array(64);
        }

        _processBlock(block, offset) {
            const w = this.w;
            for (let i = 0; i < 16; i++) {
                const j = offset + i * 4;
                w[i] = ((block[j] << 24) | (block[j + 1] << 16) | (block[j + 2] << 8) | block[j + 3]) >>> 0;
            }
            for (let i = 16; i < 64; i++) {
                const s0 = ((w[i - 15] >>> 7) | (w[i - 15] << 25)) ^ ((w[i - 15] >>> 18) | (w[i - 15] << 14)) ^ (w[i - 15] >>> 3);
                const s1 = ((w[i - 2] >>> 17) | (w[i - 2] << 15)) ^ ((w[i - 2] >>> 19) | (w[i - 2] << 13)) ^ (w[i - 2] >>> 10);
                w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
            }
            let [a, b, c, d, e, f, g, h] = this.h;
            for (let i = 0; i < 64; i++) {
                const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
                const ch = (e & f) ^ ((~e) & g);
                const temp1 = (h + S1 + ch + this.k[i] + w[i]) >>> 0;
                const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
                const maj = (a & b) ^ (a & c) ^ (b & c);
                const temp2 = (S0 + maj) >>> 0;
                h = g; g = f; f = e; e = (d + temp1) >>> 0;
                d = c; c = b; b = a; a = (temp1 + temp2) >>> 0;
            }
            this.h[0] = (this.h[0] + a) >>> 0;
            this.h[1] = (this.h[1] + b) >>> 0;
            this.h[2] = (this.h[2] + c) >>> 0;
            this.h[3] = (this.h[3] + d) >>> 0;
            this.h[4] = (this.h[4] + e) >>> 0;
            this.h[5] = (this.h[5] + f) >>> 0;
            this.h[6] = (this.h[6] + g) >>> 0;
            this.h[7] = (this.h[7] + h) >>> 0;
        }

        update(chunk) {
            let offset = 0;
            this.totalBytes += chunk.length;
            if (this.bufLen > 0) {
                const need = 64 - this.bufLen;
                if (chunk.length < need) {
                    this.buf.set(chunk, this.bufLen);
                    this.bufLen += chunk.length;
                    return;
                }
                this.buf.set(chunk.subarray(0, need), this.bufLen);
                this._processBlock(this.buf, 0);
                this.bufLen = 0;
                offset = need;
            }
            while (offset + 64 <= chunk.length) {
                this._processBlock(chunk, offset);
                offset += 64;
            }
            if (offset < chunk.length) {
                this.buf.set(chunk.subarray(offset), 0);
                this.bufLen = chunk.length - offset;
            }
        }

        digestHex() {
            const bitLen = this.totalBytes * 8;
            const padBuf = new Uint8Array(128);
            padBuf.set(this.buf.subarray(0, this.bufLen), 0);
            padBuf[this.bufLen] = 0x80;
            const totalPadLen = (this.bufLen < 56) ? 64 : 128;
            const highBits = Math.floor(bitLen / 0x100000000);
            const lowBits = bitLen >>> 0;
            const dv = new DataView(padBuf.buffer);
            dv.setUint32(totalPadLen - 8, highBits, false);
            dv.setUint32(totalPadLen - 4, lowBits, false);
            this._processBlock(padBuf, 0);
            if (totalPadLen === 128) this._processBlock(padBuf, 64);

            let hex = '';
            for (let i = 0; i < 8; i++) {
                hex += this.h[i].toString(16).padStart(8, '0');
            }
            return hex;
        }
    }

    // --- Base64URL & Compression Helpers ---
    function toBase64Url(u8) {
        let bin = '';
        for (let i = 0; i < u8.length; i++) bin += String.fromCharCode(u8[i]);
        return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function fromBase64Url(str) {
        const pad = str.length % 4 ? '='.repeat(4 - (str.length % 4)) : '';
        const bin = atob(str.replace(/-/g, '+').replace(/_/g, '/') + pad);
        const u8 = new Uint8Array(bin.length);
        for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
        return u8;
    }

    async function deflateBytes(u8) {
        if (typeof CompressionStream === 'undefined') return { compressed: false, data: u8 };
        const cs = new CompressionStream('deflate-raw');
        const writer = cs.writable.getWriter();
        writer.write(u8);
        writer.close();
        const buf = await new Response(cs.readable).arrayBuffer();
        return { compressed: true, data: new Uint8Array(buf) };
    }

    async function inflateBytes(u8, compressed) {
        if (!compressed) return u8;
        if (typeof DecompressionStream === 'undefined') throw new Error('浏览器不支持 DecompressionStream');
        const ds = new DecompressionStream('deflate-raw');
        const writer = ds.writable.getWriter();
        writer.write(u8);
        writer.close();
        const buf = await new Response(ds.readable).arrayBuffer();
        return new Uint8Array(buf);
    }

    async function derivePassphraseKey(passphrase, salt) {
        const enc = new TextEncoder().encode(passphrase);
        const baseKey = await crypto.subtle.importKey('raw', enc, 'PBKDF2', false, ['deriveKey']);
        return crypto.subtle.deriveKey(
            { name: 'PBKDF2', salt, iterations: 100000, hash: 'SHA-256' },
            baseKey,
            { name: 'AES-GCM', length: 256 },
            false,
            ['encrypt', 'decrypt']
        );
    }

    async function encodeSignalToken(payloadObj, passphrase) {
        const raw = new TextEncoder().encode(JSON.stringify(payloadObj));
        const { compressed, data } = await deflateBytes(raw);
        const flagByte = compressed ? 1 : 0;
        const body = new Uint8Array(1 + data.length);
        body[0] = flagByte;
        body.set(data, 1);

        if (passphrase) {
            const salt = crypto.getRandomValues(new Uint8Array(16));
            const iv = crypto.getRandomValues(new Uint8Array(12));
            const key = await derivePassphraseKey(passphrase, salt);
            const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, key, body));
            const packed = new Uint8Array(28 + ct.length);
            packed.set(salt, 0);
            packed.set(iv, 16);
            packed.set(ct, 28);
            return 'WB2:' + toBase64Url(packed);
        }
        return 'WB1:' + toBase64Url(body);
    }

    async function decodeSignalToken(tokenStr, passphrase) {
        let trimmed = tokenStr.trim();
        if (trimmed.includes('#offer=')) {
            trimmed = decodeURIComponent(trimmed.split('#offer=')[1]);
        } else if (trimmed.includes('#answer=')) {
            trimmed = decodeURIComponent(trimmed.split('#answer=')[1]);
        }

        let body;
        if (trimmed.startsWith('WB2:')) {
            if (!passphrase) throw new Error('此连接码已加密，请先输入“信令口令”');
            const packed = fromBase64Url(trimmed.slice(4));
            const salt = packed.subarray(0, 16);
            const iv = packed.subarray(16, 28);
            const ct = packed.subarray(28);
            const key = await derivePassphraseKey(passphrase, salt);
            try {
                body = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ct));
            } catch (_) {
                throw new Error('信令口令错误或连接码已损坏');
            }
        } else if (trimmed.startsWith('WB1:')) {
            body = fromBase64Url(trimmed.slice(4));
        } else {
            throw new Error('无效的连接码格式');
        }

        const compressed = body[0] === 1;
        const raw = await inflateBytes(body.subarray(1), compressed);
        return JSON.parse(new TextDecoder().decode(raw));
    }

    function formatBytes(bytes) {
        if (bytes < 1024) return bytes + ' B';
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
    }

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    // --- Main Tool Controller ---
    let activeUnmount = null;

    function initFileTransfer() {
        const root = document.getElementById('fileTransferToolApp');
        if (!root) return;
        if (activeUnmount) activeUnmount();

        const $ = (id) => document.getElementById(id);
        const els = {
            wasmBadge: $('ftWasmBadge'),
            natProbeBtn: $('ftNatProbeBtn'),
            natProbeLabel: $('ftNatProbeLabel'),
            natResultBox: $('ftNatResultBox'),
            natResultIcon: $('ftNatResultIcon'),
            natResultTitle: $('ftNatResultTitle'),
            natResultDesc: $('ftNatResultDesc'),
            natResultDetail: $('ftNatResultDetail'),
            connBadge: $('ftConnBadge'),
            disconnectBtn: $('ftDisconnectBtn'),
            techBadges: $('ftTechBadges'),
            easySubtitle: $('ftEasySubtitle'),
            techSubtitle: $('ftTechSubtitle'),
            modeEasyBtn: $('ftModeEasyBtn'),
            modeAdvBtn: $('ftModeAdvBtn'),
            easyGuaranteeBadge: $('ftEasyGuaranteeBadge'),
            easyCard: $('ftEasyCard'),
            easyStatusText: $('ftEasyStatusText'),
            easyActionBtns: $('ftEasyActionBtns'),
            easyPickFileBtn: $('ftEasyPickFileBtn'),
            easyCopyLinkBtn: $('ftEasyCopyLinkBtn'),
            guideSection: $('ftGuideSection'),
            securitySettingsBox: $('ftSecuritySettingsBox'),
            toggleAdvBtn: $('ftToggleAdvancedBtn'),
            advPanel: $('ftAdvancedPanel'),
            e2eeToggle: $('ftE2eeToggle'),
            eccSelect: $('ftEccSelect'),
            iceMode: $('ftIceMode'),
            customIceWrap: $('ftCustomIceWrap'),
            customIceInput: $('ftCustomIceInput'),
            signalPass: $('ftSignalPass'),
            channelMode: $('ftChannelMode'),
            autoAccept: $('ftAutoAccept'),
            pairingSection: $('ftPairingSection'),
            roleHostBtn: $('ftRoleHostBtn'),
            roleJoinBtn: $('ftRoleJoinBtn'),
            hostPanel: $('ftHostPanel'),
            joinPanel: $('ftJoinPanel'),
            createOfferBtn: $('ftCreateOfferBtn'),
            offerOut: $('ftOfferOut'),
            copyOfferBtn: $('ftCopyOfferBtn'),
            copyOfferLinkBtn: $('ftCopyOfferLinkBtn'),
            offerStats: $('ftOfferStats'),
            offerQrWrap: $('ftOfferQrWrap'),
            offerQrCanvas: $('ftOfferQrCanvas'),
            answerIn: $('ftAnswerIn'),
            acceptAnswerBtn: $('ftAcceptAnswerBtn'),
            scanAnswerCamBtn: $('ftScanAnswerCamBtn'),
            scanAnswerImgInput: $('ftScanAnswerImgInput'),
            offerIn: $('ftOfferIn'),
            createAnswerBtn: $('ftCreateAnswerBtn'),
            answerOut: $('ftAnswerOut'),
            copyAnswerBtn: $('ftCopyAnswerBtn'),
            answerQrWrap: $('ftAnswerQrWrap'),
            answerQrCanvas: $('ftAnswerQrCanvas'),
            scanOfferCamBtn: $('ftScanOfferCamBtn'),
            scanOfferImgInput: $('ftScanOfferImgInput'),
            scannerBox: $('ftScannerBox'),
            closeScannerBtn: $('ftCloseScannerBtn'),
            qrVideo: $('ftQrVideo'),
            qrScanCanvas: $('ftQrScanCanvas'),
            sessionBar: $('ftSessionBar'),
            sessionEncLabel: $('ftSessionEncLabel'),
            sasEmoji: $('ftSasEmoji'),
            sasCode: $('ftSasCode'),
            runtimeEncBtn: $('ftRuntimeEncBtn'),
            statEccEngine: $('ftStatEccEngine'),
            statStripes: $('ftStatStripes'),
            statRecovered: $('ftStatRecovered'),
            statCrcBad: $('ftStatCrcBad'),
            sendReadyHint: $('ftSendReadyHint'),
            dropzone: $('ftDropzone'),
            fileInput: $('ftFileInput'),
            textMsgInput: $('ftTextMsgInput'),
            sendTextBtn: $('ftSendTextBtn'),
            transferList: $('ftTransferList'),
            emptyState: $('ftEmptyState'),
            clearDoneBtn: $('ftClearDoneBtn')
        };

        const state = {
            disposed: false,
            transferMode: 'easy',
            easyPollTimer: null,
            easyShareUrl: '',
            ecc: null,
            pc: null,
            dc: null,
            ecdhKeyPair: null,
            myPubB64: '',
            peerPubB64: '',
            aesKey: null,
            sessionEncrypted: true,
            myEncPref: true,
            peerEncPref: true,
            ivPrefix: crypto.getRandomValues(new Uint8Array(4)),
            ivCounter: 1,
            nextFileId: 1,
            scanStream: null,
            scanRaf: null,
            scanTargetInput: null,
            stats: { stripes: 0, parity: 0, recovered: 0, crcBad: 0 },
            transfers: new Map(), // key: "out:<id>" or "in:<id>"
            sendingQueue: [],
            isSending: false
        };

        function setConnStatus(text, tone) {
            const colors = {
                idle: 'text-slate-400',
                wait: 'text-amber-400',
                ok: 'text-emerald-400',
                err: 'text-rose-400'
            };
            els.connBadge.innerHTML = `<i class="fa-solid fa-circle text-[8px] mr-1.5 ${colors[tone] || colors.idle}"></i>${escapeHtml(text)}`;
        }

        function updateStatsUi() {
            els.statStripes.textContent = `${state.stats.stripes} / ${state.stats.parity}`;
            els.statRecovered.textContent = String(state.stats.recovered);
            els.statCrcBad.textContent = String(state.stats.crcBad);
        }

        function updateEncUi() {
            const active = state.sessionEncrypted && Boolean(state.aesKey);
            if (active) {
                els.sessionEncLabel.textContent = 'ECDH P-256 + AES-256-GCM 防监听加密已启用';
                els.sessionEncLabel.parentElement.className = 'px-3 py-1.5 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-xs font-mono text-emerald-400 flex items-center gap-2';
            } else {
                els.sessionEncLabel.textContent = '应用层加密已关闭 (仅 WebRTC DTLS 传输)';
                els.sessionEncLabel.parentElement.className = 'px-3 py-1.5 rounded-xl bg-amber-500/10 border border-amber-500/20 text-xs font-mono text-amber-400 flex items-center gap-2';
            }
        }

        // Load WASM ECC immediately
        getEccEngine().then((engine) => {
            if (state.disposed) return;
            state.ecc = engine;
            if (engine.isWasm) {
                els.wasmBadge.innerHTML = '<i class="fa-solid fa-microchip text-[9px] mr-1 text-emerald-400"></i>Rust WASM ECC 就绪';
                els.statEccEngine.textContent = 'Rust WASM (GF 2^8)';
            } else {
                els.wasmBadge.innerHTML = '<i class="fa-solid fa-microchip text-[9px] mr-1 text-amber-400"></i>JS ECC 回退';
                els.statEccEngine.textContent = 'JS Fallback';
            }
        });

        // --- UI Controls ---
        els.toggleAdvBtn.addEventListener('click', () => {
            els.advPanel.classList.toggle('hidden');
        });

        els.iceMode.addEventListener('change', () => {
            els.customIceWrap.classList.toggle('hidden', els.iceMode.value !== 'custom');
        });

        async function probeNatType() {
            if (els.iceMode.value === 'lan') {
                els.natResultBox.className = 'rounded-2xl border border-amber-500/30 bg-amber-500/10 p-3.5 text-xs font-mono flex flex-wrap items-center justify-between gap-2';
                els.natResultIcon.className = 'fa-solid fa-house-laptop text-amber-400';
                els.natResultTitle.textContent = '仅局域网模式';
                els.natResultDesc.textContent = '当前已禁用外部 STUN，仅采集本地局域网地址，适合同一 Wi-Fi/热点内零外联传输。';
                els.natResultDetail.textContent = 'STUN: Disabled';
                return;
            }
            els.natProbeBtn.disabled = true;
            els.natProbeLabel.textContent = '探测中…';
            const srflxByRelatedPort = new Map();
            const hostAddrs = new Set();
            const probeServers = [
                { urls: 'stun:stun.qq.com:3478' },
                { urls: 'stun:stun.miwifi.com:3478' },
                { urls: 'stun:stun.l.google.com:19302' }
            ];
            let pc;
            try {
                pc = new RTCPeerConnection({ iceServers: probeServers });
                pc.createDataChannel('nat-probe');
                pc.onicecandidate = (ev) => {
                    const c = ev.candidate;
                    if (!c || !c.candidate) return;
                    if (c.type === 'host' && c.address) {
                        hostAddrs.add(c.address);
                    } else if (c.type === 'srflx' && c.address && c.port) {
                        const rPort = c.relatedPort || 0;
                        if (!srflxByRelatedPort.has(rPort)) srflxByRelatedPort.set(rPort, new Set());
                        srflxByRelatedPort.get(rPort).add(`${c.address}:${c.port}`);
                    }
                };
                const offer = await pc.createOffer();
                await pc.setLocalDescription(offer);
                await gatherIceComplete(pc, 4000);

                let totalSrflx = 0;
                let maxUniqueForSameLocal = 0;
                let sampleSrflx = '';
                for (const set of srflxByRelatedPort.values()) {
                    totalSrflx += set.size;
                    if (set.size > maxUniqueForSameLocal) maxUniqueForSameLocal = set.size;
                    if (!sampleSrflx && set.size > 0) sampleSrflx = Array.from(set)[0];
                }

                if (totalSrflx === 0) {
                    els.natResultBox.className = 'rounded-2xl border border-amber-500/30 bg-amber-500/10 p-3.5 text-xs font-mono flex flex-wrap items-center justify-between gap-2';
                    els.natResultIcon.className = 'fa-solid fa-triangle-exclamation text-amber-400';
                    els.natResultTitle.textContent = '未获取到公网反射地址 (受限/仅局域网)';
                    els.natResultDesc.textContent = 'UDP STUN 探测无响应，跨公网直连可能受阻，建议同一局域网/热点传输或配置 TURN。';
                    els.natResultDetail.textContent = `Host: ${hostAddrs.size}`;
                } else if (maxUniqueForSameLocal > 1) {
                    els.natResultBox.className = 'rounded-2xl border border-rose-500/30 bg-rose-500/10 p-3.5 text-xs font-mono flex flex-wrap items-center justify-between gap-2';
                    els.natResultIcon.className = 'fa-solid fa-shield-halved text-rose-400';
                    els.natResultTitle.textContent = '对称型 NAT (Symmetric NAT)';
                    els.natResultDesc.textContent = '不同目标映射了不同外网端口，跨公网打洞较困难，建议双方连入同一 Wi-Fi/热点或使用 TURN 中继。';
                    els.natResultDetail.textContent = `Srflx: ${sampleSrflx} (多端口映射)`;
                } else {
                    els.natResultBox.className = 'rounded-2xl border border-emerald-500/30 bg-emerald-500/10 p-3.5 text-xs font-mono flex flex-wrap items-center justify-between gap-2';
                    els.natResultIcon.className = 'fa-solid fa-circle-check text-emerald-400';
                    els.natResultTitle.textContent = '圆锥型 NAT / 易穿透 (Cone NAT)';
                    els.natResultDesc.textContent = '外网端口映射稳定，跨网 P2P 打洞直连成功率高。';
                    els.natResultDetail.textContent = `Srflx: ${sampleSrflx}`;
                }
            } catch (err) {
                els.natResultBox.className = 'rounded-2xl border border-rose-500/30 bg-rose-500/10 p-3.5 text-xs font-mono flex flex-wrap items-center justify-between gap-2';
                els.natResultIcon.className = 'fa-solid fa-circle-xmark text-rose-400';
                els.natResultTitle.textContent = 'NAT 探测失败';
                els.natResultDesc.textContent = err.message || '浏览器不支持或网络受限';
                els.natResultDetail.textContent = '';
            } finally {
                if (pc) {
                    try { pc.close(); } catch (_) {}
                }
                els.natProbeBtn.disabled = false;
                els.natProbeLabel.textContent = '检测 NAT 类型';
            }
        }
        els.natProbeBtn.addEventListener('click', probeNatType);

        function switchRole(role) {
            const isHost = role === 'host';
            els.hostPanel.classList.toggle('hidden', !isHost);
            els.joinPanel.classList.toggle('hidden', isHost);
            els.roleHostBtn.className = isHost
                ? 'px-3.5 py-1.5 rounded-xl text-xs font-mono font-semibold bg-accent text-white transition-colors cursor-pointer'
                : 'px-3.5 py-1.5 rounded-xl text-xs font-mono font-semibold bg-input-bg text-meta border border-border hover:text-main transition-colors cursor-pointer';
            els.roleJoinBtn.className = !isHost
                ? 'px-3.5 py-1.5 rounded-xl text-xs font-mono font-semibold bg-accent text-white transition-colors cursor-pointer'
                : 'px-3.5 py-1.5 rounded-xl text-xs font-mono font-semibold bg-input-bg text-meta border border-border hover:text-main transition-colors cursor-pointer';
        }
        els.roleHostBtn.addEventListener('click', () => switchRole('host'));
        els.roleJoinBtn.addEventListener('click', () => switchRole('join'));

        // --- QR Helpers ---
        async function renderQrToCanvas(canvas, text) {
            await loadScript(QR_GEN_URL);
            const qr = window.qrcode(0, 'L');
            qr.addData(text);
            qr.make();
            const count = qr.getModuleCount();
            const size = 220;
            const margin = 10;
            const cell = Math.max(1, Math.floor((size - margin * 2) / count));
            const actualSize = cell * count + margin * 2;
            canvas.width = actualSize;
            canvas.height = actualSize;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#ffffff';
            ctx.fillRect(0, 0, actualSize, actualSize);
            ctx.fillStyle = '#0f172a';
            for (let r = 0; r < count; r++) {
                for (let c = 0; c < count; c++) {
                    if (qr.isDark(r, c)) {
                        ctx.fillRect(margin + c * cell, margin + r * cell, cell, cell);
                    }
                }
            }
        }

        function stopCameraScanner() {
            if (state.scanRaf) {
                cancelAnimationFrame(state.scanRaf);
                state.scanRaf = null;
            }
            if (state.scanStream) {
                state.scanStream.getTracks().forEach((t) => t.stop());
                state.scanStream = null;
            }
            els.qrVideo.srcObject = null;
            els.scannerBox.classList.add('hidden');
        }

        async function startCameraScanner(targetTextarea) {
            stopCameraScanner();
            try {
                await loadScript(QR_SCAN_URL);
                const stream = await navigator.mediaDevices.getUserMedia({
                    video: { facingMode: { ideal: 'environment' } }
                });
                state.scanStream = stream;
                state.scanTargetInput = targetTextarea;
                els.qrVideo.srcObject = stream;
                els.scannerBox.classList.remove('hidden');

                const canvas = els.qrScanCanvas;
                const ctx = canvas.getContext('2d', { willReadFrequently: true });

                const tick = () => {
                    if (state.disposed || !state.scanStream) return;
                    if (els.qrVideo.readyState === els.qrVideo.HAVE_ENOUGH_DATA) {
                        canvas.width = els.qrVideo.videoWidth;
                        canvas.height = els.qrVideo.videoHeight;
                        ctx.drawImage(els.qrVideo, 0, 0, canvas.width, canvas.height);
                        const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                        const code = window.jsQR(imgData.data, imgData.width, imgData.height);
                        if (code && code.data) {
                            targetTextarea.value = code.data;
                            stopCameraScanner();
                            return;
                        }
                    }
                    state.scanRaf = requestAnimationFrame(tick);
                };
                state.scanRaf = requestAnimationFrame(tick);
            } catch (err) {
                alert('无法启动摄像头扫码: ' + (err.message || err));
                stopCameraScanner();
            }
        }

        async function scanImageFile(file, targetTextarea) {
            if (!file) return;
            try {
                await loadScript(QR_SCAN_URL);
                const bmp = await createImageBitmap(file);
                const canvas = document.createElement('canvas');
                canvas.width = bmp.width;
                canvas.height = bmp.height;
                const ctx = canvas.getContext('2d');
                ctx.drawImage(bmp, 0, 0);
                const imgData = ctx.getImageData(0, 0, canvas.width, canvas.height);
                const code = window.jsQR(imgData.data, imgData.width, imgData.height);
                if (code && code.data) {
                    targetTextarea.value = code.data;
                } else {
                    alert('未在图片中识别到有效二维码');
                }
            } catch (err) {
                alert('图片二维码识别失败: ' + (err.message || err));
            }
        }

        els.closeScannerBtn.addEventListener('click', stopCameraScanner);
        els.scanAnswerCamBtn.addEventListener('click', () => startCameraScanner(els.answerIn));
        els.scanOfferCamBtn.addEventListener('click', () => startCameraScanner(els.offerIn));
        els.scanAnswerImgInput.addEventListener('change', (e) => {
            scanImageFile(e.target.files[0], els.answerIn);
            e.target.value = '';
        });
        els.scanOfferImgInput.addEventListener('change', (e) => {
            scanImageFile(e.target.files[0], els.offerIn);
            e.target.value = '';
        });

        // --- WebRTC PeerConnection & Signaling ---
        function buildIceServers() {
            const mode = els.iceMode.value;
            if (mode === 'lan') return [];
            if (mode === 'custom') {
                const raw = els.customIceInput.value.trim();
                if (!raw) return PUBLIC_ICE_SERVERS;
                try {
                    if (raw.startsWith('[')) return JSON.parse(raw);
                } catch (_) { /* fall through */ }
                return raw.split(',').map((s) => s.trim()).filter(Boolean).map((urls) => ({ urls }));
            }
            return PUBLIC_ICE_SERVERS;
        }

        function resetPeerConnection() {
            if (state.easyPollTimer) {
                clearInterval(state.easyPollTimer);
                state.easyPollTimer = null;
            }
            state.easyShareUrl = '';
            if (els.easyCopyLinkBtn) els.easyCopyLinkBtn.disabled = true;
            stopCameraScanner();
            if (state.dc) {
                try { state.dc.close(); } catch (_) {}
                state.dc = null;
            }
            if (state.pc) {
                try { state.pc.close(); } catch (_) {}
                state.pc = null;
            }
            state.aesKey = null;
            state.peerPubB64 = '';
            els.sessionBar.classList.add('hidden');
            els.disconnectBtn.classList.add('hidden');
            els.pairingSection.classList.remove('hidden');
            els.sendTextBtn.disabled = true;
            els.sendReadyHint.textContent = '等待建立 P2P 连接';
        }

        async function prepareEcdh() {
            state.ecdhKeyPair = await crypto.subtle.generateKey(
                { name: 'ECDH', namedCurve: 'P-256' },
                false,
                ['deriveBits']
            );
            const rawPub = new Uint8Array(await crypto.subtle.exportKey('raw', state.ecdhKeyPair.publicKey));
            state.myPubB64 = toBase64Url(rawPub);
        }

        async function deriveSessionCrypto(peerPubB64) {
            state.peerPubB64 = peerPubB64;
            const peerRaw = fromBase64Url(peerPubB64);
            const peerKey = await crypto.subtle.importKey(
                'raw', peerRaw, { name: 'ECDH', namedCurve: 'P-256' }, false, []
            );
            const sharedBits = await crypto.subtle.deriveBits(
                { name: 'ECDH', public: peerKey },
                state.ecdhKeyPair.privateKey,
                256
            );
            const hkdfBase = await crypto.subtle.importKey('raw', sharedBits, 'HKDF', false, ['deriveKey']);
            const enc = new TextEncoder();
            state.aesKey = await crypto.subtle.deriveKey(
                {
                    name: 'HKDF',
                    hash: 'SHA-256',
                    salt: enc.encode('windblog-p2p-v1'),
                    info: enc.encode('aes-gcm-session')
                },
                hkdfBase,
                { name: 'AES-GCM', length: 256 },
                false,
                ['encrypt', 'decrypt']
            );

            // Compute SAS fingerprint
            const pair = [state.myPubB64, peerPubB64].sort().join(':');
            const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(pair)));
            let emojis = '';
            for (let i = 0; i < 4; i++) emojis += SAS_EMOJIS[digest[i] & 0x0F];
            const hex = Array.from(digest.subarray(4, 7)).map((b) => b.toString(16).padStart(2, '0')).join('').toUpperCase();
            els.sasEmoji.textContent = emojis;
            els.sasCode.textContent = hex;
        }

        function nextIv() {
            const iv = new Uint8Array(12);
            iv.set(state.ivPrefix, 0);
            const dv = new DataView(iv.buffer);
            const cnt = state.ivCounter++;
            dv.setUint32(4, Math.floor(cnt / 0x100000000), false);
            dv.setUint32(8, cnt >>> 0, false);
            return iv;
        }

        async function sendControl(obj) {
            if (!state.dc || state.dc.readyState !== 'open') return;
            if (state.sessionEncrypted && state.aesKey && obj.type !== 'hello') {
                const iv = nextIv();
                const plain = new TextEncoder().encode(JSON.stringify(obj));
                const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, state.aesKey, plain));
                state.dc.send(JSON.stringify({ type: 'enc-ctrl', iv: toBase64Url(iv), ct: toBase64Url(ct) }));
            } else {
                state.dc.send(JSON.stringify(obj));
            }
        }

        async function sendBinaryFrame(plainFrameU8) {
            if (!state.dc || state.dc.readyState !== 'open') throw new Error('连接已断开');
            if (state.dc.bufferedAmount > 1024 * 1024) {
                await new Promise((resolve) => {
                    const onLow = () => {
                        state.dc.removeEventListener('bufferedamountlow', onLow);
                        resolve();
                    };
                    state.dc.addEventListener('bufferedamountlow', onLow);
                    setTimeout(onLow, 150);
                });
            }
            if (state.sessionEncrypted && state.aesKey) {
                const iv = nextIv();
                const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, state.aesKey, plainFrameU8));
                const out = new Uint8Array(13 + ct.length);
                out[0] = ENC_FRAME_TAG;
                out.set(iv, 1);
                out.set(ct, 13);
                state.dc.send(out);
            } else {
                state.dc.send(plainFrameU8);
            }
        }

        function gatherIceComplete(pc, timeoutMs = 3500) {
            return new Promise((resolve) => {
                if (pc.iceGatheringState === 'complete') {
                    resolve();
                    return;
                }
                const timer = setTimeout(resolve, timeoutMs);
                pc.addEventListener('icegatheringstatechange', () => {
                    if (pc.iceGatheringState === 'complete') {
                        clearTimeout(timer);
                        resolve();
                    }
                });
            });
        }

        function attachDataChannel(dc) {
            state.dc = dc;
            dc.binaryType = 'arraybuffer';
            dc.bufferedAmountLowThreshold = 256 * 1024;
            let rxQueue = Promise.resolve();

            dc.onopen = async () => {
                await prepareEcdh();
                state.myEncPref = els.e2eeToggle.checked;
                await sendControl({
                    type: 'hello',
                    proto: 1,
                    ecdhPub: state.myPubB64,
                    encryptEnabled: state.myEncPref
                });
                setConnStatus('密钥协商中…', 'wait');
            };

            async function processIncomingMessage(event) {
                if (typeof event.data === 'string') {
                    let msg;
                    try { msg = JSON.parse(event.data); } catch (_) { return; }
                    if (msg.type === 'enc-ctrl') {
                        if (!state.aesKey) return;
                        try {
                            const iv = fromBase64Url(msg.iv);
                            const ct = fromBase64Url(msg.ct);
                            const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, state.aesKey, ct);
                            msg = JSON.parse(new TextDecoder().decode(plain));
                        } catch (_) {
                            return;
                        }
                    }
                    await handleControlMessage(msg);
                } else if (event.data instanceof ArrayBuffer) {
                    let frame = new Uint8Array(event.data);
                    if (frame[0] === ENC_FRAME_TAG) {
                        if (!state.aesKey) return;
                        try {
                            const iv = frame.subarray(1, 13);
                            const ct = frame.subarray(13);
                            frame = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, state.aesKey, ct));
                        } catch (_) {
                            return;
                        }
                    } else if (state.sessionEncrypted) {
                        // Reject unencrypted binary frames when encryption is required
                        return;
                    }
                    await handleBinaryFrame(frame);
                }
            }

            dc.onmessage = (event) => {
                rxQueue = rxQueue.then(() => processIncomingMessage(event)).catch((err) => {
                    console.error('[FileTransfer] rx error:', err);
                });
            };

            dc.onclose = () => {
                setConnStatus('连接已关闭', 'idle');
                els.sendTextBtn.disabled = true;
                els.sendReadyHint.textContent = '连接已断开';
                els.disconnectBtn.classList.add('hidden');
            };
        }

        els.createOfferBtn.addEventListener('click', async () => {
            resetPeerConnection();
            setConnStatus('生成邀请码…', 'wait');
            const pc = new RTCPeerConnection({ iceServers: buildIceServers() });
            state.pc = pc;
            const unreliable = els.channelMode.value === 'unreliable';
            const dc = pc.createDataChannel('windblog-ft', unreliable
                ? { ordered: false, maxRetransmits: 0 }
                : { ordered: true });
            attachDataChannel(dc);

            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            await gatherIceComplete(pc);

            const token = await encodeSignalToken(
                { t: 'O', s: pc.localDescription.sdp },
                els.signalPass.value.trim()
            );
            els.offerOut.value = token;
            els.copyOfferBtn.disabled = false;
            els.copyOfferLinkBtn.disabled = false;
            els.offerStats.textContent = `${token.length} 字符`;
            els.offerQrWrap.classList.remove('hidden');
            await renderQrToCanvas(els.offerQrCanvas, token);
            setConnStatus('等待对方应答', 'wait');
            if (els.easyCopyLinkBtn) {
                els.easyCopyLinkBtn.disabled = false;
            }
            if (els.easyAnswerWrap) {
                els.easyAnswerWrap.classList.remove('hidden');
            }
            if (els.easyStatusText) {
                els.easyStatusText.textContent = '第 2 步：点击「复制分享链接」发给对方；对方发回应答码后粘贴到下方，将自动连接并开始传输。';
            }
        });

        els.copyOfferBtn.addEventListener('click', () => {
            if (els.offerOut.value) navigator.clipboard.writeText(els.offerOut.value);
        });
        els.copyOfferLinkBtn.addEventListener('click', () => {
            if (!els.offerOut.value) return;
            const url = window.location.href.split('#')[0] + '#offer=' + encodeURIComponent(els.offerOut.value);
            navigator.clipboard.writeText(url);
        });

        els.acceptAnswerBtn.addEventListener('click', async () => {
            if (!state.pc) {
                alert('请先点击“生成连接码 & 二维码”');
                return;
            }
            try {
                const payload = await decodeSignalToken(els.answerIn.value, els.signalPass.value.trim());
                if (payload.t !== 'A' || !payload.s) throw new Error('不是有效的 Answer 应答码');
                await state.pc.setRemoteDescription({ type: 'answer', sdp: payload.s });
                setConnStatus('正在建立直连…', 'wait');
            } catch (err) {
                alert(err.message || err);
            }
        });

        els.createAnswerBtn.addEventListener('click', async () => {
            resetPeerConnection();
            try {
                const payload = await decodeSignalToken(els.offerIn.value, els.signalPass.value.trim());
                if (payload.t !== 'O' || !payload.s) throw new Error('不是有效的 Offer 连接码');
                setConnStatus('生成应答码…', 'wait');
                const pc = new RTCPeerConnection({ iceServers: buildIceServers() });
                state.pc = pc;
                pc.ondatachannel = (ev) => attachDataChannel(ev.channel);
                await pc.setRemoteDescription({ type: 'offer', sdp: payload.s });
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                await gatherIceComplete(pc);

                const token = await encodeSignalToken(
                    { t: 'A', s: pc.localDescription.sdp },
                    els.signalPass.value.trim()
                );
                els.answerOut.value = token;
                els.copyAnswerBtn.disabled = false;
                els.answerQrWrap.classList.remove('hidden');
                await renderQrToCanvas(els.answerQrCanvas, token);
                setConnStatus('等待发起方确认…', 'wait');
                if (els.easyAnswerOut && els.easyJoinWrap) {
                    els.easyAnswerOut.value = token;
                    els.easyJoinWrap.classList.remove('hidden');
                }
                if (els.easyStatusText) {
                    els.easyStatusText.textContent = '已自动生成应答码！点击下方「复制应答码发回给对方」，对方粘贴后即自动开始传输。';
                }
            } catch (err) {
                alert(err.message || err);
                setConnStatus('配对失败', 'err');
            }
        });

        els.copyAnswerBtn.addEventListener('click', () => {
            if (els.answerOut.value) navigator.clipboard.writeText(els.answerOut.value);
        });

        els.disconnectBtn.addEventListener('click', () => {
            resetPeerConnection();
            setConnStatus('已断开连接', 'idle');
        });

        els.runtimeEncBtn.addEventListener('click', async () => {
            if (!state.dc || state.dc.readyState !== 'open') return;
            const next = !state.sessionEncrypted;
            state.sessionEncrypted = next;
            updateEncUi();
            await sendControl({ type: 'enc-toggle', enabled: next });
        });

        // --- Transfer Queue & Rendering ---
        function renderTransferCard(item) {
            els.emptyState.classList.add('hidden');
            let card = document.getElementById('ft-card-' + item.key);
            if (!card) {
                card = document.createElement('div');
                card.id = 'ft-card-' + item.key;
                card.className = 'p-3.5 rounded-xl bg-input-bg border border-border space-y-2';
                els.transferList.prepend(card);
            }

            if (item.kind === 'text') {
                const dirBadge = item.dir === 'out'
                    ? '<span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-accent/10 text-accent">已发送</span>'
                    : '<span class="text-[10px] font-mono px-1.5 py-0.5 rounded bg-emerald-500/10 text-emerald-400">收到文本</span>';
                card.innerHTML = `
                    <div class="flex items-center justify-between gap-2">
                        ${dirBadge}
                        <button type="button" class="ft-copy-text text-[11px] font-mono text-meta hover:text-accent cursor-pointer">
                            <i class="fa-regular fa-copy mr-1"></i>复制
                        </button>
                    </div>
                    <pre class="text-xs font-mono text-main whitespace-pre-wrap break-all bg-card p-2.5 rounded-lg border border-border/60">${escapeHtml(item.text)}</pre>
                `;
                card.querySelector('.ft-copy-text').onclick = () => navigator.clipboard.writeText(item.text);
                return;
            }

            const pct = item.size > 0 ? Math.min(100, Math.round((item.doneBytes / item.size) * 100)) : 100;
            const dirIcon = item.dir === 'out' ? 'fa-arrow-up text-accent' : 'fa-arrow-down text-emerald-400';
            let actionsHtml = '';
            if (item.dir === 'in' && item.status === 'pending-accept') {
                actionsHtml = `
                    <div class="flex items-center gap-2 pt-1">
                        <button type="button" class="ft-accept-btn px-3 py-1 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-[11px] font-semibold cursor-pointer">保存文件</button>
                        <button type="button" class="ft-reject-btn px-3 py-1 rounded-lg bg-card border border-border text-meta hover:text-rose-400 text-[11px] cursor-pointer">拒绝</button>
                    </div>
                `;
            }

            card.innerHTML = `
                <div class="flex items-center justify-between gap-2">
                    <div class="flex items-center gap-2 min-w-0">
                        <i class="fa-solid ${dirIcon} text-xs shrink-0"></i>
                        <span class="text-xs font-semibold text-main truncate">${escapeHtml(item.name)}</span>
                    </div>
                    <span class="text-[11px] font-mono text-meta shrink-0">${formatBytes(item.size)}</span>
                </div>
                <div class="w-full h-1.5 rounded-full bg-card overflow-hidden">
                    <div class="h-full bg-accent transition-all duration-150" style="width:${pct}%"></div>
                </div>
                <div class="flex flex-wrap items-center justify-between gap-2 text-[11px] font-mono text-meta">
                    <span>${escapeHtml(item.statusText || (pct + '%'))}</span>
                    <span>${item.hashStatus ? escapeHtml(item.hashStatus) : ''}</span>
                </div>
                ${actionsHtml}
            `;

            const acceptBtn = card.querySelector('.ft-accept-btn');
            const rejectBtn = card.querySelector('.ft-reject-btn');
            if (acceptBtn) acceptBtn.onclick = () => acceptIncomingFile(item);
            if (rejectBtn) rejectBtn.onclick = () => rejectIncomingFile(item);
        }

        // --- Control Message Handler ---
        async function handleControlMessage(msg) {
            if (msg.type === 'hello') {
                state.peerEncPref = Boolean(msg.encryptEnabled);
                await deriveSessionCrypto(msg.ecdhPub);
                state.sessionEncrypted = state.myEncPref && state.peerEncPref;
                updateEncUi();
                els.pairingSection.classList.add('hidden');
                if (state.transferMode === 'advanced') {
                    els.sessionBar.classList.remove('hidden');
                } else if (els.easyStatusText) {
                    els.easyStatusText.textContent = '已建立安全连接，正在自动传输文件…';
                }
                els.disconnectBtn.classList.remove('hidden');
                els.sendTextBtn.disabled = false;
                els.sendReadyHint.textContent = 'P2P 直连已就绪，可双向互传';
                setConnStatus(state.sessionEncrypted ? '已加密直连' : '已直连', 'ok');
                if (state.sendingQueue.length > 0) {
                    processSendQueue();
                }
                return;
            }
            if (msg.type === 'enc-toggle') {
                state.sessionEncrypted = Boolean(msg.enabled);
                updateEncUi();
                return;
            }
            if (msg.type === 'text-msg') {
                const key = 'in-txt-' + Date.now() + '-' + Math.random();
                const item = { key, kind: 'text', dir: 'in', text: msg.text, status: 'done' };
                state.transfers.set(key, item);
                renderTransferCard(item);
                return;
            }
            if (msg.type === 'file-offer') {
                const key = 'in:' + msg.fileId;
                const item = {
                    key,
                    kind: 'file',
                    dir: 'in',
                    fileId: msg.fileId,
                    name: msg.name,
                    size: msg.size,
                    totalChunks: msg.totalChunks,
                    eccK: msg.eccK,
                    eccM: msg.eccM,
                    doneBytes: 0,
                    status: 'pending-accept',
                    statusText: '等待接收确认…',
                    hashStatus: '',
                    stripes: new Map(),
                    nextFlushStripe: 0,
                    chunks: [],
                    writable: null,
                    sha: new IncrementalSha256(),
                    expectedSha: null,
                    doneReceived: false
                };
                state.transfers.set(key, item);
                renderTransferCard(item);
                if (els.autoAccept.checked) {
                    await acceptIncomingFile(item, true);
                }
                return;
            }
            if (msg.type === 'file-accept') {
                const item = state.transfers.get('out:' + msg.fileId);
                if (item && item.resolveAccept) item.resolveAccept(true);
                return;
            }
            if (msg.type === 'file-reject') {
                const item = state.transfers.get('out:' + msg.fileId);
                if (item && item.resolveAccept) item.resolveAccept(false);
                return;
            }
            if (msg.type === 'file-done') {
                const item = state.transfers.get('in:' + msg.fileId);
                if (item) {
                    item.expectedSha = msg.sha256;
                    item.doneReceived = true;
                    await tryFinalizeIncomingFile(item);
                }
            }
        }

        async function acceptIncomingFile(item, isAuto = false) {
            if (item.status !== 'pending-accept') return;
            if (!isAuto && typeof window.showSaveFilePicker === 'function') {
                try {
                    const handle = await window.showSaveFilePicker({ suggestedName: item.name });
                    item.writable = await handle.createWritable();
                } catch (err) {
                    if (err && err.name === 'AbortError') return;
                }
            }
            item.status = 'receiving';
            item.statusText = item.writable ? '流式写入磁盘中…' : '接收中…';
            renderTransferCard(item);
            await sendControl({ type: 'file-accept', fileId: item.fileId });
        }

        async function rejectIncomingFile(item) {
            item.status = 'rejected';
            item.statusText = '已拒绝接收';
            renderTransferCard(item);
            await sendControl({ type: 'file-reject', fileId: item.fileId });
        }

        // --- Binary Chunk + RS ECC Receiver ---
        async function handleBinaryFrame(frame) {
            if (frame.length < HEADER_SIZE || frame[0] !== MAGIC_BYTE) return;
            const dv = new DataView(frame.buffer, frame.byteOffset, frame.byteLength);
            const flags = frame[1];
            const fileId = dv.getUint32(2, false);
            const stripeIdx = dv.getUint32(6, false);
            const shardIdx = frame[10];
            const payloadLen = dv.getUint16(11, false);
            const expectedCrc = dv.getUint32(13, false);
            const payload = frame.subarray(HEADER_SIZE);

            const item = state.transfers.get('in:' + fileId);
            if (!item || item.status === 'rejected') return;

            const ecc = state.ecc || await getEccEngine();
            const actualCrc = ecc.crc32(payload);
            if (actualCrc !== expectedCrc) {
                state.stats.crcBad++;
                updateStatsUi();
                return; // Treat corrupted shard as erasure so RS ECC can recover it
            }

            const k = item.eccK;
            const m = item.eccM;
            const n = k + m;
            if (shardIdx >= n) return;

            let st = item.stripes.get(stripeIdx);
            if (!st) {
                st = {
                    shards: new Uint8Array(n * CHUNK_SIZE),
                    present: new Uint8Array(n),
                    lens: new Uint16Array(k),
                    validCount: 0,
                    dataCount: 0,
                    flushed: false
                };
                const chunksInThisStripe = Math.min(k, item.totalChunks - stripeIdx * k);
                for (let z = chunksInThisStripe; z < k; z++) {
                    st.present[z] = 1;
                    st.validCount++;
                }
                item.stripes.set(stripeIdx, st);
            }
            if (st.flushed || st.present[shardIdx]) return;

            st.shards.set(payload.subarray(0, Math.min(payload.length, CHUNK_SIZE)), shardIdx * CHUNK_SIZE);
            st.present[shardIdx] = 1;
            st.validCount++;
            if ((flags & 1) === 0 && shardIdx < k) {
                st.lens[shardIdx] = payloadLen;
                st.dataCount++;
            }

            if (flags & 1) state.stats.parity++;
            await flushReadyStripes(item, ecc);
            await tryFinalizeIncomingFile(item);
        }

        async function flushReadyStripes(item, ecc) {
            const k = item.eccK;
            const m = item.eccM;
            while (true) {
                const st = item.stripes.get(item.nextFlushStripe);
                if (!st) break;

                const stripeStartChunk = item.nextFlushStripe * k;
                const chunksInThisStripe = Math.min(k, item.totalChunks - stripeStartChunk);
                const needReconstruct = st.dataCount < chunksInThisStripe;

                if (needReconstruct) {
                    if (m === 0 || st.validCount < k) break; // Wait for more shards
                    const rec = ecc.rsReconstruct(st.shards, st.present, k, m, CHUNK_SIZE);
                    if (rec < 0) break;
                    state.stats.recovered += rec;
                    for (let i = 0; i < chunksInThisStripe; i++) {
                        if (!st.present[i]) {
                            const chunkIdx = stripeStartChunk + i;
                            const isLast = chunkIdx === item.totalChunks - 1;
                            const rem = item.size - chunkIdx * CHUNK_SIZE;
                            st.lens[i] = isLast ? rem : CHUNK_SIZE;
                        }
                    }
                }

                st.flushed = true;
                item.stripes.delete(item.nextFlushStripe);
                item.nextFlushStripe++;

                for (let i = 0; i < chunksInThisStripe; i++) {
                    const len = st.lens[i];
                    const data = st.shards.subarray(i * CHUNK_SIZE, i * CHUNK_SIZE + len);
                    item.sha.update(data);
                    if (item.writable) {
                        await item.writable.write(data);
                    } else {
                        item.chunks.push(data.slice());
                    }
                    item.doneBytes += len;
                }
                state.stats.stripes++;
                updateStatsUi();
                renderTransferCard(item);
            }
        }

        async function tryFinalizeIncomingFile(item) {
            if (!item.doneReceived || item.status === 'done' || item.doneBytes < item.size) return;
            item.status = 'done';
            const actualSha = item.sha.digestHex();
            const matched = actualSha === item.expectedSha;
            item.hashStatus = matched ? 'SHA-256 校验通过 ✓' : 'SHA-256 校验失败 ✗';
            item.statusText = matched ? '接收完成' : '接收完成 (校验异常)';

            if (item.writable) {
                await item.writable.close();
                item.writable = null;
            } else {
                const blob = new Blob(item.chunks);
                item.chunks = [];
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = item.name;
                a.click();
                setTimeout(() => URL.revokeObjectURL(url), 10000);
            }
            renderTransferCard(item);
        }

        // --- File & Text Sending ---
        async function processSendQueue() {
            if (state.isSending) return;
            state.isSending = true;
            try {
                while (state.sendingQueue.length > 0) {
                    const item = state.sendingQueue.shift();
                    await sendSingleFile(item);
                }
            } finally {
                state.isSending = false;
            }
        }

        async function sendSingleFile(item) {
            if (!state.dc || state.dc.readyState !== 'open') return;
            const file = item.file;
            const totalChunks = Math.max(1, Math.ceil(file.size / CHUNK_SIZE));
            const profile = ECC_PROFILES[els.eccSelect.value] || ECC_PROFILES.std;
            const k = profile.k;
            const m = profile.m;

            item.statusText = '等待对方确认…';
            renderTransferCard(item);

            const acceptPromise = new Promise((res) => { item.resolveAccept = res; });
            await sendControl({
                type: 'file-offer',
                fileId: item.fileId,
                name: file.name,
                size: file.size,
                totalChunks,
                eccK: k,
                eccM: m
            });

            const accepted = await acceptPromise;
            if (!accepted) {
                item.status = 'rejected';
                item.statusText = '对方拒绝接收';
                renderTransferCard(item);
                return;
            }

            item.status = 'sending';
            item.statusText = '发送中…';
            renderTransferCard(item);

            const ecc = state.ecc || await getEccEngine();
            const sha = new IncrementalSha256();
            const totalStripes = Math.ceil(totalChunks / k);

            for (let stripeIdx = 0; stripeIdx < totalStripes; stripeIdx++) {
                const stripeData = new Uint8Array(k * CHUNK_SIZE);
                const startChunk = stripeIdx * k;
                const count = Math.min(k, totalChunks - startChunk);

                for (let i = 0; i < count; i++) {
                    const chunkIdx = startChunk + i;
                    const start = chunkIdx * CHUNK_SIZE;
                    const end = Math.min(file.size, start + CHUNK_SIZE);
                    const sliceBuf = new Uint8Array(await file.slice(start, end).arrayBuffer());
                    sha.update(sliceBuf);
                    stripeData.set(sliceBuf, i * CHUNK_SIZE);

                    const frame = new Uint8Array(HEADER_SIZE + sliceBuf.length);
                    const dv = new DataView(frame.buffer);
                    frame[0] = MAGIC_BYTE;
                    frame[1] = 0;
                    dv.setUint32(2, item.fileId, false);
                    dv.setUint32(6, stripeIdx, false);
                    frame[10] = i;
                    dv.setUint16(11, sliceBuf.length, false);
                    dv.setUint32(13, ecc.crc32(sliceBuf), false);
                    frame.set(sliceBuf, HEADER_SIZE);
                    await sendBinaryFrame(frame);

                    item.doneBytes += sliceBuf.length;
                }

                if (m > 0) {
                    const parity = ecc.rsEncode(stripeData, k, m, CHUNK_SIZE);
                    for (let p = 0; p < m; p++) {
                        const pSlice = parity.subarray(p * CHUNK_SIZE, (p + 1) * CHUNK_SIZE);
                        const frame = new Uint8Array(HEADER_SIZE + CHUNK_SIZE);
                        const dv = new DataView(frame.buffer);
                        frame[0] = MAGIC_BYTE;
                        frame[1] = 1;
                        dv.setUint32(2, item.fileId, false);
                        dv.setUint32(6, stripeIdx, false);
                        frame[10] = k + p;
                        dv.setUint16(11, CHUNK_SIZE, false);
                        dv.setUint32(13, ecc.crc32(pSlice), false);
                        frame.set(pSlice, HEADER_SIZE);
                        await sendBinaryFrame(frame);
                        state.stats.parity++;
                    }
                }
                state.stats.stripes++;
                updateStatsUi();
                renderTransferCard(item);
            }

            const shaHex = sha.digestHex();
            await sendControl({ type: 'file-done', fileId: item.fileId, sha256: shaHex });
            item.status = 'done';
            item.statusText = '发送完成';
            item.hashStatus = 'SHA-256: ' + shaHex.slice(0, 12) + '…';
            renderTransferCard(item);
        }

        function enqueueFiles(fileList) {
            const connected = Boolean(state.dc && state.dc.readyState === 'open');
            for (const file of fileList) {
                const fileId = state.nextFileId++;
                const item = {
                    key: 'out:' + fileId,
                    kind: 'file',
                    dir: 'out',
                    fileId,
                    file,
                    name: file.name,
                    size: file.size,
                    doneBytes: 0,
                    status: 'queued',
                    statusText: connected ? '排队中…' : '已加入队列，等待配对连接后自动发送…',
                    hashStatus: ''
                };
                state.transfers.set(item.key, item);
                renderTransferCard(item);
                state.sendingQueue.push(item);
            }
            if (connected) {
                processSendQueue();
            } else if (state.transferMode === 'easy' && !state.pc && !state.easyShareUrl) {
                startEasySendSession();
            }
        }

        els.fileInput.addEventListener('change', (e) => {
            if (e.target.files.length) enqueueFiles(e.target.files);
            e.target.value = '';
        });

        els.dropzone.addEventListener('dragover', (e) => {
            e.preventDefault();
            els.dropzone.classList.add('ft-dragover');
        });
        els.dropzone.addEventListener('dragleave', () => {
            els.dropzone.classList.remove('ft-dragover');
        });
        els.dropzone.addEventListener('drop', (e) => {
            e.preventDefault();
            els.dropzone.classList.remove('ft-dragover');
            if (e.dataTransfer && e.dataTransfer.files.length) enqueueFiles(e.dataTransfer.files);
        });

        els.sendTextBtn.addEventListener('click', async () => {
            const text = els.textMsgInput.value.trim();
            if (!text || !state.dc || state.dc.readyState !== 'open') return;
            await sendControl({ type: 'text-msg', text });
            const key = 'out-txt-' + Date.now() + '-' + Math.random();
            const item = { key, kind: 'text', dir: 'out', text, status: 'done' };
            state.transfers.set(key, item);
            renderTransferCard(item);
            els.textMsgInput.value = '';
        });

        els.clearDoneBtn.addEventListener('click', () => {
            for (const [k, v] of state.transfers.entries()) {
                if (v.status === 'done' || v.status === 'rejected') {
                    const card = document.getElementById('ft-card-' + k);
                    if (card) card.remove();
                    state.transfers.delete(k);
                }
            }
            if (state.transfers.size === 0) els.emptyState.classList.remove('hidden');
        });

        async function startEasySendSession() {
            resetPeerConnection();
            if (els.easyStatusText) els.easyStatusText.textContent = '正在准备分享链接…';
            setConnStatus('准备中…', 'wait');
            try {
                const pc = new RTCPeerConnection({ iceServers: buildIceServers() });
                state.pc = pc;
                const dc = pc.createDataChannel('windblog-ft', { ordered: true });
                attachDataChannel(dc);

                const offer = await pc.createOffer();
                await pc.setLocalDescription(offer);
                await gatherIceComplete(pc);

                const token = await encodeSignalToken({ t: 'O', s: pc.localDescription.sdp }, '');
                const csrfToken = typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';
                const resp = await fetch('/api/public/ft-signal/rooms', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {})
                    },
                    body: JSON.stringify({ offer: token })
                });
                if (!resp.ok) throw new Error('创建分享链接失败');
                const data = await resp.json();
                state.easyShareUrl = window.location.href.split('#')[0] + '#easy=' + encodeURIComponent(data.roomId);
                if (els.easyCopyLinkBtn) els.easyCopyLinkBtn.disabled = false;
                if (els.easyStatusText) {
                    els.easyStatusText.textContent = '分享链接已就绪！点击「复制分享链接」发给对方，对方打开后将自动开始传输。';
                }
                setConnStatus('等待对方打开链接', 'wait');

                state.easyPollTimer = setInterval(async () => {
                    if (state.disposed || !state.pc) {
                        clearInterval(state.easyPollTimer);
                        state.easyPollTimer = null;
                        return;
                    }
                    try {
                        const poll = await fetch('/api/public/ft-signal/rooms/' + encodeURIComponent(data.roomId) + '/answer?hostToken=' + encodeURIComponent(data.hostToken));
                        if (!poll.ok) return;
                        const res = await poll.json();
                        if (res.ready && res.answer) {
                            clearInterval(state.easyPollTimer);
                            state.easyPollTimer = null;
                            const payload = await decodeSignalToken(res.answer, '');
                            if (payload.t === 'A' && payload.s && state.pc) {
                                await state.pc.setRemoteDescription({ type: 'answer', sdp: payload.s });
                                if (els.easyStatusText) els.easyStatusText.textContent = '对方已打开链接，正在建立连接并自动传输…';
                                setConnStatus('正在连接…', 'wait');
                            }
                        }
                    } catch (_) {}
                }, 1200);
            } catch (err) {
                if (els.easyStatusText) els.easyStatusText.textContent = '生成分享链接失败，请重试或切换高级模式。';
                setConnStatus('准备失败', 'err');
            }
        }

        async function joinEasySession(roomId) {
            resetPeerConnection();
            if (els.easyActionBtns) els.easyActionBtns.classList.add('hidden');
            if (els.easyStatusText) els.easyStatusText.textContent = '正在连接发送方，连接后将自动开始接收文件…';
            setConnStatus('正在连接…', 'wait');
            try {
                const resp = await fetch('/api/public/ft-signal/rooms/' + encodeURIComponent(roomId));
                if (!resp.ok) throw new Error('分享链接已失效或不存在');
                const data = await resp.json();
                const payload = await decodeSignalToken(data.offer, '');
                if (payload.t !== 'O' || !payload.s) throw new Error('分享链接无效');

                const pc = new RTCPeerConnection({ iceServers: buildIceServers() });
                state.pc = pc;
                pc.ondatachannel = (ev) => attachDataChannel(ev.channel);
                await pc.setRemoteDescription({ type: 'offer', sdp: payload.s });
                const answer = await pc.createAnswer();
                await pc.setLocalDescription(answer);
                await gatherIceComplete(pc);

                const token = await encodeSignalToken({ t: 'A', s: pc.localDescription.sdp }, '');
                const csrfToken = typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';
                const postResp = await fetch('/api/public/ft-signal/rooms/' + encodeURIComponent(roomId) + '/answer', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        ...(csrfToken ? { 'X-XSRF-TOKEN': csrfToken } : {})
                    },
                    body: JSON.stringify({ answer: token })
                });
                if (!postResp.ok) throw new Error('连接响应失败');
                if (els.easyStatusText) els.easyStatusText.textContent = '已通知发送方，正在等待文件传输开始…';
            } catch (err) {
                if (els.easyStatusText) els.easyStatusText.textContent = err.message || '连接失败，请让发送方重新生成链接。';
                setConnStatus('连接失败', 'err');
            }
        }

        function setTransferMode(mode) {
            state.transferMode = mode === 'advanced' ? 'advanced' : 'easy';
            const isEasy = state.transferMode === 'easy';
            if (els.modeEasyBtn && els.modeAdvBtn) {
                els.modeEasyBtn.className = isEasy
                    ? 'px-4 py-2 rounded-lg bg-accent text-white font-bold transition-all cursor-pointer flex items-center gap-1.5'
                    : 'px-4 py-2 rounded-lg text-meta hover:text-main font-semibold transition-all cursor-pointer flex items-center gap-1.5';
                els.modeAdvBtn.className = !isEasy
                    ? 'px-4 py-2 rounded-lg bg-accent text-white font-bold transition-all cursor-pointer flex items-center gap-1.5'
                    : 'px-4 py-2 rounded-lg text-meta hover:text-main font-semibold transition-all cursor-pointer flex items-center gap-1.5';
            }
            if (els.easyCard) els.easyCard.classList.toggle('hidden', !isEasy);
            if (els.easyGuaranteeBadge) els.easyGuaranteeBadge.classList.toggle('hidden', !isEasy);
            if (els.easySubtitle) els.easySubtitle.classList.toggle('hidden', !isEasy);
            if (els.techSubtitle) els.techSubtitle.classList.toggle('hidden', isEasy);
            if (els.techBadges) els.techBadges.classList.toggle('hidden', isEasy);
            if (els.natProbeBtn) els.natProbeBtn.classList.toggle('hidden', isEasy);
            if (els.guideSection) els.guideSection.classList.toggle('hidden', isEasy);
            if (els.securitySettingsBox) els.securitySettingsBox.classList.toggle('hidden', isEasy);
            if (els.pairingSection) els.pairingSection.classList.toggle('hidden', isEasy);
            const pairingHeading = document.getElementById('ftPairingHeading');
            if (pairingHeading) pairingHeading.classList.toggle('hidden', isEasy);
            if (isEasy && els.sessionBar) els.sessionBar.classList.add('hidden');

            if (isEasy) {
                els.e2eeToggle.checked = true;
                els.eccSelect.value = 'std';
                els.autoAccept.checked = true;
                els.channelMode.value = 'reliable';
                els.iceMode.value = 'public';
                els.signalPass.value = '';
                state.e2eeEnabled = true;
            }
        }

        if (els.modeEasyBtn) els.modeEasyBtn.addEventListener('click', () => setTransferMode('easy'));
        if (els.modeAdvBtn) els.modeAdvBtn.addEventListener('click', () => setTransferMode('advanced'));
        if (els.easyPickFileBtn) els.easyPickFileBtn.addEventListener('click', () => els.fileInput.click());
        if (els.easyCopyLinkBtn) {
            els.easyCopyLinkBtn.addEventListener('click', () => {
                if (!state.easyShareUrl) return;
                navigator.clipboard.writeText(state.easyShareUrl).then(() => {
                    const orig = els.easyCopyLinkBtn.innerHTML;
                    els.easyCopyLinkBtn.textContent = '已复制分享链接！';
                    setTimeout(() => { els.easyCopyLinkBtn.innerHTML = orig; }, 1500);
                }).catch(() => {});
            });
        }

        // Default to Easy Mode ("省心传")
        setTransferMode('easy');

        // Auto-join #easy= or auto-fill #offer= from URL hash
        if (window.location.hash && window.location.hash.startsWith('#easy=')) {
            const roomId = decodeURIComponent(window.location.hash.slice('#easy='.length));
            if (roomId) {
                setTransferMode('easy');
                joinEasySession(roomId);
            }
        } else if (window.location.hash && window.location.hash.startsWith('#offer=')) {
            setTransferMode('advanced');
            switchRole('join');
            els.offerIn.value = decodeURIComponent(window.location.hash.slice('#offer='.length));
        }

        setConnStatus('未配对', 'idle');

        activeUnmount = function () {
            state.disposed = true;
            resetPeerConnection();
            activeUnmount = null;
        };
    }

    window.WindBlogFileTransfer = {
        mount: initFileTransfer,
        unmount: () => {
            if (activeUnmount) activeUnmount();
        }
    };
})();
