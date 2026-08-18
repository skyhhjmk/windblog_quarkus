/**
 * WindBlog UI Components Library
 * Handles: Toasts, Modals, Button Loading States
 */
(function () {
    'use strict';

    /**
     * Show a toast notification
     * @param {string} message
     * @param {string} type - success, error, info
     * @param {number} duration - ms
     */
    window.showToast = function (message, type = 'info', duration = 3000) {
        const container = document.getElementById('toastContainer');
        if (!container) {
            console.log(`[${type}] ${message}`);
            return;
        }

        const toast = document.createElement('div');
        toast.className = `toast toast-${type}`;

        let icon = '';
        if (type === 'success') icon = '<svg class="w-5 h-5 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M5 13l4 4L19 7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else if (type === 'error') icon = '<svg class="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M6 18L18 6M6 6l12 12" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else icon = '<svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

        toast.innerHTML = `<div class="toast-icon">${icon}</div><div class="toast-message"></div>`;
        toast.querySelector('.toast-message').textContent = String(message ?? '');

        container.appendChild(toast);
        setTimeout(() => toast.classList.add('show'), 10);

        setTimeout(() => {
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 400);
        }, duration);
    };

    /**
     * Show a custom modal (alert, confirm, or prompt)
     * @param {object} options - { title, message, type, showCancel, defaultValue }
     * @returns {Promise}
     */
    window.showModal = function (options = {}) {
        return new Promise((resolve) => {
            const modal = document.getElementById('appModal');
            const titleEl = document.getElementById('modalTitle');
            const messageEl = document.getElementById('modalMessage');
            const okBtn = document.getElementById('modalOk');
            const cancelBtn = document.getElementById('modalCancel');
            const promptContainer = document.getElementById('modalPromptContainer');
            const promptInput = document.getElementById('modalPromptInput');

            if (!modal) {
                console.warn('AppModal element not found');
                resolve(false);
                return;
            }

            titleEl.textContent = options.title || '提示';
            messageEl.replaceChildren();
            if (typeof options.renderContent === 'function') {
                options.renderContent(messageEl);
            } else {
                messageEl.textContent = options.message || '';
            }

            if (options.showCancel) cancelBtn.classList.remove('hidden');
            else cancelBtn.classList.add('hidden');

            if (options.type === 'prompt') {
                promptContainer.classList.remove('hidden');
                promptInput.value = options.defaultValue || '';
                setTimeout(() => promptInput.focus(), 100);
            } else {
                promptContainer.classList.add('hidden');
            }

            const previousActiveElement = document.activeElement;
            const previousBodyOverflow = document.body.style.overflow;
            modal.setAttribute('role', 'dialog');
            modal.setAttribute('aria-modal', 'true');
            modal.setAttribute('aria-labelledby', 'modalTitle');
            modal.classList.remove('hidden');
            void modal.offsetWidth;
            modal.classList.add('show');
            document.body.style.overflow = 'hidden';
            const focusTarget = options.type === 'prompt' ? promptInput : (options.showCancel ? cancelBtn : okBtn);
            setTimeout(() => focusTarget && focusTarget.focus(), 0);

            let settled = false;
            const cleanup = (result) => {
                if (settled) return;
                settled = true;
                modal.classList.remove('show');
                document.body.style.overflow = previousBodyOverflow;
                document.removeEventListener('keydown', escHandler);
                setTimeout(() => {
                    if (!modal.classList.contains('show')) {
                        modal.classList.add('hidden');
                    }
                }, 400);
                okBtn.onclick = null;
                cancelBtn.onclick = null;
                modal.querySelector('.modal-overlay').onclick = null;
                if (previousActiveElement && typeof previousActiveElement.focus === 'function') {
                    previousActiveElement.focus();
                }
                resolve(result);
            };

            okBtn.onclick = () => {
                if (options.type === 'prompt') cleanup(promptInput.value);
                else cleanup(true);
            };

            cancelBtn.onclick = () => cleanup(false);

            const escHandler = (e) => {
                if (e.key === 'Escape') {
                    cleanup(false);
                }
            };
            document.addEventListener('keydown', escHandler);

            modal.querySelector('.modal-overlay').onclick = () => {
                cleanup(false);
            };
        });
    };

    /**
     * Set loading state for a button
     */
    window.setLoading = function (button, isLoading, options = {}) {
        const btn = typeof button === 'string' ? document.querySelector(button) : button;
        if (!btn) return;

        if (isLoading) {
            btn.disabled = true;
            btn.classList.add('btn-loading');

            let spinner = btn.querySelector('.spinner');
            if (!spinner) {
                spinner = document.createElement('span');
                spinner.className = 'spinner';
                btn.appendChild(spinner);
            }
            spinner.classList.remove('hidden');

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
            if (!options.stayDisabled) {
                btn.disabled = false;
                btn.classList.remove('btn-loading');
                const spinner = btn.querySelector('.spinner');
                if (spinner) spinner.classList.add('hidden');

                if (btn.dataset.originalText) {
                    const textSpan = btn.querySelector('.btn-text');
                    if (textSpan) textSpan.innerHTML = btn.dataset.originalText;
                    delete btn.dataset.originalText;
                }
            }
        }
    };

    // Override native methods
    window.alert = function (message) {
        return window.showModal({title: '提示', message: message});
    };

    window.confirm = function (message) {
        return window.showModal({title: '确认', message: message, showCancel: true});
    };

    window.prompt = function (message, defaultValue) {
        return window.showModal({
            title: '输入',
            message: message,
            type: 'prompt',
            defaultValue: defaultValue,
            showCancel: true
        });
    };

    /**
     * Get CSRF Token from meta tag or cookie
     * @returns {string}
     */
    window.getCsrfToken = function () {
        // 1. Try meta tag (highest priority)
        const meta = document.head.querySelector('meta[name="csrf-token"]');
        if (meta && meta.content) return meta.content;

        // 2. Try cookies (XSRF-TOKEN is set by CsrfFilter)
        const getCookie = (name) => {
            const cookie = document.cookie.split(';').map(s => s.trim());
            for (const c of cookie) {
                if (c.startsWith(name + '=')) return decodeURIComponent(c.slice(name.length + 1));
            }
            return null;
        };

        return getCookie('XSRF-TOKEN') || getCookie('_token') || '';
    };
})();
