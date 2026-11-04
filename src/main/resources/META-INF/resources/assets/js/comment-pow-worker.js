/*
 * Invisible comment proof-of-work solver.
 *
 * Runs SHA-256 in a dedicated worker so the comment form stays responsive while
 * the browser searches for a nonce whose digest starts with the required number
 * of zero bits. The protocol is intentionally tiny:
 *   in : { challenge: <signed captcha id>, difficulty: <leading zero bits> }
 *   out: { ok: true, nonce: "<decimal>" } | { ok: false, reason: <string> }
 */
(function () {
    'use strict';

    var K = new Uint32Array([
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ]);

    var H0 = new Uint32Array([
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ]);

    function rotr(value, bits) {
        return (value >>> bits) | (value << (32 - bits));
    }

    function sha256Bytes(bytes) {
        var length = bytes.length;
        var paddedLength = ((length + 9 + 63) >> 6) << 6;
        var padded = new Uint8Array(paddedLength);
        padded.set(bytes);
        padded[length] = 0x80;
        var view = new DataView(padded.buffer);
        var bitLength = length * 8;
        view.setUint32(paddedLength - 8, Math.floor(bitLength / 0x100000000), false);
        view.setUint32(paddedLength - 4, bitLength >>> 0, false);

        var h0 = H0[0], h1 = H0[1], h2 = H0[2], h3 = H0[3];
        var h4 = H0[4], h5 = H0[5], h6 = H0[6], h7 = H0[7];
        var W = new Uint32Array(64);

        for (var offset = 0; offset < paddedLength; offset += 64) {
            for (var index = 0; index < 16; index = index + 1) {
                W[index] = view.getUint32(offset + index * 4, false);
            }
            for (var round = 16; round < 64; round = round + 1) {
                var w15 = W[round - 15];
                var w2 = W[round - 2];
                var s0 = rotr(w15, 7) ^ rotr(w15, 18) ^ (w15 >>> 3);
                var s1 = rotr(w2, 17) ^ rotr(w2, 19) ^ (w2 >>> 10);
                W[round] = (W[round - 16] + s0 + W[round - 7] + s1) >>> 0;
            }

            var a = h0, b = h1, c = h2, d = h3;
            var e = h4, f = h5, g = h6, h = h7;
            for (var step = 0; step < 64; step = step + 1) {
                var sum1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                var choose = (e & f) ^ (~e & g);
                var temp1 = (h + sum1 + choose + K[step] + W[step]) >>> 0;
                var sum0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                var majority = (a & b) ^ (a & c) ^ (b & c);
                var temp2 = (sum0 + majority) >>> 0;
                h = g;
                g = f;
                f = e;
                e = (d + temp1) >>> 0;
                d = c;
                c = b;
                b = a;
                a = (temp1 + temp2) >>> 0;
            }

            h0 = (h0 + a) >>> 0;
            h1 = (h1 + b) >>> 0;
            h2 = (h2 + c) >>> 0;
            h3 = (h3 + d) >>> 0;
            h4 = (h4 + e) >>> 0;
            h5 = (h5 + f) >>> 0;
            h6 = (h6 + g) >>> 0;
            h7 = (h7 + h) >>> 0;
        }

        var digest = new Uint8Array(32);
        var digestView = new DataView(digest.buffer);
        digestView.setUint32(0, h0, false);
        digestView.setUint32(4, h1, false);
        digestView.setUint32(8, h2, false);
        digestView.setUint32(12, h3, false);
        digestView.setUint32(16, h4, false);
        digestView.setUint32(20, h5, false);
        digestView.setUint32(24, h6, false);
        digestView.setUint32(28, h7, false);
        return digest;
    }

    function leadingZeroBits(digest) {
        var bits = 0;
        for (var index = 0; index < digest.length; index = index + 1) {
            var value = digest[index];
            if (value === 0) {
                bits = bits + 8;
                continue;
            }
            return bits + Math.clz32(value) - 24;
        }
        return bits;
    }

    function solve(challenge, difficulty) {
        var prefix = new TextEncoder().encode(challenge + ':');
        var buffer = new Uint8Array(prefix.length + 20);
        buffer.set(prefix, 0);
        var nonce = 0;
        while (true) {
            var text = String(nonce);
            for (var index = 0; index < text.length; index = index + 1) {
                buffer[prefix.length + index] = text.charCodeAt(index);
            }
            var digest = sha256Bytes(buffer.subarray(0, prefix.length + text.length));
            if (leadingZeroBits(digest) >= difficulty) {
                return text;
            }
            nonce = nonce + 1;
        }
    }

    var core = { sha256Bytes: sha256Bytes, leadingZeroBits: leadingZeroBits };
    if (typeof self !== 'undefined') {
        self.WindBlogPowCore = core;
        if (typeof self.postMessage === 'function') {
            self.onmessage = function (event) {
                var data = event && event.data ? event.data : {};
                var challenge = typeof data.challenge === 'string' ? data.challenge : '';
                var difficulty = Number(data.difficulty);
                if (!challenge || !isFinite(difficulty) || difficulty < 1 || difficulty > 32) {
                    self.postMessage({ ok: false, reason: 'invalid' });
                    return;
                }
                var nonce = solve(challenge, difficulty);
                self.postMessage({ ok: true, nonce: nonce });
            };
        }
    }
})();
