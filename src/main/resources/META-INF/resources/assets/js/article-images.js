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

    function prepareImages(root) {
        if (!root) {
            return;
        }
        if (root.matches && root.matches('.prose img[src]')) {
            prepareImage(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('.prose img[src]').forEach(prepareImage);
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
