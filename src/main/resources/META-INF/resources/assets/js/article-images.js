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
        if (!image.getAttribute('src').trim() || !hasReservedDimensions(image)
                || image.dataset.articleImagePrepared === 'true') {
            return;
        }

        image.dataset.articleImagePrepared = 'true';
        if (!image.getAttribute('loading')) {
            image.setAttribute('loading', 'lazy');
        }
        if (!image.getAttribute('decoding')) {
            image.setAttribute('decoding', 'async');
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
        if (root.matches && root.matches('.prose img[src][width][height]')) {
            prepareImage(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('.prose img[src][width][height]').forEach(prepareImage);
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
