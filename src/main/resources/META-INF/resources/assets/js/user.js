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
        fetch('/user/profile/api')
            .then(r => r.json())
            .then(data => {
                const userNotLoggedIn = document.getElementById('userNotLoggedIn');
                const mobileUserAuth = document.getElementById('mobileUserAuth');
                const userLoggedIn = document.getElementById('userLoggedIn');
                const userNickname = document.getElementById('userNickname');

                const ok = data && data.code === 0 && data.data;
                if (ok) {
                    if (userNotLoggedIn) userNotLoggedIn.style.display = 'none';
                    if (mobileUserAuth) mobileUserAuth.style.display = 'none';
                    if (userLoggedIn) userLoggedIn.classList.remove('hidden');
                    if (userNickname) userNickname.textContent = data.data.nickname || data.data.username || 'User';
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

    function bindLogout() {
        const $link = document.getElementById('logoutLink');
        if (!$link) return;

        $link.addEventListener('click', (e) => {
            e.preventDefault();
            if (!confirm('Logout?')) return;

            const token = getCsrfToken();
            fetch('/user/logout', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded',
                    'X-CSRF-TOKEN': token
                },
                body: '_logout_token=' + encodeURIComponent(token)
            }).then(() => window.location.reload());
        });
    }

    ready(() => {
        checkUserStatus();
        bindLogout();

        // PJAX 刷新后，重新检查状态
        document.addEventListener('pjax:complete', () => {
            checkUserStatus();
            bindLogout();
        });
    });
})();
``