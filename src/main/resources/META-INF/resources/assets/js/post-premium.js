/**
 * WindBlog Premium Post Logic
 * Handles content unlocking, block updates, and purchase status.
 */

(function () {
    // Wait for DOM to be ready
    document.addEventListener('DOMContentLoaded', initPostPremium);
    document.addEventListener('pjax:complete', initPostPremium);

    // Global variables (will be initialized from data attributes)
    let config = {
        postRef: '',
        postPrice: 0,
        hasPurchased: false
    };
    let buyButtonListenerBound = false;
    let repostButtonListenerBound = false;
    let repostCopyButtonListenerBound = false;
    let passwordFormListenerBound = false;

    function initPostPremium() {
        initPasswordForms();
        const container = document.getElementById('post-content-section');
        if (!container) return;

        // Initialize config from data attributes
        config.postRef = container.dataset.postRef || '';
        config.postPrice = parseFloat(container.dataset.postPrice) || 0;
        config.hasPurchased = container.dataset.hasPurchased === 'true';

        initBuyButtons();
        initRepostLicenseButton();
        initCopyRepostAttributionButton();
        checkAuthorization();
        initBlockManager();
    }

    function initPasswordForms() {
        if (passwordFormListenerBound) {
            return;
        }
        passwordFormListenerBound = true;
        document.addEventListener('submit', async function (event) {
            const form = event.target.closest('form[data-password-unlock-form="true"]');
            if (!form) {
                return;
            }
            event.preventDefault();
            const submitButton = form.querySelector('button[type="submit"]');
            if (window.setLoading && submitButton) {
                window.setLoading(submitButton, true, {text: '验证中...'});
            }
            try {
                const response = await fetch(form.action, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-XSRF-TOKEN': getCsrfToken()
                    },
                    body: new URLSearchParams(new FormData(form)),
                    credentials: 'same-origin'
                });
                if (response.ok && response.url) {
                    window.location.assign(response.url);
                    return;
                }
                await window.alert('密码错误或请求已失效');
            } catch (error) {
                await window.alert('验证失败，请稍后重试');
            } finally {
                if (window.setLoading && submitButton) {
                    window.setLoading(submitButton, false, {text: '解锁内容'});
                }
            }
        });
    }

    // Initialize purchase button event listeners (using delegation)
    function initBuyButtons() {
        if (buyButtonListenerBound) {
            return;
        }
        buyButtonListenerBound = true;
        document.addEventListener('click', async function (e) {
            const target = e.target.closest('.buy-post-btn');
            if (target) {
                e.preventDefault();
                const postRef = target.dataset.postRef || config.postRef;
                const pPrice = target.dataset.price || config.postPrice;
                const bId = target.dataset.blockId;

                await buyCurrentPost(postRef, pPrice, target, bId);
            }
        });
    }

    function initRepostLicenseButton() {
        if (repostButtonListenerBound) {
            return;
        }
        repostButtonListenerBound = true;

        document.addEventListener('click', async function (event) {
            const button = event.target.closest('#request-repost-license-button, #optional-repost-registration-button');
            if (!button) {
                return;
            }

            event.preventDefault();

            const optionalRegistration = button.id === 'optional-repost-registration-button';
            if (optionalRegistration) {
                const confirmed = await window.confirm(
                    '这是自愿登记，不是转载前置条件。是否生成可选的授权码和追踪短链？');
                if (!confirmed) {
                    return;
                }
            }

            const targetUrl = await window.prompt('请输入转载页面 URL', 'https://');
            if (!targetUrl) {
                return;
            }

            if (window.setLoading) {
                window.setLoading(button, true, {text: optionalRegistration ? '登记中' : '申请中'});
            }

            try {
                const response = await fetch('/api/user/repost/licenses', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'application/json',
                        'X-XSRF-TOKEN': getCsrfToken()
                    },
                    credentials: 'same-origin',
                    body: JSON.stringify({
                        postSlug: button.dataset.postRef,
                        targetUrl: targetUrl
                    })
                });
                const responseText = await response.text();
                let result = {};
                try {
                    result = responseText ? JSON.parse(responseText) : {};
                } catch (parseError) {
                    result = {};
                }
                if (!response.ok || !result.success) {
                    const message = result.message || (optionalRegistration ? '登记失败' : '申请失败');
                    await window.alert(message);
                    return;
                }

                await window.showModal({
                    title: optionalRegistration ? '自愿登记已生成' : '转载授权已生成',
                    message: result.data.copy.markdown,
                    showCancel: false
                });
            } catch (error) {
                await window.alert(optionalRegistration ? '登记失败，请稍后重试' : '申请失败，请稍后重试');
            } finally {
                if (window.setLoading) {
                    window.setLoading(button, false, {
                        text: optionalRegistration ? '自愿登记转载' : '我要转载'
                    });
                }
                button.disabled = false;
                button.classList.remove('btn-loading');
                const spinner = button.querySelector('.spinner');
                if (spinner) spinner.classList.add('hidden');
            }
        });
    }

    function initCopyRepostAttributionButton() {
        if (repostCopyButtonListenerBound) {
            return;
        }
        repostCopyButtonListenerBound = true;

        document.addEventListener('click', async function (event) {
            const button = event.target.closest('#copy-repost-attribution-button');
            if (!button) {
                return;
            }
            event.preventDefault();

            const template = document.getElementById('repost-copy-template');
            const copyText = template && template.content
                ? template.content.textContent.trim()
                : '';
            if (!copyText) {
                await window.alert('转载说明暂不可用，请刷新页面后重试');
                return;
            }

            if (window.setLoading) {
                window.setLoading(button, true, {text: '复制中'});
            }
            try {
                await copyRepostText(copyText);
                window.showToast('转载说明已复制', 'success');
            } catch (error) {
                await window.alert('复制失败，请手动复制页面中的转载信息');
            } finally {
                if (window.setLoading) {
                    window.setLoading(button, false, {text: '复制转载说明'});
                }
            }
        });
    }

    async function copyRepostText(text) {
        if (navigator.clipboard && window.isSecureContext) {
            try {
                await navigator.clipboard.writeText(text);
                return;
            } catch (error) {
                // Fall back to the legacy textarea path when clipboard permission is unavailable.
            }
        }
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.setAttribute('readonly', '');
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        const copied = document.execCommand('copy');
        textarea.remove();
        if (!copied) {
            throw new Error('clipboard copy failed');
        }
    }

    // Check authorization status and load content if needed
    function checkAuthorization() {
        const hasButtons = document.querySelectorAll('.buy-post-btn').length > 0;

        if (hasButtons || config.postPrice > 0) {
            // The auth cookie is HttpOnly; the protected endpoint is the authority for the current user.
            document.querySelectorAll('.buy-post-btn').forEach(btn => {
                if (window.setLoading) {
                    window.setLoading(btn, true, {text: '验证授权中...'});
                }
            });
            loadFullPostContent();
        } else {
            // Free posts without protected blocks do not need a user-specific request.
            const hasLocalRecord = localStorage.getItem('purchased_' + config.postRef);
            if (hasLocalRecord) {
                loadFullPostContent();
            }
        }
    }

    // Purchase article or block
    async function buyCurrentPost(postRef, price, button, blockId) {
        const confirmMsg = blockId ? '确认解锁该内容区块吗？' : '确认使用 ' + price + ' 积分购买并解锁这篇文章吗？';
        const confirmed = await window.confirm(confirmMsg);
        if (!confirmed) return;

        if (window.setLoading && button) {
            window.setLoading(button, true, {text: '正在解锁...'});
        }

        try {
            let url = '/api/user/post/buy/' + encodeURIComponent(postRef) + '?price=' + price;
            if (blockId) url += '&blockId=' + encodeURIComponent(blockId);

            const response = await fetch(url, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Cache-Control': 'no-cache',
                    'X-XSRF-TOKEN': getCsrfToken()
                },
                credentials: 'same-origin'
            });

            const result = await response.json();
            if (result.success) {
                window.showToast('解锁成功！', 'success');
                // Success: load content after a short delay
                setTimeout(() => loadFullPostContent(blockId), 500);
            } else {
                handlePurchaseError(result);
            }
        } catch (e) {
            console.error('购买失败:', e);
            window.showToast('网络异常', 'error');
        } finally {
            if (window.setLoading && button) {
                window.setLoading(button, false);
            }
        }
    }

    function handlePurchaseError(result) {
        if (result.message && (result.message.includes('未登录') || result.message.includes('必须登录'))) {
            window.showToast('请先登录', 'error');
            setTimeout(() => {
                window.location.href = '/user/login?redirect=' + encodeURIComponent(window.location.pathname);
            }, 1000);
        } else {
            window.showToast(result.message || '购买失败', 'error');
        }
    }

    // Load full or partial content
    async function loadFullPostContent(unlockedBlockId) {
        try {
            const response = await fetch('/api/user/post/blocks/' + encodeURIComponent(config.postRef), {
                method: 'GET',
                headers: {
                    'Cache-Control': 'no-cache, no-store, must-revalidate',
                    'Pragma': 'no-cache',
                    'Expires': '0',
                    'X-XSRF-TOKEN': getCsrfToken()
                },
                credentials: 'same-origin'
            });

            if (response.status === 403 || response.status === 401) return;

            const result = await response.json();
            if (result.success && result.data) {
                const contentData = result.data;
                updateUnlockedBlocks(contentData);

                // FIXED BUG: isFullUnlock should ONLY depend on server-side hasPurchased status
                const isFullUnlock = contentData.hasPurchased;
                updatePurchaseStatus(isFullUnlock);
            }
        } catch (e) {
            console.error('加载内容失败:', e);
        }
    }

    // Update unlocked blocks in the DOM
    function updateUnlockedBlocks(contentData) {
        const renderType = contentData.renderType || 'MARKDOWN';
        const blocks = contentData.blocks || {};

        document.querySelectorAll('.buy-post-btn').forEach(btn => {
            const blockId = btn.dataset.blockId;
            if (blockId && blocks[blockId]) {
                const blockContent = blocks[blockId];
                const card = btn.closest('.buy-post-placeholder') || btn.closest('.md-region-premium-card');

                if (card) {
                    if (renderType === 'VDITOR' && typeof Vditor !== 'undefined') {
                        const tempDiv = document.createElement('div');
                        Vditor.preview(tempDiv, blockContent, {
                            mode: 'light',
                            hljs: {style: 'github'}
                        }).then(() => {
                            card.outerHTML = tempDiv.innerHTML;
                        });
                    } else {
                        card.outerHTML = blockContent;
                    }
                    return;
                }
            }

            // Restore loading state if not replaced
            if (window.setLoading) {
                window.setLoading(btn, false);
            }
        });

        if (contentData.attachments) {
            updateAttachments(contentData.attachments);
        }
    }

    // Update attachments section
    function updateAttachments(attachments) {
        const sidebarAttachments = document.querySelector('[data-attachments-section="true"]');
        if (!sidebarAttachments || !attachments) return;

        const attachmentsHtml = attachments.map(media => {
            let html = '<div class="card card-static p-4 flex items-center gap-4 group/file hover:border-accent/40 transition-all border-border/60 bg-card-bg/50">';
            html += '<div class="w-10 h-10 rounded-lg bg-card-bg border border-border flex items-center justify-center text-accent group-hover/file:scale-110 transition-transform">';
            html += '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">';
            html += '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>';
            html += '<polyline points="14 2 14 8 20 8"></polyline>';
            html += '</svg></div>';
            html += '<div class="flex-1 min-w-0">';
            html += '<p class="text-xs text-main font-bold truncate mb-0.5" title="' + escapeHtml(media.fileName) + '">' + escapeHtml(media.fileName) + '</p>';
            html += '<p class="text-[10px] text-gray-500 font-mono opacity-80">' + media.formattedSize + '</p></div>';

            if (media.url) {
                html += '<a href="' + escapeHtml(media.url) + '" download class="p-2 text-gray-500 hover:text-accent hover:bg-accent/10 rounded-lg transition-all">';
                html += '<svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">';
                html += '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>';
                html += '<polyline points="7 10 12 15 17 10"></polyline>';
                html += '<line x1="12" y1="15" x2="12" y2="3"></line></svg></a>';
            }
            html += '</div>';
            return html;
        }).join('');

        const wrapper = sidebarAttachments.querySelector('.space-y-3');
        if (wrapper) {
            wrapper.innerHTML = attachmentsHtml;
        } else {
            sidebarAttachments.innerHTML = '<h3 class="text-sm font-bold tracking-wider text-main uppercase mb-6"><div class="w-1.5 h-6 bg-accent rounded-full inline-block mr-3 vertical-middle"></div>专属资源</h3><div class="space-y-3">' + attachmentsHtml + '</div>';
        }
    }

    // Update global purchase status
    function updatePurchaseStatus(hasPurchasedFull) {
        if (!hasPurchasedFull) return;

        // Update meta indicators
        document.querySelectorAll('[data-purchase-status="true"]').forEach(el => {
            el.innerHTML = '<span class="w-2 h-2 rounded-full bg-green-500"></span> 已解锁';
        });

        // Update sidebar badge
        const badge = document.querySelector('.sidebar-status-badge');
        if (badge) {
            badge.innerHTML = '<svg xmlns="http://www.w3.org/2000/svg" class="w-4 h-4 text-green-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">' +
                '<path d="M20 6L9 17l-5-5"></path></svg>' +
                '<span class="text-green-500 font-bold">已解锁全文</span>';
        }

        // Remove all unlock buttons as everything is unlocked
        document.querySelectorAll('.buy-post-btn').forEach(btn => btn.remove());

        // Persist locally for better UX on refresh (optional but recommended)
        localStorage.setItem('purchased_' + config.postRef, 'true');
    }

    // Helpers
    function getCsrfToken() {
        const meta = document.querySelector('meta[name="csrf-token"]');
        return (meta && meta.getAttribute('content')) || getCookie('XSRF-TOKEN');
    }

    function getCookie(name) {
        const value = "; " + document.cookie;
        const parts = value.split("; " + name + "=");
        if (parts.length === 2) return parts.pop().split(';').shift();
        return null;
    }

    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // --- Block Manager & Preference Controls ---
    const expandedBlockIds = new Set();
    let blockPrefs = {
        mode: 'hidden', // 'hidden', 'card', 'line'
        enabledLevels: {},
        enabledGroups: {}
    };

    function initBlockManager() {
        const container = document.getElementById('post-content-section');
        if (container) {
            Array.from(container.children).forEach((child, index) => {
                if (!child.classList.contains('custom-block') && 
                    !child.classList.contains('block-placeholder-card') && 
                    !child.classList.contains('block-placeholder-line') &&
                    child.tagName !== 'SCRIPT' && 
                    child.tagName !== 'STYLE') {
                    
                    child.classList.add('custom-block', 'block-isolated');
                    child.dataset.name = 'basic';
                    child.dataset.blockId = 'isolated-' + index;
                } else if (child.classList.contains('custom-block')) {
                    if (!child.dataset.name || child.dataset.name.trim() === '') {
                        child.dataset.name = 'basic';
                    }
                }
            });
        }

        const blocks = document.querySelectorAll('.custom-block');
        const widget = document.getElementById('block-manager-widget');
        if (!widget) return;

        if (blocks.length === 0) {
            widget.classList.add('hidden');
            return;
        }
        widget.classList.remove('hidden');

        loadBlockPrefs(blocks);
        renderBlockControls(blocks);
        applyBlockVisibility(blocks);
    }

    function loadBlockPrefs(blocks) {
        const local = localStorage.getItem('windblog_block_prefs');
        if (local) {
            try {
                blockPrefs = JSON.parse(local);
            } catch (e) {
                console.error('解析偏好失败:', e);
            }
        }

        const levelsInPage = new Set();
        const groupsInPage = new Set();
        blocks.forEach(b => {
            if (b.dataset.name) levelsInPage.add(b.dataset.name);
            if (b.dataset.group) groupsInPage.add(b.dataset.group);
        });

        levelsInPage.forEach(lvl => {
            if (blockPrefs.enabledLevels[lvl] === undefined) {
                blockPrefs.enabledLevels[lvl] = true;
            }
        });
        groupsInPage.forEach(grp => {
            if (blockPrefs.enabledGroups[grp] === undefined) {
                blockPrefs.enabledGroups[grp] = true;
            }
        });

        if (!blockPrefs.mode) {
            blockPrefs.mode = 'hidden';
        }
    }

    function saveBlockPrefs() {
        localStorage.setItem('windblog_block_prefs', JSON.stringify(blockPrefs));
    }

    function handleLevelToggleChange(lvl, isChecked) {
        blockPrefs.enabledLevels[lvl] = isChecked;
        if (isChecked) {
            if (lvl === 'detailed') {
                if (blockPrefs.enabledLevels['basic'] !== undefined) blockPrefs.enabledLevels['basic'] = true;
                if (blockPrefs.enabledLevels['quick'] !== undefined) blockPrefs.enabledLevels['quick'] = true;
            }
            if (lvl === 'basic') {
                if (blockPrefs.enabledLevels['quick'] !== undefined) blockPrefs.enabledLevels['quick'] = true;
            }
        }
        saveBlockPrefs();
        updateCheckboxStates();
        
        const blocks = document.querySelectorAll('.custom-block');
        applyBlockVisibility(blocks);
    }

    function renderBlockControls(blocks) {
        const modeButtons = {
            'hidden': document.getElementById('pref-mode-hidden'),
            'card': document.getElementById('pref-mode-card'),
            'line': document.getElementById('pref-mode-line')
        };
        
        Object.keys(modeButtons).forEach(m => {
            const btn = modeButtons[m];
            if (btn) {
                // Remove existing listeners by cloning and replacing
                const newBtn = btn.cloneNode(true);
                btn.parentNode.replaceChild(newBtn, btn);
                modeButtons[m] = newBtn;

                newBtn.addEventListener('click', () => {
                    Object.values(modeButtons).forEach(b => { if (b) b.classList.remove('active'); });
                    newBtn.classList.add('active');
                    blockPrefs.mode = m;
                    saveBlockPrefs();
                    applyBlockVisibility(blocks);
                });
            }
        });
        
        Object.keys(modeButtons).forEach(m => {
            const btn = modeButtons[m];
            if (btn) {
                if (m === blockPrefs.mode) {
                    btn.classList.add('active');
                } else {
                    btn.classList.remove('active');
                }
            }
        });

        const levelContainer = document.getElementById('level-toggles-container');
        if (levelContainer) {
            levelContainer.innerHTML = '';
            const sortedLevels = Object.keys(blockPrefs.enabledLevels).sort((a, b) => {
                const order = { 'quick': 1, 'basic': 2, 'detailed': 3 };
                return (order[a] || 99) - (order[b] || 99);
            });
            
            sortedLevels.forEach(lvl => {
                const wrapper = document.createElement('div');
                wrapper.className = 'block-checkbox-wrapper';
                
                const labelText = getLevelDisplayName(lvl);
                const uniqueId = 'chk-level-' + lvl;
                
                wrapper.innerHTML = `
                    <label class="block-checkbox-label" for="${uniqueId}">${labelText}</label>
                    <input type="checkbox" id="${uniqueId}" class="level-toggle-checkbox accent-color-accent" data-level="${lvl}" />
                `;
                levelContainer.appendChild(wrapper);
                
                const chk = wrapper.querySelector('input');
                chk.checked = blockPrefs.enabledLevels[lvl];
                chk.addEventListener('change', (e) => {
                    handleLevelToggleChange(lvl, e.target.checked);
                });
            });
        }

        const groupContainer = document.getElementById('group-toggles-container');
        const groupSection = document.getElementById('group-toggles-section');
        const groupKeys = Object.keys(blockPrefs.enabledGroups);
        
        if (groupContainer && groupSection) {
            if (groupKeys.length > 0) {
                groupSection.classList.remove('hidden');
                groupContainer.innerHTML = '';
                groupKeys.forEach(grp => {
                    const wrapper = document.createElement('div');
                    wrapper.className = 'block-checkbox-wrapper';
                    const uniqueId = 'chk-group-' + grp;
                    wrapper.innerHTML = `
                        <label class="block-checkbox-label" for="${uniqueId}">组: ${grp}</label>
                        <input type="checkbox" id="${uniqueId}" class="group-toggle-checkbox accent-color-accent" data-group="${grp}" />
                    `;
                    groupContainer.appendChild(wrapper);
                    
                    const chk = wrapper.querySelector('input');
                    chk.checked = blockPrefs.enabledGroups[grp];
                    chk.addEventListener('change', (e) => {
                        blockPrefs.enabledGroups[grp] = e.target.checked;
                        saveBlockPrefs();
                        applyBlockVisibility(blocks);
                    });
                });
            } else {
                groupSection.classList.add('hidden');
            }
        }

        const btnAllLevels = document.getElementById('btn-select-all-levels');
        if (btnAllLevels) {
            btnAllLevels.onclick = () => {
                const checked = Object.values(blockPrefs.enabledLevels).some(v => !v);
                Object.keys(blockPrefs.enabledLevels).forEach(k => blockPrefs.enabledLevels[k] = checked);
                saveBlockPrefs();
                updateCheckboxStates();
                applyBlockVisibility(blocks);
            };
        }
        const btnAllGroups = document.getElementById('btn-select-all-groups');
        if (btnAllGroups) {
            btnAllGroups.onclick = () => {
                const checked = Object.values(blockPrefs.enabledGroups).some(v => !v);
                Object.keys(blockPrefs.enabledGroups).forEach(k => blockPrefs.enabledGroups[k] = checked);
                saveBlockPrefs();
                updateCheckboxStates();
                applyBlockVisibility(blocks);
            };
        }

        renderBlockOutline(blocks);
    }

    function getLevelDisplayName(lvl) {
        const names = {
            'quick': '快速实现 (Quick)',
            'basic': '基本模式 (Basic)',
            'detailed': '详细思路 (Detailed)'
        };
        return names[lvl] || (lvl.charAt(0).toUpperCase() + lvl.slice(1));
    }

    function updateCheckboxStates() {
        document.querySelectorAll('.level-toggle-checkbox').forEach(chk => {
            const lvl = chk.dataset.level;
            if (lvl && blockPrefs.enabledLevels[lvl] !== undefined) {
                chk.checked = blockPrefs.enabledLevels[lvl];
            }
        });
        document.querySelectorAll('.group-toggle-checkbox').forEach(chk => {
            const grp = chk.dataset.group;
            if (grp && blockPrefs.enabledGroups[grp] !== undefined) {
                chk.checked = blockPrefs.enabledGroups[grp];
            }
        });
    }

    function renderBlockOutline(blocks) {
        const container = document.getElementById('block-outline-container');
        if (!container) return;
        
        container.innerHTML = '';
        if (blocks.length === 0) {
            container.innerHTML = '<p class="text-xs text-gray-500 italic">无可用区块</p>';
            return;
        }

        blocks.forEach((b, index) => {
            const blockId = b.dataset.blockId || ('block-index-' + index);
            b.id = blockId;

            const lvl = b.dataset.name || '';
            const grp = b.dataset.group || '';
            
            let title = b.dataset.title;
            if (!title) {
                const h = b.querySelector('h1, h2, h3, h4, h5, h6');
                if (h) {
                    title = h.textContent.trim();
                } else {
                    const text = b.textContent.replace(/\s+/g, ' ').trim();
                    title = text.length > 25 ? text.substring(0, 25) + '...' : text;
                }
            }
            if (!title || title.trim() === '...') {
                title = '内容区块 #' + (index + 1);
            }

            const item = document.createElement('a');
            item.href = '#' + blockId;
            item.className = 'block-outline-item';
            
            const badgeLvl = getLevelBadgeHtml(lvl);
            const badgeGrp = grp ? `<span class="bg-input-bg border border-border/60 text-gray-400 px-1.5 py-0.5 rounded-[4px] font-mono text-[9px] scale-90 origin-left">${grp}</span>` : '';
            
            item.innerHTML = `
                <div class="flex flex-col gap-1 w-full min-w-0">
                    <div class="flex items-center gap-1.5 flex-wrap">
                        ${badgeLvl}
                        ${badgeGrp}
                    </div>
                    <div class="truncate font-bold text-[11px] mt-0.5 text-main" title="${title}">${title}</div>
                </div>
            `;
            
            item.addEventListener('click', (e) => {
                e.preventDefault();
                if (b.classList.contains('block-hidden') || b.style.display === 'none') {
                    expandedBlockIds.add(blockId);
                    applyBlockVisibility(document.querySelectorAll('.custom-block'));
                }
                
                const targetEl = document.getElementById(blockId);
                if (targetEl) {
                    targetEl.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    targetEl.classList.add('ring-2', 'ring-accent', 'ring-offset-2', 'ring-offset-card', 'transition-shadow', 'duration-500');
                    setTimeout(() => {
                        targetEl.classList.remove('ring-2', 'ring-accent', 'ring-offset-2', 'ring-offset-card');
                    }, 2000);
                }
            });
            
            container.appendChild(item);
        });
    }

    function getLevelBadgeHtml(lvl) {
        const colors = {
            'quick': 'bg-green-500/10 text-green-400 border-green-500/20',
            'basic': 'bg-blue-500/10 text-blue-400 border-blue-500/20',
            'detailed': 'bg-purple-500/10 text-purple-400 border-purple-500/20'
        };
        const defaultColor = 'bg-accent/10 text-accent border-accent/20';
        const cls = colors[lvl] || defaultColor;
        const name = lvl === 'quick' ? '快速' : lvl === 'basic' ? '基本' : lvl === 'detailed' ? '详细' : lvl;
        return `<span class="px-1.5 py-0.5 rounded-[4px] border ${cls} text-[9px] font-black tracking-wide">${name}</span>`;
    }

    function applyBlockVisibility(blocks) {
        document.querySelectorAll('.block-placeholder-card, .block-placeholder-line').forEach(el => el.remove());
        
        blocks.forEach((b, index) => {
            const blockId = b.id || b.dataset.blockId || ('block-index-' + index);
            const lvl = b.dataset.name;
            const grp = b.dataset.group;
            
            const levelDisabled = blockPrefs.enabledLevels[lvl] === false;
            const groupDisabled = grp && blockPrefs.enabledGroups[grp] === false;
            
            const shouldHide = levelDisabled || groupDisabled;
            const isTemporarilyExpanded = expandedBlockIds.has(blockId);
            
            if (shouldHide && !isTemporarilyExpanded) {
                b.style.display = 'none';
                b.classList.add('block-hidden');
                
                if (blockPrefs.mode === 'card') {
                    createPlaceholderCard(b, blockId, lvl, grp);
                } else if (blockPrefs.mode === 'line') {
                    createPlaceholderLine(b, blockId, lvl, grp);
                }
            } else {
                b.style.display = 'block';
                b.classList.remove('block-hidden');
            }
        });
    }

    function createPlaceholderCard(blockEl, blockId, lvl, grp) {
        const card = document.createElement('div');
        card.className = 'block-placeholder-card';
        
        const lvlName = lvl === 'quick' ? '快速实现' : lvl === 'basic' ? '基本模式' : lvl === 'detailed' ? '详细思路' : lvl;
        const grpSuffix = grp ? `（分组: ${grp}）` : '';
        
        card.innerHTML = `
            <div class="card-info">
                <div class="card-icon">
                    <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                        <polyline points="4 7 4 4 20 4 20 7"></polyline>
                        <line x1="9" y1="20" x2="15" y2="20"></line>
                        <line x1="12" y1="4" x2="12" y2="20"></line>
                    </svg>
                </div>
                <div>
                    <div class="card-title">已折叠 [${lvlName}]${grpSuffix} 内容</div>
                    <div class="card-desc">您在侧边栏关闭了此内容的自动显示</div>
                </div>
            </div>
            <button type="button" class="btn-expand">点击展开</button>
        `;
        
        card.addEventListener('click', () => {
            expandedBlockIds.add(blockId);
            applyBlockVisibility(document.querySelectorAll('.custom-block'));
        });
        
        blockEl.parentNode.insertBefore(card, blockEl);
    }

    function createPlaceholderLine(blockEl, blockId, lvl, grp) {
        const line = document.createElement('span');
        line.className = 'block-placeholder-line';
        
        const lvlName = lvl === 'quick' ? '快速实现' : lvl === 'basic' ? '基本模式' : lvl === 'detailed' ? '详细思路' : lvl;
        
        line.innerHTML = `
            <svg xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3">
                <polyline points="6 9 12 15 18 9"></polyline>
                <line x1="12" y1="3" x2="12" y2="15"></line>
            </svg>
            点击展开折叠的 [${lvlName}] 详细文本
        `;
        
        line.addEventListener('click', () => {
            expandedBlockIds.add(blockId);
            applyBlockVisibility(document.querySelectorAll('.custom-block'));
        });
        
        blockEl.parentNode.insertBefore(line, blockEl);
    }

})();
