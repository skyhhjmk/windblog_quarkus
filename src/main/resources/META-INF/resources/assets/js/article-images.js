(function () {
    'use strict';

    function thumbHashFromBase64(str) {
        if (!str || typeof str !== 'string') return null;
        try {
            var binary = atob(str.trim());
            var bytes = new Uint8Array(binary.length);
            for (var i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        } catch (e) {
            return null;
        }
    }

    function thumbHashToApproximateAspectRatio(hash) {
        var header = hash[3];
        var hasAlpha = (hash[2] & 0x80) !== 0;
        var isLandscape = (hash[4] & 0x80) !== 0;
        var lx = isLandscape ? (hasAlpha ? 5 : 7) : (header & 7);
        var ly = isLandscape ? (header & 7) : (hasAlpha ? 5 : 7);
        return lx / ly;
    }

    function thumbHashToRGBA(hash) {
        var PI = Math.PI, min = Math.min, max = Math.max, cos = Math.cos, round = Math.round;
        var header24 = hash[0] | (hash[1] << 8) | (hash[2] << 16);
        var header16 = hash[3] | (hash[4] << 8);
        var l_dc = (header24 & 63) / 63;
        var p_dc = ((header24 >> 6) & 63) / 31.5 - 1;
        var q_dc = ((header24 >> 12) & 63) / 31.5 - 1;
        var l_scale = ((header24 >> 18) & 31) / 31;
        var hasAlpha = (header24 >> 23) !== 0;
        var p_scale = ((header16 >> 3) & 63) / 63;
        var q_scale = ((header16 >> 9) & 63) / 63;
        var isLandscape = (header16 >> 15) !== 0;
        var lx = max(3, isLandscape ? (hasAlpha ? 5 : 7) : (header16 & 7));
        var ly = max(3, isLandscape ? (header16 & 7) : (hasAlpha ? 5 : 7));
        var a_dc = hasAlpha ? (hash[5] & 15) / 15 : 1;
        var a_scale = hasAlpha ? (hash[5] >> 4) / 15 : 0;

        var ac_start = hasAlpha ? 6 : 5;
        var ac_index = 0;
        function decodeChannel(nx, ny, scale) {
            var ac = [];
            for (var cy = 0; cy < ny; cy++) {
                for (var cx = cy ? 0 : 1; cx * ny < nx * (ny - cy); cx++) {
                    ac.push((((hash[ac_start + (ac_index >> 1)] >> ((ac_index++ & 1) << 2)) & 15) / 7.5 - 1) * scale);
                }
            }
            return ac;
        }
        var l_ac = decodeChannel(lx, ly, l_scale);
        var p_ac = decodeChannel(3, 3, p_scale * 1.25);
        var q_ac = decodeChannel(3, 3, q_scale * 1.25);
        var a_ac = hasAlpha ? decodeChannel(5, 5, a_scale) : null;

        var ratio = thumbHashToApproximateAspectRatio(hash);
        var w = round(ratio > 1 ? 32 : 32 * ratio);
        var h = round(ratio > 1 ? 32 / ratio : 32);
        w = max(1, w);
        h = max(1, h);

        var rgba = new Uint8Array(w * h * 4);
        var fx = new Float32Array(7);
        var fy = new Float32Array(7);

        for (var y = 0, i = 0; y < h; y++) {
            for (var x = 0; x < w; x++, i += 4) {
                var l = l_dc, p = p_dc, q = q_dc, a = a_dc;

                for (var cx = 0, n = max(lx, hasAlpha ? 5 : 3); cx < n; cx++) {
                    fx[cx] = cos(PI / w * (x + 0.5) * cx);
                }
                for (var cy = 0, n = max(ly, hasAlpha ? 5 : 3); cy < n; cy++) {
                    fy[cy] = cos(PI / h * (y + 0.5) * cy);
                }

                for (var cy = 0, j = 0; cy < ly; cy++) {
                    var fy2 = fy[cy] * 2;
                    for (var cx = cy ? 0 : 1; cx * ly < lx * (ly - cy); cx++, j++) {
                        l += l_ac[j] * fx[cx] * fy2;
                    }
                }

                for (var cy = 0, j = 0; cy < 3; cy++) {
                    var fy2 = fy[cy] * 2;
                    for (var cx = cy ? 0 : 1; cx < 3 - cy; cx++, j++) {
                        var f = fx[cx] * fy2;
                        p += p_ac[j] * f;
                        q += q_ac[j] * f;
                    }
                }

                if (hasAlpha) {
                    for (var cy = 0, j = 0; cy < 5; cy++) {
                        var fy2 = fy[cy] * 2;
                        for (var cx = cy ? 0 : 1; cx < 5 - cy; cx++, j++) {
                            a += a_ac[j] * fx[cx] * fy2;
                        }
                    }
                }

                var b = l - 2 / 3 * p;
                var r = (3 * l - b + q) / 2;
                var g = r - q;

                rgba[i] = max(0, round(255 * min(1, r)));
                rgba[i + 1] = max(0, round(255 * min(1, g)));
                rgba[i + 2] = max(0, round(255 * min(1, b)));
                rgba[i + 3] = max(0, round(255 * min(1, a)));
            }
        }
        return { w: w, h: h, rgba: rgba };
    }

    function renderThumbHash(canvas) {
        if (!canvas) return;
        var b64 = canvas.getAttribute('data-thumbhash');
        if (!b64 || canvas.dataset.thumbhashRendered === 'true') {
            return;
        }
        var bytes = thumbHashFromBase64(b64);
        if (!bytes || bytes.length < 5) return;
        try {
            var decoded = thumbHashToRGBA(bytes);
            canvas.width = decoded.w;
            canvas.height = decoded.h;
            var ctx = canvas.getContext('2d');
            if (ctx) {
                var imgData = ctx.createImageData(decoded.w, decoded.h);
                imgData.data.set(decoded.rgba);
                ctx.putImageData(imgData, 0, 0);
                canvas.dataset.thumbhashRendered = 'true';
            }
        } catch (e) {
            // Ignore decoding failure gracefully
        }
    }

    window.renderThumbHash = renderThumbHash;

    function hasReservedDimensions(image) {
        var width = Number.parseInt(image.getAttribute('width'), 10);
        var height = Number.parseInt(image.getAttribute('height'), 10);
        return Number.isFinite(width) && width > 0 && width <= 100000
            && Number.isFinite(height) && height > 0 && height <= 100000;
    }

    function setImageState(image, state) {
        image.classList.remove('article-image-loading', 'article-image-loaded', 'article-image-error');
        image.classList.add('article-image-' + state);
        if (state === 'loaded') {
            image.classList.add('is-loaded');
        }
    }

    function markLoaded(image) {
        if (!image) return;
        image.classList.add('is-loaded');
        var parent = image.closest('.has-thumbhash, .image-wrapper, .post-list-cover, .post-header-cover');
        if (parent) {
            var placeholder = parent.querySelector('.placeholder');
            if (placeholder) {
                placeholder.classList.add('is-hidden');
            }
        }
    }

    function prepareImage(image) {
        if (!image.getAttribute('src') || !image.getAttribute('src').trim() || image.dataset.articleImagePrepared === 'true') {
            return;
        }

        image.dataset.articleImagePrepared = 'true';
        if (!image.getAttribute('loading')) {
            image.setAttribute('loading', 'lazy');
        }
        if (!image.getAttribute('decoding')) {
            image.setAttribute('decoding', 'async');
        }

        var wrapper = image.closest('.image-wrapper');
        if (wrapper) {
            wrapper.classList.add('has-thumbhash');
        }

        image.addEventListener('load', function () {
            markLoaded(image);
            if (hasReservedDimensions(image)) {
                setImageState(image, 'loaded');
            }
        }, { once: true });
        image.addEventListener('error', function () {
            if (hasReservedDimensions(image)) {
                setImageState(image, 'error');
            }
        }, { once: true });

        if (image.complete && image.naturalWidth > 0) {
            markLoaded(image);
            if (hasReservedDimensions(image)) {
                setImageState(image, 'loaded');
            }
        } else if (image.complete && hasReservedDimensions(image)) {
            setImageState(image, 'error');
        } else if (hasReservedDimensions(image)) {
            setImageState(image, 'loading');
        }
    }

    function updateCoverCropAlignment(image) {
        if (!image || !(image.naturalWidth > 0 && image.naturalHeight > 0)) {
            return;
        }
        var aspect = image.naturalHeight / image.naturalWidth;
        if (aspect >= 1.15) {
            image.classList.add('is-tall-crop');
        } else {
            image.classList.remove('is-tall-crop');
        }
    }

    function prepareCoverImage(image) {
        if (!image || !image.getAttribute('src') || !image.getAttribute('src').trim()
                || image.dataset.coverImagePrepared === 'true') {
            return;
        }
        image.dataset.coverImagePrepared = 'true';
        if (!image.getAttribute('loading')) {
            image.setAttribute('loading', 'lazy');
        }
        if (!image.getAttribute('decoding')) {
            image.setAttribute('decoding', 'async');
        }

        var container = image.closest('.post-list-cover, .post-header-cover');
        if (container && container.querySelector('.placeholder[data-thumbhash]')) {
            container.classList.add('has-thumbhash');
        }

        image.addEventListener('load', function () {
            markLoaded(image);
            updateCoverCropAlignment(image);
        }, { once: true });
        if (image.complete && image.naturalWidth > 0) {
            markLoaded(image);
            updateCoverCropAlignment(image);
        }
    }

    function prepareThumbHashes(root) {
        if (!root) return;
        var canvases = [];
        if (root.matches && root.matches('canvas[data-thumbhash]')) {
            canvases.push(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('canvas[data-thumbhash]').forEach(function (c) {
                canvases.push(c);
            });
        }
        canvases.forEach(function (canvas) {
            var parent = canvas.closest('.image-wrapper, .post-list-cover, .post-header-cover');
            if (parent) {
                parent.classList.add('has-thumbhash');
            }
            renderThumbHash(canvas);
        });
    }

    function prepareImages(root) {
        if (!root) {
            return;
        }
        prepareThumbHashes(root);
        if (root.matches && root.matches('.prose img[src]')) {
            prepareImage(root);
        }
        if (root.matches && root.matches('.post-list-cover-img[src], .post-header-cover-img[src]')) {
            prepareCoverImage(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('.prose img[src]').forEach(prepareImage);
            root.querySelectorAll('.post-list-cover-img[src], .post-header-cover-img[src]').forEach(prepareCoverImage);
        }
    }

    WindBlogLifecycle.register('article-images', {
        mount() {
            prepareImages(document.getElementById('pjax-container') || document);
        }
    });
})();
