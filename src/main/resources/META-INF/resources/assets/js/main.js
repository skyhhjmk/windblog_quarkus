// /assets/js/main.js
(function () {
    'use strict';

    if (typeof navigator !== 'undefined' && 'serviceWorker' in navigator && typeof navigator.serviceWorker.getRegistrations === 'function') {
        navigator.serviceWorker.getRegistrations()
            .then(registrations => Promise.all(registrations.map(reg => reg.unregister().catch(() => false))))
            .catch(() => {});
    }
    if (typeof caches !== 'undefined' && typeof caches.keys === 'function') {
        caches.keys()
            .then(keys => Promise.all(keys.filter(key => key.indexOf('windblog-') === 0).map(key => caches.delete(key))))
            .catch(() => {});
    }

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function dispatch(name, detail) {
        document.dispatchEvent(new CustomEvent(name, { detail: detail || {} }));
    }

    // WindBlog keeps analytics markup declarative so PJAX fragments receive the
    // same Lens coverage as the initial document. No form values or article
    // text are sent; only stable semantic identifiers are added.
    let analyticsGoalBound = false;
    function analyticsTrackerFacade() {
        return window.SeeRay && typeof window.SeeRay.trackGoal === 'function' ? window.SeeRay : null;
    }

    function decorateAnalyticsMarkup(root) {
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        const searchForms = scope.querySelectorAll('form[data-shortcut-form="search"], form[action="/search"]');
        searchForms.forEach(form => form.setAttribute('data-seeray-search', ''));

        const formIds = {
            '#registerForm': 'register',
            '#loginForm': 'login',
            '#comment-form': 'comment',
            '#linkApplicationForm': 'link-application',
            '#userPostForm': 'user-post-editor',
            '#subscriptionForm': 'subscription-preferences',
            '#forgotPasswordForm': 'forgot-password',
            '#resendVerificationForm': 'resend-verification',
            '#resetPasswordForm': 'reset-password'
        };
        Object.entries(formIds).forEach(([selector, id]) => {
            scope.querySelectorAll(selector).forEach(form => form.setAttribute('data-seeray-form', id));
        });

        scope.querySelectorAll('article[data-article-transition-slug]').forEach(article => {
            const slug = article.getAttribute('data-article-transition-slug')?.trim();
            if (!slug || article.hasAttribute('data-seeray-content-name')) return;
            article.setAttribute('data-seeray-content-name', `article:${slug}`);
            article.setAttribute('data-seeray-content-piece', 'article-card');
            article.setAttribute('data-seeray-content-target', `/post/${encodeURIComponent(slug)}`);
        });

        let mediaIndex = 0;
        scope.querySelectorAll('audio, video').forEach(media => {
            if (media.hasAttribute('data-seeray-no-track') || media.hasAttribute('data-seeray-media')) return;
            const source = media.getAttribute('src') || media.querySelector('source')?.getAttribute('src') || '';
            const slug = source.split(/[?#]/, 1)[0].split('/').pop()?.replace(/[^a-zA-Z0-9._-]/g, '-') || `media-${mediaIndex}`;
            media.setAttribute('data-seeray-media', `windblog-${slug.slice(0, 48)}-${mediaIndex++}`.slice(0, 64));
        });

        const tracker = window.SeeRay;
        tracker?.refreshContentTracking?.();
        tracker?.refreshFormTracking?.();
        applyAnalyticsExperiments(scope);
    }

    function applyAnalyticsExperiments(root) {
        const tracker = window.SeeRay;
        if (!tracker || typeof tracker.assignExperiment !== 'function') return;
        const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
        scope.querySelectorAll('[data-seeray-experiment]').forEach(element => {
            const name = element.getAttribute('data-seeray-experiment')?.trim();
            const variants = (element.getAttribute('data-seeray-experiment-variants') || '')
                .split(',').map(value => value.trim()).filter(Boolean);
            if (!name || variants.length < 2 || element.dataset.seerayExperimentBound === 'true') return;
            element.dataset.seerayExperimentBound = 'true';
            const assign = () => {
                const variant = tracker.assignExperiment(name, variants);
                if (!variant) return;
                element.setAttribute('data-seeray-experiment-variant', variant);
                scope.querySelectorAll('[data-seeray-variant]').forEach(candidate => {
                    candidate.hidden = candidate.getAttribute('data-seeray-variant') !== variant;
                });
                dispatch('seeray:experiment', {name, variant, element});
            };
            Promise.resolve(tracker.ready?.()).then(assign).catch(() => undefined);
        });
    }

    function bindAnalyticsGoals() {
        if (analyticsGoalBound) return;
        analyticsGoalBound = true;
        document.addEventListener('click', event => {
            const target = event.target instanceof Element ? event.target.closest('[data-seeray-goal]') : null;
            const tracker = analyticsTrackerFacade();
            const name = target?.getAttribute('data-seeray-goal')?.trim();
            if (!tracker || !name || target?.closest('[data-seeray-no-track]')) return;
            tracker.trackGoal(name, {
                category: target.getAttribute('data-seeray-goal-category') || 'conversion',
                action: target.getAttribute('data-seeray-goal-action') || 'click',
                target: target.getAttribute('href') || undefined
            });
        }, true);
    }

    window.WindBlogAnalytics = Object.freeze({
        trackGoal(name, options) { analyticsTrackerFacade()?.trackGoal(name, options); },
        trackFormResult(formId, successful) { window.SeeRay?.trackFormResult?.(formId, successful); },
        decorate(root) { decorateAnalyticsMarkup(root); },
        applyExperiments(root) { applyAnalyticsExperiments(root); }
    });
    window.addEventListener('seeray:consent', () => applyAnalyticsExperiments(document));

    // Source-of-truth for the public web shortcut labels and bindings.
    // Custom key mapping is intentionally not exposed to users.
    const SHORTCUTS = Object.freeze({
        submit: 'Enter',
        search: '/'
    });

    ready(() => {
        const mobileBtn = document.getElementById('mobileMenuBtn');
        const mobileMenu = document.getElementById('mobileMenu');
        const mobileBackdrop = document.getElementById('mobileDrawerBackdrop');
        const mobileCloseBtn = document.getElementById('mobileDrawerCloseBtn');
        let restoreMobileFocusOnPageReady = false;

        const isMobileMenuOpen = () => {
            return !!(mobileMenu && mobileMenu.classList.contains('mobile-drawer-open'));
        };

        const openMobileMenu = () => {
            if (!mobileMenu) return;
            mobileMenu.classList.add('mobile-drawer-open');
            mobileMenu.setAttribute('aria-hidden', 'false');
            if (mobileBackdrop) {
                mobileBackdrop.classList.add('mobile-backdrop-open');
                mobileBackdrop.setAttribute('aria-hidden', 'false');
            }
            if (mobileBtn) {
                mobileBtn.setAttribute('aria-expanded', 'true');
                mobileBtn.setAttribute('aria-label', window.wbT('js.close_menu', '关闭菜单'));
            }
            document.documentElement.classList.add('drawer-open');
            document.body.classList.add('drawer-open');
            if (mobileCloseBtn) {
                mobileCloseBtn.focus({preventScroll: true});
            }
        };

        const closeMobileMenu = (restoreFocus = false) => {
            if (!mobileMenu) return;
            mobileMenu.classList.remove('mobile-drawer-open');
            mobileMenu.setAttribute('aria-hidden', 'true');
            if (mobileBackdrop) {
                mobileBackdrop.classList.remove('mobile-backdrop-open');
                mobileBackdrop.setAttribute('aria-hidden', 'true');
            }
            if (mobileBtn) {
                mobileBtn.setAttribute('aria-expanded', 'false');
                mobileBtn.setAttribute('aria-label', window.wbT('js.open_menu', '打开菜单'));
                if (restoreFocus) {
                    mobileBtn.focus({preventScroll: true});
                }
            }
            document.documentElement.classList.remove('drawer-open');
            document.body.classList.remove('drawer-open');
        };

        if (mobileBtn && mobileMenu) {
            mobileBtn.addEventListener('click', (event) => {
                event.stopPropagation();
                if (isMobileMenuOpen()) {
                    closeMobileMenu();
                } else {
                    openMobileMenu();
                }
            });

            if (mobileCloseBtn) {
                mobileCloseBtn.addEventListener('click', () => {
                    closeMobileMenu(true);
                });
            }

            if (mobileBackdrop) {
                mobileBackdrop.addEventListener('click', () => {
                    closeMobileMenu();
                });
            }

            mobileMenu.addEventListener('click', (event) => {
                const anchor = event.target.closest?.('a');
                if (!anchor || !anchor.getAttribute('href') || anchor.hasAttribute('download')) return;
                restoreMobileFocusOnPageReady = true;
                closeMobileMenu();
            });

            document.addEventListener('keydown', (event) => {
                if (event.key === 'Escape' && isMobileMenuOpen()) {
                    closeMobileMenu(true);
                }
            });

            window.addEventListener('resize', () => {
                if (window.innerWidth >= 1024 && isMobileMenuOpen()) {
                    closeMobileMenu();
                }
            });
        }

        // Theme Toggle Logic
        const themeToggles = document.querySelectorAll('.theme-toggle');
        const darkIcons = document.querySelectorAll('.theme-icon-dark');
        const lightIcons = document.querySelectorAll('.theme-icon-light');
        const themeColorMeta = document.querySelector('meta[name="theme-color"]');
        const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        let themeTransitionTimer = null;

        function updateThemeUI() {
            const isLight = document.documentElement.classList.contains('light');
            darkIcons.forEach(icon => isLight ? icon.classList.add('hidden') : icon.classList.remove('hidden'));
            lightIcons.forEach(icon => isLight ? icon.classList.remove('hidden') : icon.classList.add('hidden'));
            const colorSchemeMeta = document.getElementById('color-scheme-meta');
            if (colorSchemeMeta) colorSchemeMeta.setAttribute('content', isLight ? 'light' : 'dark');
            if (themeColorMeta) {
                const pageBackground = window.getComputedStyle(document.documentElement)
                    .getPropertyValue('--bg').trim();
                if (pageBackground) themeColorMeta.setAttribute('content', pageBackground);
            }
        }

        function applyTheme(isLight) {
            const root = document.documentElement;
            if (!prefersReducedMotion.matches) {
                root.classList.add('theme-transitioning');
                window.clearTimeout(themeTransitionTimer);
                themeTransitionTimer = window.setTimeout(() => {
                    root.classList.remove('theme-transitioning');
                }, 260);
            }
            root.classList.toggle('light', isLight);
            localStorage.setItem('theme', isLight ? 'light' : 'dark');
            updateThemeUI();
        }

        if (themeToggles.length > 0) {
            updateThemeUI();
            themeToggles.forEach(btn => {
                btn.addEventListener('click', () => {
                    applyTheme(!document.documentElement.classList.contains('light'));
                });
            });
        }

        const languageMenuBtn = document.getElementById('languageMenuBtn');
        const languageMenu = document.getElementById('languageMenu');
        const languageMenuContainer = document.getElementById('languageMenuContainer');
        if (languageMenuBtn && languageMenu && languageMenuContainer) {
            languageMenuBtn.addEventListener('click', () => {
                const isHidden = languageMenu.classList.toggle('hidden');
                languageMenuBtn.setAttribute('aria-expanded', (!isHidden).toString());
            });
            document.addEventListener('click', (e) => {
                if (!languageMenuContainer.contains(e.target) && !languageMenu.classList.contains('hidden')) {
                    languageMenu.classList.add('hidden');
                    languageMenuBtn.setAttribute('aria-expanded', 'false');
                }
            });
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && !languageMenu.classList.contains('hidden')) {
                    languageMenu.classList.add('hidden');
                    languageMenuBtn.setAttribute('aria-expanded', 'false');
                    languageMenuBtn.focus();
                }
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
        let progressStartTimer = null;
        let progressResetTimer = null;
        let slowRequestTimer = null;
        let activeTransitionItems = [];
        let displayedUrl = window.location.href;
        let displayedHistoryState = window.history.state;
        const navigationStatus = document.createElement('div');
        navigationStatus.className = 'pjax-status';
        navigationStatus.hidden = true;
        navigationStatus.setAttribute('role', 'status');
        navigationStatus.setAttribute('aria-live', 'polite');
        document.body.appendChild(navigationStatus);

        // WindBlog owns PJAX, History, and scroll restoration. Lens is deliberately
        // driven in manual mode so its automatic History wrapper cannot create a
        // second lifecycle for the same replacement. Every call remains a no-op
        // when analytics is disabled, blocked, or not yet available.
        let analyticsContainerUnregister = [];
        function analyticsTracker() {
            const tracker = window.SeeRay;
            return tracker && typeof tracker.beginNavigation === 'function' &&
                typeof tracker.pageReady === 'function' ? tracker : null;
        }

        function analyticsPageOptions(url, root) {
            const contentRoot = root && root.querySelector
                ? root.querySelector('#pjax-content-root')
                : document.querySelector('#pjax-content-root');
            return {
                url: url || window.location.href,
                layoutVersion: contentRoot?.getAttribute('data-seeray-layout-version') || 'windblog-unversioned'
            };
        }

        function unregisterAnalyticsContainers() {
            analyticsContainerUnregister.forEach(unregister => {
                try { unregister(); } catch (_) { /* A detached PJAX node must never block navigation. */ }
            });
            analyticsContainerUnregister = [];
        }

        function registerAnalyticsContainers(root) {
            const tracker = analyticsTracker();
            if (!tracker || !root?.querySelectorAll || typeof tracker.registerScrollContainer !== 'function') return;
            root.querySelectorAll('[data-seeray-scroll-container]').forEach(element => {
                const id = element.getAttribute('data-seeray-scroll-container')?.trim();
                if (!id || !(element instanceof HTMLElement)) return;
                const unregister = tracker.registerScrollContainer({ id, element });
                if (typeof unregister === 'function') analyticsContainerUnregister.push(unregister);
            });
        }

        function analyticsBeginNavigation() {
            unregisterAnalyticsContainers();
            analyticsTracker()?.beginNavigation();
        }

        function analyticsCancelNavigation() {
            const tracker = analyticsTracker();
            if (tracker && typeof tracker.cancelNavigation === 'function') tracker.cancelNavigation();
            registerAnalyticsContainers(document);
        }

        function analyticsPageReady(url, root) {
            const tracker = analyticsTracker();
            if (!tracker) return;
            decorateAnalyticsMarkup(root || document);
            tracker.pageReady(analyticsPageOptions(url, root || document));
            registerAnalyticsContainers(root || document);
        }

        document.addEventListener('page:ready', event => {
            analyticsPageReady(event.detail?.url, document);
        });
        bindAnalyticsGoals();
        analyticsPageReady(window.location.href, document);

        function clearProgressTimers() {
            clearTimeout(progressTimer);
            clearTimeout(progressStartTimer);
            clearTimeout(progressResetTimer);
            clearTimeout(slowRequestTimer);
        }

        function fallbackToFullPage(url) {
            navigationStatus.textContent = window.formatErrorMessage
                ? window.formatErrorMessage(window.wbT('js.page_load_failed', '页面局部加载失败，正在重新加载页面'), 'WB-UI-NAVIGATION-001')
                : window.wbT('js.page_load_failed', '页面局部加载失败，正在重新加载页面');
            navigationStatus.hidden = false;
            // A document navigation uses normal HTML, without the PJAX headers.
            // Reload explicitly for popstate failures and same-address navigation.
            if (new URL(url, window.location.href).href === window.location.href) {
                window.location.reload();
            } else {
                window.location.assign(url);
            }
        }

        function showProgress() {
            clearProgressTimers();
            navigationStatus.hidden = true;
            document.getElementById('pjax-container')?.setAttribute('aria-busy', 'true');
            slowRequestTimer = setTimeout(() => {
                navigationStatus.textContent = window.wbT('js.loading', '正在加载…');
                navigationStatus.hidden = false;
            }, 800);
            if (!bar) return;
            // scaleX keeps the indicator on the compositor; animating width
            // would force a layout pass for every frame of the bar.
            bar.dataset.active = 'true';
            bar.style.transition = 'none';
            bar.style.transform = 'scaleX(0)';
            void bar.offsetWidth;
            bar.style.transition = 'transform .25s ease';
            progressStartTimer = setTimeout(() => { bar.style.transform = 'scaleX(0.6)'; }, 120);
            progressTimer = setTimeout(() => {
                bar.style.transform = 'scaleX(0.85)';
            }, 300);
        }

        function hideProgress() {
            clearProgressTimers();
            navigationStatus.hidden = true;
            document.getElementById('pjax-container')?.removeAttribute('aria-busy');
            if (!bar) return;
            if (bar.dataset.active === 'true') {
                bar.style.transform = 'scaleX(1)';
            }
            delete bar.dataset.active;
            progressResetTimer = setTimeout(() => {
                bar.style.transition = 'none';
                bar.style.transform = 'scaleX(0)';
            }, 200);
        }

        function normalizeScrollPosition(position) {
            const left = Number(position && position.left);
            const top = Number(position && position.top);
            return {
                left: Number.isFinite(left) ? Math.max(0, left) : 0,
                top: Number.isFinite(top) ? Math.max(0, top) : 0
            };
        }

        function getCurrentScrollPosition() {
            return normalizeScrollPosition({
                left: window.scrollX || 0,
                top: window.scrollY || 0
            });
        }

        function getHistoryScrollPosition(state) {
            return normalizeScrollPosition(state && state.windblogScroll);
        }

        function scrollToPositionInstantly(position) {
            // An explicit instant behavior cancels any pending smooth scroll before
            // the target title is measured for the shared-element transition.
            const scrollPosition = normalizeScrollPosition(position);
            window.scrollTo({
                left: scrollPosition.left,
                top: scrollPosition.top,
                behavior: 'instant'
            });
        }

        function scrollToTopInstantly() {
            scrollToPositionInstantly({ left: 0, top: 0 });
        }

        function replaceCurrentHistoryState(fields) {
            const currentState = window.history.state;
            const nextState = currentState && typeof currentState === 'object'
                ? Object.assign({}, currentState)
                : {};
            Object.assign(nextState, fields || {});
            window.history.replaceState(nextState, '', window.location.href);
        }

        function switchLanguage(targetLang) {
            const root = document.documentElement;
            const defaultLang = (root.dataset.defaultLanguage || 'zh-cn').toLowerCase();
            const currentLang = (root.lang || '').toLowerCase();
            const target = String(targetLang || '').trim();
            if (!target) return;

            const targetIsDefault = target.toLowerCase() === defaultLang;
            const targetIsCurrent = currentLang === target.toLowerCase();
            let path = window.location.pathname || '/';
            path = path.replace(/^\/(zh-cn|en-us)(?=\/|$)/i, '');
            if (!path.startsWith('/')) path = '/' + path;
            if (!path) path = '/';

            const targetPath = targetIsDefault
                ? path
                : (path === '/' ? '/' + target + '/' : '/' + target + path);
            const params = new URLSearchParams(window.location.search);
            params.delete('lang');
            // Switching back to the region default uses the unprefixed URL, so
            // make the choice explicit; otherwise the existing user_lang_v2
            // cookie would keep the previous language.
            if (targetIsDefault && !targetIsCurrent) {
                params.set('lang', target);
            }
            const query = params.toString();
            const hash = window.location.hash || '';
            // Full document navigation on purpose: the header, window.i18n and
            // server-rendered content must all be rebuilt in the new language.
            window.location.assign(targetPath + (query ? '?' + query : '') + hash);
        }

        function canUsePjax(anchor) {
            if (!anchor) return false;
            if (anchor.matches('[data-language-switch]')) {
                return false;
            }
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

        const ARTICLE_LIST_RETURN_STORAGE_KEY = 'windblog:last-article-list-url';
        let lastArticleListUrl = '';

        function normalizeInternalUrl(url) {
            if (typeof url !== 'string' || !url.trim()) {
                return '';
            }

            try {
                const parsedUrl = new URL(url, window.location.href);
                if (parsedUrl.origin !== window.location.origin || parsedUrl.pathname.startsWith('/assets/')) {
                    return '';
                }
                return parsedUrl.href;
            } catch (error) {
                return '';
            }
        }

        function rememberArticleListUrl(url) {
            const normalizedUrl = normalizeInternalUrl(url);
            if (!normalizedUrl || isArticleDetailUrl(normalizedUrl)) {
                return '';
            }

            lastArticleListUrl = normalizedUrl;
            try {
                window.sessionStorage.setItem(ARTICLE_LIST_RETURN_STORAGE_KEY, normalizedUrl);
            } catch (error) {
                // Storage can be unavailable in private browsing or embedded pages.
            }
            return normalizedUrl;
        }

        function getArticleListReturnUrl() {
            const stateUrl = normalizeInternalUrl(
                window.history.state && window.history.state.articleListReturnUrl
            );
            if (stateUrl && !isArticleDetailUrl(stateUrl)) {
                return stateUrl;
            }

            if (lastArticleListUrl && !isArticleDetailUrl(lastArticleListUrl)) {
                return lastArticleListUrl;
            }

            try {
                const storedUrl = normalizeInternalUrl(
                    window.sessionStorage.getItem(ARTICLE_LIST_RETURN_STORAGE_KEY)
                );
                if (storedUrl && !isArticleDetailUrl(storedUrl)) {
                    lastArticleListUrl = storedUrl;
                    return storedUrl;
                }
            } catch (error) {
                // Storage can be unavailable in private browsing or embedded pages.
            }

            return '';
        }

        function areSameInternalUrls(firstUrl, secondUrl) {
            const normalizedFirstUrl = normalizeInternalUrl(firstUrl);
            const normalizedSecondUrl = normalizeInternalUrl(secondUrl);
            return Boolean(normalizedFirstUrl && normalizedSecondUrl &&
                normalizedFirstUrl === normalizedSecondUrl);
        }

        function updateArticleReturnLink() {
            const returnUrl = isArticleDetailUrl(window.location.href)
                ? (getArticleListReturnUrl() || '/')
                : '/';
            document.querySelectorAll('[data-article-return-link]').forEach(function (anchor) {
                anchor.setAttribute('href', returnUrl);
            });
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

        const ARTICLE_TRANSITION_PARTS = ['cover', 'title', 'summary'];

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

            ARTICLE_TRANSITION_PARTS.forEach(function (partName) {
                const element = root.querySelector(getArticleTransitionSelector(slug, partName));
                if (element) {
                    elements.push(element);
                }
            });

            return elements;
        }

        function findArticleTransitionCardElement(root, slug) {
            if (!root || !slug) {
                return null;
            }

            const safeSlug = getCssSafeSelectorValue(slug);
            const cardCandidate = root.querySelector('article[data-article-transition-slug="' + safeSlug + '"]');
            if (cardCandidate) {
                return cardCandidate.classList.contains('card')
                    ? cardCandidate
                    : (cardCandidate.closest('.card') || cardCandidate);
            }

            const firstTransitionElement = getArticleTransitionElements(root, slug)[0];
            if (!firstTransitionElement || typeof firstTransitionElement.closest !== 'function') {
                return null;
            }

            return firstTransitionElement.closest('article.card, .card');
        }

        function hasArticleTransitionSource(root, slug) {
            if (!root || !slug) {
                return false;
            }

            return getArticleTransitionElements(root, slug).length > 0;
        }

        function getCurrentArticleTransitionSlug() {
            // During popstate the URL changes before the old DOM is replaced.
            // Read the detail-page h1 first so the reverse title transition can
            // still find its source when navigating back to a list page.
            const articleElement = document.querySelector('h1[data-article-transition-slug][data-article-transition-part="title"]');
            if (articleElement) {
                const slug = articleElement.getAttribute('data-article-transition-slug');
                if (slug) {
                    return slug;
                }
            }

            if (!isArticleDetailUrl(window.location.href)) {
                return '';
            }

            return getArticleSlugFromUrl(window.location.href);
        }

        function resolveArticleTransitionSlug(url, anchor) {
            const targetIsPost = isArticleDetailUrl(url);
            const currentSlug = getCurrentArticleTransitionSlug();
            const clickedSlug = getClickedArticleTransitionSlug(anchor);
            const historyTransitionSlug = window.history.state && window.history.state.articleTransitionSlug;

            if (targetIsPost && clickedSlug && hasArticleTransitionSource(document, clickedSlug)) {
                return clickedSlug;
            }

            if (targetIsPost && historyTransitionSlug && hasArticleTransitionSource(document, historyTransitionSlug)) {
                return historyTransitionSlug;
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

        function updatePjaxContainerState(container, html, url, pushState, options) {
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

            const shouldScrollToTop = !options || options.scrollToTop !== false;
            if (pushState) {
                const sourceScrollPosition = normalizeScrollPosition(
                    options && options.sourceScrollPosition
                        ? options.sourceScrollPosition
                        : getCurrentScrollPosition()
                );
                replaceCurrentHistoryState({ windblogScroll: sourceScrollPosition });

                const nextHistoryState = {
                    pjax: true,
                    url: url,
                    windblogScroll: shouldScrollToTop
                        ? { left: 0, top: 0 }
                        : sourceScrollPosition
                };
                if (options && options.articleListReturnUrl) {
                    nextHistoryState.articleListReturnUrl = options.articleListReturnUrl;
                    nextHistoryState.articleListReturnViaHistory = true;
                }
                if (options && options.transitionSlug) {
                    nextHistoryState.articleTransitionSlug = options.transitionSlug;
                }
                window.history.pushState(nextHistoryState, '', url);

                if (shouldScrollToTop) {
                    if (options && options.instantScroll) {
                        scrollToTopInstantly();
                    } else {
                        window.scrollTo(0, 0);
                    }
                }
            } else if (options && options.restoreScrollPosition) {
                // The browser must not restore a history entry with the global
                // smooth-scroll CSS rule. Restore the target entry explicitly.
                scrollToPositionInstantly(options.restoreScrollPosition);
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
                'meta[property^="og:"], meta[property^="article:"], meta[name^="twitter:"], '
                    + 'script[type="application/ld+json"], link[rel="alternate"][hreflang], link[rel="amphtml"]'
            ).forEach(function (metadataNode) {
                metadataNode.remove();
            });

            const metadataTemplate = pageRoot.querySelector('#pjax-head-metadata');
            if (metadataTemplate) {
                document.head.appendChild(metadataTemplate.content.cloneNode(true));
            }
            if (!document.head.querySelector('meta[property="og:title"]')) {
                const description = document.head.querySelector('meta[name="description"]')?.content || '';
                const canonical = document.head.querySelector('link[rel="canonical"]')?.href || url;
                [['og:title', document.title], ['og:description', description], ['og:type', 'website'], ['og:url', canonical], ['twitter:card', 'summary'], ['twitter:title', document.title], ['twitter:description', description]].forEach(([name, content]) => {
                    const meta = document.createElement('meta');
                    meta.setAttribute(name.startsWith('og:') ? 'property' : 'name', name);
                    meta.content = content;
                    document.head.appendChild(meta);
                });
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

        function clampRectToViewport(rect) {
            if (!rect) {
                return null;
            }
            const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 800;
            const top = Math.max(-24, rect.top);
            const bottom = Math.min(viewportHeight + 24, rect.top + rect.height);
            return {
                left: rect.left,
                top: top,
                width: rect.width,
                height: Math.max(96, bottom - top)
            };
        }

        function captureArticleTransitionItems(slug) {
            const capturedItems = [];
            if (!slug) {
                return capturedItems;
            }

            const cardElement = findArticleTransitionCardElement(document, slug);
            if (cardElement) {
                const rawCardRect = cardElement.getBoundingClientRect();
                if (rawCardRect.width > 0 && rawCardRect.height > 0) {
                    capturedItems.cardSourceRect = clampRectToViewport(rawCardRect);
                }
            }

            ARTICLE_TRANSITION_PARTS.forEach(function (partName) {
                const sourceElement = document.querySelector(getArticleTransitionSelector(slug, partName));
                if (!sourceElement) {
                    return;
                }

                const sourceRect = sourceElement.getBoundingClientRect();
                if (sourceRect.width <= 0 || sourceRect.height <= 0) {
                    return;
                }

                let coverState = null;
                if (partName === 'cover') {
                    const imgElement = sourceElement.querySelector('img');
                    const canvasElement = sourceElement.querySelector('canvas[data-thumbhash]');
                    coverState = {
                        imgSrc: imgElement ? (imgElement.currentSrc || imgElement.getAttribute('src') || '') : '',
                        imgAlt: imgElement ? (imgElement.getAttribute('alt') || '') : '',
                        thumbhash: canvasElement ? canvasElement.getAttribute('data-thumbhash') : '',
                        isTallCrop: Boolean(imgElement && imgElement.classList.contains('is-tall-crop')),
                        imgOpacity: imgElement
                            ? (parseFloat(window.getComputedStyle(imgElement).opacity) || 1)
                            : 1,
                        gradientOpacity: sourceElement.querySelector('.post-header-cover-gradient') ? 1 : 0
                    };
                }

                capturedItems.push({
                    partName: partName,
                    sourceRect: {
                        left: sourceRect.left,
                        top: sourceRect.top,
                        width: sourceRect.width,
                        height: sourceRect.height
                    },
                    sourceStyle: getArticleTransitionStyleSnapshot(sourceElement),
                    coverState: coverState
                });
            });

            return capturedItems;
        }

        function getArticleTransitionStyleSnapshot(element) {
            const computedStyle = window.getComputedStyle(element);
            return {
                backgroundColor: computedStyle.backgroundColor,
                borderBottomColor: computedStyle.borderBottomColor,
                borderBottomLeftRadius: computedStyle.borderBottomLeftRadius,
                borderBottomRightRadius: computedStyle.borderBottomRightRadius,
                borderBottomStyle: computedStyle.borderBottomStyle,
                borderBottomWidth: computedStyle.borderBottomWidth,
                borderLeftColor: computedStyle.borderLeftColor,
                borderLeftStyle: computedStyle.borderLeftStyle,
                borderLeftWidth: computedStyle.borderLeftWidth,
                borderRightColor: computedStyle.borderRightColor,
                borderRightStyle: computedStyle.borderRightStyle,
                borderRightWidth: computedStyle.borderRightWidth,
                borderTopColor: computedStyle.borderTopColor,
                borderTopLeftRadius: computedStyle.borderTopLeftRadius,
                borderTopRightRadius: computedStyle.borderTopRightRadius,
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
            element.style.borderBottomLeftRadius = styleSnapshot.borderBottomLeftRadius;
            element.style.borderBottomRightRadius = styleSnapshot.borderBottomRightRadius;
            element.style.borderBottomStyle = styleSnapshot.borderBottomStyle;
            element.style.borderBottomWidth = styleSnapshot.borderBottomWidth;
            element.style.borderLeftColor = styleSnapshot.borderLeftColor;
            element.style.borderLeftStyle = styleSnapshot.borderLeftStyle;
            element.style.borderLeftWidth = styleSnapshot.borderLeftWidth;
            element.style.borderRightColor = styleSnapshot.borderRightColor;
            element.style.borderRightStyle = styleSnapshot.borderRightStyle;
            element.style.borderRightWidth = styleSnapshot.borderRightWidth;
            element.style.borderTopColor = styleSnapshot.borderTopColor;
            element.style.borderTopLeftRadius = styleSnapshot.borderTopLeftRadius;
            element.style.borderTopRightRadius = styleSnapshot.borderTopRightRadius;
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

        function getArticleTransitionContainerOffset(container) {
            if (!container) {
                return { x: 0, y: 0 };
            }
            const transform = window.getComputedStyle(container).transform;
            if (!transform || transform === 'none') {
                return { x: 0, y: 0 };
            }
            const match = transform.match(/^matrix(3d)?\((.*)\)$/);
            if (!match) {
                return { x: 0, y: 0 };
            }
            const values = match[2].split(',').map(Number);
            if (values.some(function (value) { return !Number.isFinite(value); })) {
                return { x: 0, y: 0 };
            }
            if (match[1]) {
                return { x: values[12], y: values[13] };
            }
            return { x: values[4], y: values[5] };
        }

        function getArticleTransitionTargetRect(targetElement, container) {
            const rect = targetElement.getBoundingClientRect();
            // The PJAX container fades in with a slight translate. The clone
            // lives on document.body, outside that transform, so compensate for
            // it to keep the destination in final (settled) page coordinates.
            const offset = getArticleTransitionContainerOffset(container);
            return {
                left: rect.left - offset.x,
                top: rect.top - offset.y,
                width: rect.width,
                height: rect.height
            };
        }

        function getArticleTransitionTargetSignature(preparedItems, container) {
            return preparedItems.map(function (preparedItem) {
                const rect = getArticleTransitionTargetRect(preparedItem.targetElement, container);
                const computedStyle = window.getComputedStyle(preparedItem.targetElement);
                // Round away sub-pixel float noise: the container transform is
                // interpolated per frame, and rect minus matrix would otherwise
                // differ by ~0.005px forever and never count as stable.
                return [
                    rect.left.toFixed(1),
                    rect.top.toFixed(1),
                    rect.width.toFixed(1),
                    rect.height.toFixed(1),
                    computedStyle.fontSize,
                    computedStyle.lineHeight,
                    computedStyle.fontWeight,
                    computedStyle.letterSpacing
                ].join('|');
            }).join('~');
        }

        function nextArticleTransitionFrame() {
            return new Promise(function (resolve) {
                let settled = false;
                const finish = function () {
                    if (settled) {
                        return;
                    }
                    settled = true;
                    window.clearTimeout(timer);
                    resolve();
                };
                // Fallback for browsers/tabs where requestAnimationFrame is
                // throttled; the caller always has a hard deadline anyway.
                const timer = window.setTimeout(finish, 48);
                if (typeof window.requestAnimationFrame === 'function') {
                    window.requestAnimationFrame(finish);
                } else {
                    finish();
                }
            });
        }

        async function settleArticleTransitionTargets(preparedItems, container) {
            if (!preparedItems || preparedItems.length === 0 || document.visibilityState === 'hidden') {
                return;
            }
            const deadline = performance.now() + 180;
            let previousSignature = null;
            let stableFrames = 0;
            while (performance.now() < deadline) {
                await nextArticleTransitionFrame();
                const signature = getArticleTransitionTargetSignature(preparedItems, container);
                if (signature === previousSignature) {
                    stableFrames += 1;
                    if (stableFrames >= 1) {
                        return;
                    }
                } else {
                    previousSignature = signature;
                    stableFrames = 0;
                }
            }
        }

        function prepareCoverTransitionClone(cloneElement, capturedItem) {
            cloneElement.classList.remove('post-list-cover', 'post-header-cover');
            cloneElement.style.minHeight = '0';
            cloneElement.style.maxHeight = 'none';
            cloneElement.style.margin = '0';

            const sourceCover = capturedItem.coverState || {};
            let canvasElement = cloneElement.querySelector('canvas[data-thumbhash]');
            if (!canvasElement && sourceCover.thumbhash) {
                canvasElement = document.createElement('canvas');
                canvasElement.className = 'placeholder post-list-cover-placeholder';
                canvasElement.setAttribute('data-thumbhash', sourceCover.thumbhash);
                cloneElement.insertBefore(canvasElement, cloneElement.firstChild);
            }
            if (canvasElement && window.renderThumbHash) {
                window.renderThumbHash(canvasElement);
            }

            let imgElement = cloneElement.querySelector('img');
            if (!imgElement && sourceCover.imgSrc) {
                imgElement = document.createElement('img');
                imgElement.src = sourceCover.imgSrc;
                imgElement.alt = sourceCover.imgAlt || '';
                cloneElement.insertBefore(imgElement, cloneElement.firstChild);
            }
            if (imgElement) {
                imgElement.classList.remove('post-header-cover-img');
                imgElement.classList.add('post-list-cover-img');
                if (sourceCover.isTallCrop) {
                    imgElement.classList.add('is-tall-crop');
                }
                imgElement.loading = 'eager';
                imgElement.decoding = 'async';
                imgElement.style.opacity = String(sourceCover.imgOpacity != null ? sourceCover.imgOpacity : 1);
            }

            let gradientElement = cloneElement.querySelector('.post-header-cover-gradient');
            if (!gradientElement) {
                gradientElement = document.createElement('div');
                gradientElement.className = 'post-header-cover-gradient';
                gradientElement.setAttribute('aria-hidden', 'true');
                cloneElement.appendChild(gradientElement);
            }
            gradientElement.style.opacity = String(sourceCover.gradientOpacity != null ? sourceCover.gradientOpacity : 0);

            return {
                imgElement: imgElement,
                gradientElement: gradientElement
            };
        }

        function prepareArticleTransitionTargets(slug, capturedItems) {
            const preparedItems = [];
            if (!slug || !capturedItems || capturedItems.length === 0) {
                return preparedItems;
            }

            const targetCardElement = findArticleTransitionCardElement(document, slug);
            if (capturedItems.cardSourceRect && targetCardElement) {
                const backdropElement = document.createElement('div');
                backdropElement.className = 'pjax-article-transition-backdrop';
                backdropElement.setAttribute('aria-hidden', 'true');
                backdropElement.style.transform = 'translate3d('
                    + capturedItems.cardSourceRect.left + 'px, '
                    + capturedItems.cardSourceRect.top + 'px, 0)';
                backdropElement.style.width = capturedItems.cardSourceRect.width + 'px';
                backdropElement.style.height = capturedItems.cardSourceRect.height + 'px';
                backdropElement.style.opacity = '0.94';
                document.body.appendChild(backdropElement);
                preparedItems.backdropItem = {
                    element: backdropElement,
                    sourceRect: capturedItems.cardSourceRect,
                    targetElement: targetCardElement,
                    targetRect: null
                };
            }

            capturedItems.forEach(function (capturedItem) {
                const targetElement = document.querySelector(getArticleTransitionSelector(slug, capturedItem.partName));
                if (!targetElement) {
                    return;
                }

                const cloneElement = targetElement.cloneNode(true);
                cloneElement.classList.add(
                    'pjax-article-transition-clone',
                    'pjax-article-transition-clone-' + capturedItem.partName
                );
                cloneElement.classList.remove('pjax-article-transition-target-hidden');
                cloneElement.removeAttribute('data-article-transition-part');
                cloneElement.removeAttribute('data-article-transition-slug');
                targetElement.classList.add('pjax-article-transition-target-hidden');

                let coverNodes = null;
                if (capturedItem.partName === 'cover') {
                    cloneElement.style.zIndex = '9998';
                    coverNodes = prepareCoverTransitionClone(cloneElement, capturedItem);
                } else if (capturedItem.partName === 'summary') {
                    cloneElement.style.zIndex = '9999';
                    const clampedParagraph = cloneElement.querySelector('.line-clamp-2');
                    if (clampedParagraph) {
                        clampedParagraph.classList.remove('line-clamp-2');
                    }
                } else if (capturedItem.partName === 'title') {
                    cloneElement.style.zIndex = '10001';
                }

                // Park the clone where the source element was visible while the
                // destination geometry settles in the hidden container.
                cloneElement.style.transform = 'translate3d(' + capturedItem.sourceRect.left + 'px, ' + capturedItem.sourceRect.top + 'px, 0)';
                cloneElement.style.width = capturedItem.sourceRect.width + 'px';
                cloneElement.style.height = capturedItem.sourceRect.height + 'px';
                applyArticleTransitionStyleSnapshot(cloneElement, capturedItem.sourceStyle);
                document.body.appendChild(cloneElement);

                preparedItems.push({
                    partName: capturedItem.partName,
                    sourceRect: capturedItem.sourceRect,
                    sourceStyle: capturedItem.sourceStyle,
                    coverState: capturedItem.coverState,
                    coverNodes: coverNodes,
                    targetRect: null,
                    targetStyle: null,
                    cloneElement: cloneElement,
                    targetElement: targetElement
                });
            });

            if (preparedItems.length === 0 && preparedItems.backdropItem) {
                preparedItems.backdropItem.element.remove();
                preparedItems.backdropItem = null;
            }

            return preparedItems;
        }

        function measureArticleTransitionTargets(preparedItems, container) {
            const measuredItems = [];
            if (preparedItems && preparedItems.backdropItem) {
                const rawTargetCardRect = getArticleTransitionTargetRect(
                    preparedItems.backdropItem.targetElement,
                    container
                );
                if (rawTargetCardRect.width > 0 && rawTargetCardRect.height > 0) {
                    preparedItems.backdropItem.targetRect = clampRectToViewport(rawTargetCardRect);
                    measuredItems.backdropItem = preparedItems.backdropItem;
                } else {
                    preparedItems.backdropItem.element.remove();
                }
            }

            preparedItems.forEach(function (preparedItem) {
                const targetRect = getArticleTransitionTargetRect(preparedItem.targetElement, container);
                if (targetRect.width <= 0 || targetRect.height <= 0) {
                    cleanupArticleTransitionItems([preparedItem]);
                    return;
                }
                preparedItem.targetRect = targetRect;
                preparedItem.targetStyle = getArticleTransitionStyleSnapshot(preparedItem.targetElement);
                if (preparedItem.partName === 'cover') {
                    const targetImg = preparedItem.targetElement.querySelector('img');
                    if (targetImg && targetImg.classList.contains('is-tall-crop') && preparedItem.coverNodes && preparedItem.coverNodes.imgElement) {
                        preparedItem.coverNodes.imgElement.classList.add('is-tall-crop');
                    }
                    preparedItem.targetCoverState = {
                        imgOpacity: targetImg
                            ? (parseFloat(window.getComputedStyle(targetImg).opacity) || 1)
                            : 1,
                        gradientOpacity: preparedItem.targetElement.querySelector('.post-header-cover-gradient') ? 1 : 0
                    };
                }
                measuredItems.push(preparedItem);
            });

            if (measuredItems.length === 0 && measuredItems.backdropItem) {
                measuredItems.backdropItem.element.remove();
                measuredItems.backdropItem = null;
            }

            return measuredItems;
        }

        function animateTitleTransitionItem(preparedItem, duration, easing) {
            const cloneElement = preparedItem.cloneElement;
            const sourceRect = preparedItem.sourceRect;
            const targetRect = preparedItem.targetRect;
            const sourceStyle = preparedItem.sourceStyle;
            const targetStyle = preparedItem.targetStyle;

            // Lock discrete font properties (fontWeight, fontFamily) to targetStyle
            // up front, then smoothly interpolate rect and font metrics to the
            // exact destination box on top of the cover image.
            applyArticleTransitionStyleSnapshot(cloneElement, targetStyle);
            cloneElement.style.width = targetRect.width + 'px';
            cloneElement.style.height = 'auto';

            return cloneElement.animate([
                {
                    transform: 'translate3d(' + sourceRect.left + 'px, ' + sourceRect.top + 'px, 0)',
                    width: sourceRect.width + 'px',
                    fontSize: sourceStyle.fontSize,
                    lineHeight: sourceStyle.lineHeight,
                    letterSpacing: sourceStyle.letterSpacing,
                    color: sourceStyle.color
                },
                {
                    transform: 'translate3d(' + targetRect.left + 'px, ' + targetRect.top + 'px, 0)',
                    width: targetRect.width + 'px',
                    fontSize: targetStyle.fontSize,
                    lineHeight: targetStyle.lineHeight,
                    letterSpacing: targetStyle.letterSpacing,
                    color: targetStyle.color
                }
            ], {
                duration: duration,
                easing: easing,
                fill: 'forwards'
            }).finished;
        }

        function animateCoverTransitionItem(preparedItem, duration, easing) {
            const cloneElement = preparedItem.cloneElement;
            const sourceRect = preparedItem.sourceRect;
            const targetRect = preparedItem.targetRect;
            const sourceStyle = preparedItem.sourceStyle;
            const targetStyle = preparedItem.targetStyle;
            const sourceCover = preparedItem.coverState || { imgOpacity: 1, gradientOpacity: 0 };
            const targetCover = preparedItem.targetCoverState || { imgOpacity: 1, gradientOpacity: 0 };
            const promises = [];

            promises.push(cloneElement.animate([
                {
                    transform: 'translate3d(' + sourceRect.left + 'px, ' + sourceRect.top + 'px, 0)',
                    width: sourceRect.width + 'px',
                    height: sourceRect.height + 'px',
                    borderTopLeftRadius: sourceStyle.borderTopLeftRadius,
                    borderTopRightRadius: sourceStyle.borderTopRightRadius,
                    borderBottomRightRadius: sourceStyle.borderBottomRightRadius,
                    borderBottomLeftRadius: sourceStyle.borderBottomLeftRadius
                },
                {
                    transform: 'translate3d(' + targetRect.left + 'px, ' + targetRect.top + 'px, 0)',
                    width: targetRect.width + 'px',
                    height: targetRect.height + 'px',
                    borderTopLeftRadius: targetStyle.borderTopLeftRadius,
                    borderTopRightRadius: targetStyle.borderTopRightRadius,
                    borderBottomRightRadius: targetStyle.borderBottomRightRadius,
                    borderBottomLeftRadius: targetStyle.borderBottomLeftRadius
                }
            ], {
                duration: duration,
                easing: easing,
                fill: 'forwards'
            }).finished);

            if (preparedItem.coverNodes && preparedItem.coverNodes.imgElement) {
                promises.push(preparedItem.coverNodes.imgElement.animate([
                    { opacity: sourceCover.imgOpacity, transform: 'scale(1.03)' },
                    { opacity: targetCover.imgOpacity, transform: 'scale(1)' }
                ], {
                    duration: duration,
                    easing: easing,
                    fill: 'forwards'
                }).finished);
            }

            if (preparedItem.coverNodes && preparedItem.coverNodes.gradientElement) {
                promises.push(preparedItem.coverNodes.gradientElement.animate([
                    { opacity: sourceCover.gradientOpacity },
                    { opacity: targetCover.gradientOpacity }
                ], {
                    duration: duration,
                    easing: easing,
                    fill: 'forwards'
                }).finished);
            }

            return Promise.all(promises);
        }

        function animateSummaryTransitionItem(preparedItem, duration, easing) {
            const cloneElement = preparedItem.cloneElement;
            const sourceRect = preparedItem.sourceRect;
            const targetRect = preparedItem.targetRect;
            const sourceStyle = preparedItem.sourceStyle;
            const targetStyle = preparedItem.targetStyle;

            applyArticleTransitionStyleSnapshot(cloneElement, targetStyle);

            return cloneElement.animate([
                {
                    transform: 'translate3d(' + sourceRect.left + 'px, ' + sourceRect.top + 'px, 0)',
                    width: sourceRect.width + 'px',
                    height: sourceRect.height + 'px',
                    backgroundColor: sourceStyle.backgroundColor,
                    borderTopLeftRadius: sourceStyle.borderTopLeftRadius,
                    borderTopRightRadius: sourceStyle.borderTopRightRadius,
                    borderBottomRightRadius: sourceStyle.borderBottomRightRadius,
                    borderBottomLeftRadius: sourceStyle.borderBottomLeftRadius
                },
                {
                    transform: 'translate3d(' + targetRect.left + 'px, ' + targetRect.top + 'px, 0)',
                    width: targetRect.width + 'px',
                    height: targetRect.height + 'px',
                    backgroundColor: targetStyle.backgroundColor,
                    borderTopLeftRadius: targetStyle.borderTopLeftRadius,
                    borderTopRightRadius: targetStyle.borderTopRightRadius,
                    borderBottomRightRadius: targetStyle.borderBottomRightRadius,
                    borderBottomLeftRadius: targetStyle.borderBottomLeftRadius
                }
            ], {
                duration: duration,
                easing: easing,
                fill: 'forwards'
            }).finished;
        }

        function animateArticleTransitionItems(preparedItems, container) {
            if (!preparedItems || preparedItems.length === 0) {
                return Promise.resolve();
            }
            if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
                cleanupArticleTransitionItems(preparedItems);
                return Promise.resolve();
            }

            const duration = 380;
            const easing = 'cubic-bezier(0.22, 1, 0.36, 1)';
            const animationPromises = [];

            if (preparedItems.backdropItem && preparedItems.backdropItem.targetRect) {
                const backdrop = preparedItems.backdropItem;
                animationPromises.push(backdrop.element.animate([
                    {
                        transform: 'translate3d(' + backdrop.sourceRect.left + 'px, ' + backdrop.sourceRect.top + 'px, 0)',
                        width: backdrop.sourceRect.width + 'px',
                        height: backdrop.sourceRect.height + 'px',
                        opacity: 0.94
                    },
                    {
                        transform: 'translate3d(' + backdrop.targetRect.left + 'px, ' + backdrop.targetRect.top + 'px, 0)',
                        width: backdrop.targetRect.width + 'px',
                        height: backdrop.targetRect.height + 'px',
                        opacity: 0.32,
                        offset: 0.68
                    },
                    {
                        transform: 'translate3d(' + backdrop.targetRect.left + 'px, ' + backdrop.targetRect.top + 'px, 0)',
                        width: backdrop.targetRect.width + 'px',
                        height: backdrop.targetRect.height + 'px',
                        opacity: 0
                    }
                ], {
                    duration: duration,
                    easing: easing,
                    fill: 'forwards'
                }).finished);
            }

            preparedItems.forEach(function (preparedItem) {
                if (preparedItem.partName === 'title') {
                    animationPromises.push(animateTitleTransitionItem(preparedItem, duration, easing));
                } else if (preparedItem.partName === 'cover') {
                    animationPromises.push(animateCoverTransitionItem(preparedItem, duration, easing));
                } else {
                    animationPromises.push(animateSummaryTransitionItem(preparedItem, duration, easing));
                }
            });

            return Promise.all(animationPromises).then(function () {
                return reconcileArticleTransitionTargets(preparedItems, container);
            }).then(function () {
                cleanupArticleTransitionItems(preparedItems);
            }).catch(function () {
                cleanupArticleTransitionItems(preparedItems);
            });
        }

        function reconcileArticleTransitionTargets(preparedItems, container) {
            // Content above the title (lazy images, ad slots, late layout init)
            // may still settle while the morph runs. Glide the clone to the
            // final rect instead of snapping when the real element is revealed.
            const corrections = [];
            preparedItems.forEach(function (preparedItem) {
                const targetRect = getArticleTransitionTargetRect(preparedItem.targetElement, container);
                const moved = Math.abs(targetRect.left - preparedItem.targetRect.left) > 0.5
                    || Math.abs(targetRect.top - preparedItem.targetRect.top) > 0.5
                    || Math.abs(targetRect.width - preparedItem.targetRect.width) > 0.5
                    || Math.abs(targetRect.height - preparedItem.targetRect.height) > 0.5;
                if (!moved) {
                    return;
                }
                if (preparedItem.partName === 'title') {
                    corrections.push(preparedItem.cloneElement.animate([
                        {
                            transform: 'translate3d(' + preparedItem.targetRect.left + 'px, ' + preparedItem.targetRect.top + 'px, 0)',
                            width: preparedItem.targetRect.width + 'px'
                        },
                        {
                            transform: 'translate3d(' + targetRect.left + 'px, ' + targetRect.top + 'px, 0)',
                            width: targetRect.width + 'px'
                        }
                    ], {
                        duration: 120,
                        easing: 'ease-out',
                        fill: 'forwards'
                    }).finished);
                    return;
                }
                corrections.push(preparedItem.cloneElement.animate([
                    {
                        transform: 'translate3d(' + preparedItem.targetRect.left + 'px, ' + preparedItem.targetRect.top + 'px, 0)',
                        width: preparedItem.targetRect.width + 'px',
                        height: preparedItem.targetRect.height + 'px'
                    },
                    {
                        transform: 'translate3d(' + targetRect.left + 'px, ' + targetRect.top + 'px, 0)',
                        width: targetRect.width + 'px',
                        height: targetRect.height + 'px'
                    }
                ], {
                    duration: 120,
                    easing: 'ease-out',
                    fill: 'forwards'
                }).finished);
            });

            return Promise.all(corrections);
        }

        function cleanupArticleTransitionItems(preparedItems) {
            if (!preparedItems) {
                return;
            }
            if (preparedItems.backdropItem && preparedItems.backdropItem.element) {
                preparedItems.backdropItem.element.remove();
                preparedItems.backdropItem = null;
            }
            preparedItems.forEach(function (preparedItem) {
                if (preparedItem.targetElement) {
                    preparedItem.targetElement.classList.remove('pjax-article-transition-target-hidden');
                }
                if (preparedItem.cloneElement) {
                    preparedItem.cloneElement.remove();
                }
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

        async function loadByPjax(url, pushState, transitionSlug, navigationOptions) {
            const container = document.getElementById('pjax-container');
            if (!container) {
                fallbackToFullPage(url);
                return;
            }

            const currentUrl = window.location.href;
            const sourceScrollPosition = getCurrentScrollPosition();
            if (pushState) {
                displayedUrl = currentUrl;
                displayedHistoryState = { ...window.history.state, windblogScroll: sourceScrollPosition };
            }
            const restoreScrollPosition = pushState
                ? null
                : getHistoryScrollPosition(window.history.state);
            const enteringArticleFromList = isArticleDetailUrl(url) &&
                !isArticleDetailUrl(currentUrl);
            const articleListReturnUrl = enteringArticleFromList
                ? rememberArticleListUrl(currentUrl)
                : '';
            const instantScrollForArticleNavigation = isArticleDetailUrl(url) &&
                !isArticleDetailUrl(currentUrl);
            const pjaxUpdateOptions = {
                scrollToTop: true,
                instantScroll: instantScrollForArticleNavigation ||
                    Boolean(navigationOptions && navigationOptions.instantScroll),
                sourceScrollPosition: sourceScrollPosition,
                restoreScrollPosition: restoreScrollPosition,
                articleListReturnUrl: articleListReturnUrl,
                transitionSlug: transitionSlug || ''
            };

            if (activePjaxController) {
                activePjaxController.abort();
            }
            cleanupArticleTransitionItems(activeTransitionItems);
            activeTransitionItems = [];
            container.getAnimations?.().forEach(animation => animation.cancel());
            container.style.opacity = '';
            const requestController = new AbortController();
            activePjaxController = requestController;
            document.dispatchEvent(new CustomEvent('pjax:start', { detail: { url: url } }));
            const previousHtml = container.innerHTML;
            const previousTitle = document.title;
            const previousHead = Array.from(document.head.querySelectorAll('meta, link[rel="canonical"], script[type="application/ld+json"]')).map(node => node.cloneNode(true));
            let committed = false;
            try {
                const response = await fetch(url, {
                    method: 'GET',
                    // PJAX responses are fragments, never a reusable document.
                    // Keep the browser cache from retaining one for this URL.
                    cache: 'no-store',
                    signal: requestController.signal,
                    headers: {
                        'X-PJAX': 'true',
                        'X-PJAX-Container': '#pjax-container'
                    }
                });

                if (!response.ok) {
                    throw new Error('PJAX request failed: ' + response.status);
                }
                if (!response.headers.get('content-type')?.includes('text/html')) {
                    throw new Error('PJAX response is not HTML');
                }
                if (new URL(response.url).origin !== window.location.origin) {
                    throw new Error('PJAX redirect leaves this site');
                }
                const resolvedUrl = new URL(response.url);
                resolvedUrl.hash = new URL(url, window.location.href).hash;
                url = resolvedUrl.href;

                const html = await response.text();
                if (activePjaxController !== requestController || requestController.signal.aborted) return;
                const hasArticleTransitionSourceOnCurrentPage = Boolean(
                    transitionSlug && hasArticleTransitionSource(document, transitionSlug)
                );
                const capturedItems = hasArticleTransitionSourceOnCurrentPage
                    ? captureArticleTransitionItems(transitionSlug)
                    : [];
                const extractedHtml = extractPjaxHtml(html, transitionSlug, capturedItems);

                try {
                    analyticsBeginNavigation();
                    updatePjaxContainerState(container, extractedHtml, url, pushState, pjaxUpdateOptions);
                    committed = true;
                    displayedUrl = window.location.href;
                    displayedHistoryState = window.history.state;
                } catch (error) {
                    analyticsCancelNavigation();
                    container.innerHTML = previousHtml;
                    document.title = previousTitle;
                    document.head.querySelectorAll('meta, link[rel="canonical"], script[type="application/ld+json"]').forEach(node => node.remove());
                    previousHead.forEach(node => document.head.appendChild(node));
                    document.dispatchEvent(new CustomEvent('page:ready', { detail: { url: displayedUrl } }));
                    throw error;
                }
                // Animation failures must never invalidate successful navigation.
                let navigationFinalized = false;
                const finalizeNavigation = function () {
                    if (navigationFinalized) {
                        return;
                    }
                    navigationFinalized = true;
                    finalizePjaxLoad(url);
                };
                const isCurrentNavigation = function () {
                    return activePjaxController === requestController && !requestController.signal.aborted;
                };
                try {
                    if (hasArticleTransitionSourceOnCurrentPage) {
                        const preparedItems = prepareArticleTransitionTargets(transitionSlug, capturedItems);
                        activeTransitionItems = preparedItems;
                        if (preparedItems.length > 0) {
                            logArticleTransitionMode(url, transitionSlug, true, '');
                            const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
                            if (!reducedMotion) {
                                // Keep the destination container transparent while its
                                // geometry settles for 1 frame so the user only sees the
                                // parked clones and card backdrop until the synchronized
                                // morph and fade-in start together.
                                container.style.opacity = '0';
                            }
                            // Page-level init (ad slots, comments, ...) may insert
                            // content above the title. Complete it before pinning
                            // the destination so the morph ends where the title
                            // actually lands.
                            finalizeNavigation();
                            if (reducedMotion) {
                                container.style.opacity = '';
                                cleanupArticleTransitionItems(preparedItems);
                            } else {
                                await settleArticleTransitionTargets(preparedItems, container);
                                if (!isCurrentNavigation()) {
                                    container.style.opacity = '';
                                    cleanupArticleTransitionItems(preparedItems);
                                    return;
                                }
                                const measuredItems = measureArticleTransitionTargets(preparedItems, container);
                                activeTransitionItems = measuredItems;
                                container.style.opacity = '';
                                if (container.animate) {
                                    container.animate([
                                        { opacity: 0, transform: 'translate3d(0, 8px, 0)' },
                                        { opacity: 0.28, transform: 'translate3d(0, 5px, 0)', offset: 0.26 },
                                        { opacity: 1, transform: 'translate3d(0, 0, 0)' }
                                    ], {
                                        duration: 380,
                                        easing: 'cubic-bezier(0.22, 1, 0.36, 1)'
                                    });
                                }
                                await animateArticleTransitionItems(measuredItems, container);
                            }
                        } else {
                            logArticleTransitionMode(url, transitionSlug, false, 'target page does not expose a matching article transition target');
                        }
                    } else {
                        logArticleTransitionMode(url, transitionSlug, false, 'current page does not expose a matching article transition source');
                        if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches && container.animate) {
                            await container.animate([{ opacity: 0.65 }, { opacity: 1 }], { duration: 180, easing: 'ease-out' }).finished;
                        }
                    }
                } catch (animationError) {
                    container.style.opacity = '';
                    if (isCurrentNavigation()) cleanupArticleTransitionItems(activeTransitionItems);
                }
                if (isCurrentNavigation()) finalizeNavigation();
            } catch (error) {
                container.style.opacity = '';
                if (activePjaxController !== requestController || requestController.signal.aborted) {
                    return;
                }
                document.dispatchEvent(new CustomEvent('pjax:end', { detail: { url: url, error: error } }));
                if (!committed) {
                    fallbackToFullPage(url);
                }
                console.warn('[PJAX] navigation failed', error);
            } finally {
                if (activePjaxController === requestController) {
                    activePjaxController = null;
                }
            }
        }

        function extractPjaxHtml(html, transitionSlug, capturedItems) {
            if (!html) {
                throw new Error('Empty PJAX response');
            }

            const parser = new DOMParser();
            const documentSnapshot = parser.parseFromString(html, 'text/html');

            if (transitionSlug && Array.isArray(capturedItems) && capturedItems.length > 0) {
                capturedItems.forEach(function (capturedItem) {
                    const targetNode = documentSnapshot.querySelector(
                        getArticleTransitionSelector(transitionSlug, capturedItem.partName)
                    );
                    if (targetNode) {
                        targetNode.classList.add('pjax-article-transition-target-hidden');
                    }
                });
            }

            const contentRoot = documentSnapshot.querySelector('#pjax-content-root');
            if (contentRoot) {
                return contentRoot.outerHTML;
            }

            const pjaxContainer = documentSnapshot.querySelector('#pjax-container');
            if (pjaxContainer) {
                return pjaxContainer.innerHTML;
            }

            throw new Error('PJAX response has no content container');
        }

        function formatTimestamps(root = document) {
            const elements = root.querySelectorAll('.timestamp:not(.formatted)');
            const candidates = root.matches?.('.timestamp:not(.formatted)')
                ? [root, ...elements]
                : Array.from(elements);
            candidates.forEach(el => {
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

                    const formatted = new Intl.DateTimeFormat(document.documentElement.lang || 'zh-CN', {
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
            const title = anchor.dataset.linkName || anchor.textContent || window.wbT('js.article_link', '文章外链');
            const url = anchor.dataset.linkUrl || anchor.getAttribute('href') || '';
            const description = anchor.dataset.linkDescription || '';
            const icon = anchor.dataset.linkIcon || '';

            const iconNode = card.querySelector('.article-link-preview-icon');
            const titleNode = card.querySelector('.article-link-preview-title');
            const urlNode = card.querySelector('.article-link-preview-url');
            const descriptionNode = card.querySelector('.article-link-preview-description');

            titleNode.textContent = title;
            urlNode.textContent = url;
            descriptionNode.textContent = description || window.wbT('js.link_preview_default', '已接入站内文章外链管理。');

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

        let articleLinkPreviewScrollQueued = false;
        document.addEventListener('scroll', () => {
            if (!articleLinkPreviewCurrentAnchor || articleLinkPreviewScrollQueued) {
                return;
            }
            articleLinkPreviewScrollQueued = true;
            window.requestAnimationFrame(() => {
                articleLinkPreviewScrollQueued = false;
                if (articleLinkPreviewCurrentAnchor) {
                    showArticleLinkPreview(articleLinkPreviewCurrentAnchor);
                }
            });
        }, true);

        window.addEventListener('resize', () => {
            if (!articleLinkPreviewCurrentAnchor) {
                return;
            }
            showArticleLinkPreview(articleLinkPreviewCurrentAnchor);
        });

        // MutationObserver to handle dynamic content. Only the inserted
        // subtrees are rescanned, and all insertions in one frame are batched,
        // instead of re-querying the whole document per mutation.
        let mutationRefreshQueued = false;
        const pendingMutationRoots = new Set();
        const observer = new MutationObserver((mutations) => {
            mutations.forEach(mutation => {
                for (const node of mutation.addedNodes) {
                    if (node.nodeType === Node.ELEMENT_NODE && !node.classList?.contains('code-block-wrapper')) {
                        pendingMutationRoots.add(node);
                    }
                }
            });
            if (pendingMutationRoots.size === 0 || mutationRefreshQueued) {
                return;
            }
            mutationRefreshQueued = true;
            window.requestAnimationFrame(() => {
                mutationRefreshQueued = false;
                const roots = Array.from(pendingMutationRoots);
                pendingMutationRoots.clear();
                roots.forEach(root => {
                    if (!root.isConnected) {
                        return;
                    }
                    formatTimestamps(root);
                    decorateCodeBlocks(root);
                });
            });
        });
        observer.observe(document.body, { childList: true, subtree: true });

        if ('scrollRestoration' in window.history) {
            window.history.scrollRestoration = 'manual';
        }

        if (!window.history.state) {
            window.history.replaceState({
                pjax: false,
                url: window.location.href,
                windblogScroll: getCurrentScrollPosition()
            }, '', window.location.href);
        } else if (!window.history.state.windblogScroll) {
            replaceCurrentHistoryState({ windblogScroll: getCurrentScrollPosition() });
        }
        displayedHistoryState = window.history.state;

        document.addEventListener('click', (event) => {
            if (event.defaultPrevented) return;
            if (event.button !== 0) return;
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

            const languageLink = event.target.closest?.('[data-language-switch]');
            if (languageLink) {
                event.preventDefault();
                event.stopPropagation();
                switchLanguage(languageLink.dataset.languageSwitch);
                return;
            }

            const anchor = event.target.closest('a');
            const isArticleReturnNavigation = anchor &&
                anchor.matches('[data-article-return-link]') &&
                isArticleDetailUrl(window.location.href);
            const currentHistoryState = window.history.state;
            if (isArticleReturnNavigation &&
                currentHistoryState &&
                currentHistoryState.articleListReturnViaHistory &&
                areSameInternalUrls(getArticleListReturnUrl(), currentHistoryState.articleListReturnUrl)) {
                event.preventDefault();
                window.history.back();
                return;
            }

            if (!canUsePjax(anchor)) return;

            const url = new URL(anchor.href, window.location.href);
            if (url.pathname === window.location.pathname && url.search === window.location.search && url.hash) return;

            event.preventDefault();
            if (url.href === window.location.href) return;
            loadByPjax(
                url.href,
                true,
                resolveArticleTransitionSlug(url.href, anchor),
                { instantScroll: isArticleReturnNavigation }
            );
        });

        window.addEventListener('popstate', () => {
            if (window.location.pathname === new URL(displayedUrl).pathname &&
                window.location.search === new URL(displayedUrl).search) {
                displayedUrl = window.location.href;
                displayedHistoryState = window.history.state;
                return;
            }
            loadByPjax(window.location.href, false, resolveArticleTransitionSlug(window.location.href, null));
        });

        document.addEventListener('page:ready', () => {
            formatTimestamps();
            decorateCodeBlocks();
            injectSidebar();
            decorateShortcutButtons();
            bindPaginationForms();
            bindPaginationBoundaryButtons();
            updateArticleReturnLink();
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
            if (typeof openGithubSearchModal === 'function' && openGithubSearchModal()) return true;
            const searchInput = Array.from(document.querySelectorAll('[data-shortcut-target="search"]'))
                .find(isVisible);
            if (!searchInput) return false;
            searchInput.focus({preventScroll: true});
            searchInput.select?.();
            return true;
        }

        function decorateCodeBlocks(root = document) {
            if (!root || !root.querySelectorAll) return;
            const matches = root.matches?.('.prose pre, .vditor-reset pre') ? [root] : [];
            const preElements = matches.concat(Array.from(root.querySelectorAll('.prose pre, .vditor-reset pre')));
            preElements.forEach((pre) => {
                if (pre.dataset.codeDecorated === 'true' || pre.closest('.code-block-wrapper')) {
                    return;
                }
                pre.dataset.codeDecorated = 'true';

                const code = pre.querySelector('code');
                let lang = '';
                if (code) {
                    const classList = Array.from(code.classList);
                    for (const cls of classList) {
                        const m = cls.match(/^(?:language|lang)-([a-zA-Z0-9_+#.-]+)$/i);
                        if (m) {
                            lang = m[1].toUpperCase();
                            break;
                        }
                    }
                }
                if (!lang && pre.dataset.lang) {
                    lang = pre.dataset.lang.toUpperCase();
                }
                if (!lang) {
                    lang = 'CODE';
                }

                const wrapper = document.createElement('div');
                wrapper.className = 'code-block-wrapper';

                const header = document.createElement('div');
                header.className = 'code-block-header';

                const left = document.createElement('div');
                left.className = 'code-block-left';
                left.innerHTML = `
                    <div class="code-block-dots" aria-hidden="true">
                        <span class="code-dot code-dot-red"></span>
                        <span class="code-dot code-dot-yellow"></span>
                        <span class="code-dot code-dot-green"></span>
                    </div>
                    <span class="code-block-lang">${lang}</span>
                `;

                const copyBtn = document.createElement('button');
                copyBtn.type = 'button';
                copyBtn.className = 'code-block-copy-btn';
                copyBtn.setAttribute('aria-label', window.wbT('js.copy_code', '复制代码'));
                copyBtn.setAttribute('title', window.wbT('js.copy_code', '复制代码'));
                copyBtn.innerHTML = `
                    <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                        <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                        <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                    </svg>
                    <span class="code-copy-text">${window.wbT('js.copy', '复制')}</span>
                `;

                let copyTimer = null;
                copyBtn.addEventListener('click', async (e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    const text = (code ? code.innerText : pre.innerText).replace(/\n$/, '');
                    let success = false;
                    try {
                        if (navigator.clipboard && window.isSecureContext) {
                            await navigator.clipboard.writeText(text);
                            success = true;
                        } else {
                            const textarea = document.createElement('textarea');
                            textarea.value = text;
                            textarea.style.position = 'fixed';
                            textarea.style.left = '-9999px';
                            textarea.style.top = '0';
                            document.body.appendChild(textarea);
                            textarea.focus();
                            textarea.select();
                            success = document.execCommand('copy');
                            document.body.removeChild(textarea);
                        }
                    } catch (err) {
                        console.error('Failed to copy code: ', err);
                    }

                    if (success) {
                        copyBtn.classList.add('is-copied');
                        copyBtn.innerHTML = `
                            <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <polyline points="20 6 9 17 4 12"></polyline>
                            </svg>
                            <span class="code-copy-text">${window.wbT('js.copied', '已复制')}</span>
                        `;
                        clearTimeout(copyTimer);
                        copyTimer = setTimeout(() => {
                            copyBtn.classList.remove('is-copied');
                            copyBtn.innerHTML = `
                                <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                                </svg>
                                <span class="code-copy-text">${window.wbT('js.copy', '复制')}</span>
                            `;
                        }, 2000);
                    }
                });

                header.appendChild(left);
                header.appendChild(copyBtn);

                if (pre.parentNode) {
                    pre.parentNode.insertBefore(wrapper, pre);
                    wrapper.appendChild(header);

                    const bodyWrapper = document.createElement('div');
                    bodyWrapper.className = 'code-block-body';
                    wrapper.appendChild(bodyWrapper);
                    bodyWrapper.appendChild(pre);

                    const isTall = pre.scrollHeight > 360 || (code && code.innerText.split('\n').length > 22);
                    if (isTall) {
                        wrapper.classList.add('is-collapsible', 'is-collapsed');
                        const overlay = document.createElement('div');
                        overlay.className = 'code-block-expand-overlay';
                        const expandBtn = document.createElement('button');
                        expandBtn.type = 'button';
                        expandBtn.className = 'code-block-expand-btn';
                        expandBtn.setAttribute('aria-expanded', 'false');
                        expandBtn.innerHTML = `
                            <span class="code-expand-text">${window.wbT('js.expand_code', '展开代码')}</span>
                            <svg class="code-block-expand-icon" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
                                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M19 9l-7 7-7-7"/>
                            </svg>
                        `;
                        expandBtn.addEventListener('click', (e) => {
                            e.preventDefault();
                            e.stopPropagation();
                            const isCollapsed = wrapper.classList.contains('is-collapsed');
                            if (isCollapsed) {
                                wrapper.classList.remove('is-collapsed');
                                wrapper.classList.add('is-expanded');
                                expandBtn.setAttribute('aria-expanded', 'true');
                                expandBtn.querySelector('.code-expand-text').textContent = window.wbT('js.collapse_code', '折叠代码');
                            } else {
                                wrapper.classList.remove('is-expanded');
                                wrapper.classList.add('is-collapsed');
                                expandBtn.setAttribute('aria-expanded', 'false');
                                expandBtn.querySelector('.code-expand-text').textContent = window.wbT('js.expand_code', '展开代码');
                                const rect = wrapper.getBoundingClientRect();
                                if (rect.top < 80) {
                                    wrapper.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                                }
                            }
                        });
                        overlay.appendChild(expandBtn);
                        wrapper.appendChild(overlay);
                    }
                }
            });
        }

        function decorateShortcutButtons() {
            document.querySelectorAll('form:not([data-shortcut-form="search"]):not([data-pagination-form]) button[type="submit"]')
                .forEach((button) => {
                    button.dataset.shortcut = button.dataset.shortcut || 'enter';
                    button.setAttribute('aria-keyshortcuts', SHORTCUTS.submit);
                    if (button.querySelector('[data-shortcut-hint]')) return;
                                        const hint = document.createElement('span');
                    hint.dataset.shortcutHint = 'enter';
                    hint.className = 'ml-1 text-xs opacity-70';
                    hint.textContent = window.wbT('js.enter_hint', '（回车）');
                    button.appendChild(hint);
                });
        }

        function bindPaginationForms(root = document) {
            root.querySelectorAll?.('form[data-pagination-form]').forEach(form => {
                if (form.dataset.paginationBound === 'true') return;
                form.dataset.paginationBound = 'true';
                form.addEventListener('submit', event => {
                    event.preventDefault();
                    event.stopPropagation();

                    const input = form.querySelector('input[name="page"]');
                    const goButton = form.querySelector('.windblog-pagination-go');
                    const pageLinks = [...form.querySelectorAll('a[data-page]')];
                    const maxVisiblePage = Math.max(...pageLinks.map(link => Number(link.dataset.page) || 1), 1);
                    const totalPages = Number(input?.max) || maxVisiblePage;
                    const requestedPage = Number.parseInt(input?.value || '', 10);
                    const invalidPage = !Number.isInteger(requestedPage) || requestedPage < 1 || requestedPage > totalPages;
                    if (invalidPage) {
                        goButton?.classList.remove('is-invalid');
                        void goButton?.offsetWidth;
                        goButton?.classList.add('is-invalid');
                        input?.setAttribute('aria-invalid', 'true');
                        clearTimeout(form.paginationInvalidTimer);
                        form.paginationInvalidTimer = setTimeout(() => {
                            goButton?.classList.remove('is-invalid');
                            input?.removeAttribute('aria-invalid');
                        }, 520);
                        return;
                    }
                    input.value = String(requestedPage);

                    const target = pageLinks.find(link => Number(link.dataset.page) === requestedPage);
                    const targetHref = target?.href || buildPaginationPageUrl(form, requestedPage, totalPages);
                    if (!targetHref || new URL(targetHref, window.location.href).href === window.location.href) return;
                    startPaginationGoAnimation(form.querySelector('.windblog-pagination-go'));
                    if (target) {
                        target.click();
                    } else {
                        loadByPjax(targetHref, true, '', {});
                    }
                });
            });
        }

        function buildPaginationPageUrl(form, requestedPage, totalPages) {
            const pagination = form.closest('.windblog-pagination');
            const firstPageLink = pagination?.querySelector('[data-pagination-nav="first"]');
            if (requestedPage === 1 && firstPageLink) return firstPageLink.href;

            const lastPageLink = pagination?.querySelector('[data-pagination-nav="last"]');
            if (!lastPageLink) return '';

            const url = new URL(lastPageLink.href, window.location.href);
            if (url.searchParams.has('page')) {
                if (requestedPage === 1) url.searchParams.delete('page');
                else url.searchParams.set('page', String(requestedPage));
                return url.href;
            }

            const lastPageSuffix = `/page/${totalPages}`;
            if (!url.pathname.endsWith(lastPageSuffix)) return '';
            url.pathname = `${url.pathname.slice(0, -lastPageSuffix.length)}/page/${requestedPage}`;
            return url.href;
        }

        let activePaginationGoAnimation = null;

        function startPaginationGoAnimation(button) {
            if (!button) return;
            const rect = button.getBoundingClientRect();
            const flight = document.createElement('div');
            flight.className = 'windblog-pagination-flight';
            flight.setAttribute('aria-hidden', 'true');
            flight.style.setProperty('--pagination-flight-left', `${rect.left}px`);
            flight.style.setProperty('--pagination-flight-top', `${rect.top}px`);
            flight.style.setProperty('--pagination-flight-width', `${rect.width}px`);
            flight.style.setProperty('--pagination-flight-height', `${rect.height}px`);

            const circle = document.createElement('span');
            circle.className = 'windblog-pagination-flight-circle';
            const rocket = document.createElement('span');
            rocket.className = 'windblog-pagination-flight-rocket';
            rocket.textContent = '🚀';
            flight.append(circle, rocket);
            document.body.appendChild(flight);
            button.classList.add('is-loading');
            activePaginationGoAnimation = {button, flight};
        }

        function cleanupPaginationGoAnimation() {
            if (!activePaginationGoAnimation) return;
            activePaginationGoAnimation.button?.classList.remove('is-loading');
            activePaginationGoAnimation.flight?.remove();
            activePaginationGoAnimation = null;
        }

        function completePaginationGoAnimation() {
            const animation = activePaginationGoAnimation;
            if (!animation) return;
            window.scrollTo({top: 0, behavior: 'smooth'});
            animation.flight.classList.add('is-flying');
            window.setTimeout(() => {
                if (activePaginationGoAnimation?.flight === animation.flight) {
                    cleanupPaginationGoAnimation();
                }
            }, 1150);
        }

        document.addEventListener('pjax:complete', completePaginationGoAnimation);
        document.addEventListener('pjax:end', event => {
            if (event.detail?.error) cleanupPaginationGoAnimation();
        });

        function bindPaginationBoundaryButtons(root = document) {
            root.querySelectorAll?.('button[data-pagination-boundary]').forEach(button => {
                if (button.dataset.paginationBoundaryBound === 'true') return;
                button.dataset.paginationBoundaryBound = 'true';
                button.addEventListener('click', () => {
                    button.classList.remove('is-denied');
                    void button.offsetWidth;
                    button.classList.add('is-denied');
                    clearTimeout(button.paginationDeniedTimer);
                    button.paginationDeniedTimer = setTimeout(() => {
                        button.classList.remove('is-denied');
                    }, 520);
                });
            });
        }

        // Application-scoped shortcuts. Do not react while this document is
        // hidden or has lost focus, and never compete with multiline editors.
        document.addEventListener('keydown', (event) => {
            if (!document.hasFocus() || document.visibilityState === 'hidden' || event.isComposing) return;

            // Shortcut '/' or 'Ctrl+K' / 'Cmd+K' opens GitHub-style Spotlight Search Palette
            if (
                (event.key === '/' && !event.ctrlKey && !event.metaKey && !event.altKey && !isFormControlTarget(event.target)) ||
                (event.key.toLowerCase() === 'k' && (event.ctrlKey || event.metaKey) && !event.altKey && !event.shiftKey && !isFormControlTarget(event.target))
            ) {
                event.preventDefault();
                if (typeof openGithubSearchModal === 'function') {
                    openGithubSearchModal();
                } else {
                    focusSearchInput();
                }
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

                searchInput.addEventListener('click', (e) => {
                    e.preventDefault();
                    openGithubSearchModal(searchInput.value);
                });
                form.addEventListener('submit', (e) => {
                    e.preventDefault();
                    openGithubSearchModal(searchInput.value);
                });

                const suggestWrap = document.createElement('div');
                const suggestId = `search-suggestions-${++searchSuggestionCounter}`;
                suggestWrap.id = suggestId;
                suggestWrap.setAttribute('role', 'listbox');
                suggestWrap.setAttribute('aria-label', window.wbT('js.search_suggestions', '搜索建议'));
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

        // ==========================================
        // GitHub-Style Command Palette & Spotlight Search
        // ==========================================
        function escapeHtml(str) {
            if (!str) return '';
            return String(str)
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');
        }

        function escapeRegex(str) {
            if (!str) return '';
            return String(str).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
        }

        const githubSearchBackdrop = document.getElementById('githubSearchBackdrop');
        const githubSearchModal = document.getElementById('githubSearchModal');
        const githubSearchPalette = document.getElementById('githubSearchPalette');
        const githubSearchInput = document.getElementById('githubSearchInput');
        const githubSearchClearBtn = document.getElementById('githubSearchClearBtn');
        const githubSearchActiveFilters = document.getElementById('githubSearchActiveFilters');
        const githubSearchInPageToolbar = document.getElementById('githubSearchInPageToolbar');
        const githubSearchInPageCount = document.getElementById('githubSearchInPageCount');
        const githubSearchInPagePrev = document.getElementById('githubSearchInPagePrev');
        const githubSearchInPageNext = document.getElementById('githubSearchInPageNext');
        const githubSearchResultsList = document.getElementById('githubSearchResultsList');
        const chipInArticle = document.getElementById('chipInArticle');

        let currentSearchMode = 'all'; // 'all' | 'in-page' | 'in-article'
        let inPageMatches = [];
        let inPageCurrentIndex = -1;
        let searchSuggestTimer = null;
        let searchSuggestRequestId = 0;
        let paletteActiveItemIndex = 0;

        function isGithubSearchOpen() {
            return Boolean(githubSearchModal && githubSearchModal.classList.contains('is-open'));
        }

        function openGithubSearchModal(initialQuery = '') {
            if (!githubSearchModal || !githubSearchBackdrop) return false;

            const hasArticle = Boolean(document.querySelector('.prose, #post-content-section, article.post-article'));
            if (chipInArticle) {
                chipInArticle.classList.toggle('hidden', !hasArticle);
            }

            githubSearchBackdrop.classList.remove('hidden');
            githubSearchModal.classList.remove('hidden');
            void githubSearchModal.offsetWidth;
            githubSearchBackdrop.classList.add('is-open');
            githubSearchModal.classList.add('is-open');

            document.body.style.overflow = 'hidden';

            if (typeof initialQuery === 'string' && initialQuery.length > 0 && githubSearchInput) {
                githubSearchInput.value = initialQuery;
            }

            setSearchFilterMode('all');

            requestAnimationFrame(() => {
                if (githubSearchInput) {
                    githubSearchInput.focus();
                    if (githubSearchInput.value) {
                        githubSearchInput.select();
                    }
                }
                updateSearchPalette();
            });

            return true;
        }

        function closeGithubSearchModal() {
            if (!githubSearchModal || !githubSearchBackdrop) return;
            githubSearchBackdrop.classList.remove('is-open');
            githubSearchModal.classList.remove('is-open');
            setTimeout(() => {
                githubSearchBackdrop.classList.add('hidden');
                githubSearchModal.classList.add('hidden');
            }, 200);

            document.body.style.overflow = '';
            if (currentSearchMode !== 'in-page') {
                clearInPageHighlights();
            }
        }

        function updateClearButtonVisibility() {
            if (!githubSearchClearBtn || !githubSearchInput) return;
            githubSearchClearBtn.classList.toggle('hidden', !githubSearchInput.value);
        }

        function setSearchFilterMode(mode) {
            currentSearchMode = mode;
            const chips = document.querySelectorAll('.search-filter-chip[data-filter-mode]');
            chips.forEach(chip => {
                chip.classList.toggle('active', chip.dataset.filterMode === mode);
            });
            if (mode !== 'in-page') {
                clearInPageHighlights();
            }
            updateSearchPalette();
        }

        function parseSearchSyntax(raw) {
            const result = {
                title: null,
                author: null,
                content: null,
                user: null,
                freeText: ''
            };
            if (!raw) return result;

            let text = raw;
            const matchToken = (prefix) => {
                const re = new RegExp(`\\b${prefix}:(?:"([^"]*)"|'([^']*)'|([^\\s]+))`, 'i');
                const m = text.match(re);
                if (m) {
                    const val = m[1] || m[2] || m[3] || '';
                    text = text.replace(m[0], ' ');
                    return val;
                }
                return null;
            };

            result.title = matchToken('title');
            result.author = matchToken('author');
            result.content = matchToken('content');
            result.user = matchToken('user');
            result.freeText = text.replace(/\s+/g, ' ').trim();
            return result;
        }

        function renderActiveFiltersBar(parsed) {
            if (!githubSearchActiveFilters) return;
            const tags = [];
            if (parsed.title) tags.push({ key: 'title', label: '标题', val: parsed.title, prefix: 'title:' });
            if (parsed.author) tags.push({ key: 'author', label: '作者', val: parsed.author, prefix: 'author:' });
            if (parsed.content) tags.push({ key: 'content', label: '正文', val: parsed.content, prefix: 'content:' });
            if (parsed.user) tags.push({ key: 'user', label: '用户', val: parsed.user, prefix: 'user:' });

            if (tags.length === 0) {
                githubSearchActiveFilters.classList.add('hidden');
                githubSearchActiveFilters.replaceChildren();
                return;
            }

            githubSearchActiveFilters.classList.remove('hidden');
            githubSearchActiveFilters.replaceChildren();

            const titleSpan = document.createElement('span');
            titleSpan.className = 'text-meta text-[11px] self-center mr-1 select-none';
            titleSpan.textContent = '过滤条件:';
            githubSearchActiveFilters.appendChild(titleSpan);

            tags.forEach(tag => {
                const badge = document.createElement('span');
                badge.className = 'inline-flex items-center gap-1 px-2 py-0.5 rounded bg-accent/10 text-accent border border-accent/30 text-xs';
                badge.innerHTML = `<span>${tag.label}: <strong class="font-semibold">${escapeHtml(tag.val)}</strong></span>`;
                const removeBtn = document.createElement('button');
                removeBtn.type = 'button';
                removeBtn.className = 'hover:text-red-400 p-0.5 rounded transition-colors ml-0.5 font-bold';
                removeBtn.innerHTML = '×';
                removeBtn.title = `移除 ${tag.label} 筛选`;
                removeBtn.addEventListener('click', (e) => {
                    e.stopPropagation();
                    removeFilterFromInput(tag.prefix);
                });
                badge.appendChild(removeBtn);
                githubSearchActiveFilters.appendChild(badge);
            });
        }

        function removeFilterFromInput(prefix) {
            if (!githubSearchInput) return;
            const re = new RegExp(`\\b${prefix}(?:"[^"]*"|'[^']*'|[^\\s]+)\\s*`, 'i');
            githubSearchInput.value = githubSearchInput.value.replace(re, '').trim();
            updateSearchPalette();
            githubSearchInput.focus();
        }

        function appendSyntaxToInput(syntax) {
            if (!githubSearchInput) return;
            let current = githubSearchInput.value.trim();
            if (current && !current.endsWith(' ')) {
                current += ' ';
            }
            githubSearchInput.value = current + syntax;
            githubSearchInput.focus();
            const len = githubSearchInput.value.length;
            githubSearchInput.setSelectionRange(len, len);
            updateSearchPalette();
        }

        function clearInPageHighlights() {
            const marks = document.querySelectorAll('.github-search-inpage-highlight');
            marks.forEach(m => {
                const parent = m.parentNode;
                if (parent) {
                    parent.replaceChild(document.createTextNode(m.textContent), m);
                    parent.normalize();
                }
            });
            inPageMatches = [];
            inPageCurrentIndex = -1;
            if (githubSearchInPageToolbar) githubSearchInPageToolbar.classList.add('hidden');
        }

        function performInPageSearch(keyword) {
            clearInPageHighlights();
            const term = (keyword || '').trim();
            if (!term) return;

            const root = document.getElementById('pjax-container') || document.querySelector('main') || document.body;
            const walker = document.createTreeWalker(
                root,
                NodeFilter.SHOW_TEXT,
                {
                    acceptNode(node) {
                        if (!node.nodeValue.trim()) return NodeFilter.FILTER_REJECT;
                        const parent = node.parentElement;
                        if (!parent) return NodeFilter.FILTER_REJECT;
                        if (parent.closest('#githubSearchModal, #githubSearchBackdrop, script, style, noscript, svg, button')) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                }
            );

            const textNodes = [];
            let n;
            while ((n = walker.nextNode())) {
                textNodes.push(n);
            }

            const lower = term.toLowerCase();
            textNodes.forEach(node => {
                const text = node.nodeValue;
                const lowerText = text.toLowerCase();
                let idx = lowerText.indexOf(lower);
                if (idx === -1) return;

                const frag = document.createDocumentFragment();
                let last = 0;
                while (idx !== -1) {
                    if (idx > last) {
                        frag.appendChild(document.createTextNode(text.substring(last, idx)));
                    }
                    const mark = document.createElement('mark');
                    mark.className = 'github-search-inpage-highlight';
                    mark.textContent = text.substring(idx, idx + term.length);
                    frag.appendChild(mark);
                    inPageMatches.push(mark);

                    last = idx + term.length;
                    idx = lowerText.indexOf(lower, last);
                }
                if (last < text.length) {
                    frag.appendChild(document.createTextNode(text.substring(last)));
                }
                node.parentNode.replaceChild(frag, node);
            });

            if (githubSearchInPageToolbar) {
                githubSearchInPageToolbar.classList.toggle('hidden', inPageMatches.length === 0);
            }
            if (githubSearchInPageCount) {
                githubSearchInPageCount.textContent = `${inPageMatches.length} 处匹配`;
            }
            if (inPageMatches.length > 0) {
                goToInPageMatch(0);
            }
        }

        function goToInPageMatch(index) {
            if (inPageMatches.length === 0) return;
            inPageMatches.forEach(m => m.classList.remove('is-current'));
            inPageCurrentIndex = (index + inPageMatches.length) % inPageMatches.length;
            const mark = inPageMatches[inPageCurrentIndex];
            if (mark) {
                mark.classList.add('is-current');
                mark.scrollIntoView({ behavior: 'smooth', block: 'center' });
                if (githubSearchInPageCount) {
                    githubSearchInPageCount.textContent = `${inPageCurrentIndex + 1} / ${inPageMatches.length} 处匹配`;
                }
            }
        }

        function findArticleMatches(term) {
            const article = document.querySelector('.prose') || document.querySelector('#post-content-section');
            if (!article || !term) return [];

            const elements = Array.from(article.querySelectorAll('h1, h2, h3, h4, h5, h6, p, li, blockquote'));
            const lowerTerm = term.toLowerCase();
            const results = [];

            for (const el of elements) {
                const text = el.textContent.trim();
                if (!text) continue;
                const idx = text.toLowerCase().indexOf(lowerTerm);
                if (idx !== -1) {
                    const isHeading = /^H[1-6]$/i.test(el.tagName);
                    const start = Math.max(0, idx - 30);
                    const end = Math.min(text.length, idx + term.length + 50);
                    let snippet = text.substring(start, end);
                    if (start > 0) snippet = '...' + snippet;
                    if (end < text.length) snippet = snippet + '...';

                    results.push({
                        element: el,
                        isHeading: isHeading,
                        tag: el.tagName.toUpperCase(),
                        text: text,
                        snippet: snippet
                    });
                    if (results.length >= 8) break;
                }
            }
            return results;
        }

        function renderResultsList(query, parsed, suggestions = []) {
            if (!githubSearchResultsList) return;
            githubSearchResultsList.replaceChildren();
            paletteActiveItemIndex = 0;

            const rawQuery = (query || '').trim();
            const freeText = parsed.freeText;
            const searchTerm = freeText || rawQuery;

            if (currentSearchMode === 'in-page') {
                performInPageSearch(searchTerm);
                const infoItem = document.createElement('div');
                infoItem.className = 'github-search-item is-selected';
                infoItem.innerHTML = `
                    <div class="flex items-center gap-2.5">
                        <span class="text-accent">📄</span>
                        <span class="font-medium text-main">页面内匹配 (${inPageMatches.length} 处)</span>
                    </div>
                    <span class="text-xs text-meta">按 Enter 键跳转下一处</span>
                `;
                githubSearchResultsList.appendChild(infoItem);
                return;
            }

            if (currentSearchMode === 'in-article') {
                const matches = findArticleMatches(searchTerm);
                if (matches.length === 0) {
                    const emptyItem = document.createElement('div');
                    emptyItem.className = 'px-4 py-6 text-center text-meta text-xs';
                    emptyItem.textContent = searchTerm ? '当前文章内未找到匹配内容' : '输入关键词在文章段落与标题中检索';
                    githubSearchResultsList.appendChild(emptyItem);
                    return;
                }

                matches.forEach((m, idx) => {
                    const item = document.createElement('div');
                    item.className = `github-search-item ${idx === 0 ? 'is-selected' : ''}`;
                    item.dataset.articleResultIndex = String(idx);
                    const tagLabel = m.isHeading ? `<span class="px-1.5 py-0.5 rounded bg-accent/10 text-accent font-mono text-[10px]">${m.tag}</span>` : '<span class="text-meta text-[11px]">段落</span>';
                    const highlightedSnippet = escapeHtml(m.snippet).replace(
                        new RegExp(escapeRegex(searchTerm), 'gi'),
                        match => `<mark class="bg-accent/25 text-accent font-semibold px-0.5 rounded">${match}</mark>`
                    );
                    item.innerHTML = `
                        <div class="flex-1 min-w-0 pr-2">
                            <div class="flex items-center gap-1.5 mb-1">
                                ${tagLabel}
                                <span class="font-semibold text-main text-xs truncate">${escapeHtml(m.text.substring(0, 40))}</span>
                            </div>
                            <div class="text-xs text-body line-clamp-1 opacity-80">${highlightedSnippet}</div>
                        </div>
                        <span class="search-item-action-icon text-meta text-xs shrink-0 font-mono">跳转 →</span>
                    `;
                    item.addEventListener('click', () => {
                        jumpToArticleElement(m.element);
                    });
                    githubSearchResultsList.appendChild(item);
                });
                return;
            }

            // Mode 'all': Global & multi-target spotlight
            // Item 1: Site-wide search
            const globalSearchItem = document.createElement('div');
            globalSearchItem.className = 'github-search-item is-selected';
            globalSearchItem.dataset.actionType = 'site-search';
            globalSearchItem.innerHTML = `
                <div class="flex items-center gap-2.5 min-w-0">
                    <span class="text-accent text-base">🌐</span>
                    <div class="min-w-0">
                        <div class="font-semibold text-main text-xs sm:text-sm truncate">全站搜索: <span class="text-accent font-mono">${escapeHtml(rawQuery || '*')}</span></div>
                        <div class="text-[11px] text-meta">前往全站搜索页，支持全部结构化过滤条件与高亮检索</div>
                    </div>
                </div>
                <kbd class="hidden sm:inline-block px-1.5 py-0.5 rounded bg-card border border-border text-[10px] text-meta font-mono shrink-0">Enter ↵</kbd>
            `;
            globalSearchItem.addEventListener('click', () => executeSiteSearch(rawQuery));
            githubSearchResultsList.appendChild(globalSearchItem);

            // Item 2: Search in current page
            if (rawQuery) {
                const inPageItem = document.createElement('div');
                inPageItem.className = 'github-search-item';
                inPageItem.dataset.actionType = 'in-page';
                inPageItem.innerHTML = `
                    <div class="flex items-center gap-2.5 min-w-0">
                        <span class="text-accent text-base">📄</span>
                        <div class="min-w-0">
                            <div class="font-medium text-main text-xs sm:text-sm truncate">在当前页面查找: <span class="text-accent font-mono">${escapeHtml(searchTerm)}</span></div>
                            <div class="text-[11px] text-meta">在当前页面 DOM 中高亮匹配并快速跳转</div>
                        </div>
                    </div>
                    <span class="text-xs text-meta shrink-0">切换模式 →</span>
                `;
                inPageItem.addEventListener('click', () => {
                    setSearchFilterMode('in-page');
                });
                githubSearchResultsList.appendChild(inPageItem);
            }

            // Item 3: Search in current article if exists
            const hasArticle = Boolean(document.querySelector('.prose, #post-content-section'));
            if (hasArticle && rawQuery) {
                const inArticleItem = document.createElement('div');
                inArticleItem.className = 'github-search-item';
                inArticleItem.dataset.actionType = 'in-article';
                inArticleItem.innerHTML = `
                    <div class="flex items-center gap-2.5 min-w-0">
                        <span class="text-accent text-base">📖</span>
                        <div class="min-w-0">
                            <div class="font-medium text-main text-xs sm:text-sm truncate">在当前文章正文中查找: <span class="text-accent font-mono">${escapeHtml(searchTerm)}</span></div>
                            <div class="text-[11px] text-meta">检索文章章节标题、正文与代码段落</div>
                        </div>
                    </div>
                    <span class="text-xs text-meta shrink-0">文章检索 →</span>
                `;
                inArticleItem.addEventListener('click', () => {
                    setSearchFilterMode('in-article');
                });
                githubSearchResultsList.appendChild(inArticleItem);
            }

            // Suggestion items from /search/suggest
            if (suggestions && suggestions.length > 0) {
                const header = document.createElement('div');
                header.className = 'px-3 pt-2 pb-1 text-[11px] font-mono uppercase text-meta tracking-wider';
                header.textContent = '推荐建议';
                githubSearchResultsList.appendChild(header);

                suggestions.forEach(s => {
                    const sugItem = document.createElement('div');
                    sugItem.className = 'github-search-item';
                    sugItem.dataset.actionType = 'suggestion';
                    sugItem.dataset.suggestValue = String(s);
                    sugItem.innerHTML = `
                        <div class="flex items-center gap-2.5 min-w-0">
                            <span class="text-meta">💡</span>
                            <span class="text-xs sm:text-sm text-body hover:text-main truncate">${escapeHtml(String(s))}</span>
                        </div>
                        <kbd class="hidden sm:inline-block px-1.5 py-0.5 rounded bg-card border border-border text-[10px] text-meta font-mono shrink-0">Tab 补全</kbd>
                    `;
                    sugItem.addEventListener('click', () => {
                        githubSearchInput.value = String(s);
                        updateSearchPalette();
                        executeSiteSearch(String(s));
                    });
                    githubSearchResultsList.appendChild(sugItem);
                });
            }
        }

        function jumpToArticleElement(element) {
            if (!element) return;
            closeGithubSearchModal();
            element.scrollIntoView({ behavior: 'smooth', block: 'center' });
            element.classList.add('bg-accent/20', 'transition-all', 'duration-500', 'rounded');
            setTimeout(() => {
                element.classList.remove('bg-accent/20');
            }, 2200);
        }

        function executeSiteSearch(query) {
            const q = (query || '').trim();
            closeGithubSearchModal();
            const targetUrl = '/search' + (q ? '?q=' + encodeURIComponent(q) : '');
            if (typeof loadByPjax === 'function') {
                loadByPjax(targetUrl, true);
            } else {
                window.location.href = targetUrl;
            }
        }

        function updateSearchPalette() {
            updateClearButtonVisibility();
            const raw = githubSearchInput ? githubSearchInput.value : '';
            const parsed = parseSearchSyntax(raw);
            renderActiveFiltersBar(parsed);

            const q = (parsed.freeText || raw).trim();
            clearTimeout(searchSuggestTimer);

            if (currentSearchMode !== 'all' || q.length < 2) {
                renderResultsList(raw, parsed, []);
                return;
            }

            const requestId = ++searchSuggestRequestId;
            searchSuggestTimer = setTimeout(async () => {
                try {
                    const res = await fetch(`/search/suggest?q=${encodeURIComponent(q)}`);
                    if (!res.ok) throw new Error('Suggest failed');
                    const suggestions = await res.json();
                    if (requestId !== searchSuggestRequestId) return;
                    renderResultsList(raw, parsed, suggestions || []);
                } catch {
                    if (requestId === searchSuggestRequestId) {
                        renderResultsList(raw, parsed, []);
                    }
                }
            }, 250);
        }

        // Initialize Search Modal DOM Events
        if (githubSearchClearBtn) {
            githubSearchClearBtn.addEventListener('click', () => {
                if (githubSearchInput) {
                    githubSearchInput.value = '';
                    updateSearchPalette();
                    githubSearchInput.focus();
                }
            });
        }

        document.querySelectorAll('.search-filter-chip[data-filter-mode]').forEach(btn => {
            btn.addEventListener('click', () => {
                setSearchFilterMode(btn.dataset.filterMode);
            });
        });

        document.querySelectorAll('.search-filter-chip[data-syntax]').forEach(btn => {
            btn.addEventListener('click', () => {
                appendSyntaxToInput(btn.dataset.syntax);
            });
        });

        githubSearchBackdrop?.addEventListener('click', closeGithubSearchModal);
        githubSearchModal?.addEventListener('click', (e) => {
            if (e.target === githubSearchModal) {
                closeGithubSearchModal();
            }
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && isGithubSearchOpen()) {
                e.preventDefault();
                closeGithubSearchModal();
            }
        });

        githubSearchInput?.addEventListener('input', () => {
            updateSearchPalette();
        });

        githubSearchInPagePrev?.addEventListener('click', () => goToInPageMatch(inPageCurrentIndex - 1));
        githubSearchInPageNext?.addEventListener('click', () => goToInPageMatch(inPageCurrentIndex + 1));

        githubSearchInput?.addEventListener('keydown', (event) => {
            if (event.key === 'ArrowDown') {
                const items = Array.from(githubSearchResultsList.querySelectorAll('.github-search-item'));
                if (items.length > 0) {
                    event.preventDefault();
                    items.forEach(i => i.classList.remove('is-selected'));
                    paletteActiveItemIndex = (paletteActiveItemIndex + 1) % items.length;
                    items[paletteActiveItemIndex].classList.add('is-selected');
                    items[paletteActiveItemIndex].scrollIntoView({ block: 'nearest' });
                }
            } else if (event.key === 'ArrowUp') {
                const items = Array.from(githubSearchResultsList.querySelectorAll('.github-search-item'));
                if (items.length > 0) {
                    event.preventDefault();
                    items.forEach(i => i.classList.remove('is-selected'));
                    paletteActiveItemIndex = (paletteActiveItemIndex - 1 + items.length) % items.length;
                    items[paletteActiveItemIndex].classList.add('is-selected');
                    items[paletteActiveItemIndex].scrollIntoView({ block: 'nearest' });
                }
            } else if (event.key === 'Tab') {
                const items = Array.from(githubSearchResultsList.querySelectorAll('.github-search-item'));
                const selected = items[paletteActiveItemIndex];
                if (selected && selected.dataset.suggestValue) {
                    event.preventDefault();
                    githubSearchInput.value = selected.dataset.suggestValue;
                    updateSearchPalette();
                }
            } else if (event.key === 'Enter') {
                event.preventDefault();
                if (currentSearchMode === 'in-page') {
                    goToInPageMatch(event.shiftKey ? inPageCurrentIndex - 1 : inPageCurrentIndex + 1);
                    return;
                }
                const items = Array.from(githubSearchResultsList.querySelectorAll('.github-search-item'));
                const selected = items[paletteActiveItemIndex] || items[0];
                if (selected) {
                    selected.click();
                } else {
                    executeSiteSearch(githubSearchInput.value);
                }
            }
        });

        document.addEventListener('pjax:start', () => {
            closeGithubSearchModal();
            clearInPageHighlights();
        });

        // ==========================================
        // TOC & Mobile Floating Controls (Scrollspy, Mobile Drawer, Back to Top)
        // ==========================================
        let activeHeadingId = null;
        let tocHeadings = [];

        const mobileFloatingControls = document.getElementById('mobileFloatingControls');
        const mobileTocBtn = document.getElementById('mobileTocBtn');
        const mobileBackToTopBtn = document.getElementById('mobileBackToTopBtn');
        const mobileProgressCirclePath = document.getElementById('mobileScrollProgressPath');
        const mobileTocDrawer = document.getElementById('mobileTocDrawer');
        const mobileTocBackdrop = document.getElementById('mobileTocBackdrop');
        const mobileTocCloseBtn = document.getElementById('mobileTocCloseBtn');
        const mobileTocContent = document.getElementById('mobileTocContent');

        const isMobileTocDrawerOpen = () => {
            return !!(mobileTocDrawer && mobileTocDrawer.classList.contains('mobile-drawer-open'));
        };

        const openMobileTocDrawer = () => {
            if (!mobileTocDrawer) return;
            mobileTocDrawer.classList.add('mobile-drawer-open');
            mobileTocDrawer.setAttribute('aria-hidden', 'false');
            if (mobileTocBackdrop) {
                mobileTocBackdrop.classList.add('mobile-backdrop-open');
            }
            if (mobileTocBtn) {
                mobileTocBtn.setAttribute('aria-expanded', 'true');
            }
            document.body.style.overflow = 'hidden';
        };

        const closeMobileTocDrawer = (restoreFocus = false) => {
            if (!mobileTocDrawer) return;
            mobileTocDrawer.classList.remove('mobile-drawer-open');
            mobileTocDrawer.setAttribute('aria-hidden', 'true');
            if (mobileTocBackdrop) {
                mobileTocBackdrop.classList.remove('mobile-backdrop-open');
            }
            if (mobileTocBtn) {
                mobileTocBtn.setAttribute('aria-expanded', 'false');
                if (restoreFocus) mobileTocBtn.focus();
            }
            document.body.style.overflow = '';
        };

        mobileTocBtn?.addEventListener('click', openMobileTocDrawer);
        mobileTocCloseBtn?.addEventListener('click', () => closeMobileTocDrawer(true));
        mobileTocBackdrop?.addEventListener('click', () => closeMobileTocDrawer(false));

        mobileBackToTopBtn?.addEventListener('click', () => {
            window.scrollTo({ top: 0, behavior: 'smooth' });
        });

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape' && isMobileTocDrawerOpen()) {
                closeMobileTocDrawer(true);
            }
        });

        function initToc() {
            const content = document.querySelector('.prose') || document.querySelector('#post-content-section');
            const desktopTocWidget = document.getElementById('toc-widget');
            const desktopTocContent = document.getElementById('toc-content');

            if (!content) {
                if (desktopTocWidget) desktopTocWidget.classList.add('hidden');
                if (mobileTocBtn) mobileTocBtn.classList.add('hidden');
                closeMobileTocDrawer();
                tocHeadings = [];
                activeHeadingId = null;
                return;
            }

            const rawHeadings = Array.from(content.querySelectorAll('h1, h2, h3, h4, h5, h6'))
                .filter(h => h.textContent.trim().length > 0 && !h.closest('.not-prose, #comment-section'));

            if (rawHeadings.length < 2) {
                if (desktopTocWidget) desktopTocWidget.classList.add('hidden');
                if (mobileTocBtn) mobileTocBtn.classList.add('hidden');
                closeMobileTocDrawer();
                tocHeadings = [];
                activeHeadingId = null;
                return;
            }

            tocHeadings = rawHeadings;

            if (desktopTocWidget) desktopTocWidget.classList.remove('hidden');
            if (mobileTocBtn) mobileTocBtn.classList.remove('hidden');

            const minLevel = Math.min(...tocHeadings.map(h => parseInt(h.tagName[1], 10) || 2));

            if (desktopTocContent) desktopTocContent.replaceChildren();
            if (mobileTocContent) mobileTocContent.replaceChildren();

            tocHeadings.forEach((heading, index) => {
                const id = heading.id || ('heading-' + index);
                heading.id = id;

                const level = parseInt(heading.tagName[1], 10) || minLevel;
                const indent = Math.max(0, level - minLevel);
                const titleText = heading.textContent.trim();

                // Desktop link
                if (desktopTocContent) {
                    const dLink = document.createElement('a');
                    dLink.href = '#' + id;
                    dLink.className = 'toc-link';
                    dLink.style.paddingLeft = `${indent * 0.75 + 0.5}rem`;
                    dLink.dataset.headingId = id;
                    dLink.title = titleText;
                    dLink.textContent = titleText;
                    dLink.addEventListener('click', (e) => {
                        e.preventDefault();
                        heading.scrollIntoView({ behavior: 'smooth' });
                        history.replaceState(null, '', '#' + id);
                    });
                    desktopTocContent.appendChild(dLink);
                }

                // Mobile link
                if (mobileTocContent) {
                    const mLink = document.createElement('a');
                    mLink.href = '#' + id;
                    mLink.className = 'toc-link';
                    mLink.style.paddingLeft = `${indent * 0.75 + 0.65}rem`;
                    mLink.dataset.headingId = id;
                    mLink.title = titleText;
                    mLink.textContent = titleText;
                    mLink.addEventListener('click', (e) => {
                        e.preventDefault();
                        closeMobileTocDrawer();
                        heading.scrollIntoView({ behavior: 'smooth' });
                        history.replaceState(null, '', '#' + id);
                    });
                    mobileTocContent.appendChild(mLink);
                }
            });

            updateScrollspy();
        }

        function setActiveHeading(id) {
            if (activeHeadingId === id) return;
            activeHeadingId = id;

            const allLinks = document.querySelectorAll('.toc-link');
            allLinks.forEach(link => {
                if (link.dataset.headingId === id) {
                    link.classList.add('is-active');
                    const container = link.closest('#toc-content, #mobileTocContent');
                    if (container) {
                        const linkTop = link.offsetTop;
                        const containerScroll = container.scrollTop;
                        const containerHeight = container.clientHeight;
                        if (linkTop < containerScroll || linkTop > containerScroll + containerHeight - 36) {
                            container.scrollTo({ top: Math.max(0, linkTop - 36), behavior: 'smooth' });
                        }
                    }
                } else {
                    link.classList.remove('is-active');
                }
            });
        }

        function updateScrollspy() {
            if (!tocHeadings || tocHeadings.length === 0) return;

            const scrollY = window.scrollY || document.documentElement.scrollTop;
            const windowHeight = window.innerHeight;
            const docHeight = document.documentElement.scrollHeight;

            if (scrollY + windowHeight >= docHeight - 40) {
                setActiveHeading(tocHeadings[tocHeadings.length - 1].id);
                return;
            }

            const offset = 95;
            let currentId = tocHeadings[0].id;
            for (let i = 0; i < tocHeadings.length; i++) {
                const rect = tocHeadings[i].getBoundingClientRect();
                if (rect.top <= offset) {
                    currentId = tocHeadings[i].id;
                } else {
                    break;
                }
            }
            setActiveHeading(currentId);
        }

        function updateScrollProgress() {
            const scrollTop = window.scrollY || document.documentElement.scrollTop;
            const scrollHeight = document.documentElement.scrollHeight - window.innerHeight;
            const progress = scrollHeight > 0 ? Math.min(1, Math.max(0, scrollTop / scrollHeight)) : 0;

            if (mobileBackToTopBtn) {
                if (scrollTop > 280) {
                    mobileBackToTopBtn.classList.add('is-visible');
                } else {
                    mobileBackToTopBtn.classList.remove('is-visible');
                }
            }

            if (mobileProgressCirclePath) {
                const dashoffset = Math.max(0, 100 - (progress * 100));
                mobileProgressCirclePath.style.strokeDashoffset = dashoffset;
            }
        }

        let scrollTicking = false;
        window.addEventListener('scroll', () => {
            if (!scrollTicking) {
                window.requestAnimationFrame(() => {
                    updateScrollspy();
                    updateScrollProgress();
                    scrollTicking = false;
                });
                scrollTicking = true;
            }
        }, { passive: true });

        // ==========================================
        // Post Reactions & Article Share with PoW Anti-Spam
        // ==========================================
        let reactionPowWorker = null;
        let cachedReactionChallenge = null;
        let currentReactionPowPromise = null;

        function solveReactionPow(challenge, difficulty) {
            return new Promise((resolve, reject) => {
                if (typeof Worker === 'undefined') {
                    reject(new Error('PoW worker unsupported'));
                    return;
                }

                if (reactionPowWorker) {
                    reactionPowWorker.terminate();
                    reactionPowWorker = null;
                }

                try {
                    const worker = new Worker('/assets/js/comment-pow-worker.js?v=reaction-pow-1');
                    reactionPowWorker = worker;
                    const timer = setTimeout(() => {
                        worker.terminate();
                        if (reactionPowWorker === worker) reactionPowWorker = null;
                        reject(new Error('PoW solve timeout'));
                    }, 10000);

                    worker.onmessage = (e) => {
                        clearTimeout(timer);
                        worker.terminate();
                        if (reactionPowWorker === worker) reactionPowWorker = null;
                        if (e.data && e.data.ok && e.data.nonce !== undefined) {
                            resolve(String(e.data.nonce));
                        } else {
                            reject(new Error(e.data?.reason || 'PoW failed'));
                        }
                    };

                    worker.onerror = (err) => {
                        clearTimeout(timer);
                        worker.terminate();
                        if (reactionPowWorker === worker) reactionPowWorker = null;
                        reject(err);
                    };

                    worker.postMessage({ challenge: challenge, difficulty: difficulty });
                } catch (err) {
                    reject(err);
                }
            });
        }

        async function ensureReactionChallenge() {
            const now = Math.floor(Date.now() / 1000);
            if (cachedReactionChallenge && cachedReactionChallenge.expiresAt > now + 15) {
                return cachedReactionChallenge;
            }
            if (currentReactionPowPromise) {
                return currentReactionPowPromise;
            }

            currentReactionPowPromise = (async () => {
                try {
                    const res = await fetch('/api/reactions/challenge', { credentials: 'same-origin', cache: 'no-store' });
                    if (!res.ok) throw new Error('Failed to fetch reaction challenge');
                    const json = await res.json();
                    if (!json.success || !json.data) throw new Error('Invalid challenge response');
                    const ch = json.data;
                    const nonce = await solveReactionPow(ch.id, ch.difficulty);
                    cachedReactionChallenge = {
                        challengeId: ch.id,
                        powNonce: nonce,
                        expiresAt: ch.expiresAt
                    };
                    return cachedReactionChallenge;
                } catch (err) {
                    console.warn('[Reactions] Background PoW preparation:', err.message);
                    return null;
                } finally {
                    currentReactionPowPromise = null;
                }
            })();

            return currentReactionPowPromise;
        }

        function initPostReactions(root = document) {
            const container = root.querySelector?.('.post-reactions-container') ||
                (root.matches?.('.post-reactions-container') ? root : null);
            if (!container || container.dataset.reactionsBound === 'true') return;
            container.dataset.reactionsBound = 'true';

            const slug = container.dataset.postSlug;
            if (!slug) return;

            const storageReactionsKey = `windblog_reactions_${slug}`;
            const storageCountsKey = `windblog_reaction_counts_${slug}`;

            const getSlugSeed = (s) => {
                if (!s) return 42;
                let h = 0;
                for (let i = 0; i < s.length; i++) {
                    h = ((h << 5) - h) + s.charCodeAt(i);
                    h |= 0;
                }
                return Math.abs(h & 0x7fffffff);
            };

            const seedCount = (type) => {
                const seed = getSlugSeed(slug);
                switch (type) {
                    case 'like': return 16 + (seed % 23);
                    case 'heart': return 11 + (Math.floor(seed / 7) % 18);
                    case 'insightful': return 6 + (Math.floor(seed / 13) % 14);
                    case 'rocket': return 3 + (Math.floor(seed / 19) % 12);
                    case 'celebrate': return 2 + (Math.floor(seed / 29) % 10);
                    default: return 5 + (seed % 10);
                }
            };

            let userReactions = [];
            try {
                const stored = localStorage.getItem(storageReactionsKey);
                if (stored) userReactions = JSON.parse(stored);
            } catch {
                userReactions = [];
            }

            let counts = {};
            try {
                const storedCounts = localStorage.getItem(storageCountsKey);
                if (storedCounts) counts = JSON.parse(storedCounts);
            } catch {
                counts = {};
            }

            const buttons = container.querySelectorAll('.post-reaction-btn');
            const updateButtonViews = () => {
                buttons.forEach(btn => {
                    const type = btn.dataset.reactionType;
                    if (!type) return;
                    if (typeof counts[type] !== 'number') {
                        counts[type] = seedCount(type);
                    }
                    const isActive = userReactions.includes(type);
                    btn.classList.toggle('is-active', isActive);
                    btn.setAttribute('aria-pressed', isActive.toString());
                    const countEl = btn.querySelector('.reaction-count');
                    if (countEl) countEl.textContent = String(counts[type]);
                });
            };
            updateButtonViews();

            // Fetch live counts from backend
            fetch(`/api/reactions/${encodeURIComponent(slug)}`, { credentials: 'same-origin', cache: 'no-cache' })
                .then(r => r.ok ? r.json() : null)
                .then(json => {
                    if (json && json.success && json.data) {
                        counts = { ...counts, ...json.data };
                        updateButtonViews();
                        try { localStorage.setItem(storageCountsKey, JSON.stringify(counts)); } catch {}
                    }
                })
                .catch(() => {});

            // Proactively pre-fetch and background-solve a PoW challenge so human clicks are instantaneous
            ensureReactionChallenge();

            buttons.forEach(btn => {
                const type = btn.dataset.reactionType;
                if (!type) return;

                btn.addEventListener('click', async () => {
                    const activeNow = btn.classList.contains('is-active');
                    const action = activeNow ? 'unreact' : 'react';

                    // Optimistic UI update
                    if (activeNow) {
                        btn.classList.remove('is-active');
                        btn.setAttribute('aria-pressed', 'false');
                        counts[type] = Math.max(0, (counts[type] || 0) - 1);
                        userReactions = userReactions.filter(r => r !== type);
                    } else {
                        btn.classList.add('is-active');
                        btn.setAttribute('aria-pressed', 'true');
                        counts[type] = (counts[type] || 0) + 1;
                        if (!userReactions.includes(type)) {
                            userReactions.push(type);
                        }

                        // Burst animation
                        const badge = document.createElement('span');
                        badge.className = 'reaction-float-badge';
                        badge.textContent = '+1';
                        btn.appendChild(badge);
                        setTimeout(() => badge.remove(), 700);
                    }

                    const countEl = btn.querySelector('.reaction-count');
                    if (countEl) countEl.textContent = String(counts[type]);

                    try {
                        localStorage.setItem(storageReactionsKey, JSON.stringify(userReactions));
                        localStorage.setItem(storageCountsKey, JSON.stringify(counts));
                    } catch {}

                    // Server sync with PoW challenge
                    try {
                        let payload = { reactionType: type, action: action };
                        if (action === 'react') {
                            let ch = cachedReactionChallenge;
                            cachedReactionChallenge = null;
                            if (!ch) {
                                ch = await ensureReactionChallenge();
                            }
                            if (ch) {
                                payload.challengeId = ch.challengeId;
                                payload.powNonce = ch.powNonce;
                            }
                        }

                        const headers = { 'Content-Type': 'application/json' };
                        const csrfToken = typeof window.getCsrfToken === 'function' ? window.getCsrfToken() : '';
                        if (csrfToken) {
                            headers['X-XSRF-TOKEN'] = csrfToken;
                        }

                        const res = await fetch(`/api/reactions/${encodeURIComponent(slug)}`, {
                            method: 'POST',
                            headers: headers,
                            credentials: 'same-origin',
                            body: JSON.stringify(payload)
                        });

                        if (res.ok) {
                            const data = await res.json();
                            if (data.success && data.data) {
                                counts = { ...counts, ...data.data };
                                updateButtonViews();
                                try { localStorage.setItem(storageCountsKey, JSON.stringify(counts)); } catch {}
                            }
                        } else if (res.status === 429) {
                            if (typeof window.showToast === 'function') {
                                window.showToast('操作过于频繁，请稍后重试', 'warning');
                            }
                        } else {
                            console.warn('[Reactions] Server returned error status:', res.status);
                        }
                        }
                    } catch (netErr) {
                        console.warn('[Reactions] Sync error:', netErr.message);
                    } finally {
                        // Keep a challenge solved and warm for subsequent interactions
                        ensureReactionChallenge();
                    }
                });
            });

            // Share button
            const shareBtn = container.querySelector('#postShareBtn');
            if (shareBtn && !shareBtn.dataset.bound) {
                shareBtn.dataset.bound = 'true';
                shareBtn.addEventListener('click', async () => {
                    const url = window.location.href;
                    try {
                        if (navigator.clipboard && typeof navigator.clipboard.writeText === 'function') {
                            await navigator.clipboard.writeText(url);
                        } else {
                            const input = document.createElement('input');
                            input.value = url;
                            document.body.appendChild(input);
                            input.select();
                            document.execCommand('copy');
                            document.body.removeChild(input);
                        }
                        if (typeof window.showToast === 'function') {
                            window.showToast(window.wbT('post.share_copied', '文章链接已复制到剪贴板！'), 'success');
                        }
                    } catch {
                        if (typeof window.showToast === 'function') {
                            window.showToast(url, 'info');
                        }
                    }
                });
            }
        }

        document.addEventListener('page:ready', () => {
            initToc();
            updateScrollProgress();
            initPostReactions(document);
        });
        document.addEventListener('pjax:complete', () => {
            initToc();
            closeMobileTocDrawer();
            updateScrollProgress();
            initPostReactions(document);
        });
        document.addEventListener('sidebar:updated', initToc);

        bindSearchForms();
        decorateShortcutButtons();
        initPostReactions(document);
        document.addEventListener('page:ready', () => {
            bindSearchForms();
            decorateShortcutButtons();
            initPostReactions(document);
        });
    });
})();
