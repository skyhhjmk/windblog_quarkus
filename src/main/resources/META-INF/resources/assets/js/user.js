// /assets/js/user.js
(function () {
    'use strict';

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
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
                    method: 'POST',
                    headers: {
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    }
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
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': window.getCsrfToken()
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
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': window.getCsrfToken()
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

    // --- 用户中心钱包与积分记录逻辑 ---
    var walletCurrentPage = 1;
    var walletPageSize = 10;
    var walletTotalItems = 0;

    async function loadTransactions(page) {
        var list = document.getElementById('transactions-list');
        if (!list) return;

        list.innerHTML = '<div class="flex flex-col items-center justify-center py-20 gap-4"><span class="spinner w-8 h-8 text-accent"></span><p class="text-sm text-meta animate-pulse">正在获取数据...</p></div>';

        try {
            var response = await fetch('/api/user/wallet/transactions?page=' + page + '&pageSize=' + walletPageSize);
            var result = await response.json();

            if (result.success && result.data && result.data.length > 0) {
                walletTotalItems = result.total || 0;
                list.innerHTML = result.data.map(function (t) {
                    var amountColor = t.changeAmount > 0 ? '#10b981' : '#ef4444';
                    var amountBg = t.changeAmount > 0 ? 'rgba(16,185,129,0.1)' : 'rgba(239,68,68,0.1)';
                    var prefix = t.changeAmount > 0 ? '+' : '';
                    var desc = t.description || t.bizType;
                    var dateStr = new Date(t.createdAt).toLocaleString();

                    return '<div class="group flex justify-between items-center p-4 border border-border/50 bg-bg/20 hover:bg-bg/40 hover:border-accent/30 rounded-xl transition-all duration-300">' +
                        '<div class="flex items-center gap-4">' +
                        '<div class="w-10 h-10 rounded-full flex items-center justify-center font-bold" style="background:' + amountBg + '; color:' + amountColor + '">' +
                        (t.changeAmount > 0 ? '↑' : '↓') +
                        '</div>' +
                        '<div>' +
                        '<div class="font-bold text-main group-hover:text-accent transition-colors">' + desc + '</div>' +
                        '<div class="text-[11px] text-meta mt-0.5 font-mono">' + dateStr + '</div>' +
                        '</div>' +
                        '</div>' +
                        '<div class="text-right">' +
                        '<div class="font-black text-xl tracking-tight" style="color:' + amountColor + '">' + prefix + t.changeAmount + '</div>' +
                        '<div class="text-[10px] text-meta font-mono tracking-tighter mt-0.5 opacity-60">BALANCE: ' + t.balanceAfter + '</div>' +
                        '</div>' +
                        '</div>';
                }).join('');

                updateWalletPagination();
            } else {
                list.innerHTML = '<div class="text-center py-20"><div class="text-6xl mb-4 opacity-10">∅</div><p class="text-meta font-medium">暂无积分变动记录</p></div>';
                updateWalletPagination();
            }
        } catch (e) {
            list.innerHTML = '<div class="text-center py-20"><div class="text-red-400/20 text-6xl mb-4">!</div><p class="text-red-400 text-sm">加载失败，请检查网络连接</p></div>';
        }
    }

    function updateWalletPagination() {
        var prev = document.getElementById('prevPage');
        var next = document.getElementById('nextPage');
        var info = document.getElementById('pageInfo');
        var totalPages = Math.ceil(walletTotalItems / walletPageSize) || 1;

        if (prev) prev.disabled = (walletCurrentPage <= 1);
        if (next) next.disabled = (walletCurrentPage >= totalPages);
        if (info) info.textContent = 'PAGE ' + walletCurrentPage + ' / ' + totalPages;

        var dots = document.getElementById('pageDots');
        if (dots) {
            dots.innerHTML = '';
            for (var i = 1; i <= Math.min(totalPages, 5); i++) {
                var dot = document.createElement('div');
                dot.className = 'w-1 h-1 rounded-full ' + (i === walletCurrentPage ? 'bg-accent w-3' : 'bg-border');
                dot.style.transition = 'all 0.3s ease';
                dots.appendChild(dot);
            }
        }
    }

    function bindUserWallet() {
        var checkInBtn = document.getElementById('checkInBtn');
        var showHistoryBtn = document.getElementById('showHistoryBtn');
        var historyModal = document.getElementById('historyModal');

        if (checkInBtn) {
            checkInBtn.onclick = async function () {
                if (checkInBtn.disabled) return;
                checkInBtn.disabled = true;
                var originalHtml = checkInBtn.innerHTML;
                checkInBtn.innerHTML = '<span class="spinner mr-2"></span>签到中...';

                try {
                    var response = await fetch('/api/user/wallet/check-in', {
                        method: 'POST',
                        headers: {
                            'Content-Type': 'application/json',
                            'X-XSRF-TOKEN': window.getCsrfToken()
                        }
                    });
                    var result = await response.json();

                    if (result.success) {
                        window.showToast('签到成功！获得积分奖励', 'success');
                        var balanceEl = document.getElementById('userPointsBalance');
                        if (balanceEl && result.newBalance !== undefined) {
                            balanceEl.textContent = result.newBalance;
                        }
                        checkInBtn.disabled = true;
                        checkInBtn.className = "px-8 py-3 bg-gray-500/20 text-gray-500 border border-gray-500/30 rounded cursor-not-allowed flex items-center gap-2";
                        checkInBtn.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" class="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>今日已签到';
                    } else {
                        window.showToast(result.message || '签到失败', 'error');
                        checkInBtn.disabled = false;
                        checkInBtn.innerHTML = originalHtml;
                    }
                } catch (e) {
                    window.showToast('网络异常', 'error');
                    checkInBtn.disabled = false;
                    checkInBtn.innerHTML = originalHtml;
                }
            };
        }

        if (showHistoryBtn && historyModal) {
            var overlay = historyModal.querySelector('.modal-overlay');
            var closeBtn = historyModal.querySelector('.modal-close');

            showHistoryBtn.onclick = function () {
                historyModal.classList.remove('hidden');
                void historyModal.offsetWidth;
                historyModal.classList.add('show');
                document.body.style.overflow = 'hidden';
                walletCurrentPage = 1;
                loadTransactions(1);
            };

            var close = function () {
                historyModal.classList.remove('show');
                setTimeout(function () {
                    if (!historyModal.classList.contains('show')) {
                        historyModal.classList.add('hidden');
                    }
                }, 300);
                document.body.style.overflow = '';
            };

            if (overlay) overlay.onclick = close;
            if (closeBtn) closeBtn.onclick = close;
        }

        var prevBtn = document.getElementById('prevPage');
        var nextBtn = document.getElementById('nextPage');
        if (prevBtn) {
            prevBtn.onclick = function () {
                if (walletCurrentPage > 1) {
                    walletCurrentPage--;
                    loadTransactions(walletCurrentPage);
                }
            };
        }
        if (nextBtn) {
            nextBtn.onclick = function () {
                if (walletCurrentPage * walletPageSize < walletTotalItems) {
                    walletCurrentPage++;
                    loadTransactions(walletCurrentPage);
                }
            };
        }

        if (window.location.hash === '#points-history' && showHistoryBtn) {
            showHistoryBtn.click();
        }

        // 背包物品详情
        window.showItemDetails = function (id, name, desc, rarity, type) {
            var modal = document.getElementById('itemModal');
            if (!modal) return;
            var nameEl = document.getElementById('itemModalName');
            var typeEl = document.getElementById('itemModalType');
            var descEl = document.getElementById('itemModalDesc');

            if (nameEl) {
                nameEl.textContent = name;
                nameEl.style.color = rarity;
            }
            if (typeEl) typeEl.textContent = type;
            if (descEl) descEl.textContent = desc || '无详细描述';

            modal.classList.remove('hidden');
            void modal.offsetWidth;
            var container = modal.querySelector('.item-modal-container');
            if (container) {
                container.style.transform = 'translate(-50%, -50%) scale(1)';
                container.style.opacity = '1';
            }

            var closeItem = function () {
                if (container) {
                    container.style.transform = 'translate(-50%, -50%) scale(0.95)';
                    container.style.opacity = '0';
                }
                setTimeout(function () {
                    modal.classList.add('hidden');
                }, 300);
            };

            var itemOverlay = modal.querySelector('.item-modal-overlay');
            var itemClose = modal.querySelector('.item-modal-close');
            if (itemOverlay) itemOverlay.onclick = closeItem;
            if (itemClose) itemClose.onclick = closeItem;
        };
    }

    function init() {
        checkUserStatus();
        bindLogout();
        bindLoginForm();
        bindRegisterForm();
        bindUserWallet();
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