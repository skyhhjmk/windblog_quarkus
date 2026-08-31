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
        toast.setAttribute('role', type === 'error' ? 'alert' : 'status');
        toast.setAttribute('aria-live', type === 'error' ? 'assertive' : 'polite');

        let icon = '';
        if (type === 'success') icon = '<svg class="w-5 h-5 text-emerald-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M5 13l4 4L19 7" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else if (type === 'error') icon = '<svg class="w-5 h-5 text-red-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M6 18L18 6M6 6l12 12" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
        else icon = '<svg class="w-5 h-5 text-accent" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>';

        toast.innerHTML = `<div class="toast-icon">${icon}</div><div class="toast-message"></div><button type="button" class="toast-close" aria-label="关闭通知" title="关闭通知"><svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" stroke-width="2" stroke-linecap="round"/></svg></button>`;
        toast.querySelector('.toast-message').textContent = String(message ?? '');

        container.appendChild(toast);
        setTimeout(() => toast.classList.add('show'), 10);

        const dismiss = () => {
            if (!toast.isConnected) return;
            toast.classList.remove('show');
            setTimeout(() => toast.remove(), 400);
        };
        const dismissTimer = setTimeout(dismiss, duration);
        toast.querySelector('.toast-close').addEventListener('click', () => {
            clearTimeout(dismissTimer);
            dismiss();
        });
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
            modal.setAttribute('aria-describedby', 'modalMessage');
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
                document.removeEventListener('keydown', enterHandler);
                document.removeEventListener('keydown', trapFocusHandler);
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
            const enterHandler = (e) => {
                if (e.key !== 'Enter' || e.isComposing || !document.hasFocus()) return;
                if (e.target.closest && e.target.closest('#modalCancel')) return;
                if (e.target.closest && e.target.closest('textarea,[contenteditable="true"]')) return;
                e.preventDefault();
                okBtn.click();
            };
            const trapFocusHandler = (e) => {
                if (e.key !== 'Tab') return;
                const focusable = Array.from(modal.querySelectorAll(
                    'button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'
                )).filter((element) => element.getClientRects().length > 0 &&
                    getComputedStyle(element).visibility !== 'hidden');
                if (focusable.length === 0) {
                    e.preventDefault();
                    return;
                }
                const first = focusable[0];
                const last = focusable[focusable.length - 1];
                if (!modal.contains(document.activeElement)) {
                    e.preventDefault();
                    first.focus();
                } else if (e.shiftKey && document.activeElement === first) {
                    e.preventDefault();
                    last.focus();
                } else if (!e.shiftKey && document.activeElement === last) {
                    e.preventDefault();
                    first.focus();
                }
            };
            document.addEventListener('keydown', escHandler);
            document.addEventListener('keydown', enterHandler);
            document.addEventListener('keydown', trapFocusHandler);

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
