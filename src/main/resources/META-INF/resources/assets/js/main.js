// /assets/js/main.js
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function dispatch(name, detail) {
        document.dispatchEvent(new CustomEvent(name, { detail: detail || {} }));
    }

    // Source-of-truth for the public web shortcut labels and bindings.
    // Custom key mapping is intentionally not exposed to users.
    const SHORTCUTS = Object.freeze({
        submit: 'Enter',
        search: 'Ctrl+K'
    });

    ready(() => {
        const mobileBtn = document.getElementById('mobileMenuBtn');
        const mobileMenu = document.getElementById('mobileMenu');
        let restoreMobileFocusOnPageReady = false;
        const closeMobileMenu = (restoreFocus = false) => {
            if (!mobileBtn || !mobileMenu) return;
            mobileMenu.classList.add('hidden');
            mobileBtn.setAttribute('aria-expanded', 'false');
            mobileBtn.setAttribute('aria-label', '打开菜单');
            if (restoreFocus) {
                mobileBtn.focus({preventScroll: true});
            }
        };
        if (mobileBtn && mobileMenu) {
            mobileBtn.addEventListener('click', () => {
                const isOpen = mobileMenu.classList.toggle('hidden') === false;
                mobileBtn.setAttribute('aria-expanded', isOpen.toString());
                mobileBtn.setAttribute('aria-label', isOpen ? '关闭菜单' : '打开菜单');
            });

            mobileMenu.addEventListener('click', (event) => {
                const anchor = event.target.closest?.('a');
                if (!anchor || !anchor.getAttribute('href') || anchor.hasAttribute('download')) return;
                restoreMobileFocusOnPageReady = true;
                closeMobileMenu();
            });

            document.addEventListener('click', (event) => {
                if (!mobileMenu.classList.contains('hidden') &&
                    !mobileMenu.contains(event.target) && !mobileBtn.contains(event.target)) {
                    closeMobileMenu();
                }
            });

            document.addEventListener('keydown', (event) => {
                if (event.key === 'Escape' && !mobileMenu.classList.contains('hidden')) {
                    closeMobileMenu(true);
                }
            });
        }

        // Theme Toggle Logic
        const themeToggles = document.querySelectorAll('.theme-toggle');
        const darkIcons = document.querySelectorAll('.theme-icon-dark');
        const lightIcons = document.querySelectorAll('.theme-icon-light');

        function updateThemeUI() {
            const isLight = document.documentElement.classList.contains('light');
            darkIcons.forEach(icon => isLight ? icon.classList.add('hidden') : icon.classList.remove('hidden'));
            lightIcons.forEach(icon => isLight ? icon.classList.remove('hidden') : icon.classList.add('hidden'));
            const colorSchemeMeta = document.getElementById('color-scheme-meta');
            if (colorSchemeMeta) colorSchemeMeta.setAttribute('content', isLight ? 'light' : 'dark');
        }

        if (themeToggles.length > 0) {
            updateThemeUI();
            themeToggles.forEach(btn => {
                btn.addEventListener('click', () => {
                    const isLight = document.documentElement.classList.toggle('light');
                    localStorage.setItem('theme', isLight ? 'light' : 'dark');
                    updateThemeUI();
                });
            });
        }

        const userMenuBtn = document.getElementById('userMenuBtn');
        const userDropdown = document.getElementById('userDropdown');
        const userMenuContainer = document.getElementById('userMenuContainer');
        if (userMenuBtn && userDropdown && userMenuContainer) {
            userMenuBtn.addEventListener('click', () => {
                const isHidden = userDropdown.classList.toggle('hidden');
                userMenuBtn.setAttribute('aria-expanded', (!isHidden).toString());
            });

            document.addEventListener('click', (e) => {
                if (!userMenuContainer.contains(e.target) && !userDropdown.classList.contains('hidden')) {
                    userDropdown.classList.add('hidden');
                    userMenuBtn.setAttribute('aria-expanded', 'false');
                }
            });

            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && !userDropdown.classList.contains('hidden')) {
                    userDropdown.classList.add('hidden');
                    userMenuBtn.setAttribute('aria-expanded', 'false');
                    userMenuBtn.focus();
                }
            });
        }

        const bar = document.getElementById('pjax-progress');
        let progressTimer = null;
        let activePjaxController = null;

        function showProgress() {
            if (!bar) return;
            bar.style.transition = 'none';
            bar.style.width = '0';
            void bar.offsetWidth;
            bar.style.transition = 'width .25s ease';
            bar.style.width = '60%';
            clearTimeout(progressTimer);
            progressTimer = setTimeout(() => {
                bar.style.width = '85%';
            }, 300);
        }

        function hideProgress() {
            if (!bar) return;
            clearTimeout(progressTimer);
            bar.style.width = '100%';
            setTimeout(() => {
                bar.style.transition = 'none';
                bar.style.width = '0';
            }, 200);
        }

        function canUsePjax(anchor) {
            if (!anchor) return false;
            if (anchor.dataset.noPjax === 'true') return false;
            if (anchor.target && anchor.target !== '_self') return false;
            if (anchor.hasAttribute('download')) return false;

            const href = anchor.getAttribute('href');
            if (!href || href.startsWith('#') || href.startsWith('javascript:')) return false;

            const url = new URL(anchor.href, window.location.href);
            if (url.origin !== window.location.origin) return false;
            if (url.pathname.startsWith('/assets/')) return false;
            return true;
        }

        function isArticleDetailUrl(url) {
            try {
                const parsedUrl = new URL(url, window.location.href);
                const pathParts = parsedUrl.pathname.split('/').filter(function (part) {
                    return part;
                });
                const postIndex = pathParts.indexOf('post');
                return postIndex >= 0 && postIndex < pathParts.length - 1;
            } catch (error) {
                return false;
            }
        }

        function getArticleSlugFromUrl(url) {
            try {
                const parsedUrl = new URL(url, window.location.href);
                const pathParts = parsedUrl.pathname.split('/').filter(function (part) {
                    return part;
                });
                const postIndex = pathParts.indexOf('post');
                if (postIndex < 0 || postIndex >= pathParts.length - 1) {
                    return '';
                }

                return pathParts[postIndex + 1] || '';
            } catch (error) {
                return '';
            }
        }

        function getCssSafeSelectorValue(value) {
            if (!value) {
                return '';
            }

            if (window.CSS && typeof window.CSS.escape === 'function') {
                return window.CSS.escape(value);
            }

            return value.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
        }

        function getArticleTransitionSelector(slug, partName) {
            if (!slug || !partName) {
                return '';
            }

            return '[data-article-transition-slug="' + getCssSafeSelectorValue(slug) + '"][data-article-transition-part="' + partName + '"]';
        }

        function getArticleTransitionElements(root, slug) {
            const elements = [];
            if (!root || !slug) {
                return elements;
            }

            const titleElement = root.querySelector(getArticleTransitionSelector(slug, 'title'));
            if (titleElement) {
                elements.push(titleElement);
            }

            const summaryElement = root.querySelector(getArticleTransitionSelector(slug, 'summary'));
            if (summaryElement) {
                elements.push(summaryElement);
            }

            return elements;
        }

        function hasArticleTransitionSource(root, slug) {
            if (!root || !slug) {
                return false;
            }

            return getArticleTransitionElements(root, slug).length > 0;
        }

        function getCurrentArticleTransitionSlug() {
            if (!isArticleDetailUrl(window.location.href)) {
                return '';
            }

            const articleElement = document.querySelector('[data-article-transition-slug][data-article-transition-part="title"]');
            if (articleElement) {
                const slug = articleElement.getAttribute('data-article-transition-slug');
                if (slug) {
                    return slug;
                }
            }

            return getArticleSlugFromUrl(window.location.href);
        }

        function resolveArticleTransitionSlug(url, anchor) {
            const targetIsPost = isArticleDetailUrl(url);
            const currentSlug = getCurrentArticleTransitionSlug();
            const clickedSlug = getClickedArticleTransitionSlug(anchor);

            if (targetIsPost && clickedSlug && hasArticleTransitionSource(document, clickedSlug)) {
                return clickedSlug;
            }

            if (!targetIsPost && currentSlug && hasArticleTransitionSource(document, currentSlug)) {
                return currentSlug;
            }

            return '';
        }

        function getClickedArticleTransitionSlug(anchor) {
            if (!anchor) {
                return '';
            }

            const articleElement = anchor.closest('[data-article-transition-slug]');
            if (!articleElement) {
                return '';
            }

            const slug = articleElement.getAttribute('data-article-transition-slug');
            if (!slug) {
                return '';
            }

            return slug;
        }

        function updatePjaxContainerState(container, html, url, pushState) {
            container.innerHTML = html;

            const titleHolder = container.querySelector('[data-page-title]');
            if (titleHolder) {
                const title = titleHolder.getAttribute('data-page-title');
                if (title) {
                    document.title = title;
                }
            }
            updatePjaxHeadMetadata(container, url);

            const navPathNode = document.getElementById('nav-path-text');
            const navHolder = container.querySelector('#pjax-nav-path');
            if (navHolder && navPathNode) {
                const navPath = (navHolder.textContent || '').trim();
                if (navPath) {
                    navPathNode.textContent = navPath;
                }
            }

            if (pushState) {
                window.history.pushState({ pjax: true, url: url }, '', url);
                window.scrollTo(0, 0);
            }

            document.dispatchEvent(new CustomEvent('page:ready', { detail: { url: url } }));
        }

        function updatePjaxHeadMetadata(container, url) {
            const pageRoot = container.querySelector('#pjax-content-root');
            if (!pageRoot) {
                return;
            }

            const descriptionMeta = document.head.querySelector('meta[name="description"]');
            if (descriptionMeta) {
                const siteDescription = descriptionMeta.getAttribute('data-site-content') || '';
                const pageDescription = pageRoot.getAttribute('data-page-description') || siteDescription;
                descriptionMeta.setAttribute('content', pageDescription);
            }

            const keywordsMeta = document.head.querySelector('meta[name="keywords"]');
            if (keywordsMeta) {
                const siteKeywords = keywordsMeta.getAttribute('data-site-content') || '';
                const pageKeywords = pageRoot.getAttribute('data-page-keywords') || siteKeywords;
                keywordsMeta.setAttribute('content', pageKeywords);
            }

            const canonicalLink = document.head.querySelector('link[rel="canonical"]');
            if (canonicalLink) {
                const pageCanonical = pageRoot.getAttribute('data-page-canonical');
                const targetUrl = new URL(url, window.location.href);
                let canonicalUrl = pageCanonical;
                if (!canonicalUrl) {
                    const configuredBaseUrl = canonicalLink.getAttribute('data-site-base') || targetUrl.origin;
                    const normalizedBaseUrl = configuredBaseUrl.replace(/\/+$/, '');
                    canonicalUrl = normalizedBaseUrl + targetUrl.pathname;
                }
                canonicalLink.setAttribute('href', canonicalUrl);
            }

            const pageRobots = pageRoot.getAttribute('data-page-robots') || '';
            let robotsMeta = document.head.querySelector('meta[name="robots"]');
            if (pageRobots) {
                if (!robotsMeta) {
                    robotsMeta = document.createElement('meta');
                    robotsMeta.setAttribute('name', 'robots');
                    document.head.appendChild(robotsMeta);
                }
                robotsMeta.setAttribute('content', pageRobots);
            } else if (robotsMeta) {
                robotsMeta.remove();
            }

            document.head.querySelectorAll(
                'meta[property^="og:"], meta[name^="twitter:"], script[type="application/ld+json"]'
            ).forEach(function (metadataNode) {
                metadataNode.remove();
            });

            const metadataTemplate = pageRoot.querySelector('#pjax-head-metadata');
            if (metadataTemplate) {
                document.head.appendChild(metadataTemplate.content.cloneNode(true));
            }
        }

        function injectSidebar() {
            const template = document.querySelector('#pjax-sidebar-html');
            const container = document.getElementById('sidebar-container');
            if (!container) return;

            // 如果当前页面没有提供新的侧边栏模板，则不修改现有侧边栏
            if (!template) return;

            const nextHtml = template.innerHTML || '';
            // 只有当内容确实改变时才更新，避免闪烁
            if (container.innerHTML.trim() !== nextHtml.trim()) {
                container.innerHTML = nextHtml;
                document.dispatchEvent(new CustomEvent('sidebar:updated', { detail: {} }));
            }
        }

        function finalizePjaxLoad(url) {
            document.dispatchEvent(new CustomEvent('pjax:complete', { detail: { url: url } }));
            document.dispatchEvent(new CustomEvent('pjax:end', { detail: { url: url } }));
        }

        function captureArticleTransitionItems(slug) {
            const capturedItems = [];
            if (!slug) {
                return capturedItems;
            }

            const articleTransitionParts = ['title', 'summary'];
            articleTransitionParts.forEach(function (partName) {
                const sourceElement = document.querySelector(getArticleTransitionSelector(slug, partName));
                if (!sourceElement) {
                    return;
                }

                const sourceRect = sourceElement.getBoundingClientRect();
                if (sourceRect.width <= 0 || sourceRect.height <= 0) {
                    return;
                }

                capturedItems.push({
                    partName: partName,
                    sourceRect: sourceRect,
                    sourceStyle: getArticleTransitionStyleSnapshot(sourceElement)
                });
            });

            return capturedItems;
        }

        function getArticleTransitionStyleSnapshot(element) {
            const computedStyle = window.getComputedStyle(element);
            return {
                backgroundColor: computedStyle.backgroundColor,
                borderBottomColor: computedStyle.borderBottomColor,
                borderBottomStyle: computedStyle.borderBottomStyle,
                borderBottomWidth: computedStyle.borderBottomWidth,
                borderLeftColor: computedStyle.borderLeftColor,
                borderLeftStyle: computedStyle.borderLeftStyle,
                borderLeftWidth: computedStyle.borderLeftWidth,
                borderRightColor: computedStyle.borderRightColor,
                borderRightStyle: computedStyle.borderRightStyle,
                borderRightWidth: computedStyle.borderRightWidth,
                borderTopColor: computedStyle.borderTopColor,
                borderTopStyle: computedStyle.borderTopStyle,
                borderTopWidth: computedStyle.borderTopWidth,
                color: computedStyle.color,
                fontFamily: computedStyle.fontFamily,
                fontSize: computedStyle.fontSize,
                fontStyle: computedStyle.fontStyle,
                fontWeight: computedStyle.fontWeight,
                letterSpacing: computedStyle.letterSpacing,
                lineHeight: computedStyle.lineHeight,
                paddingBottom: computedStyle.paddingBottom,
                paddingLeft: computedStyle.paddingLeft,
                paddingRight: computedStyle.paddingRight,
                paddingTop: computedStyle.paddingTop,
                textAlign: computedStyle.textAlign,
                textTransform: computedStyle.textTransform
            };
        }

        function applyArticleTransitionStyleSnapshot(element, styleSnapshot) {
            element.style.backgroundColor = styleSnapshot.backgroundColor;
            element.style.borderBottomColor = styleSnapshot.borderBottomColor;
            element.style.borderBottomStyle = styleSnapshot.borderBottomStyle;
            element.style.borderBottomWidth = styleSnapshot.borderBottomWidth;
            element.style.borderLeftColor = styleSnapshot.borderLeftColor;
            element.style.borderLeftStyle = styleSnapshot.borderLeftStyle;
            element.style.borderLeftWidth = styleSnapshot.borderLeftWidth;
            element.style.borderRightColor = styleSnapshot.borderRightColor;
            element.style.borderRightStyle = styleSnapshot.borderRightStyle;
            element.style.borderRightWidth = styleSnapshot.borderRightWidth;
            element.style.borderTopColor = styleSnapshot.borderTopColor;
            element.style.borderTopStyle = styleSnapshot.borderTopStyle;
            element.style.borderTopWidth = styleSnapshot.borderTopWidth;
            element.style.color = styleSnapshot.color;
            element.style.fontFamily = styleSnapshot.fontFamily;
            element.style.fontSize = styleSnapshot.fontSize;
            element.style.fontStyle = styleSnapshot.fontStyle;
            element.style.fontWeight = styleSnapshot.fontWeight;
            element.style.letterSpacing = styleSnapshot.letterSpacing;
            element.style.lineHeight = styleSnapshot.lineHeight;
            element.style.paddingBottom = styleSnapshot.paddingBottom;
            element.style.paddingLeft = styleSnapshot.paddingLeft;
            element.style.paddingRight = styleSnapshot.paddingRight;
            element.style.paddingTop = styleSnapshot.paddingTop;
            element.style.textAlign = styleSnapshot.textAlign;
            element.style.textTransform = styleSnapshot.textTransform;
        }

        function buildArticleTransitionKeyframe(rect, styleSnapshot) {
            return {
                backgroundColor: styleSnapshot.backgroundColor,
                borderBottomColor: styleSnapshot.borderBottomColor,
                borderBottomStyle: styleSnapshot.borderBottomStyle,
                borderBottomWidth: styleSnapshot.borderBottomWidth,
                borderLeftColor: styleSnapshot.borderLeftColor,
                borderLeftStyle: styleSnapshot.borderLeftStyle,
                borderLeftWidth: styleSnapshot.borderLeftWidth,
                borderRightColor: styleSnapshot.borderRightColor,
                borderRightStyle: styleSnapshot.borderRightStyle,
                borderRightWidth: styleSnapshot.borderRightWidth,
                borderTopColor: styleSnapshot.borderTopColor,
                borderTopStyle: styleSnapshot.borderTopStyle,
                borderTopWidth: styleSnapshot.borderTopWidth,
                color: styleSnapshot.color,
                fontFamily: styleSnapshot.fontFamily,
                fontSize: styleSnapshot.fontSize,
                fontStyle: styleSnapshot.fontStyle,
                fontWeight: styleSnapshot.fontWeight,
                height: rect.height + 'px',
                left: rect.left + 'px',
                letterSpacing: styleSnapshot.letterSpacing,
                lineHeight: styleSnapshot.lineHeight,
                paddingBottom: styleSnapshot.paddingBottom,
                paddingLeft: styleSnapshot.paddingLeft,
                paddingRight: styleSnapshot.paddingRight,
                paddingTop: styleSnapshot.paddingTop,
                textAlign: styleSnapshot.textAlign,
                textTransform: styleSnapshot.textTransform,
                top: rect.top + 'px',
                width: rect.width + 'px'
            };
        }

        function prepareArticleTransitionTargets(slug, capturedItems) {
            const preparedItems = [];
            if (!slug || !capturedItems || capturedItems.length === 0) {
                return preparedItems;
            }

            capturedItems.forEach(function (capturedItem) {
                const targetElement = document.querySelector(getArticleTransitionSelector(slug, capturedItem.partName));
                if (!targetElement) {
                    return;
                }

                const targetRect = targetElement.getBoundingClientRect();
                if (targetRect.width <= 0 || targetRect.height <= 0) {
                    return;
                }

                const cloneElement = targetElement.cloneNode(true);
                cloneElement.classList.add('pjax-article-transition-clone');
                cloneElement.classList.remove('pjax-article-transition-target-hidden');
                cloneElement.removeAttribute('data-article-transition-part');
                cloneElement.removeAttribute('data-article-transition-slug');
                targetElement.classList.add('pjax-article-transition-target-hidden');

                preparedItems.push({
                    partName: capturedItem.partName,
                    sourceRect: capturedItem.sourceRect,
                    sourceStyle: capturedItem.sourceStyle,
                    targetRect: targetRect,
                    targetStyle: getArticleTransitionStyleSnapshot(targetElement),
                    cloneElement: cloneElement,
                    targetElement: targetElement
                });
            });

            return preparedItems;
        }

        function animateArticleTransitionItems(preparedItems) {
            if (!preparedItems || preparedItems.length === 0) {
                return Promise.resolve();
            }
            if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                cleanupArticleTransitionItems(preparedItems);
                return Promise.resolve();
            }

            const animations = [];
            preparedItems.forEach(function (preparedItem) {
                const cloneElement = preparedItem.cloneElement;
                cloneElement.style.left = preparedItem.sourceRect.left + 'px';
                cloneElement.style.top = preparedItem.sourceRect.top + 'px';
                cloneElement.style.width = preparedItem.sourceRect.width + 'px';
                cloneElement.style.height = preparedItem.sourceRect.height + 'px';
                applyArticleTransitionStyleSnapshot(cloneElement, preparedItem.sourceStyle);
                document.body.appendChild(cloneElement);

                const animation = cloneElement.animate([
                    buildArticleTransitionKeyframe(preparedItem.sourceRect, preparedItem.sourceStyle),
                    buildArticleTransitionKeyframe(preparedItem.targetRect, preparedItem.targetStyle)
                ], {
                    duration: 460,
                    easing: 'cubic-bezier(0.16, 1, 0.3, 1)',
                    fill: 'forwards'
                });
                animations.push(animation.finished);
            });

            return Promise.all(animations).then(function () {
                cleanupArticleTransitionItems(preparedItems);
            }).catch(function () {
                cleanupArticleTransitionItems(preparedItems);
            });
        }

        function cleanupArticleTransitionItems(preparedItems) {
            preparedItems.forEach(function (preparedItem) {
                preparedItem.targetElement.classList.remove('pjax-article-transition-target-hidden');
                preparedItem.cloneElement.remove();
            });
        }

        function logArticleTransitionMode(url, transitionSlug, usedManualTransition, fallbackReason) {
            if (!transitionSlug) {
                return;
            }

            if (usedManualTransition) {
                console.info('[PJAX] article transition uses manual FLIP fallback', {
                    url: url,
                    slug: transitionSlug,
                    reason: 'native view transition screenshots do not resize text reliably across these layouts'
                });
                return;
            }

            console.info('[PJAX] article transition uses plain pjax replacement', {
                url: url,
                slug: transitionSlug,
                reason: fallbackReason || 'matching transition element unavailable'
            });
        }

        async function loadByPjax(url, pushState, transitionSlug) {
            const container = document.getElementById('pjax-container');
            if (!container) {
                window.location.href = url;
                return;
            }

            document.dispatchEvent(new CustomEvent('pjax:start', { detail: { url: url } }));
            if (activePjaxController) {
                activePjaxController.abort();
            }
            const requestController = new AbortController();
            activePjaxController = requestController;
            try {
                const response = await fetch(url, {
                    method: 'GET',
                    signal: requestController.signal,
                    headers: {
                        'X-PJAX': 'true',
                        'X-PJAX-Container': '#pjax-container'
                    }
                });

                if (!response.ok) {
                    throw new Error('PJAX request failed: ' + response.status);
                }

                const html = await response.text();
                const hasArticleTransitionSourceOnCurrentPage = transitionSlug && hasArticleTransitionSource(document, transitionSlug);
                const extractedHtml = extractPjaxHtml(html, transitionSlug);

                if (hasArticleTransitionSourceOnCurrentPage) {
                    const capturedItems = captureArticleTransitionItems(transitionSlug);
                    updatePjaxContainerState(container, extractedHtml, url, pushState);
                    const preparedItems = prepareArticleTransitionTargets(transitionSlug, capturedItems);

                    if (preparedItems.length > 0) {
                        logArticleTransitionMode(url, transitionSlug, true, '');
                        animateArticleTransitionItems(preparedItems).then(function () {
                            finalizePjaxLoad(url);
                        });
                    } else {
                        logArticleTransitionMode(url, transitionSlug, false, 'target page does not expose a matching article transition target');
                        finalizePjaxLoad(url);
                    }
                } else {
                    let fallbackReason = '';
                    if (transitionSlug && !hasArticleTransitionSourceOnCurrentPage) {
                        fallbackReason = 'current page does not expose a matching article transition source';
                    }
                    logArticleTransitionMode(url, transitionSlug, false, fallbackReason);
                    updatePjaxContainerState(container, extractedHtml, url, pushState);
                    finalizePjaxLoad(url);
                }
            } catch (error) {
                document.dispatchEvent(new CustomEvent('pjax:end', { detail: { url: url, error: error } }));
                if (error && error.name === 'AbortError') {
                    return;
                }
                window.location.href = url;
            } finally {
                if (activePjaxController === requestController) {
                    activePjaxController = null;
                }
            }
        }

        function extractPjaxHtml(html, transitionSlug) {
            if (!html) {
                return '';
            }

            const parser = new DOMParser();
            const documentSnapshot = parser.parseFromString(html, 'text/html');
            const contentRoot = documentSnapshot.querySelector('#pjax-content-root');
            if (contentRoot) {
                return contentRoot.outerHTML;
            }

            const pjaxContainer = documentSnapshot.querySelector('#pjax-container');
            if (pjaxContainer) {
                return pjaxContainer.innerHTML;
            }

            return html;
        }

        function formatTimestamps(root = document) {
            const elements = root.querySelectorAll('.timestamp:not(.formatted)');
            elements.forEach(el => {
                try {
                    if (el.classList.contains('formatted')) return;
                    const raw = el.getAttribute('data-timestamp') || el.textContent;
                    if (!raw) return;

                    // 处理各种日期格式，确保能够被 new Date() 正确解析
                    let isoStr = raw.trim();
                    if (!isoStr.includes('T')) {
                        isoStr = isoStr.replace(' ', 'T');
                    }

                    const date = new Date(isoStr);
                    if (isNaN(date.getTime())) {
                        console.warn('Invalid date format:', raw);
                        return;
                    }

                    const formatted = new Intl.DateTimeFormat('zh-CN', {
                        year: 'numeric',
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        hour12: false
                    }).format(date);

                    el.textContent = formatted;
                    el.classList.add('formatted');
                } catch (err) {
                    console.error('Failed to format timestamp:', err, el);
                }
            });
        }

        let articleLinkPreviewCard = null;
        let articleLinkPreviewHideTimer = null;
        let articleLinkPreviewCurrentAnchor = null;

        function ensureArticleLinkPreviewCard() {
            if (articleLinkPreviewCard) {
                return articleLinkPreviewCard;
            }

            articleLinkPreviewCard = document.createElement('div');
            articleLinkPreviewCard.className = 'article-link-preview-card';
            articleLinkPreviewCard.innerHTML =
                '<div class="article-link-preview-arrow"></div>' +
                '<div class="article-link-preview-head">' +
                '<img class="article-link-preview-icon" alt="">' +
                '<div class="article-link-preview-title"></div>' +
                '</div>' +
                '<div class="article-link-preview-url"></div>' +
                '<div class="article-link-preview-description"></div>';
            document.body.appendChild(articleLinkPreviewCard);

            articleLinkPreviewCard.addEventListener('mouseenter', () => {
                clearTimeout(articleLinkPreviewHideTimer);
            });
            articleLinkPreviewCard.addEventListener('mouseleave', () => {
                scheduleArticleLinkPreviewHide();
            });

            return articleLinkPreviewCard;
        }

        function showArticleLinkPreview(anchor) {
            clearTimeout(articleLinkPreviewHideTimer);
            articleLinkPreviewCurrentAnchor = anchor;

            const card = ensureArticleLinkPreviewCard();
            const title = anchor.dataset.linkName || anchor.textContent || '文章外链';
            const url = anchor.dataset.linkUrl || anchor.getAttribute('href') || '';
            const description = anchor.dataset.linkDescription || '';
            const icon = anchor.dataset.linkIcon || '';

            const iconNode = card.querySelector('.article-link-preview-icon');
            const titleNode = card.querySelector('.article-link-preview-title');
            const urlNode = card.querySelector('.article-link-preview-url');
            const descriptionNode = card.querySelector('.article-link-preview-description');

            titleNode.textContent = title;
            urlNode.textContent = url;
            descriptionNode.textContent = description || '已接入站内文章外链管理。';

            if (icon) {
                iconNode.src = icon;
                iconNode.classList.remove('hidden');
            } else {
                iconNode.removeAttribute('src');
                iconNode.classList.add('hidden');
            }

            const rect = anchor.getBoundingClientRect();
            card.classList.add('is-measuring');
            card.classList.add('is-visible');
            card.classList.remove('is-below');
            card.classList.remove('is-above');

            let placement = 'above';
            let top = window.scrollY + rect.top - card.offsetHeight - 12;
            if (top < window.scrollY + 12) {
                placement = 'below';
                top = window.scrollY + rect.bottom + 12;
            }

            let left = window.scrollX + rect.left + rect.width / 2 - card.offsetWidth / 2;
            const maxLeft = window.scrollX + document.documentElement.clientWidth - card.offsetWidth - 16;
            if (left > maxLeft) {
                left = maxLeft;
            }
            if (left < 16) {
                left = 16;
            }
            card.style.top = top + 'px';
            card.style.left = left + 'px';

            const anchorCenterX = window.scrollX + rect.left + rect.width / 2;
            let arrowLeft = anchorCenterX - left;
            if (arrowLeft < 18) {
                arrowLeft = 18;
            }
            if (arrowLeft > card.offsetWidth - 18) {
                arrowLeft = card.offsetWidth - 18;
            }
            card.style.setProperty('--article-link-arrow-left', arrowLeft + 'px');

            if (placement === 'below') {
                card.classList.add('is-below');
            } else {
                card.classList.add('is-above');
            }

            card.classList.remove('is-measuring');
        }

        function scheduleArticleLinkPreviewHide() {
            clearTimeout(articleLinkPreviewHideTimer);
            articleLinkPreviewHideTimer = setTimeout(() => {
                hideArticleLinkPreview();
            }, 140);
        }

        function hideArticleLinkPreview() {
            if (!articleLinkPreviewCard) {
                return;
            }
            articleLinkPreviewCard.classList.remove('is-visible');
            articleLinkPreviewCard.classList.remove('is-above');
            articleLinkPreviewCard.classList.remove('is-below');
            articleLinkPreviewCurrentAnchor = null;
        }

        document.addEventListener('mouseover', (event) => {
            const anchor = event.target.closest('a[data-article-link-preview="true"]');
            if (!anchor) {
                return;
            }
            if (anchor.contains(event.relatedTarget)) {
                return;
            }
            showArticleLinkPreview(anchor);
        });

        document.addEventListener('mouseout', (event) => {
            const anchor = event.target.closest('a[data-article-link-preview="true"]');
            if (!anchor) {
                return;
            }
            if (anchor.contains(event.relatedTarget)) {
                return;
            }
            if (articleLinkPreviewCard && articleLinkPreviewCard.contains(event.relatedTarget)) {
                return;
            }
            scheduleArticleLinkPreviewHide();
        });

        document.addEventListener('focusin', (event) => {
            const anchor = event.target.closest('a[data-article-link-preview="true"]');
            if (!anchor) {
                return;
            }
            showArticleLinkPreview(anchor);
        });

        document.addEventListener('focusout', (event) => {
            const anchor = event.target.closest('a[data-article-link-preview="true"]');
            if (!anchor) {
                return;
            }
            scheduleArticleLinkPreviewHide();
        });

        document.addEventListener('scroll', () => {
            if (!articleLinkPreviewCurrentAnchor) {
                return;
            }
            showArticleLinkPreview(articleLinkPreviewCurrentAnchor);
        }, true);

        window.addEventListener('resize', () => {
            if (!articleLinkPreviewCurrentAnchor) {
                return;
            }
            showArticleLinkPreview(articleLinkPreviewCurrentAnchor);
        });

        // MutationObserver to handle dynamic content
        const observer = new MutationObserver((mutations) => {
            mutations.forEach(mutation => {
                if (mutation.addedNodes.length) {
                    formatTimestamps();
                }
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });

        if (!window.history.state) {
            window.history.replaceState({ pjax: false, url: window.location.href }, '', window.location.href);
        }

        document.addEventListener('click', (event) => {
            if (event.defaultPrevented) return;
            if (event.button !== 0) return;
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

            const anchor = event.target.closest('a');
            if (!canUsePjax(anchor)) return;

            const url = new URL(anchor.href, window.location.href);
            if (url.href === window.location.href) return;

            event.preventDefault();
            loadByPjax(url.href, true, resolveArticleTransitionSlug(url.href, anchor));
        });

        window.addEventListener('popstate', () => {
            loadByPjax(window.location.href, false, resolveArticleTransitionSlug(window.location.href, null));
        });

        document.addEventListener('page:ready', () => {
            formatTimestamps();
            injectSidebar();
            decorateShortcutButtons();
            if (restoreMobileFocusOnPageReady) {
                restoreMobileFocusOnPageReady = false;
                closeMobileMenu(true);
            } else {
                closeMobileMenu();
            }
        });

        dispatch('page:ready', { url: window.location.href });

        document.addEventListener('pjax:start', showProgress);
        document.addEventListener('pjax:end', hideProgress);
        document.addEventListener('pjax:complete', hideProgress);

        // Global Form Debouncing
        document.addEventListener('submit', (e) => {
            const form = e.target;
            if (form.dataset.noDebounce === 'true') return;

            const submitBtn = form.querySelector('button[type="submit"]') || 
                            document.querySelector(`button[type="submit"][form="${form.id}"]`);
            
            if (submitBtn) {
                // For AJAX forms handled elsewhere, they will call setLoading(false) manually.
                // For standard forms, the page will reload, so it stays disabled.
                window.setLoading(submitBtn, true);
            }
        });

        function isTextEditingTarget(target) {
            if (!target || !target.closest) return false;
            return Boolean(target.closest('textarea,[contenteditable="true"]'));
        }

        function isFormControlTarget(target) {
            if (!target || !target.closest) return false;
            return Boolean(target.closest('input,textarea,select,[contenteditable="true"]'));
        }

        function isVisible(element) {
            return Boolean(element && element.getClientRects().length &&
                getComputedStyle(element).visibility !== 'hidden');
        }

        function focusSearchInput() {
            const searchInput = Array.from(document.querySelectorAll('[data-shortcut-target="search"]'))
                .find(isVisible);
            if (!searchInput) return false;
            searchInput.focus({preventScroll: true});
            searchInput.select?.();
            return true;
        }

        function decorateShortcutButtons() {
            document.querySelectorAll('form:not([data-shortcut-form="search"]) button[type="submit"]')
                .forEach((button) => {
                    button.dataset.shortcut = button.dataset.shortcut || 'enter';
                    button.setAttribute('aria-keyshortcuts', SHORTCUTS.submit);
                    if (button.querySelector('[data-shortcut-hint]')) return;
                    if (button.textContent.includes('回车')) return;
                    const hint = document.createElement('span');
                    hint.dataset.shortcutHint = 'enter';
                    hint.className = 'ml-1 text-xs opacity-70';
                    hint.textContent = '（回车）';
                    button.appendChild(hint);
                });
        }

        // Application-scoped shortcuts. Do not react while this document is
        // hidden or has lost focus, and never compete with multiline editors.
        document.addEventListener('keydown', (event) => {
            if (!document.hasFocus() || document.visibilityState === 'hidden' || event.isComposing) return;

            if (event.key.toLowerCase() === 'k' && event.ctrlKey &&
                !event.metaKey && !event.altKey && !event.shiftKey &&
                !isFormControlTarget(event.target)) {
                if (focusSearchInput()) event.preventDefault();
                return;
            }

            if (event.key !== 'Enter' || event.ctrlKey || event.metaKey ||
                event.altKey || event.shiftKey || isTextEditingTarget(event.target)) return;
            const target = event.target;
            const form = target?.closest?.('form');
            if (!form || form.dataset.shortcutForm === 'search') return;
            const button = form.querySelector('button[data-shortcut="enter"]:not(:disabled)');
            if (!button) return;
            event.preventDefault();
            if (typeof form.requestSubmit === 'function') form.requestSubmit(button);
            else button.click();
        });

        // Search Suggestions logic
        let searchSuggestionCounter = 0;
        function bindSearchForms() {
            const searchForms = document.querySelectorAll('form[action="/search"]');
            searchForms.forEach((form) => {
                if (form.dataset.searchBound === 'true') return;
                form.dataset.searchBound = 'true';

                const searchInput = form.querySelector('input[name="q"]');
                if (!searchInput) return;
                searchInput.dataset.shortcutTarget = 'search';
                searchInput.setAttribute('role', 'combobox');
                searchInput.setAttribute('aria-autocomplete', 'list');
                searchInput.setAttribute('aria-haspopup', 'listbox');
                searchInput.setAttribute('aria-expanded', 'false');
                searchInput.setAttribute('autocomplete', 'off');

                const suggestWrap = document.createElement('div');
                const suggestId = `search-suggestions-${++searchSuggestionCounter}`;
                suggestWrap.id = suggestId;
                suggestWrap.setAttribute('role', 'listbox');
                suggestWrap.setAttribute('aria-label', '搜索建议');
                suggestWrap.className = 'search-suggestions absolute top-full left-0 right-0 mt-1 bg-card border border-border rounded-lg shadow-2xl hidden z-[60] overflow-hidden';
                searchInput.setAttribute('aria-controls', suggestId);
                form.appendChild(suggestWrap);

                let suggestTimer = null;
                let suggestRequestId = 0;
                let activeIndex = -1;

                const setSuggestionsVisible = (visible) => {
                    suggestWrap.classList.toggle('hidden', !visible);
                    searchInput.setAttribute('aria-expanded', visible.toString());
                    if (!visible) {
                        activeIndex = -1;
                        searchInput.removeAttribute('aria-activedescendant');
                    }
                };

                const suggestionItems = () => Array.from(suggestWrap.querySelectorAll('[data-val]'));

                const setActiveSuggestion = (index) => {
                    const items = suggestionItems();
                    if (items.length === 0) {
                        activeIndex = -1;
                        return;
                    }
                    activeIndex = (index + items.length) % items.length;
                    items.forEach((item, itemIndex) => {
                        const active = itemIndex === activeIndex;
                        item.setAttribute('aria-selected', active.toString());
                        item.classList.toggle('bg-accent/10', active);
                        item.classList.toggle('text-accent', active);
                    });
                    searchInput.setAttribute('aria-activedescendant', items[activeIndex].id);
                };

                const selectSuggestion = (item) => {
                    if (!item) return;
                    searchInput.value = item.getAttribute('data-val') || '';
                    setSuggestionsVisible(false);
                    if (typeof form.requestSubmit === 'function') form.requestSubmit();
                    else form.submit();
                };

                searchInput.addEventListener('input', () => {
                    const q = searchInput.value.trim();
                    clearTimeout(suggestTimer);
                    const requestId = ++suggestRequestId;
                    activeIndex = -1;
                    setSuggestionsVisible(false);
                    if (q.length < 2) {
                        suggestWrap.replaceChildren();
                        return;
                    }

                    suggestTimer = setTimeout(async () => {
                        try {
                            const res = await fetch(`/search/suggest?q=${encodeURIComponent(q)}`);
                            if (!res.ok) throw new Error('Suggest fetch failed');
                            const suggestions = await res.json();
                            if (requestId !== suggestRequestId || searchInput.value.trim() !== q) return;

                            if (suggestions && suggestions.length > 0) {
                                suggestWrap.replaceChildren();
                                suggestions.forEach((suggestion, index) => {
                                    const item = document.createElement('div');
                                    item.id = `${suggestId}-option-${index}`;
                                    item.setAttribute('role', 'option');
                                    item.setAttribute('aria-selected', 'false');
                                    item.tabIndex = -1;
                                    item.className = 'px-4 py-2 text-sm hover:bg-accent/10 cursor-pointer transition-colors border-b border-border/50 last:border-0';
                                    item.dataset.val = String(suggestion);
                                    item.textContent = String(suggestion);
                                    suggestWrap.appendChild(item);
                                });
                                setSuggestionsVisible(true);
                            } else {
                                suggestWrap.replaceChildren();
                                setSuggestionsVisible(false);
                            }
                        } catch (err) {
                            if (requestId === suggestRequestId) setSuggestionsVisible(false);
                            console.warn('Autocomplete error:', err);
                        }
                    }, 300);
                });

                suggestWrap.addEventListener('click', (event) => {
                    const item = event.target.closest?.('[data-val]');
                    selectSuggestion(item);
                });

                suggestWrap.addEventListener('mousemove', (event) => {
                    const item = event.target.closest?.('[data-val]');
                    if (!item) return;
                    setActiveSuggestion(suggestionItems().indexOf(item));
                });

                document.addEventListener('click', (event) => {
                    if (!form.contains(event.target)) setSuggestionsVisible(false);
                });

                searchInput.addEventListener('keydown', (event) => {
                    const items = suggestionItems();
                    if (event.key === 'ArrowDown' && items.length > 0) {
                        event.preventDefault();
                        setActiveSuggestion(activeIndex < 0 ? 0 : activeIndex + 1);
                    } else if (event.key === 'ArrowUp' && items.length > 0) {
                        event.preventDefault();
                        setActiveSuggestion(activeIndex < 0 ? items.length - 1 : activeIndex - 1);
                    } else if (event.key === 'Enter' && activeIndex >= 0) {
                        event.preventDefault();
                        selectSuggestion(items[activeIndex]);
                    } else if (event.key === 'Escape') {
                        setSuggestionsVisible(false);
                    }
                });
            });
        }

        bindSearchForms();
        decorateShortcutButtons();
        document.addEventListener('page:ready', () => {
            bindSearchForms();
            decorateShortcutButtons();
        });
    });
})();
