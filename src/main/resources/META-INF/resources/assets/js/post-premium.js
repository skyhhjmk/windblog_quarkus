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
        postId: 0,
        postPrice: 0,
        hasPurchased: false
    };
    let buyButtonListenerBound = false;
    let repostButtonListenerBound = false;

    function initPostPremium() {
        const container = document.getElementById('post-content-section');
        if (!container) return;

        // Initialize config from data attributes
        config.postId = parseInt(container.dataset.postId) || 0;
        config.postPrice = parseFloat(container.dataset.postPrice) || 0;
        config.hasPurchased = container.dataset.hasPurchased === 'true';

        initBuyButtons();
        initRepostLicenseButton();
        checkAuthorization();
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
                const pid = target.dataset.postId || config.postId;
                const pPrice = target.dataset.price || config.postPrice;
                const bId = target.dataset.blockId;

                await buyCurrentPost(pid, pPrice, target, bId);
            }
        });
    }

    function initRepostLicenseButton() {
        if (repostButtonListenerBound) {
            return;
        }
        repostButtonListenerBound = true;

        document.addEventListener('click', async function (event) {
            const button = event.target.closest('#request-repost-license-button');
            if (!button) {
                return;
            }

            event.preventDefault();

            if (button.dataset.loggedIn !== 'true') {
                await window.alert('请先登录后再申请转载授权');
                return;
            }

            const targetUrl = await window.prompt('请输入转载页面 URL', 'https://');
            if (!targetUrl) {
                return;
            }

            if (window.setLoading) {
                window.setLoading(button, true, {text: '申请中'});
            }

            try {
                const response = await fetch('/api/user/repost/licenses', {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({
                        postId: Number(button.dataset.postId),
                        targetUrl: targetUrl
                    })
                });
                const result = await response.json();
                if (!response.ok || !result.success) {
                    const message = result.message || '申请失败';
                    await window.alert(message);
                    return;
                }

                await window.showModal({
                    title: '转载授权已生成',
                    message: result.data.copy.markdown,
                    showCancel: false
                });
            } catch (error) {
                await window.alert('申请失败，请稍后重试');
            } finally {
                if (window.setLoading) {
                    window.setLoading(button, false, {text: '我要转载'});
                }
            }
        });
    }

    // Check authorization status and load content if needed
    function checkAuthorization() {
        const loggedIn = !!getCookie('user_token');
        const hasButtons = document.querySelectorAll('.buy-post-btn').length > 0;

        if (loggedIn && hasButtons) {
            // Already logged in: show verification state and try to pull content
            document.querySelectorAll('.buy-post-btn').forEach(btn => {
                if (window.setLoading) {
                    window.setLoading(btn, true, {text: '验证授权中...'});
                }
            });
            loadFullPostContent();
        } else {
            // Not logged in or no blocks: check local record or initial purchase status
            const hasLocalRecord = localStorage.getItem('purchased_' + config.postId);
            if (hasLocalRecord || config.hasPurchased) {
                loadFullPostContent();
            }
        }
    }

    // Purchase article or block
    async function buyCurrentPost(postId, price, button, blockId) {
        const confirmMsg = blockId ? '确认解锁该内容区块吗？' : '确认使用 ' + price + ' 积分购买并解锁这篇文章吗？';
        const confirmed = await window.confirm(confirmMsg);
        if (!confirmed) return;

        if (window.setLoading && button) {
            window.setLoading(button, true, {text: '正在解锁...'});
        }

        try {
            let url = '/api/user/post/buy/' + postId + '?price=' + price;
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
            const response = await fetch('/api/user/post/blocks/' + config.postId, {
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
        localStorage.setItem('purchased_' + config.postId, 'true');
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

})();
