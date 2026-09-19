// /assets/js/user.js
(function () {
    'use strict';

    function analyticsFormResult(formId, successful) {
        window.WindBlogAnalytics?.trackFormResult(formId, successful === true);
    }

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    // Lens hashes this opaque site-scoped identifier and only sends it after
    // the visitor has granted analytics consent. Never pass the username or
    // email address to the tracker.
    function syncAnalyticsIdentity(profile) {
        const tracker = window.SeeRay;
        if (!tracker || typeof tracker.setUserId !== 'function') return;
        const id = profile?.success && profile.data?.id;
        tracker.setUserId(id == null ? null : String(id));
    }


    function checkUserStatus() {
        fetch('/user/api/profile')
            .then(r => r.json())
            .then(data => {
                const loggedOutNodes = document.querySelectorAll('#userNotLoggedIn, [data-mobile-user-logged-out]');
                const loggedInNodes = document.querySelectorAll('#userLoggedIn, [data-mobile-user-logged-in]');
                const userNickname = document.getElementById('userNickname');
                const mobileNicknames = document.querySelectorAll('[data-mobile-user-nickname]');

                const ok = data && data.success && data.data;
                syncAnalyticsIdentity(data);
                if (ok) {
                    loggedOutNodes.forEach(node => {
                        node.classList.add('hidden');
                        node.style.display = 'none';
                    });
                    loggedInNodes.forEach(node => {
                        node.classList.remove('hidden');
                        node.style.display = '';
                    });
                    if (userNickname) userNickname.textContent = data.data.username || 'User';
                    mobileNicknames.forEach(node => node.textContent = data.data.username || 'User');
                } else {
                    loggedOutNodes.forEach(node => {
                        node.classList.remove('hidden');
                        node.style.display = '';
                    });
                    loggedInNodes.forEach(node => {
                        node.classList.add('hidden');
                        node.style.display = 'none';
                    });
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
        const mobileLogoutLinks = document.querySelectorAll('[data-mobile-logout]');

        const doLogout = (e) => {
            if (e) e.preventDefault();
            syncAnalyticsIdentity({success: false});
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
        mobileLogoutLinks.forEach(link => {
            link.onclick = doLogout;
        });
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
                    analyticsFormResult('login', true);
                    isRedirecting = true;
                    window.showToast(result.message || window.i18n.login_success || 'Login successful', 'success');
                    setTimeout(() => {
                        window.location.href = result.redirect || '/';
                    }, 1000);
                } else {
                    analyticsFormResult('login', false);
                    window.showToast(result.message || window.i18n.login_failed || 'Login failed', 'error');
                    if (errorDiv) {
                        errorDiv.textContent = result.message || window.i18n.login_failed || 'Login failed';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                analyticsFormResult('login', false);
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

    function bindSubscriptions() {
        const form = document.getElementById('subscriptionForm');
        if (!form) return;
        const articleUpdates = document.getElementById('subscribeArticleUpdates');
        const promotions = document.getElementById('subscribePromotions');
        const hint = document.getElementById('subscriptionVerificationHint');
        const saveButton = form.querySelector('button[type="submit"]');
        fetch('/user/api/subscriptions')
            .then(response => response.json())
            .then(result => {
                if (!result.success) throw new Error(result.message || '订阅偏好加载失败');
                articleUpdates.checked = result.data.subscribeArticleUpdates === true;
                promotions.checked = result.data.subscribePromotions === true;
                if (result.data.emailVerified !== true) {
                    articleUpdates.disabled = true;
                    promotions.disabled = true;
                    if (saveButton) saveButton.disabled = true;
                    hint.textContent = '请先完成邮箱验证后再管理订阅。';
                }
            })
            .catch(error => {
                hint.textContent = error.message || '订阅偏好加载失败，请刷新重试。';
                hint.classList.add('text-red-400');
            });
        form.onsubmit = async function (event) {
            event.preventDefault();
            window.setLoading(saveButton, true, {text: '保存中...'});
            try {
                const response = await fetch('/user/api/subscriptions', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken()},
                    body: JSON.stringify({
                        subscribeArticleUpdates: articleUpdates.checked,
                        subscribePromotions: promotions.checked
                    })
                });
                const result = await response.json();
                if (!response.ok || !result.success) throw new Error(result.message || '订阅偏好保存失败');
                analyticsFormResult('subscription-preferences', true);
                window.showToast('订阅偏好已保存', 'success');
            } catch (error) {
                analyticsFormResult('subscription-preferences', false);
                window.showToast(error.message || '订阅偏好保存失败，请稍后重试', 'error');
            } finally {
                window.setLoading(saveButton, false);
            }
        };
    }

    function bindFavoriteButton() {
        const button = document.getElementById('favoritePostBtn');
        if (!button || button.dataset.bound === 'true') return;
        button.dataset.bound = 'true';
        const slug = button.dataset.postSlug;
        const label = button.querySelector('[data-favorite-label]');
        const icon = button.querySelector('span[aria-hidden="true"]');
        if (!slug) return;

        const setState = (favorited) => {
            button.setAttribute('aria-pressed', String(favorited));
            button.classList.toggle('bg-accent/10', favorited);
            if (label) label.textContent = favorited ? '已收藏' : '收藏文章';
            if (icon) icon.textContent = favorited ? '★' : '☆';
        };

        fetch('/api/user/favorites/post/' + encodeURIComponent(slug) + '/status')
            .then(response => response.ok ? response.json() : null)
            .then(result => {
                if (result && result.success) setState(result.favorited === true);
            })
            .catch(() => {});

        button.onclick = async function () {
            if (button.disabled) return;
            const favorited = button.getAttribute('aria-pressed') === 'true';
            button.disabled = true;
            try {
                const response = await fetch('/api/user/favorites/post/' + encodeURIComponent(slug), {
                    method: favorited ? 'DELETE' : 'POST',
                    headers: {'X-XSRF-TOKEN': window.getCsrfToken()}
                });
                const result = await response.json();
                if (response.status === 401) {
                    const redirect = window.location.pathname + window.location.search + window.location.hash;
                    window.location.href = '/user/login?redirect=' + encodeURIComponent(redirect);
                    return;
                }
                if (!response.ok || !result.success) throw new Error(result.message || '收藏操作失败');
                setState(result.favorited === true);
                window.showToast(result.favorited ? '已加入收藏' : '已取消收藏', 'success');
            } catch (error) {
                window.showToast(error.message || '收藏操作失败，请稍后重试', 'error');
            } finally {
                button.disabled = false;
            }
        };
    }

    function bindUserPosts() {
        const form = document.getElementById('userPostForm');
        const list = document.getElementById('userPostList');
        if (!form || !list || form.dataset.bound === 'true') return;
        form.dataset.bound = 'true';
        const idInput = document.getElementById('userPostId');
        const titleInput = document.getElementById('userPostTitle');
        const slugInput = document.getElementById('userPostSlug');
        const summaryInput = document.getElementById('userPostSummary');
        const contentInput = document.getElementById('userPostContent');
        const saveButton = document.getElementById('saveUserPost');
        const submitButton = document.getElementById('submitUserPost');
        const resetButton = document.getElementById('resetUserPost');
        const feedback = document.getElementById('userPostFeedback');

        const showFeedback = (message, error) => {
            if (!feedback) return;
            feedback.textContent = message || '';
            feedback.classList.remove('hidden', 'border-green-500/30', 'text-green-400', 'border-red-500/30', 'text-red-400');
            feedback.classList.add(error ? 'border-red-500/30' : 'border-green-500/30');
            feedback.classList.add(error ? 'text-red-400' : 'text-green-400');
        };

        const resetForm = () => {
            idInput.value = '';
            titleInput.value = '';
            slugInput.value = '';
            summaryInput.value = '';
            contentInput.value = '';
            showFeedback('', false);
            if (feedback) feedback.classList.add('hidden');
        };

        const statusName = (status) => ({0: '草稿', 1: '已发布', 2: '已归档', 3: '待审核'}[status] || '未知状态');

        const loadPosts = async () => {
            try {
                const response = await fetch('/user/api/posts');
                const result = await response.json();
                if (!response.ok) throw new Error(result.message || '投稿列表加载失败');
                list.replaceChildren();
                if (!result.length) {
                    list.textContent = '还没有投稿，先写下第一篇文章吧。';
                    return;
                }
                result.forEach((post) => {
                    const item = document.createElement('button');
                    item.type = 'button';
                    item.className = 'w-full text-left rounded border border-[var(--border)] px-3 py-3 hover:border-[var(--accent)] transition-colors';
                    const title = document.createElement('div');
                    title.className = 'font-medium';
                    title.textContent = post.title || post.slug;
                    const meta = document.createElement('div');
                    meta.className = 'mt-1 text-xs text-gray-500';
                    meta.textContent = statusName(post.status) + ' · ' + (post.reviewNote || '点击编辑');
                    item.append(title, meta);
                    item.onclick = async () => {
                        try {
                            const detailResponse = await fetch('/user/api/posts/' + post.id);
                            const detail = await detailResponse.json();
                            if (!detailResponse.ok) throw new Error(detail.message || '投稿加载失败');
                            idInput.value = detail.id;
                            titleInput.value = detail.title || '';
                            slugInput.value = detail.slug || '';
                            summaryInput.value = detail.summary || '';
                            contentInput.value = detail.contentMarkdown || '';
                            showFeedback(detail.statusName === 'DRAFT' ? '已载入草稿' : '已载入文章；待审核或已发布文章不能直接保存', false);
                        } catch (error) {
                            showFeedback(error.message || '投稿加载失败', true);
                        }
                    };
                    list.appendChild(item);
                });
            } catch (error) {
                list.textContent = error.message || '投稿列表加载失败，请刷新重试。';
            }
        };

        const payload = () => ({
            title: titleInput.value.trim(),
            slug: slugInput.value.trim(),
            summary: summaryInput.value.trim(),
            contentMarkdown: contentInput.value
        });

        const saveDraft = async (notify) => {
            const id = idInput.value;
            const method = id ? 'PUT' : 'POST';
            const endpoint = id ? '/user/api/posts/' + id : '/user/api/posts';
            const response = await fetch(endpoint, {
                method,
                headers: {'Content-Type': 'application/json', 'X-XSRF-TOKEN': window.getCsrfToken()},
                body: JSON.stringify(payload())
            });
            const result = await response.json();
            if (!response.ok) throw new Error(result.message || '草稿保存失败');
            idInput.value = result.id;
            slugInput.value = result.slug || slugInput.value;
            if (notify) window.showToast('草稿已保存', 'success');
            await loadPosts();
            return result;
        };

        form.onsubmit = async (event) => {
            event.preventDefault();
            window.setLoading(saveButton, true, {text: '保存中...'});
            try {
                await saveDraft(true);
                analyticsFormResult('user-post-editor', true);
                showFeedback('草稿已保存，可以继续编辑或提交审核。', false);
            } catch (error) {
                analyticsFormResult('user-post-editor', false);
                showFeedback(error.message || '草稿保存失败', true);
            } finally {
                window.setLoading(saveButton, false);
            }
        };

        submitButton.onclick = async () => {
            window.setLoading(submitButton, true, {text: '提交中...'});
            try {
                if (!idInput.value) await saveDraft(false);
                const response = await fetch('/user/api/posts/' + idInput.value + '/submit', {
                    method: 'POST', headers: {'X-XSRF-TOKEN': window.getCsrfToken()}
                });
                const result = await response.json();
                if (!response.ok) throw new Error(result.message || '提交审核失败');
                analyticsFormResult('user-post-editor', true);
                window.showToast('文章已提交审核', 'success');
                showFeedback('已提交审核，审核结果会通过站内通知告知你。', false);
                await loadPosts();
            } catch (error) {
                analyticsFormResult('user-post-editor', false);
                showFeedback(error.message || '提交审核失败', true);
            } finally {
                window.setLoading(submitButton, false);
            }
        };

        resetButton.onclick = resetForm;
        loadPosts();
    }

    function bindFavoriteList() {
        const list = document.getElementById('favoritePostsList');
        if (!list || list.dataset.bound === 'true') return;
        list.dataset.bound = 'true';
        fetch('/api/user/favorites?page=1&pageSize=50')
            .then(response => response.json().then(result => ({response, result})))
            .then(({response, result}) => {
                if (!response.ok || !result.success) throw new Error(result.message || '收藏加载失败');
                list.replaceChildren();
                if (!result.data || !result.data.length) {
                    list.textContent = '还没有收藏文章。';
                    return;
                }
                result.data.forEach((post) => {
                    const link = document.createElement('a');
                    link.href = '/post/' + encodeURIComponent(post.slug);
                    link.className = 'block rounded border border-[var(--border)] px-3 py-3 hover:border-[var(--accent)] transition-colors';
                    link.textContent = post.title || post.slug;
                    list.appendChild(link);
                });
            })
            .catch(error => { list.textContent = error.message || '收藏加载失败，请刷新重试。'; });
    }

    function bindUserNotifications() {
        const list = document.getElementById('userNotificationsList');
        const unreadCount = document.getElementById('notificationUnreadCount');
        const readAllButton = document.getElementById('markAllNotificationsRead');
        if (!list || list.dataset.bound === 'true') return;
        list.dataset.bound = 'true';

        const load = () => fetch('/user/api/notifications?page=1&pageSize=50')
            .then(response => response.json().then(result => ({response, result})))
            .then(({response, result}) => {
                if (!response.ok || !result.success) throw new Error(result.message || '通知加载失败');
                unreadCount.textContent = result.unread ? '(' + result.unread + ' 未读)' : '';
                list.replaceChildren();
                if (!result.data || !result.data.length) {
                    list.textContent = '暂无通知。';
                    return;
                }
                result.data.forEach((notification) => {
                    const item = document.createElement('div');
                    item.className = 'rounded border px-3 py-3 cursor-pointer hover:border-[var(--accent)] transition-colors ' + (notification.readAt ? 'border-[var(--border)]' : 'border-[var(--accent)]/50');
                    const title = document.createElement('div');
                    title.className = 'font-medium text-[var(--text)]';
                    title.textContent = notification.title;
                    const body = document.createElement('div');
                    body.className = 'mt-1 text-gray-400';
                    body.textContent = notification.body;
                    item.append(title, body);
                    item.onclick = async () => {
                        await fetch('/user/api/notifications/' + notification.id + '/read', {method: 'POST', headers: {'X-XSRF-TOKEN': window.getCsrfToken()}});
                        if (notification.targetUrl && notification.targetUrl.startsWith('/')) window.location.href = notification.targetUrl;
                        else await load();
                    };
                    list.appendChild(item);
                });
            })
            .catch(error => { list.textContent = error.message || '通知加载失败，请刷新重试。'; });

        if (readAllButton) {
            readAllButton.onclick = async () => {
                await fetch('/user/api/notifications/read-all', {method: 'POST', headers: {'X-XSRF-TOKEN': window.getCsrfToken()}});
                await load();
            };
        }
        load();
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
                if (input.type !== 'checkbox' || input.checked) {
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
                    analyticsFormResult('register', true);
                    isRedirecting = true;
                    window.showToast(result.message || window.i18n.register_success || 'Registration successful', 'success');
                    // 延迟跳转，让用户看到成功提示
                    setTimeout(() => {
                        window.location.href = result.redirect || '/';
                    }, 1500);
                } else {
                    analyticsFormResult('register', false);
                    window.showToast(result.message || window.i18n.register_failed || 'Registration failed', 'error');
                    if (errorDiv) {
                        errorDiv.textContent = result.message || window.i18n.register_failed || 'Registration failed';
                        errorDiv.classList.remove('hidden');
                    }
                }
            } catch (err) {
                analyticsFormResult('register', false);
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
                    var desc = escapeHtml(t.description || t.bizType);
                    var dateStr = new Date(t.createdAt).toLocaleString();

                    return '<div class="group flex justify-between items-center p-4 border border-border/50 bg-bg/20 hover:bg-bg/40 hover:border-accent/30 rounded-xl transition-all duration-300">' +
                        '<div class="flex items-center gap-4">' +
                        '<div class="w-10 h-10 rounded-full flex items-center justify-center font-bold wallet-amount-icon" data-wallet-background="' + amountBg + '" data-wallet-color="' + amountColor + '">' +
                        (t.changeAmount > 0 ? '↑' : '↓') +
                        '</div>' +
                        '<div>' +
                        '<div class="font-bold text-main group-hover:text-accent transition-colors">' + desc + '</div>' +
                        '<div class="text-[11px] text-meta mt-0.5 font-mono">' + escapeHtml(dateStr) + '</div>' +
                        '</div>' +
                        '</div>' +
                        '<div class="text-right">' +
                        '<div class="font-black text-xl tracking-tight wallet-amount-value" data-wallet-color="' + amountColor + '">' + prefix + escapeHtml(t.changeAmount) + '</div>' +
                        '<div class="text-[10px] text-meta font-mono tracking-tighter mt-0.5 opacity-60">BALANCE: ' + escapeHtml(t.balanceAfter) + '</div>' +
                        '</div>' +
                        '</div>';
                }).join('');
                applyWalletColors(list);

                updateWalletPagination();
            } else {
                list.innerHTML = '<div class="text-center py-20"><div class="text-6xl mb-4 opacity-10">∅</div><p class="text-meta font-medium">暂无积分变动记录</p></div>';
                updateWalletPagination();
            }
        } catch (e) {
            list.innerHTML = '<div class="text-center py-20"><div class="text-red-400/20 text-6xl mb-4">!</div><p class="text-red-400 text-sm">加载失败，请检查网络连接</p></div>';
        }
    }

    function escapeHtml(value) {
        return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
            var entities = {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'};
            return entities[character];
        });
    }

    function safeColor(value) {
        var color = String(value || '').trim();
        if (/^#[0-9a-fA-F]{3,8}$/.test(color)) {
            return color;
        }
        return 'var(--accent)';
    }

    function applyWalletColors(container) {
        container.querySelectorAll('[data-wallet-background]').forEach(function (element) {
            element.style.backgroundColor = safeColor(element.dataset.walletBackground);
            element.style.color = safeColor(element.dataset.walletColor);
        });
        container.querySelectorAll('[data-wallet-color]').forEach(function (element) {
            element.style.color = safeColor(element.dataset.walletColor);
        });
    }

    function applyDynamicPublicStyles() {
        document.querySelectorAll('.exp-progress-bar[data-exp-percent]').forEach(function (element) {
            var percent = Number.parseFloat(element.dataset.expPercent);
            if (!Number.isFinite(percent)) {
                percent = 0;
            }
            percent = Math.max(0, Math.min(100, percent));
            element.style.width = percent + '%';
        });
        document.querySelectorAll('[data-backpack-item]').forEach(function (element) {
            element.style.setProperty('--backpack-rarity', safeColor(element.dataset.itemRarity));
        });
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
        bindForgotPasswordForm();
        bindResendVerificationForm();
        bindResetPasswordForm();
        bindUserWallet();
        bindBackpackItems();
        bindSubscriptions();
        bindFavoriteButton();
        bindUserPosts();
        bindFavoriteList();
        bindUserNotifications();
        applyDynamicPublicStyles();
    }

    function bindForgotPasswordForm() {
        const form = document.getElementById('forgotPasswordForm');
        const message = document.getElementById('forgotPasswordMessage');
        if (!form) return;

        form.onsubmit = async function (event) {
            event.preventDefault();
            const button = form.querySelector('button[type="submit"]');
            window.setLoading(button, true);
            try {
                const response = await fetch('/user/api/forgot-password', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    },
                    body: new URLSearchParams(new FormData(form)).toString()
                });
                const result = await response.json();
                if (message) {
                    message.textContent = result.message || '请求已提交';
                    message.classList.remove('hidden');
                }
                analyticsFormResult('forgot-password', result.success === true);
                window.showToast(result.message || '请求已提交', result.success ? 'success' : 'error');
            } catch (_) {
                analyticsFormResult('forgot-password', false);
                window.showToast(window.i18n.network_error || '网络错误，请稍后重试', 'error');
            } finally {
                window.setLoading(button, false);
            }
        };
    }

    function bindResetPasswordForm() {
        const form = document.getElementById('resetPasswordForm');
        const message = document.getElementById('resetPasswordMessage');
        if (!form) return;

        form.onsubmit = async function (event) {
            event.preventDefault();
            const password = form.querySelector('[name="password"]');
            const confirmPassword = form.querySelector('[name="confirmPassword"]');
            if (password && confirmPassword && password.value !== confirmPassword.value) {
                if (message) {
                    message.textContent = window.i18n.password_mismatch || '两次输入的密码不一致';
                    message.classList.remove('hidden');
                }
                return;
            }
            const button = form.querySelector('button[type="submit"]');
            window.setLoading(button, true);
            try {
                const response = await fetch('/user/api/reset-password', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    },
                    body: new URLSearchParams(new FormData(form)).toString()
                });
                const result = await response.json();
                if (!result.success) throw new Error(result.message || '密码重置失败');
                analyticsFormResult('reset-password', true);
                window.showToast(result.message || '密码已重置', 'success');
                window.setTimeout(() => {
                    window.location.href = result.redirect || '/user/login';
                }, 800);
            } catch (error) {
                analyticsFormResult('reset-password', false);
                if (message) {
                    message.textContent = error.message || '密码重置失败';
                    message.classList.remove('hidden');
                }
                window.showToast(error.message || '密码重置失败', 'error');
            } finally {
                window.setLoading(button, false);
            }
        };
    }

    function bindResendVerificationForm() {
        const form = document.getElementById('resendVerificationForm');
        if (!form) return;
        form.onsubmit = async function (event) {
            event.preventDefault();
            const button = form.querySelector('button[type="submit"]');
            window.setLoading(button, true);
            try {
                const response = await fetch('/user/api/resend-verification', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    },
                    body: new URLSearchParams(new FormData(form)).toString()
                });
                const result = await response.json();
                analyticsFormResult('resend-verification', result.success === true);
                window.showToast(result.message || '请求已提交', result.success ? 'success' : 'error');
            } catch (_) {
                analyticsFormResult('resend-verification', false);
                window.showToast(window.i18n.network_error || '网络错误，请稍后重试', 'error');
            } finally {
                window.setLoading(button, false);
            }
        };
    }

    function bindBackpackItems() {
        document.querySelectorAll('[data-backpack-item]').forEach((item) => {
            item.onclick = () => window.showItemDetails(
                item.dataset.itemId,
                item.dataset.itemName,
                item.dataset.itemDescription,
                item.dataset.itemRarity,
                item.dataset.itemType
            );
        });
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
