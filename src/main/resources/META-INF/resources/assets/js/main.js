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

    ready(() => {
        const mobileBtn = document.getElementById('mobileMenuBtn');
        const mobileMenu = document.getElementById('mobileMenu');
        if (mobileBtn && mobileMenu) {
            mobileBtn.addEventListener('click', () => {
                mobileMenu.classList.toggle('hidden');
                mobileBtn.setAttribute('aria-expanded', (!mobileMenu.classList.contains('hidden')).toString());
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
                dispatch('sidebar:updated');
            }
        }

        async function loadByPjax(url, pushState) {
            const container = document.getElementById('pjax-container');
            if (!container) {
                window.location.href = url;
                return;
            }

            dispatch('pjax:start', { url: url });
            try {
                const response = await fetch(url, {
                    method: 'GET',
                    headers: {
                        'X-PJAX': 'true',
                        'X-PJAX-Container': '#pjax-container'
                    }
                });

                if (!response.ok) {
                    throw new Error('PJAX request failed: ' + response.status);
                }

                const html = await response.text();
                container.innerHTML = extractPjaxHtml(html);

                const titleHolder = container.querySelector('[data-page-title]');
                if (titleHolder) {
                    const title = titleHolder.getAttribute('data-page-title');
                    if (title) document.title = title;
                }
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
                }

                dispatch('page:ready', { url: url });
                dispatch('pjax:complete', { url: url });
                dispatch('pjax:end', { url: url });
            } catch (error) {
                dispatch('pjax:end', { url: url, error: error });
                window.location.href = url;
            }
        }

        function extractPjaxHtml(html) {
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
            loadByPjax(url.href, true);
        });

        window.addEventListener('popstate', () => {
            loadByPjax(window.location.href, false);
        });

        document.addEventListener('page:ready', () => {
            formatTimestamps();
            injectSidebar();
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

        // Search Suggestions logic
        const searchInput = document.querySelector('form[action="/search"] input[name="q"]');
        if (searchInput) {
            const form = searchInput.closest('form');
            const suggestWrap = document.createElement('div');
            suggestWrap.id = 'search-suggestions';
            suggestWrap.className = 'absolute top-full left-0 right-0 mt-1 bg-card border border-border rounded-lg shadow-2xl hidden z-[60] overflow-hidden';
            form.appendChild(suggestWrap);

            let suggestTimer = null;
            searchInput.addEventListener('input', () => {
                const q = searchInput.value.trim();
                clearTimeout(suggestTimer);
                if (q.length < 2) {
                    suggestWrap.innerHTML = '';
                    suggestWrap.classList.add('hidden');
                    return;
                }

                suggestTimer = setTimeout(async () => {
                    try {
                        const res = await fetch(`/search/suggest?q=${encodeURIComponent(q)}`);
                        if (!res.ok) throw new Error('Suggest fetch failed');
                        const suggestions = await res.json();

                        if (suggestions && suggestions.length > 0) {
                            suggestWrap.innerHTML = suggestions.map(s => `
                                <div class="px-4 py-2 text-sm hover:bg-accent/10 cursor-pointer transition-colors border-b border-border/50 last:border-0" data-val="${s.replace(/"/g, '&quot;')}">
                                    ${s}
                                </div>
                            `).join('');
                            suggestWrap.classList.remove('hidden');
                        } else {
                            suggestWrap.innerHTML = '';
                            suggestWrap.classList.add('hidden');
                        }
                    } catch (err) {
                        console.warn('Autocomplete error:', err);
                    }
                }, 300);
            });

            suggestWrap.addEventListener('click', (e) => {
                const item = e.target.closest('[data-val]');
                if (item) {
                    searchInput.value = item.getAttribute('data-val');
                    suggestWrap.classList.add('hidden');
                    form.submit();
                }
            });

            document.addEventListener('click', (e) => {
                if (!form.contains(e.target)) {
                    suggestWrap.classList.add('hidden');
                }
            });

            searchInput.addEventListener('keydown', (e) => {
                if (e.key === 'Escape') {
                    suggestWrap.classList.add('hidden');
                }
            });
        }
    });
})();
