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

    function showConfirm(title, message, onConfirm) {
        const dialog = document.getElementById('confirmDialog');
        const titleEl = document.getElementById('confirmTitle');
        const messageEl = document.getElementById('confirmMessage');
        const okBtn = document.getElementById('confirmOk');
        const cancelBtn = document.getElementById('confirmCancel');

        if (!dialog) {
            if (confirm(message)) onConfirm();
            return;
        }

        titleEl.textContent = title;
        messageEl.textContent = message;
        dialog.classList.remove('hidden');

        function cleanup() {
            dialog.classList.add('hidden');
            okBtn.onclick = null;
            cancelBtn.onclick = null;
        }

        okBtn.onclick = () => {
            cleanup();
            onConfirm();
        };

        cancelBtn.onclick = cleanup;

        dialog.onclick = (e) => {
            if (e.target === dialog) cleanup();
        };
    }

    function bindLogout() {
        const logoutLink = document.getElementById('logoutLink');
        const logoutBtn = document.getElementById('logoutBtn');

        const doLogout = (e) => {
            if (e) e.preventDefault();
            showConfirm('退出登录', '确定要退出登录吗？', () => {
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

        if (!form) return;

        form.onsubmit = async function (e) {
            e.preventDefault();
            e.stopPropagation();

            if (errorDiv) errorDiv.classList.add('hidden');

            // 将表单数据转换为 URLSearchParams
            const formData = new URLSearchParams();
            const inputs = form.querySelectorAll('input[name]');
            inputs.forEach(input => {
                if (input.type !== 'checkbox' || input.checked) {
                    formData.append(input.name, input.value);
                }
            });

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
                    window.location.href = result.redirect || '/';
                } else {
                    if (errorDiv) {
                        errorDiv.textContent = result.message || '登录失败';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                if (errorDiv) {
                    errorDiv.textContent = '网络错误，请稍后重试';
                    errorDiv.classList.remove('hidden');
                }
            }

            return false;
        };
    }

    function bindRegisterForm() {
        const form = document.getElementById('registerForm');
        const errorDiv = document.getElementById('errorMessage');
        const password = document.getElementById('password');
        const confirmPassword = document.getElementById('confirmPassword');

        if (!form) return;

        form.onsubmit = async function (e) {
            e.preventDefault();
            e.stopPropagation();

            if (errorDiv) errorDiv.classList.add('hidden');

            if (password && confirmPassword && password.value !== confirmPassword.value) {
                errorDiv.textContent = '两次输入的密码不一致';
                errorDiv.classList.remove('hidden');
                return false;
            }

            // 将表单数据转换为 URLSearchParams
            const formData = new URLSearchParams();
            const inputs = form.querySelectorAll('input[name]');
            inputs.forEach(input => {
                if (input.name !== 'confirmPassword' && (input.type !== 'checkbox' || input.checked)) {
                    formData.append(input.name, input.value);
                }
            });

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
                    window.location.href = result.redirect || '/';
                } else {
                    if (errorDiv) {
                        errorDiv.textContent = result.message || '注册失败';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                if (errorDiv) {
                    errorDiv.textContent = '网络错误，请稍后重试';
                    errorDiv.classList.remove('hidden');
                }
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