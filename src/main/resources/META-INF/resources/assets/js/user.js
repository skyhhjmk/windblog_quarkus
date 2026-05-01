// /assets/js/user.js
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function getCookie(name) {
        const cookie = document.cookie.split(';').map(s => s.trim());
        for (const c of cookie) {
            if (c.startsWith(name + '=')) return decodeURIComponent(c.slice(name.length + 1));
        }
        return null;
    }

    function getCsrfToken() {
        const meta = document.head.querySelector('meta[name="csrf-token"]');
        if (meta && meta.content) return meta.content;
        // 回退 cookie
        return getCookie('_token') || getCookie('XSRF-TOKEN') || '';
    }

    function checkUserStatus() {
        fetch('/user/api/profile')
            .then(r => r.json())
            .then(data => {
                const userNotLoggedIn = document.getElementById('userNotLoggedIn');
                const mobileUserAuth = document.getElementById('mobileUserAuth');
                const userLoggedIn = document.getElementById('userLoggedIn');
                const userNickname = document.getElementById('userNickname');

                const ok = data && data.success && data.data;
                if (ok) {
                    if (userNotLoggedIn) userNotLoggedIn.style.display = 'none';
                    if (mobileUserAuth) mobileUserAuth.style.display = 'none';
                    if (userLoggedIn) userLoggedIn.classList.remove('hidden');
                    if (userNickname) userNickname.textContent = data.data.username || 'User';
                } else {
                    if (userNotLoggedIn) userNotLoggedIn.style.display = 'block';
                    if (mobileUserAuth) mobileUserAuth.style.display = 'block';
                    if (userLoggedIn) userLoggedIn.classList.add('hidden');
                }
            })
            .catch(() => {
                console.warn('User Status API failed');
            });
    }

    async function showConfirm(title, message, onConfirm) {
        const result = await window.showModal({
            title: title,
            message: message,
            showCancel: true
        });
        if (result && onConfirm) {
            onConfirm();
        }
    }

    function bindLogout() {
        const logoutLink = document.getElementById('logoutLink');
        const logoutBtn = document.getElementById('logoutBtn');

        const doLogout = (e) => {
            if (e) e.preventDefault();
            const targetBtn = e.currentTarget;
            showConfirm(window.i18n.logout_title || 'Logout', window.i18n.logout_confirm || 'Are you sure you want to logout?', () => {
                window.setLoading(targetBtn, true);
                fetch('/user/api/logout', {
                    method: 'POST'
                }).then(() => {
                    window.location.href = '/';
                }).catch(() => {
                    window.location.href = '/';
                });
            });
        };

        if (logoutLink) {
            logoutLink.onclick = doLogout;
        }

        if (logoutBtn) {
            logoutBtn.onclick = doLogout;
        }
    }

    function bindLoginForm() {
        const form = document.getElementById('loginForm');
        const errorDiv = document.getElementById('errorMessage');
        const submitBtn = form?.querySelector('button[type="submit"]');

        if (!form) return;

        form.onsubmit = async function (e) {
            e.preventDefault();
            e.stopPropagation();

            if (errorDiv) errorDiv.classList.add('hidden');
            
            // Loading state
            window.setLoading(submitBtn, true);

            // 将表单数据转换为 URLSearchParams
            const formData = new URLSearchParams();
            const inputs = form.querySelectorAll('input[name]');
            inputs.forEach(input => {
                if (input.type !== 'checkbox' || input.checked) {
                    formData.append(input.name, input.value);
                }
            });

            let isRedirecting = false;
            try {
                const response = await fetch('/user/api/login', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: formData.toString()
                });

                const result = await response.json();

                if (result.success) {
                    isRedirecting = true;
                    window.showToast(result.message || window.i18n.login_success || 'Login successful', 'success');
                    setTimeout(() => {
                        window.location.href = result.redirect || '/';
                    }, 1000);
                } else {
                    window.showToast(result.message || window.i18n.login_failed || 'Login failed', 'error');
                    if (errorDiv) {
                        errorDiv.textContent = result.message || window.i18n.login_failed || 'Login failed';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                window.showToast(window.i18n.network_error || 'Network error', 'error');
                if (errorDiv) {
                    errorDiv.textContent = window.i18n.network_error || 'Network error';
                    errorDiv.classList.remove('hidden');
                }
            } finally {
                // Reset loading state ONLY if NOT redirecting
                window.setLoading(submitBtn, false, { stayDisabled: isRedirecting });
            }

            return false;
        };
    }

    function bindRegisterForm() {
        const form = document.getElementById('registerForm');
        const errorDiv = document.getElementById('errorMessage');
        const password = document.getElementById('password');
        const confirmPassword = document.getElementById('confirmPassword');
        const submitBtn = document.getElementById('registerSubmit');

        if (!form) return;

        form.onsubmit = async function (e) {
            e.preventDefault();
            e.stopPropagation();

            if (errorDiv) errorDiv.classList.add('hidden');

            if (password && confirmPassword && password.value !== confirmPassword.value) {
                const msg = window.i18n.password_mismatch || 'Passwords do not match';
                window.showToast(msg, 'error');
                if (errorDiv) {
                    errorDiv.textContent = msg;
                    errorDiv.classList.remove('hidden');
                }
                return false;
            }

            // Loading state
            window.setLoading(submitBtn, true);

            // 将表单数据转换为 URLSearchParams
            const formData = new URLSearchParams();
            const inputs = form.querySelectorAll('input[name]');
            inputs.forEach(input => {
                if (input.name !== 'confirmPassword' && (input.type !== 'checkbox' || input.checked)) {
                    formData.append(input.name, input.value);
                }
            });

            let isRedirecting = false;
            try {
                const response = await fetch('/user/api/register', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded'
                    },
                    body: formData.toString()
                });

                const result = await response.json();

                if (result.success) {
                    isRedirecting = true;
                    window.showToast(result.message || window.i18n.register_success || 'Registration successful', 'success');
                    // 延迟跳转，让用户看到成功提示
                    setTimeout(() => {
                        window.location.href = result.redirect || '/';
                    }, 1500);
                } else {
                    window.showToast(result.message || window.i18n.register_failed || 'Registration failed', 'error');
                    if (errorDiv) {
                        errorDiv.textContent = result.message || window.i18n.register_failed || 'Registration failed';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                window.showToast(window.i18n.network_error || 'Network error', 'error');
                if (errorDiv) {
                    errorDiv.textContent = window.i18n.network_error || 'Network error';
                    errorDiv.classList.remove('hidden');
                }
            } finally {
                // Reset loading state if not redirecting (failed)
                window.setLoading(submitBtn, false, { stayDisabled: isRedirecting });
            }

            return false;
        };
    }

    function init() {
        checkUserStatus();
        bindLogout();
        bindLoginForm();
        bindRegisterForm();
    }

    ready(() => {
        init();

        // PJAX 刷新后，重新初始化
        document.addEventListener('pjax:complete', () => {
            init();
        });
    });
})();
``