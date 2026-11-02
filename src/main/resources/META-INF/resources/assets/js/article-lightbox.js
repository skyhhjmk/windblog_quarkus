/**
 * Article image lightbox.
 *
 * theme.css caps the height of images inside #post-content-section so an
 * extremely tall picture cannot push the rest of the post far down. This
 * module is the pure client-side counterpart: it decorates only images that
 * are actually rendered inside the article body and opens them in a
 * click-to-zoom lightbox with a FLIP transition from the inline thumbnail.

 * #post-content-section is the stable root: post-premium.js renames the
 * inner #post-body-content wrapper when it builds its block outline, so the
 * inner id cannot be used as the long-lived anchor.
 *
 * Locked blocks never contain image markup. Authorized block HTML is injected
 * by post-premium.js into the same article body, and the MutationObserver below
 * decorates those images only after the server has returned them.
 */
(function () {
    'use strict';

    var OVERLAY_ID = 'article-lightbox';
    var CONTENT_ID = 'post-content-section';
    var MIN_ZOOM_EDGE = 96;
    var READABLE_WIDTH = 320;
    var VIEWPORT_GAP = 24;
    var ANIMATION_MS = 320;

    var openState = null;
    var observedBody = null;
    var observer = null;
    var reducedMotionQuery = window.matchMedia
        ? window.matchMedia('(prefers-reduced-motion: reduce)')
        : null;

    function t(key, fallback) {
        return window.wbT ? window.wbT(key, fallback) : fallback;
    }

    function articleBody() {
        return document.getElementById(CONTENT_ID);
    }

    function isVisible(image) {
        return image.getClientRects().length > 0
            && window.getComputedStyle(image).visibility !== 'hidden';
    }

    function isCandidate(image) {
        var body = articleBody();
        if (!image || image.tagName !== 'IMG' || !body || !body.contains(image)) {
            return false;
        }
        if (image.getAttribute('data-lightbox') === 'false') {
            return false;
        }
        if (image.classList.contains('article-image-error')) {
            return false;
        }
        // Locked blocks render a placeholder with no image; guard anyway so a
        // future placeholder image can never reach the lightbox.
        if (image.closest('.buy-post-placeholder, .premium-card-body, a[download]')) {
            return false;
        }
        if (!(image.naturalWidth > 0 && image.naturalHeight > 0)) {
            return false;
        }
        return Math.max(image.naturalWidth, image.naturalHeight) >= MIN_ZOOM_EDGE;
    }

    function canOpen(image) {
        return isCandidate(image) && isVisible(image);
    }

    function decorate(image) {
        if (!isCandidate(image) || image.dataset.articleZoomable === 'true') {
            return;
        }
        image.dataset.articleZoomable = 'true';
        image.classList.add('article-image-zoomable');
        if (!image.closest('a[href]')) {
            image.setAttribute('tabindex', '0');
            image.setAttribute('role', 'button');
            image.setAttribute('aria-label', t('js.view_image', 'View image'));
        }
    }

    function decorateTree(root) {
        if (!root) {
            return;
        }
        if (root.tagName === 'IMG') {
            decorate(root);
        }
        if (root.querySelectorAll) {
            root.querySelectorAll('img').forEach(decorate);
        }
    }

    function watchArticleBody() {
        var body = articleBody();
        if (body !== observedBody) {
            if (observer) {
                observer.disconnect();
            }
            observedBody = body;
            if (body && window.MutationObserver) {
                observer = observer || new MutationObserver(function (records) {
                    records.forEach(function (record) {
                        record.addedNodes.forEach(function (node) {
                            if (node.nodeType === 1) {
                                decorateTree(node);
                            }
                        });
                    });
                });
                observer.observe(body, { childList: true, subtree: true });
            }
        }
        decorateTree(body);
    }

    /** Fit within the viewport, but keep very tall images readable by width. */
    function targetGeometry(source) {
        var naturalWidth = source.naturalWidth;
        var naturalHeight = source.naturalHeight;
        var maxWidth = Math.max(1, window.innerWidth - VIEWPORT_GAP * 2);
        var maxHeight = Math.max(1, window.innerHeight - VIEWPORT_GAP * 2);
        var fitScale = Math.min(maxWidth / naturalWidth, maxHeight / naturalHeight, 1);
        var readableWidth = Math.min(naturalWidth, maxWidth, READABLE_WIDTH);
        var scale = fitScale;
        if (naturalWidth * fitScale < readableWidth) {
            // A 1:5 screenshot fitted to the viewport becomes a thin sliver.
            // Keep a readable width and let the stage scroll vertically.
            scale = Math.min(1, maxWidth / naturalWidth);
        }
        return {
            width: Math.max(1, Math.round(naturalWidth * scale)),
            height: Math.max(1, Math.round(naturalHeight * scale))
        };
    }

    function transformBetween(from, to) {
        var scaleX = to.width > 0 ? from.width / to.width : 1;
        var scaleY = to.height > 0 ? from.height / to.height : 1;
        return 'translate(' + (from.left - to.left) + 'px, ' + (from.top - to.top) + 'px)'
            + ' scale(' + scaleX + ', ' + scaleY + ')';
    }

    function buildCloseIcon() {
        var namespace = 'http://www.w3.org/2000/svg';
        var svg = document.createElementNS(namespace, 'svg');
        svg.setAttribute('width', '20');
        svg.setAttribute('height', '20');
        svg.setAttribute('viewBox', '0 0 24 24');
        svg.setAttribute('fill', 'none');
        svg.setAttribute('stroke', 'currentColor');
        svg.setAttribute('stroke-width', '2.2');
        svg.setAttribute('stroke-linecap', 'round');
        svg.setAttribute('stroke-linejoin', 'round');
        svg.setAttribute('aria-hidden', 'true');
        [['M18 6 6 18'], ['m6 6 12 12']].forEach(function (pathData) {
            var path = document.createElementNS(namespace, 'path');
            path.setAttribute('d', pathData[0]);
            svg.appendChild(path);
        });
        return svg;
    }

    function openLightbox(source) {
        if (openState) {
            return;
        }

        var geometry = targetGeometry(source);
        var overlay = document.createElement('div');
        overlay.id = OVERLAY_ID;
        overlay.className = 'article-lightbox';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', t('js.image_viewer', 'Image viewer'));

        var backdrop = document.createElement('div');
        backdrop.className = 'article-lightbox-backdrop';

        var stage = document.createElement('div');
        stage.className = 'article-lightbox-stage';

        var image = document.createElement('img');
        image.className = 'article-lightbox-image';
        image.decoding = 'async';
        image.alt = source.getAttribute('alt') || '';
        image.src = source.currentSrc || source.src;
        image.draggable = false;

        var closeButton = document.createElement('button');
        closeButton.type = 'button';
        closeButton.className = 'article-lightbox-close';
        closeButton.setAttribute('aria-label', t('js.close', 'Close'));
        closeButton.appendChild(buildCloseIcon());

        stage.appendChild(image);
        overlay.appendChild(backdrop);
        overlay.appendChild(stage);
        overlay.appendChild(closeButton);

        overlay.style.setProperty('--article-lightbox-image-height', geometry.height + 'px');
        image.style.width = geometry.width + 'px';
        image.style.height = geometry.height + 'px';

        var previousActive = document.activeElement;
        var previousOverflow = document.body.style.overflow;

        document.body.appendChild(overlay);
        document.body.style.overflow = 'hidden';

        openState = {
            overlay: overlay,
            image: image,
            source: source,
            previousActive: previousActive,
            previousOverflow: previousOverflow,
            closing: false
        };

        var sourceRect = source.getBoundingClientRect();
        var targetRect = image.getBoundingClientRect();

        overlay.addEventListener('click', function (event) {
            if (event.target === stage || event.target === backdrop || event.target === closeButton) {
                event.preventDefault();
                closeLightbox(false);
            }
        });

        // Force a frame so the backdrop fade starts from zero opacity.
        void overlay.offsetWidth;
        overlay.classList.add('is-open');
        closeButton.focus({ preventScroll: true });

        if (reducedMotionQuery && reducedMotionQuery.matches) {
            return;
        }

        image.style.transformOrigin = 'top left';
        image.style.transition = 'none';
        image.style.transform = transformBetween(sourceRect, targetRect);
        void image.offsetWidth;
        image.style.transition = '';
        image.style.transform = '';
    }

    function closeLightbox(immediate) {
        if (!openState || openState.closing) {
            return;
        }
        var state = openState;
        state.closing = true;

        function finish() {
            if (openState === state) {
                openState = null;
            }
            if (state.overlay.parentNode) {
                state.overlay.parentNode.removeChild(state.overlay);
            }
            document.body.style.overflow = state.previousOverflow;
            var focusTarget = state.previousActive;
            if (!focusTarget || focusTarget === document.body || !document.contains(focusTarget)) {
                focusTarget = state.source;
            }
            if (focusTarget && typeof focusTarget.focus === 'function') {
                try {
                    focusTarget.focus({ preventScroll: true });
                } catch (error) {
                    focusTarget.focus();
                }
            }
        }

        var reduced = reducedMotionQuery && reducedMotionQuery.matches;
        if (immediate || reduced || !document.contains(state.source)) {
            finish();
            return;
        }

        var sourceRect = state.source.getBoundingClientRect();
        // Measure the untransformed layout box: getBoundingClientRect would
        // otherwise include an opening transform that is still animating.
        state.image.style.transition = 'none';
        state.image.style.transform = '';
        var imageRect = state.image.getBoundingClientRect();
        state.overlay.classList.remove('is-open');
        state.image.style.transformOrigin = 'top left';
        state.image.style.transition = '';
        state.image.style.transform = transformBetween(sourceRect, imageRect);
        window.setTimeout(finish, ANIMATION_MS + 30);
    }

    document.addEventListener('click', function (event) {
        // Leave modified clicks alone so an image wrapped in a link still
        // supports "open in new tab" from the keyboard/mouse.
        if (event.defaultPrevented || event.button !== 0
                || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
        }
        var target = event.target;
        if (!target || !target.closest) {
            return;
        }
        var image = target.closest('img');
        if (!image || !canOpen(image)) {
            return;
        }
        // Runs in the capture phase so PJAX never navigates from an <a> that
        // wraps the image before the lightbox can open.
        event.preventDefault();
        openLightbox(image);
    }, true);

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape' && openState) {
            event.preventDefault();
            closeLightbox(false);
            return;
        }
        if (openState) {
            if (event.key === 'Tab') {
                event.preventDefault();
                var close = openState.overlay.querySelector('.article-lightbox-close');
                if (close) {
                    close.focus({ preventScroll: true });
                }
            }
            return;
        }
        if (event.key !== 'Enter' && event.key !== ' ' && event.key !== 'Spacebar') {
            return;
        }
        var target = event.target;
        if (!target || target.tagName !== 'IMG' || !canOpen(target)) {
            return;
        }
        event.preventDefault();
        openLightbox(target);
    });

    document.addEventListener('load', function (event) {
        var target = event.target;
        var body = articleBody();
        if (target && target.tagName === 'IMG' && body && body.contains(target)) {
            decorate(target);
        }
    }, true);

    document.addEventListener('pjax:start', function () {
        closeLightbox(true);
    });
    document.addEventListener('pjax:complete', watchArticleBody);
    document.addEventListener('pjax:end', watchArticleBody);

    function init() {
        watchArticleBody();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }
})();
