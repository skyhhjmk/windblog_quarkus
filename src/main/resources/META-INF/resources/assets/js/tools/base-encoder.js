/**
 * Base64 及常见 Base 变体 (Base16, Base32, Base58, Base85) 编解码交互脚本
 */
(function () {
    'use strict';

    function initBaseEncoder() {
        const root = document.getElementById('baseEncoderToolApp');
        if (!root) return;

        const inputText = document.getElementById('baseInputText');
        const outputPrimary = document.getElementById('baseOutputPrimary');
        const btnModeEncode = document.getElementById('btnModeEncode');
        const btnModeDecode = document.getElementById('btnModeDecode');
        const baseInputLabel = document.getElementById('baseInputLabel');
        const baseOutputLabel = document.getElementById('baseOutputLabel');
        const baseInputStats = document.getElementById('baseInputStats');
        const baseOutputStats = document.getElementById('baseOutputStats');
        const btnClear = document.getElementById('btnClearBaseInput');
        const btnCopyPrimary = document.getElementById('btnCopyPrimaryBase');
        const chkUrlSafe = document.getElementById('chkUrlSafe');
        const variantsContainer = document.getElementById('baseVariantsContainer');

        let currentMode = 'encode'; // 'encode' or 'decode'

        // UTF-8 Helpers
        function stringToUtf8Bytes(str) {
            return new TextEncoder().encode(str);
        }

        function utf8BytesToString(bytes) {
            return new TextDecoder().decode(bytes);
        }

        // Standard Base64
        function bytesToBase64(bytes) {
            let binary = '';
            const len = bytes.byteLength;
            for (let i = 0; i < len; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary);
        }

        function base64ToBytes(base64Str) {
            // Un-urlsafe if needed
            let normalized = base64Str.replace(/-/g, '+').replace(/_/g, '/');
            while (normalized.length % 4 !== 0) {
                normalized += '=';
            }
            const binary = atob(normalized);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        }

        // Base16 (Hex)
        function bytesToHex(bytes) {
            return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
        }

        function hexToBytes(hex) {
            const clean = hex.replace(/[^0-9a-fA-F]/g, '');
            const bytes = new Uint8Array(clean.length / 2);
            for (let i = 0; i < clean.length; i += 2) {
                bytes[i / 2] = parseInt(clean.substr(i, 2), 16);
            }
            return bytes;
        }

        // Base32 (RFC 4648)
        const B32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
        function bytesToBase32(bytes) {
            let bits = 0;
            let value = 0;
            let output = '';
            for (let i = 0; i < bytes.length; i++) {
                value = (value << 8) | bytes[i];
                bits += 8;
                while (bits >= 5) {
                    output += B32_ALPHABET[(value >>> (bits - 5)) & 31];
                    bits -= 5;
                }
            }
            if (bits > 0) {
                output += B32_ALPHABET[(value << (5 - bits)) & 31];
            }
            while (output.length % 8 !== 0) {
                output += '=';
            }
            return output;
        }

        function base32ToBytes(str) {
            const clean = str.toUpperCase().replace(/=+$/, '');
            let bits = 0;
            let value = 0;
            let index = 0;
            const bytes = new Uint8Array(Math.floor((clean.length * 5) / 8));
            for (let i = 0; i < clean.length; i++) {
                const val = B32_ALPHABET.indexOf(clean[i]);
                if (val === -1) continue;
                value = (value << 5) | val;
                bits += 5;
                if (bits >= 8) {
                    bytes[index++] = (value >>> (bits - 8)) & 255;
                    bits -= 8;
                }
            }
            return bytes.slice(0, index);
        }

        // Base58 (Bitcoin / IPFS)
        const B58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
        function bytesToBase58(bytes) {
            if (bytes.length === 0) return '';
            let zeros = 0;
            while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
            const digits = [0];
            for (let i = zeros; i < bytes.length; i++) {
                let carry = bytes[i];
                for (let j = 0; j < digits.length; j++) {
                    carry += digits[j] << 8;
                    digits[j] = carry % 58;
                    carry = (carry / 58) | 0;
                }
                while (carry > 0) {
                    digits.push(carry % 58);
                    carry = (carry / 58) | 0;
                }
            }
            let output = '';
            for (let i = 0; i < zeros; i++) output += '1';
            for (let i = digits.length - 1; i >= 0; i--) output += B58_ALPHABET[digits[i]];
            return output;
        }

        function base58ToBytes(str) {
            if (str.length === 0) return new Uint8Array(0);
            let zeros = 0;
            while (zeros < str.length && str[zeros] === '1') zeros++;
            const bytes = [0];
            for (let i = zeros; i < str.length; i++) {
                const val = B58_ALPHABET.indexOf(str[i]);
                if (val === -1) continue;
                let carry = val;
                for (let j = 0; j < bytes.length; j++) {
                    carry += bytes[j] * 58;
                    bytes[j] = carry & 255;
                    carry = carry >> 8;
                }
                while (carry > 0) {
                    bytes.push(carry & 255);
                    carry = carry >> 8;
                }
            }
            const out = new Uint8Array(zeros + bytes.length);
            for (let i = 0; i < bytes.length; i++) {
                out[zeros + i] = bytes[bytes.length - 1 - i];
            }
            return out;
        }

        function executeConversion() {
            const input = inputText.value;
            baseInputStats.textContent = `${input.length} 字符`;

            if (!input) {
                outputPrimary.value = '';
                baseOutputStats.textContent = '0 字符';
                variantsContainer.innerHTML = '<p class="text-meta/60 italic text-center py-4">等待输入...</p>';
                return;
            }

            try {
                if (currentMode === 'encode') {
                    const bytes = stringToUtf8Bytes(input);
                    let b64 = bytesToBase64(bytes);
                    if (chkUrlSafe.checked) {
                        b64 = b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                    }
                    outputPrimary.value = b64;
                    baseOutputStats.textContent = `${b64.length} 字符`;

                    const hex = bytesToHex(bytes);
                    const b32 = bytesToBase32(bytes);
                    const b58 = bytesToBase58(bytes);

                    renderVariants([
                        { name: 'Base16 (十六进制 Hex)', val: hex },
                        { name: 'Base32 (RFC 4648)', val: b32 },
                        { name: 'Base58 (比特币/区块链)', val: b58 },
                        { name: 'Base64 URL-Safe', val: bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }
                    ]);
                } else {
                    // Decode mode
                    let decodedBytes = null;
                    let decodeType = 'Base64';

                    try {
                        decodedBytes = base64ToBytes(input.trim());
                    } catch (e) {
                        outputPrimary.value = '无法解析为合法的 Base64 编码，请检查输入格式。';
                        baseOutputStats.textContent = '错误';
                        return;
                    }

                    const plainText = utf8BytesToString(decodedBytes);
                    outputPrimary.value = plainText;
                    baseOutputStats.textContent = `${plainText.length} 字符`;

                    renderVariants([
                        { name: 'Hex 十六进制转文本尝试', val: tryDecodeHex(input.trim()) },
                        { name: 'Base32 解码尝试', val: tryDecodeBase32(input.trim()) },
                        { name: 'Base58 解码尝试', val: tryDecodeBase58(input.trim()) }
                    ]);
                }
            } catch (err) {
                outputPrimary.value = `转换发生错误: ${err.message}`;
            }
        }

        function tryDecodeHex(str) {
            try { return utf8BytesToString(hexToBytes(str)); } catch (e) { return '(无法解析为 Hex)'; }
        }
        function tryDecodeBase32(str) {
            try { return utf8BytesToString(base32ToBytes(str)); } catch (e) { return '(无法解析为 Base32)'; }
        }
        function tryDecodeBase58(str) {
            try { return utf8BytesToString(base58ToBytes(str)); } catch (e) { return '(无法解析为 Base58)'; }
        }

        function renderVariants(variants) {
            variantsContainer.innerHTML = '';
            variants.forEach(item => {
                const row = document.createElement('div');
                row.className = 'p-2.5 rounded-xl bg-input-bg border border-border/60 flex items-center justify-between gap-3';
                row.innerHTML = `
                    <div class="min-w-0 flex-1">
                        <span class="text-[10px] text-meta block">${item.name}</span>
                        <span class="text-main font-semibold select-all block truncate">${item.val || '-'}</span>
                    </div>
                    <button type="button" class="btn-copy-var px-2 py-1 rounded bg-card border border-border text-meta hover:text-accent text-[11px] shrink-0" data-val="${item.val}">
                        <i class="fa-regular fa-copy"></i>
                    </button>
                `;
                variantsContainer.appendChild(row);
            });

            variantsContainer.querySelectorAll('.btn-copy-var').forEach(btn => {
                btn.addEventListener('click', () => {
                    const text = btn.getAttribute('data-val');
                    if (text) {
                        navigator.clipboard.writeText(text);
                        btn.innerHTML = '<i class="fa-solid fa-check text-emerald-500"></i>';
                        setTimeout(() => { btn.innerHTML = '<i class="fa-regular fa-copy"></i>'; }, 1500);
                    }
                });
            });
        }

        // Mode switch
        btnModeEncode.addEventListener('click', () => {
            currentMode = 'encode';
            btnModeEncode.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnModeDecode.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            baseInputLabel.textContent = '输入明文字符串 (Plain Text):';
            baseOutputLabel.textContent = '标准 Base64 编码结果:';
            executeConversion();
        });

        btnModeDecode.addEventListener('click', () => {
            currentMode = 'decode';
            btnModeDecode.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnModeEncode.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            baseInputLabel.textContent = '输入已编码的 Base64 字符串:';
            baseOutputLabel.textContent = '解码后的明文 (Plain Text):';
            executeConversion();
        });

        inputText.addEventListener('input', executeConversion);
        chkUrlSafe.addEventListener('change', executeConversion);

        btnClear.addEventListener('click', () => {
            inputText.value = '';
            executeConversion();
        });

        btnCopyPrimary.addEventListener('click', () => {
            if (outputPrimary.value) {
                navigator.clipboard.writeText(outputPrimary.value);
                const orig = btnCopyPrimary.innerHTML;
                btnCopyPrimary.innerHTML = '<i class="fa-solid fa-check text-emerald-500 mr-1"></i>已复制';
                setTimeout(() => { btnCopyPrimary.innerHTML = orig; }, 1500);
            }
        });

        // Initialize with default demo
        inputText.value = 'Hello WindBlog! 欢迎使用新一代在线实用工具箱 🚀';
        executeConversion();
    }

    if (document.readyState !== 'loading') {
        initBaseEncoder();
    } else {
        document.addEventListener('DOMContentLoaded', initBaseEncoder);
    }
    document.addEventListener('pjax:complete', initBaseEncoder);
})();
