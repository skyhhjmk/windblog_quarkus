(function () {
    'use strict';

    function hasReservedDimensions(image) {
        var width = Number.parseInt(image.getAttribute('width'), 10);
        var height = Number.parseInt(image.getAttribute('height'), 10);
        return Number.isFinite(width) && width > 0 && width <= 100000
            && Number.isFinite(height) && height > 0 && height <= 100000;
    }

    function setImageState(image, state) {
        image.classList.remove('article-image-loading', 'article-image-loaded', 'article-image-error');
        image.classList.add('article-image-' + state);
    }

    function prepareImage(image) {
        if (!image.getAttribute('src').trim() || image.dataset.articleImagePrepared === 'true') {
            return;
        }

        image.dataset.articleImagePrepared = 'true';
        // Lazy loading and async decoding do not need reserved dimensions, so
        // every article image gets them. The shimmer needs a known box, so it
        // stays limited to images rendered with width/height.
        if (!image.getAttribute('loading')) {
            image.setAttribute('loading', 'lazy');
        }
        if (!image.getAttribute('decoding')) {
            image.setAttribute('decoding', 'async');
        }
        if (!hasReservedDimensions(image)) {
            return;
        }
        setImageState(image, 'loading');
        image.addEventListener('load', function () {
            setImageState(image, 'loaded');
        }, { once: true });
        image.addEventListener('error', function () {
            setImageState(image, 'error');
        }, { once: true });

        if (image.complete && image.naturalWidth > 0) {
            setImageState(image, 'loaded');
        } else if (image.complete) {
            setImageState(image, 'error');
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
        image.addEventListener('load', function () {
            updateCoverCropAlignment(image);
        }, { once: true });
        if (image.complete && image.naturalWidth > 0) {
            updateCoverCropAlignment(image);
        }
    }

    function prepareImages(root) {
        if (!root) {
            return;
        }
        if (root.matches && root.matches('.prose img[src]')) {
            prepareImage(root);
        }
        if (root.matches && root.matches('.post-list-cover-img[src]')) {
            prepareCoverImage(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('.prose img[src]').forEach(prepareImage);
            root.querySelectorAll('.post-list-cover-img[src]').forEach(prepareCoverImage);
        }
    }

    prepareImages(document);
    document.addEventListener('pjax:complete', function () {
        prepareImages(document.getElementById('pjax-container') || document);
    });
    document.addEventListener('pjax:end', function () {
        prepareImages(document.getElementById('pjax-container') || document);
    });
})();
