(function () {
    'use strict';

    function showToast(message) {
        let toast = document.getElementById('wb-global-toast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'wb-global-toast';
            toast.className = 'fixed bottom-6 right-6 z-50 px-4 py-2.5 rounded-xl bg-card border border-accent/40 text-main text-xs font-mono font-bold shadow-lg transition-all duration-300 transform translate-y-4 opacity-0 pointer-events-none flex items-center gap-2';
            toast.innerHTML = '<span class="w-2 h-2 rounded-full bg-accent animate-ping"></span><span id="wb-toast-message"></span>';
            document.body.appendChild(toast);
        }
        const msgEl = toast.querySelector('#wb-toast-message');
        if (msgEl) msgEl.textContent = message;
        toast.classList.remove('translate-y-4', 'opacity-0', 'pointer-events-none');
        toast.classList.add('translate-y-0', 'opacity-100');

        clearTimeout(toast._timeout);
        toast._timeout = setTimeout(function () {
            toast.classList.add('translate-y-4', 'opacity-0', 'pointer-events-none');
            toast.classList.remove('translate-y-0', 'opacity-100');
        }, 2200);
    }

    function initModeFilter() {
        // Mode filter is now handled server-side via localized PJAX navigation
        // preserving pagination states and mode query parameters.
    }

    function initTutorialTracker() {
        const tracker = document.getElementById('tutorial-tracker-widget');
        if (!tracker) return;

        const slug = tracker.dataset.postSlug;
        if (!slug) return;

        const stepsContainer = document.getElementById('tutorial-steps-container');
        const progressText = document.getElementById('tutorial-progress-text');
        const progressPercent = document.getElementById('tutorial-progress-percent');
        const progressFill = document.getElementById('tutorial-progress-fill');
        const resetBtn = document.getElementById('btn-reset-tutorial-progress');

        const postBody = document.getElementById('post-content-section');
        if (!postBody || !stepsContainer) return;

        const headings = postBody.querySelectorAll('h2, [data-tutorial-step]');
        if (headings.length === 0) {
            stepsContainer.innerHTML = '<p class="text-xs text-meta italic">未检测到具体步骤小节</p>';
            return;
        }

        const storageKey = 'wb_tutorial_step_' + slug;
        let completed = [];
        try {
            completed = JSON.parse(localStorage.getItem(storageKey) || '[]');
            if (!Array.isArray(completed)) completed = [];
        } catch (e) {
            completed = [];
        }

        function updateProgress() {
            const total = headings.length;
            const count = completed.length;
            const pct = total > 0 ? Math.round((count / total) * 100) : 0;

            if (progressText) progressText.textContent = '已完成 ' + count + '/' + total + ' 步';
            if (progressPercent) progressPercent.textContent = pct + '%';
            if (progressFill) progressFill.style.width = pct + '%';

            try {
                localStorage.setItem(storageKey, JSON.stringify(completed));
            } catch (e) {}
        }

        stepsContainer.innerHTML = '';
        headings.forEach(function (heading, idx) {
            if (!heading.id) {
                heading.id = 'step-' + (idx + 1);
            }
            const stepTitle = heading.textContent.trim().replace(/^#+\s*/, '');
            const isDone = completed.indexOf(idx) !== -1;

            const itemEl = document.createElement('div');
            itemEl.className = 'flex items-center gap-2.5 p-2 rounded-lg bg-input-bg/40 border border-border/40 hover:border-indigo-500/30 transition-all';

            const checkbox = document.createElement('input');
            checkbox.type = 'checkbox';
            checkbox.checked = isDone;
            checkbox.className = 'w-4 h-4 rounded text-indigo-600 focus:ring-indigo-500 border-border cursor-pointer';

            const label = document.createElement('a');
            label.href = '#' + heading.id;
            label.className = 'flex-1 min-w-0 truncate text-xs ' + (isDone ? 'line-through text-meta' : 'text-main font-medium') + ' hover:text-accent transition-colors';
            label.textContent = (idx + 1) + '. ' + stepTitle;
            label.onclick = function (e) {
                e.preventDefault();
                heading.scrollIntoView({ behavior: 'smooth', block: 'start' });
            };

            checkbox.onchange = function () {
                const checked = checkbox.checked;
                const pos = completed.indexOf(idx);
                if (checked && pos === -1) {
                    completed.push(idx);
                    label.classList.add('line-through', 'text-meta');
                    label.classList.remove('text-main', 'font-medium');
                } else if (!checked && pos !== -1) {
                    completed.splice(pos, 1);
                    label.classList.remove('line-through', 'text-meta');
                    label.classList.add('text-main', 'font-medium');
                }
                updateProgress();
            };

            itemEl.appendChild(checkbox);
            itemEl.appendChild(label);
            stepsContainer.appendChild(itemEl);
        });

        if (resetBtn) {
            resetBtn.onclick = function () {
                completed = [];
                stepsContainer.querySelectorAll('input[type="checkbox"]').forEach(function (cb) {
                    cb.checked = false;
                });
                stepsContainer.querySelectorAll('a').forEach(function (lbl) {
                    lbl.classList.remove('line-through', 'text-meta');
                    lbl.classList.add('text-main', 'font-medium');
                });
                updateProgress();
                showToast('已重置步骤进度');
            };
        }

        updateProgress();
    }

    function initResourceActions() {
        document.querySelectorAll('.btn-copy-checksum, .btn-copy-code').forEach(function (btn) {
            btn.onclick = function () {
                const text = btn.dataset.copy;
                if (!text) return;
                if (navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(text).then(function () {
                        showToast('已复制: ' + text);
                    }).catch(function () {
                        showToast('复制失败，请手动选择复制');
                    });
                } else {
                    showToast('复制完成: ' + text);
                }
            };
        });

        document.querySelectorAll('.btn-unlock-resource').forEach(function (btn) {
            btn.onclick = function () {
                const paywallBtn = document.querySelector('[data-paywall-trigger="true"], #btn-purchase-post, [data-purchase-status="true"]');
                if (paywallBtn) {
                    paywallBtn.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    paywallBtn.click();
                } else {
                    const paywall = document.getElementById('post-content-section');
                    if (paywall) paywall.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    showToast('请在此文章页面下方兑换积分解锁完整资源');
                }
            };
        });
    }

    function init() {
        initModeFilter();
        initTutorialTracker();
        initResourceActions();
    }

    WindBlogLifecycle.register('article-modes', {
        mount: init
    });
})();
