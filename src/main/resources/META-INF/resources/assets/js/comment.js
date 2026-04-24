(function () {
    'use strict';

    let currentReply = null;

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

    function renderComment(node) {
        const children = (node.replies || []).map(renderComment).join('');
        const userName = escapeHtml(node.userName || 'Guest');
        const body = node.contentHtml || '';

        return `
            <article class="comment-item">
                <div class="comment-meta">
                    <div class="flex items-center gap-3">
                        <strong class="text-[var(--accent)]">${userName}</strong>
                        <time class="timestamp text-xs opacity-70" data-timestamp="${escapeHtml(node.createdAt)}">${escapeHtml(node.createdAt)}</time>
                    </div>
                    <button
                        type="button"
                        class="comment-reply-btn text-sm text-[var(--warning)] hover:underline"
                        data-comment-id="${node.id}">
                        ${window.i18n.reply || 'Reply'}
                    </button>
                </div>
                <div class="comment-body prose max-w-none">${body}</div>
                ${children ? `<div class="comment-children space-y-4">${children}</div>` : ''}
            </article>
        `;
    }

    function bindReplyButtons(root, nodes) {
        const buttons = root.querySelectorAll('.comment-reply-btn');
        const index = new Map();

        function walk(list) {
            (list || []).forEach(node => {
                index.set(String(node.id), node);
                walk(node.replies || []);
            });
        }

        walk(nodes);

        buttons.forEach(button => {
            button.onclick = () => {
                const node = index.get(button.dataset.commentId);
                if (!node) return;
                currentReply = { id: node.id, userName: node.userName || 'Guest' };
                updateReplyUi(root);
                const textarea = root.querySelector('#comment-content');
                if (textarea) textarea.focus();
            };
        });
    }

    async function loadComments(root) {
        const slug = root.dataset.postSlug;
        const list = root.querySelector('#comment-list');
        const count = root.querySelector('#comment-count');

        if (!slug || !list || !count) return;

        list.innerHTML = `<div class="comment-empty">${window.i18n.loading_comments || 'Loading comments...'}</div>`;

        try {
            const response = await fetch('/api/comments/post/' + encodeURIComponent(slug));
            const result = await response.json();
            const nodes = result && result.success && Array.isArray(result.data) ? result.data : [];
            const total = countComments(nodes);

            count.textContent = total + ' ' + (window.i18n.comments_unit || 'comments');

            if (!nodes.length) {
                list.innerHTML = `<div class="comment-empty">${window.i18n.no_comments_yet || 'No comments yet. Be the first to write one.'}</div>`;
                return;
            }

            list.innerHTML = nodes.map(renderComment).join('');
            bindReplyButtons(root, nodes);
        } catch (error) {
            count.textContent = '0 comments';
            list.innerHTML = `<div class="comment-empty">${window.i18n.load_comments_failed || 'Failed to load comments.'}</div>`;
        }
    }

    async function syncLoginState(root) {
        const hint = root.querySelector('#comment-login-hint');
        const submit = root.querySelector('#comment-submit');
        const profile = await getProfile();
        const loggedIn = !!(profile && profile.success && profile.data);
        root.dataset.commentLoggedIn = loggedIn ? 'true' : 'false';

        if (hint) {
            hint.innerHTML = loggedIn
                ? `${window.i18n.signed_in_as || 'Signed in as'} <span class="text-[var(--accent)]">${escapeHtml(profile.data.username)}</span>`
                : (window.i18n.login_required || 'Login is required before submission.');
        }

        if (submit) {
            submit.disabled = false;
            submit.textContent = loggedIn ? (window.i18n.submit_comment || 'Submit Comment') : (window.i18n.login_required_btn || 'Login Required');
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
                    headers: { 'Content-Type': 'application/json' },
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
        await syncLoginState(root);
        await loadComments(root);
    }

    ready(() => {
        initComments();
        document.addEventListener('pjax:complete', initComments);
    });
})();
