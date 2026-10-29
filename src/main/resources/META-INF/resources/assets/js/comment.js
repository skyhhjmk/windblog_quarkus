(function () {
    'use strict';

    let currentReply = null;
    let currentPage = 0;
    const pageSize = 10;
    let hasMore = true;
    const commentIndex = new Map();
    let expandListenerBound = false;
    const guestInfoStorageKey = 'windblog.comment.guest-info.v1';

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function getCommentRoot() {
        return document.getElementById('comment-section');
    }

    function getProfile() {
        return fetch('/user/api/profile', {signal: AbortSignal.timeout(10000)})
            .then(r => r.ok ? r.json() : null)
            .catch(() => null);
    }

    function updateSubmitState(root) {
        const submit = root.querySelector('#comment-submit');
        if (!submit) return;
        submit.disabled = !root.dataset.captchaId
            || root.dataset.commentSubmitting === 'true';
    }

    function readSavedGuestInfo() {
        try {
            const saved = JSON.parse(localStorage.getItem(guestInfoStorageKey) || 'null');
            return saved && typeof saved === 'object' ? saved : null;
        } catch (_) {
            return null;
        }
    }

    function saveGuestInfo(root) {
        const remember = root.querySelector('#comment-remember-info');
        if (!remember) return;
        try {
            if (!remember.checked) {
                localStorage.removeItem(guestInfoStorageKey);
                return;
            }
            const info = {
                name: root.querySelector('#comment-guest-name')?.value || '',
                email: root.querySelector('#comment-guest-email')?.value || '',
                website: root.querySelector('#comment-guest-website')?.value || ''
            };
            if (Object.values(info).some(value => value.trim())) {
                localStorage.setItem(guestInfoStorageKey, JSON.stringify(info));
            }
        } catch (_) {
            // Storage can be unavailable in private browsing or restricted contexts.
        }
    }

    function restoreGuestInfo(root) {
        const remember = root.querySelector('#comment-remember-info');
        const name = root.querySelector('#comment-guest-name');
        const email = root.querySelector('#comment-guest-email');
        const website = root.querySelector('#comment-guest-website');
        const saved = readSavedGuestInfo();
        if (!remember || !saved) return;
        remember.checked = true;
        if (name && typeof saved.name === 'string') name.value = saved.name.slice(0, 80);
        if (email && typeof saved.email === 'string') email.value = saved.email.slice(0, 255);
        if (website && typeof saved.website === 'string') website.value = saved.website.slice(0, 2048);
    }

    function bindGuestInfo(root) {
        const form = root.querySelector('#comment-form');
        if (!form || form.dataset.guestInfoBound === 'true') return;
        form.dataset.guestInfoBound = 'true';
        restoreGuestInfo(root);
        const remember = root.querySelector('#comment-remember-info');
        if (remember) remember.addEventListener('change', () => saveGuestInfo(root));
        ['#comment-guest-name', '#comment-guest-email', '#comment-guest-website'].forEach(selector => {
            const input = root.querySelector(selector);
            if (input) input.addEventListener('input', () => saveGuestInfo(root));
        });
    }

    function escapeHtml(value) {
        return String(value ?? '')
            .replaceAll('&', '&amp;')
            .replaceAll('<', '&lt;')
            .replaceAll('>', '&gt;')
            .replaceAll('"', '&quot;')
            .replaceAll("'", '&#39;');
    }

    function formatTime(value) {
        if (!value) return '';
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) return value;
        return date.toLocaleString();
    }

    function setFeedback(root, message, type, errorCode = 'WB-UI-UNCLASSIFIED-001') {
        const box = root.querySelector('#comment-feedback');
        if (!box) return;

        if (!message) {
            box.textContent = '';
            box.className = 'hidden mt-4 rounded-sm border px-3 py-2 text-sm';
            return;
        }

        box.textContent = type === 'error' && window.formatErrorMessage
            ? window.formatErrorMessage(message, errorCode)
            : message;
        box.className = 'mt-4 rounded-sm border px-3 py-2 text-sm ' +
            (type === 'error' ? 'comment-feedback-error' : 'comment-feedback-success');
    }

    function updateReplyUi(root) {
        const replyBox = root.querySelector('#comment-replying');
        const replyUser = root.querySelector('#comment-replying-user');
        const parentInput = root.querySelector('#comment-parent-id');

        if (!replyBox || !replyUser || !parentInput) return;

        if (!currentReply) {
            replyBox.classList.add('hidden');
            replyUser.textContent = '';
            parentInput.value = '';
            return;
        }

        replyUser.textContent = currentReply.userName;
        parentInput.value = currentReply.id;
        replyBox.classList.remove('hidden');
    }

    function countComments(nodes) {
        return (nodes || []).reduce((total, node) => total + 1 + countComments(node.replies || []), 0);
    }

    function renderClient(node) {
        const osIcons = {Windows: 'windows', macOS: 'apple', iOS: 'apple', Android: 'android', Linux: 'linux', ChromeOS: 'chrome'};
        const browserIcons = {Edge: 'edge', Chrome: 'chrome', Firefox: 'firefox-browser', Safari: 'safari', Opera: 'opera', 'Internet Explorer': 'internet-explorer'};
        const os = Object.hasOwn(osIcons, node.clientOs) ? node.clientOs : null;
        const browser = Object.hasOwn(browserIcons, node.clientBrowser) ? node.clientBrowser : null;
        if (!os && !browser) return '';
        const mark = (label, icon) => `<i class="fa-brands fa-${icon}" aria-hidden="true"></i><span>${escapeHtml(label)}</span>`;
        return `<span class="comment-client">${os ? mark(os, osIcons[os]) : ''}${os && browser ? '<span class="comment-client-divider" aria-hidden="true">|</span>' : ''}${browser ? mark(browser, browserIcons[browser]) : ''}</span>`;
    }

    function authorHtml(node, name) {
        try {
            const url = new URL(node.guestWebsite);
            if (['https:', 'http:'].includes(url.protocol) && !url.username && !url.password) {
                return `<a class="comment-author-link" href="${escapeHtml(url.href)}" target="_blank" rel="nofollow ugc noopener noreferrer">${name}</a>`;
            }
        } catch (_) { /* Older comments have no website. */ }
        return name;
    }

    function renderComment(node, depth = 0) {
        const userName = escapeHtml(node.userName || 'Guest');
        const body = node.contentHtml || '';
        const replies = node.replies || [];

        let childrenHtml = '';
        let expandBtnHtml = '';

        const nextDepth = depth + 1;
        const isMini = depth > 0;

        // Add to global index for reply functionality
        commentIndex.set(String(node.id), node);

        if (replies.length > 0) {
            // Apply truncation for any comment with many direct replies
            if (replies.length > 2) {
                const visible = replies.slice(0, 2);
                const hidden = replies.slice(2);

                childrenHtml = visible.map(n => renderComment(n, nextDepth)).join('');
                childrenHtml += `
                    <div class="hidden-replies hidden" id="replies-${node.id}">
                        ${hidden.map(n => renderComment(n, nextDepth)).join('')}
                    </div>
                `;
                expandBtnHtml = `
                    <button type="button" class="expand-replies-btn" data-comment-id="${node.id}">
                        ${window.wbTf('js.expand_replies', hidden.length, '展开 {0} 条回复')}
                    </button>
                `;
            } else {
                childrenHtml = replies.map(n => renderComment(n, nextDepth)).join('');
            }
        }

        const commentHtml = `
            <article class="comment-item card card-static ${isMini ? 'comment-mini' : ''}" id="comment-${node.id}">
                <div class="comment-meta">
                    <div class="flex items-center gap-3">
                        <strong class="text-[var(--accent)]">${authorHtml(node, userName)}</strong>
                        ${renderClient(node)}
                        <time class="timestamp text-xs opacity-70" data-timestamp="${escapeHtml(node.createdAt)}">${escapeHtml(node.createdAt)}</time>
                    </div>
                </div>
                <div class="comment-body prose max-w-none">${body}</div>
                <div class="comment-footer">
                    <button
                        type="button"
                        class="comment-reply-btn"
                        data-comment-id="${node.id}">
                        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 17 4 12 9 7"></polyline><path d="M20 18v-2a4 4 0 0 0-4-4H4"></path></svg>
                        ${window.i18n.reply || 'Reply'}
                    </button>
                </div>
            </article>
        `;

        if (childrenHtml) {
            // Strict visual cap at 2 levels of indentation
            if (depth < 2) {
                return commentHtml + `<div class="comment-children space-y-4">${childrenHtml}${expandBtnHtml}</div>`;
            } else {
                return commentHtml + childrenHtml + expandBtnHtml;
            }
        }
        return commentHtml;
    }

    function bindReplyButtons(root) {
        const buttons = root.querySelectorAll('.comment-reply-btn');
        buttons.forEach(button => {
            button.onclick = () => {
                const node = commentIndex.get(button.dataset.commentId);
                if (!node) return;
                currentReply = { id: node.id, userName: node.userName || 'Guest' };
                updateReplyUi(root);
                const textarea = root.querySelector('#comment-content');
                if (textarea) textarea.focus();
            };
        });
    }

    async function loadComments(root, isAppend = false) {
        const slug = root.dataset.postSlug;
        const list = root.querySelector('#comment-list');
        const count = root.querySelector('#comment-count');
        const loadMoreBox = root.querySelector('#comment-load-more');
        const loadMoreBtn = root.querySelector('#btn-load-more');
        const loginLink = root.querySelector('.comment-login-link');
        if (loginLink) {
            const redirect = window.location.pathname + window.location.search + window.location.hash;
            loginLink.href = '/user/login?redirect=' + encodeURIComponent(redirect);
        }

        if (!slug || !list || !count) return;

        if (!isAppend) {
            currentPage = 0;
            commentIndex.clear();
            list.innerHTML = `<div class="comment-empty">${window.i18n.loading_comments || '正在加载评论...'}</div>`;
        }

        if (loadMoreBtn) window.setLoading(loadMoreBtn, true);

        try {
            const url = `/api/comments/post/${encodeURIComponent(slug)}?page=${currentPage}&size=${pageSize}`;
            const response = await fetch(url);
            const result = await response.json();
            if (!response.ok || !result?.success) {
                throw new Error(result?.message || window.wbT('js.comments_request_failed', '评论列表请求失败'));
            }
            const nodes = result && result.success && Array.isArray(result.data) ? result.data : [];
            const totalRoots = result.total || 0;

            if (!isAppend) {
                count.textContent = totalRoots + ' ' + (window.i18n.comments_unit || window.wbT('js.discussions', '条讨论'));
                list.innerHTML = '';
            }

            if (!nodes.length && !isAppend) {
                list.innerHTML = `<div class="comment-empty">${window.i18n.no_comments_yet || '暂无评论。'}</div>`;
                if (loadMoreBox) loadMoreBox.classList.add('hidden');
                return;
            }

            const html = nodes.map(n => renderComment(n, 0)).join('');
            list.insertAdjacentHTML('beforeend', html);
            bindReplyButtons(root);

            hasMore = (currentPage + 1) * pageSize < totalRoots;
            if (loadMoreBox) {
                if (hasMore) loadMoreBox.classList.remove('hidden');
                else loadMoreBox.classList.add('hidden');
            }
        } catch (error) {
            if (!isAppend) {
                count.textContent = '0 ' + window.wbT('js.discussions', '条讨论');
                const errorMessage = window.formatErrorMessage
                    ? window.formatErrorMessage(error.message || window.i18n.load_comments_failed || '加载评论失败。',
                        window.getErrorCode?.(error.message, 'WB-UI-COMMENT-LOAD-001'))
                    : (error.message || window.i18n.load_comments_failed || '加载评论失败。');
                const errorBox = document.createElement('div');
                errorBox.className = 'comment-empty';
                errorBox.textContent = errorMessage;
                list.replaceChildren(errorBox);
            }
        } finally {
            if (loadMoreBtn) window.setLoading(loadMoreBtn, false);
        }
    }

    async function syncLoginState(root) {
        const hint = root.querySelector('#comment-login-hint');
        const profile = await getProfile();
        const loggedIn = !!(profile && profile.success && profile.data);
        root.dataset.commentLoggedIn = loggedIn ? 'true' : 'false';

        if (hint) {
            hint.innerHTML = loggedIn
                ? `<span class="text-xs font-bold text-gray-500">${window.wbT('js.signed_in_as', '已登录为')}</span> <span class="text-xs font-black text-accent">${escapeHtml(profile.data.username)}</span>`
                : `<span class="text-xs font-bold text-gray-400">${window.wbT('js.guest_mode', '访客模式')}</span>`;
        }

        const guestFields = root.querySelector('#comment-guest-fields');
        if (guestFields) {
            guestFields.classList.toggle('hidden', loggedIn);
            guestFields.querySelectorAll('input').forEach(input => { input.disabled = loggedIn; });
        }
    }

    async function loadCaptcha(root) {
        const question = root.querySelector('#comment-captcha-question');
        const answer = root.querySelector('#comment-captcha-answer');
        const version = (Number(root.dataset.captchaVersion) || 0) + 1;
        root.dataset.captchaVersion = String(version);
        root.dataset.captchaId = '';
        if (!question || !answer) return;
        question.textContent = window.wbT('js.loading_short', '正在加载...');
        updateSubmitState(root);
        answer.value = '';
        try {
            const response = await fetch('/api/comments/captcha', {cache: 'no-store', credentials: 'same-origin', headers: {'Accept': 'application/json'}, signal: AbortSignal.timeout(10000)});
            const result = await response.json().catch(() => null);
            if (root.dataset.captchaVersion !== String(version)) return;
            if (!response.ok || !result?.success || !result.data?.id || !result.data?.question) {
                const message = result?.message || window.wbTf('js.http_error', response.status, '验证码服务返回 HTTP {0}，未提供错误详情');
                const failure = new Error(message);
                failure.errorCode = result?.errorCode || response.headers.get('X-WindBlog-Error-Code');
                throw failure;
            }
            root.dataset.captchaId = result.data.id;
            question.textContent = result.data.question;
            updateSubmitState(root);
        } catch (error) {
            if (root.dataset.captchaVersion === String(version)) {
                const fallbackMessage = error.name === 'TimeoutError'
                    ? window.wbT('js.captcha_timeout', '验证码加载超时，请重试')
                    : error.message === 'Failed to fetch'
                        ? window.wbT('js.captcha_network', '网络连接失败，未能加载验证码')
                        : window.wbT('js.captcha_loading_failed', '验证码加载失败，请重试');
                const message = error.message && error.message !== 'Failed to fetch'
                    && error.name !== 'TimeoutError' ? error.message : fallbackMessage;
                const errorCode = error.errorCode
                    || window.getErrorCode?.(message, 'WB-UI-COMMENT-CAPTCHA-002');
                question.textContent = window.formatErrorMessage
                    ? window.formatErrorMessage(message, errorCode)
                    : message;
            }
        }
    }

    function bindForm(root) {
        const form = root.querySelector('#comment-form');
        const cancelReply = root.querySelector('#comment-cancel-reply');
        const textarea = root.querySelector('#comment-content');
        const submit = root.querySelector('#comment-submit');

        if (cancelReply) {
            cancelReply.onclick = () => {
                currentReply = null;
                updateReplyUi(root);
            };
        }

        if (!form || form.dataset.bound === 'true') return;
        form.dataset.bound = 'true';

        form.onsubmit = async function (event) {
            event.preventDefault();
            event.stopPropagation();

            if (root.dataset.commentSubmitting === 'true') return;
            if (!root.dataset.captchaId) {
                setFeedback(root, window.wbT('js.captcha_required', '请先加载验证码，点击换一道题重试。'), 'error', 'WB-UI-COMMENT-CAPTCHA-001');
                return;
            }

            const content = textarea ? textarea.value.trim() : '';
            if (!content) {
                const message = window.i18n.content_required || '请填写评论内容。';
                window.showToast(message, 'error', 8000, 'WB-UI-COMMENT-CONTENT-001');
                setFeedback(root, message, 'error', 'WB-UI-COMMENT-CONTENT-001');
                return;
            }

            if (!form.reportValidity()) return;
            if (!root.dataset.captchaId) {
                setFeedback(root, window.wbT('js.captcha_required', '请先加载验证码，点击换一道题重试。'), 'error', 'WB-UI-COMMENT-CAPTCHA-001');
                return;
            }

            const payload = {
                postSlug: root.dataset.postSlug,
                parentId: currentReply ? currentReply.id : null,
                content: content,
                guestName: root.querySelector('#comment-guest-name')?.value.trim() || '',
                guestEmail: root.querySelector('#comment-guest-email')?.value.trim() || '',
                guestWebsite: root.querySelector('#comment-guest-website')?.value.trim() || '',
                captchaId: root.dataset.captchaId,
                captchaAnswer: root.querySelector('#comment-captcha-answer')?.value.trim() || ''
            };

            saveGuestInfo(root);
            root.dataset.commentSubmitting = 'true';
            updateSubmitState(root);
            window.setLoading(submit, true);
            setFeedback(root, '', 'success');

            try {
                const response = await fetch('/api/comments', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    },
                    body: JSON.stringify(payload)
                });
                const result = await response.json().catch(() => null);

                if (!response.ok || !result || !result.success) {
                    const message = result?.message
                        || `服务器返回 HTTP ${response.status}，但未提供错误详情`;
                    const error = new Error(message);
                    error.errorCode = result?.errorCode || response.headers.get('X-WindBlog-Error-Code')
                        || window.getErrorCode?.(message, 'WB-UI-COMMENT-SUBMIT-001');
                    throw error;
                }

                form.reset();
                restoreGuestInfo(root);
                currentReply = null;
                updateReplyUi(root);
                window.WindBlogAnalytics?.trackFormResult('comment', true);
                window.showToast(window.i18n.comment_submitted || 'Comment submitted and is waiting for review.', 'success');
                setFeedback(root, window.i18n.comment_submitted || 'Comment submitted and is waiting for review.', 'success');
                
                // Keep disabled for a short duration to show success
                setTimeout(() => {
                    root.dataset.commentSubmitting = 'false';
                    root.dataset.captchaId = '';
                    window.setLoading(submit, false);
                    updateSubmitState(root);
                    loadCaptcha(root);
                }, 2000);
            } catch (error) {
                window.WindBlogAnalytics?.trackFormResult('comment', false);
                const detail = error.message === 'Failed to fetch'
                    ? '网络连接失败，浏览器未能连接到服务器'
                    : (error.message || '网络请求失败，未能收到服务器响应');
                const message = detail.startsWith('评论提交失败') ? detail : window.wbTf('js.submit_failed_detail', detail, '评论提交失败：{0}');
                const errorCode = error.errorCode || window.getErrorCode?.(message, 'WB-UI-COMMENT-SUBMIT-001');
                window.showToast(message, 'error', 8000, errorCode);
                setFeedback(root, message, 'error', errorCode);
                root.dataset.commentSubmitting = 'false';
                window.setLoading(submit, false);
                updateSubmitState(root);
                loadCaptcha(root);
            }
        };
    }

    async function initComments() {
        const root = getCommentRoot();
        if (!root) return;

        currentReply = null;
        updateReplyUi(root);
        root.dataset.commentSubmitting = 'false';
        bindGuestInfo(root);
        bindForm(root);

        const loadMoreBtn = root.querySelector('#btn-load-more');
        if (loadMoreBtn) {
            loadMoreBtn.onclick = () => {
                currentPage++;
                loadComments(root, true);
            };
        }

        const refresh = root.querySelector('#comment-captcha-refresh');
        if (refresh) refresh.onclick = () => loadCaptcha(root);
        // Each request can finish independently; a stalled profile must not block the challenge or list.
        await Promise.allSettled([syncLoginState(root), loadCaptcha(root), loadComments(root)]);
    }

    window.toggleReplies = function (commentId, btn) {
        const container = document.getElementById(`replies-${commentId}`);
        if (!container || !btn) return;

        const isHidden = container.classList.contains('hidden');
        if (isHidden) {
            container.classList.remove('hidden');
            btn.textContent = window.wbT('js.collapse_replies', '收起回复');
        } else {
            container.classList.add('hidden');
            const count = container.querySelectorAll('.comment-item').length;
            btn.textContent = window.wbTf('js.expand_replies', count, '展开 {0} 条回复');
        }
    };

    ready(() => {
        initComments();
        if (!expandListenerBound) {
            document.addEventListener('click', (event) => {
                const button = event.target.closest('.expand-replies-btn');
                if (!button) return;
                window.toggleReplies(button.dataset.commentId, button);
            });
            expandListenerBound = true;
        }
        document.addEventListener('pjax:complete', initComments);
    });
})();
