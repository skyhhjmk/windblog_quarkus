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

    /**
     * Show a toast notification
     * @param {string} message 
     * @param {string} type - success, error, info
     * @param {number} duration - ms
     */
    window.showToast = function(message, type = 'info', duration = 3000) {
        const container = document.getElementById('toastContainer');
        if (!container) {
            // Fallback to console if no container
            console.log(`[${type}] ${message}`);
            return;
        }

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;
        
        let icon = '';
        if (type === 'success') icon = '<svg class="w-5 h-5 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M5 13l4 4L19 7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else if (type === 'error') icon = '<svg class="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M6 18L18 6M6 6l12 12" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else icon = '<svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

        toast.innerHTML = `
            <div class="toast-icon">${icon}</div>
            <div class="toast-message">${message}</div>
        `;

        container.appendChild(toast);

        // Trigger animation
        setTimeout(() => toast.classList.add('show'), 10);

        // Auto remove
        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 300);
        }, duration);
    };

    /**
     * Set loading state for a button
     * @param {HTMLButtonElement|string} button - Button element or selector
     * @param {boolean} isLoading - true to enable loading, false to reset
     * @param {object} options - { text: 'New Text', stayDisabled: false }
     */
    window.setLoading = function(button, isLoading, options = {}) {
        const btn = typeof button === 'string' ? document.querySelector(button) : button;
        if (!btn) return;

        if (isLoading) {
            // Disable and show spinner
            btn.disabled = true;
            btn.classList.add('btn-loading');
            
            // Handle spinner element
            let spinner = btn.querySelector('.spinner');
            if (!spinner) {
                spinner = document.createElement('span');
                spinner.className = 'spinner';
                btn.appendChild(spinner);
            }
            spinner.classList.remove('hidden');

            // Handle text
            if (options.text) {
                let textSpan = btn.querySelector('.btn-text');
                if (!textSpan) {
                    textSpan = document.createElement('span');
                    textSpan.className = 'btn-text';
                    textSpan.innerHTML = btn.innerHTML;
                    btn.innerHTML = '';
                    btn.appendChild(textSpan);
                    btn.appendChild(spinner);
                }
                btn.dataset.originalText = textSpan.innerHTML;
                textSpan.innerHTML = options.text;
            }
        } else {
            // Re-enable unless stayDisabled is true
            if (!options.stayDisabled) {
                btn.disabled = false;
                btn.classList.remove('btn-loading');
                const spinner = btn.querySelector('.spinner');
                if (spinner) spinner.classList.add('hidden');

                // Reset text
                if (btn.dataset.originalText) {
                    const textSpan = btn.querySelector('.btn-text');
                    if (textSpan) textSpan.innerHTML = btn.dataset.originalText;
                    delete btn.dataset.originalText;
                }
            }
        }
    };

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
                container.innerHTML = html;

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

                const sidebarTemplate = container.querySelector('#pjax-sidebar-html');
                const sidebarContainer = document.getElementById('sidebar-container');
                if (sidebarTemplate && sidebarContainer) {
                    const nextSidebarHtml = sidebarTemplate.innerHTML || '';
                    if (sidebarContainer.innerHTML !== nextSidebarHtml) {
                        sidebarContainer.innerHTML = nextSidebarHtml;
                        document.dispatchEvent(new Event('sidebar:updated'));
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

        function formatTimestamps(root = document) {
            const elements = root.querySelectorAll('.timestamp:not(.formatted)');
            const options = { 
                year: 'numeric', 
                month: 'short', 
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
            };
            const formatter = new Intl.DateTimeFormat(navigator.language, options);

            elements.forEach(el => {
                const raw = el.getAttribute('data-timestamp') || el.textContent;
                if (!raw) return;
                
                try {
                    const date = new Date(raw.replace(' ', 'T'));
                    if (!isNaN(date.getTime())) {
                        el.textContent = formatter.format(date);
                        el.classList.add('formatted');
                    }
                } catch (e) {
                    console.error('Failed to format timestamp:', raw, e);
                }
            });
        }

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
    });
})();
