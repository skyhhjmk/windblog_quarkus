(function () {
    'use strict';

    let currentReply = null;
    let currentPage = 0;
    const pageSize = 10;
    let hasMore = true;
    const commentIndex = new Map();
    let expandListenerBound = false;

    function ready(fn) {
        if (document.readyState !== 'loading') fn();
        else document.addEventListener('DOMContentLoaded', fn);
    }

    function getCommentRoot() {
        return document.getElementById('comment-section');
    }

    function isLoggedIn(root) {
        return root && root.dataset.commentLoggedIn === 'true';
    }

    function getProfile() {
        return fetch('/user/api/profile')
            .then(r => r.ok ? r.json() : null)
            .catch(() => null);
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

    function setFeedback(root, message, type) {
        const box = root.querySelector('#comment-feedback');
        if (!box) return;

        if (!message) {
            box.textContent = '';
            box.className = 'hidden mt-4 rounded-sm border px-3 py-2 text-sm';
            return;
        }

        box.textContent = message;
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
                        展开 ${hidden.length} 条回复
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
                        <strong class="text-[var(--accent)]">${userName}</strong>
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
            const nodes = result && result.success && Array.isArray(result.data) ? result.data : [];
            const totalRoots = result.total || 0;

            if (!isAppend) {
                count.textContent = totalRoots + ' ' + (window.i18n.comments_unit || '条讨论');
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
                count.textContent = '0 条讨论';
                list.innerHTML = `<div class="comment-empty">${window.i18n.load_comments_failed || '加载评论失败。'}</div>`;
            }
        } finally {
            if (loadMoreBtn) window.setLoading(loadMoreBtn, false);
        }
    }

    async function syncLoginState(root) {
        const hint = root.querySelector('#comment-login-hint');
        const submit = root.querySelector('#comment-submit');
        const overlay = root.querySelector('#comment-auth-overlay');
        const profile = await getProfile();
        const loggedIn = !!(profile && profile.success && profile.data);
        root.dataset.commentLoggedIn = loggedIn ? 'true' : 'false';

        if (hint) {
            hint.innerHTML = loggedIn
                ? `<span class="text-xs font-bold text-gray-500">已登录为</span> <span class="text-xs font-black text-accent">${escapeHtml(profile.data.username)}</span>`
                : `<span class="text-xs font-bold text-gray-400">访客模式</span>`;
        }

        if (overlay) {
            if (loggedIn) {
                overlay.classList.add('hidden');
            } else {
                overlay.classList.remove('hidden');
            }
        }

        if (submit) {
            submit.disabled = !loggedIn;
            const text = loggedIn ? (window.i18n.submit_comment || '发表评论') : (window.i18n.login_required_btn || '请先登录');
            const textSpan = submit.querySelector('.btn-text');
            if (textSpan) {
                textSpan.textContent = text;
            } else {
                submit.textContent = text;
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

            const content = textarea ? textarea.value.trim() : '';
            if (!content) {
                window.showToast(window.i18n.content_required || 'Comment content is required.', 'error');
                setFeedback(root, window.i18n.content_required || 'Comment content is required.', 'error');
                return;
            }

            if (!isLoggedIn(root)) {
                window.showToast(window.i18n.login_before_submit || 'Please log in before submitting a comment.', 'error');
                setFeedback(root, window.i18n.login_before_submit || 'Please log in before submitting a comment.', 'error');
                if (textarea) textarea.focus();
                return;
            }

            const payload = {
                postSlug: root.dataset.postSlug,
                parentId: currentReply ? currentReply.id : null,
                content: content
            };

            window.setLoading(submit, true);
            setFeedback(root, '', 'success');

            try {
                const response = await fetch('/api/comments', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'X-XSRF-TOKEN': window.getCsrfToken()
                    },
                    body: JSON.stringify(payload)
                });
                const result = await response.json().catch(() => null);

                if (!response.ok || !result || !result.success) {
                    const message = result && result.message ? result.message : (window.i18n.submit_failed || 'Failed to submit comment.');
                    throw new Error(message);
                }

                form.reset();
                currentReply = null;
                updateReplyUi(root);
                window.showToast(window.i18n.comment_submitted || 'Comment submitted and is waiting for review.', 'success');
                setFeedback(root, window.i18n.comment_submitted || 'Comment submitted and is waiting for review.', 'success');
                
                // Keep disabled for a short duration to show success
                setTimeout(() => {
                    window.setLoading(submit, false);
                    syncLoginState(root);
                }, 2000);
            } catch (error) {
                window.showToast(error.message || (window.i18n.submit_failed || 'Failed to submit comment.'), 'error');
                setFeedback(root, error.message || (window.i18n.submit_failed || 'Failed to submit comment.'), 'error');
                window.setLoading(submit, false);
                syncLoginState(root);
            }
        };
    }

    async function initComments() {
        const root = getCommentRoot();
        if (!root) return;

        currentReply = null;
        updateReplyUi(root);
        bindForm(root);

        const loadMoreBtn = root.querySelector('#btn-load-more');
        if (loadMoreBtn) {
            loadMoreBtn.onclick = () => {
                currentPage++;
                loadComments(root, true);
            };
        }

        await syncLoginState(root);
        await loadComments(root);
    }

    window.toggleReplies = function (commentId, btn) {
        const container = document.getElementById(`replies-${commentId}`);
        if (!container || !btn) return;

        const isHidden = container.classList.contains('hidden');
        if (isHidden) {
            container.classList.remove('hidden');
            btn.textContent = '收起回复';
        } else {
            container.classList.add('hidden');
            const count = container.querySelectorAll('.comment-item').length;
            btn.textContent = `展开 ${count} 条回复`;
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
