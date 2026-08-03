/*
 * Trusted Types boundary for the small amount of legacy DOM templating kept by
 * the public client. Server-rendered article HTML is already sanitized; this
 * policy is a second client-side boundary for API/PJAX and UI fragments.
 */
(function () {
    'use strict';

    var policyNonce = document.currentScript && document.currentScript.nonce;
    if (policyNonce) {
        var nativeCreateElement = document.createElement.bind(document);
        document.createElement = function (tagName, options) {
            var element = nativeCreateElement(tagName, options);
            if (String(tagName).toLowerCase() === 'style') {
                element.setAttribute('nonce', policyNonce);
            }
            return element;
        };
    }

    if (!window.trustedTypes || window.windblogTrustedTypesPolicy) {
        return;
    }

    var allowedElements = new Set([
        'a', 'article', 'b', 'blockquote', 'br', 'button', 'caption', 'code', 'col', 'colgroup',
        'dd', 'del', 'div', 'dl', 'dt', 'em', 'fieldset', 'figcaption', 'figure', 'footer',
        'form', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'header', 'hr', 'i', 'img', 'input',
        'label', 'li', 'main', 'nav', 'ol', 'option', 'p', 'path', 'polyline', 'polygon',
        'pre', 'section', 'select', 'small', 'span', 'strong', 'sub', 'sup', 'svg', 'table',
        'tbody', 'td', 'textarea', 'tfoot', 'th', 'thead', 'tr', 'u', 'ul', 'video'
    ]);
    var allowedAttributes = new Set([
        'alt', 'aria-controls', 'aria-current', 'aria-describedby', 'aria-hidden', 'aria-label',
        'aria-selected', 'checked', 'class', 'cols', 'colspan', 'data-group', 'data-level',
        'data-name', 'data-post-id', 'data-price', 'data-title', 'data-type', 'data-url',
        'disabled', 'download', 'fill', 'for', 'height', 'href', 'id', 'loading', 'max', 'maxlength',
        'method', 'min', 'name', 'placeholder', 'points', 'rel', 'required', 'role', 'selected', 'action',
        'size', 'spellcheck', 'step', 'stroke', 'stroke-linecap', 'stroke-linejoin', 'stroke-width',
        'target', 'title', 'type', 'value', 'viewbox', 'width', 'x', 'x1', 'x2', 'y', 'y1', 'y2'
    ]);

    function isSafeUrl(value) {
        var normalized = String(value || '').trim().toLowerCase();
        if (normalized === '' || normalized.charAt(0) === '#') {
            return true;
        }
        if (normalized.charAt(0) === '/') {
            return normalized.charAt(1) !== '/';
        }
        return normalized.indexOf('https://') === 0
            || normalized.indexOf('http://') === 0
            || normalized.indexOf('mailto:') === 0;
    }

    function sanitizeHtml(value) {
        var parsed = new DOMParser().parseFromString(String(value || ''), 'text/html');
        var elements = Array.from(parsed.body.querySelectorAll('*'));
        elements.forEach(function (element) {
            var tagName = element.tagName.toLowerCase();
            if (!allowedElements.has(tagName)) {
                element.remove();
                return;
            }

            Array.from(element.attributes).forEach(function (attribute) {
                var name = attribute.name.toLowerCase();
                var attributeValue = attribute.value;
                if (name.indexOf('on') === 0 || name === 'style' || name === 'srcdoc'
                    || (!allowedAttributes.has(name) && name.indexOf('data-') !== 0 && name.indexOf('aria-') !== 0)) {
                    element.removeAttribute(attribute.name);
                    return;
                }
                if ((name === 'href' || name === 'src' || name === 'action') && !isSafeUrl(attributeValue)) {
                    element.removeAttribute(attribute.name);
                }
            });
        });
        return parsed.body.innerHTML;
    }

    function sanitizeScriptUrl(value) {
        var url = new URL(String(value || ''), window.location.href);
        if (url.origin !== window.location.origin) {
            throw new TypeError('Only same-origin dynamic script URLs are allowed');
        }
        return url.href;
    }

    try {
        window.windblogTrustedTypesPolicy = window.trustedTypes.createPolicy('default', {
            createHTML: sanitizeHtml,
            createScriptURL: sanitizeScriptUrl
        });
    } catch (error) {
        // A duplicate policy can occur during PJAX navigation; the CSP still
        // protects script execution and the first policy remains authoritative.
        window.windblogTrustedTypesPolicy = null;
    }
})();
