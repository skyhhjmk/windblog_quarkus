(function () {
    'use strict';

    function initLinkList() {
        const grepSelect = document.getElementById('linkGrepSelect');
        const applyBtn = document.getElementById('linkFilterApplyBtn');
        if (!grepSelect || !applyBtn) return;

        applyBtn.onclick = function () {
            const grepMode = grepSelect.value;
            const params = new URLSearchParams();
            if (grepMode && grepMode !== 'All') {
                params.set('type', grepMode);
            }
            const newUrl = params.toString() ? `/link?${params.toString()}` : '/link';
            
            window.setLoading(applyBtn, true);
            
            if (window.pjax && window.pjax.loadUrl) {
                window.pjax.loadUrl(newUrl);
            } else {
                window.location.href = newUrl;
            }
        };
    }

    function initLinkCard() {
        const cards = document.querySelectorAll('.link-card');
        cards.forEach(card => {
            const openCard = function (e) {
                if (e.target.closest('.link-anchor')) return;

                const id = card.dataset.id;
                const name = card.dataset.name;
                const url = card.dataset.url;
                const icon = card.dataset.icon;
                const description = card.dataset.description;
                const redirectType = parseInt(card.dataset.redirectType) || 1;

                if (redirectType === 4) {
                    const detailUrl = '/link/' + id;
                    if (window.pjax && window.pjax.loadUrl) {
                        window.pjax.loadUrl(detailUrl);
                    } else {
                        window.location.href = detailUrl;
                    }
                    return;
                }

                if (redirectType === 1 || redirectType === 2) {
                    window.open(url, '_blank');
                    return;
                }

                window.showModal({
                    title: "资源详情",
                    renderContent: (container) => {
                        const wrapper = document.createElement('div');
                        wrapper.className = 'text-center py-4';
                        const safeIconUrl = toHttpUrl(icon);
                        if (safeIconUrl) {
                            const image = document.createElement('img');
                            image.src = safeIconUrl;
                            image.alt = name || '资源图标';
                            image.className = 'w-16 h-16 rounded-xl border border-border mx-auto mb-4 object-cover';
                            wrapper.appendChild(image);
                        }
                        const heading = document.createElement('h3');
                        heading.className = 'text-xl font-bold text-main mb-1';
                        heading.textContent = name || '未命名资源';
                        wrapper.appendChild(heading);
                        const urlText = document.createElement('p');
                        urlText.className = 'text-xs meta-mono text-accent mb-6 break-all';
                        urlText.textContent = url;
                        wrapper.appendChild(urlText);
                        const descriptionBox = document.createElement('div');
                        descriptionBox.className = 'p-4 bg-white/5 rounded-lg border border-border text-left text-sm text-body leading-relaxed mb-6';
                        descriptionBox.textContent = description || '该资源暂无描述。';
                        wrapper.appendChild(descriptionBox);
                        const actions = document.createElement('div');
                        actions.className = 'flex justify-center';
                        const visit = document.createElement('a');
                        visit.href = toHttpUrl(url) || '#';
                        visit.target = '_blank';
                        visit.rel = 'noopener noreferrer';
                        visit.className = 'btn-action-primary px-12 py-3';
                        visit.textContent = '访问资源';
                        if (!toHttpUrl(url)) {
                            visit.setAttribute('aria-disabled', 'true');
                            visit.classList.add('pointer-events-none', 'opacity-50');
                        }
                        actions.appendChild(visit);
                        wrapper.appendChild(actions);
                        container.appendChild(wrapper);
                    },
                    showCancel: true
                });
            };
            card.onclick = openCard;
            card.onkeydown = function (event) {
                if (event.key !== 'Enter' && event.key !== ' ') return;
                event.preventDefault();
                openCard(event);
            };
        });
    }

    function toHttpUrl(value) {
        try {
            const parsed = new URL(String(value || ''), window.location.href);
            return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.href : '';
        } catch (_) {
            return '';
        }
    }

    function initLinkApplicationForm() {
        const applicationForm = document.getElementById('linkApplicationForm');
        if (!applicationForm) {
            return;
        }
        if (applicationForm.dataset.bound === 'true') {
            return;
        }
        applicationForm.dataset.bound = 'true';
        const placementOptions = applicationForm.querySelectorAll('input[name="placementType"]');
        for (const placementOption of placementOptions) {
            placementOption.addEventListener('change', updatePlacementFields);
        }
        const connectivityTestButton = document.getElementById('linkConnectivityTestButton');
        if (connectivityTestButton) {
            connectivityTestButton.addEventListener('click', testLinkConnectivityFromButton);
        }
        const siteUrlInput = document.getElementById('linkApplicationUrl');
        if (siteUrlInput) {
            siteUrlInput.addEventListener('input', resetConnectivityStatus);
        }
        applicationForm.addEventListener('submit', submitLinkApplication);
    }

    function updatePlacementFields(event) {
        const placementType = event.target.value;
        const commonFields = document.getElementById('linkPlacementCommonFields');
        const placementUrlLabel = document.getElementById('linkPlacementUrlLabel');
        const placementUrlInput = document.getElementById('linkPlacementUrl');
        const pageNameField = document.getElementById('linkPlacementPageNameField');
        const pageNameInput = document.getElementById('linkPlacementPageName');
        const descriptionLabel = document.getElementById('linkPlacementDescriptionLabel');
        const descriptionInput = document.getElementById('linkPlacementDescription');
        const siteUrlInput = document.getElementById('linkApplicationUrl');

        commonFields.classList.remove('hidden');
        placementUrlInput.required = true;
        pageNameField.classList.add('hidden');
        pageNameInput.required = false;
        descriptionInput.required = false;

        if (placementType === 'HOME_PAGE') {
            placementUrlLabel.textContent = '首页地址 *';
            descriptionLabel.textContent = '首页中的具体位置';
            if (!placementUrlInput.value && siteUrlInput) {
                placementUrlInput.value = siteUrlInput.value;
            }
            return;
        }
        if (placementType === 'LINK_PAGE') {
            placementUrlLabel.textContent = '专用友链页地址 *';
            descriptionLabel.textContent = '友链页中的具体位置';
            return;
        }

        placementUrlLabel.textContent = '其他页面地址 *';
        descriptionLabel.textContent = '具体位置说明 *';
        pageNameField.classList.remove('hidden');
        pageNameInput.required = true;
        descriptionInput.required = true;
    }

    function resetConnectivityStatus() {
        const applicationForm = document.getElementById('linkApplicationForm');
        const connectivityStatus = document.getElementById('linkConnectivityStatus');
        if (applicationForm) {
            applicationForm.dataset.testedUrl = '';
        }
        if (connectivityStatus) {
            connectivityStatus.textContent = '站点地址已变化，需要重新测试';
            connectivityStatus.className = 'block text-xs text-gray-500';
        }
    }

    async function testLinkConnectivityFromButton() {
        const siteUrlInput = document.getElementById('linkApplicationUrl');
        const connectivityTestButton = document.getElementById('linkConnectivityTestButton');
        if (!siteUrlInput || !siteUrlInput.reportValidity()) {
            return;
        }

        window.setLoading(connectivityTestButton, true, {text: '测试中...'});
        try {
            await testLinkConnectivity(siteUrlInput.value.trim());
            window.showToast('当前网络可以连接该站点', 'success');
        } catch (error) {
            window.showToast(readErrorMessage(error, '当前网络无法连接该站点'), 'error');
        } finally {
            window.setLoading(connectivityTestButton, false);
        }
    }

    async function testLinkConnectivity(siteUrl) {
        const applicationForm = document.getElementById('linkApplicationForm');
        const connectivityStatus = document.getElementById('linkConnectivityStatus');
        const abortController = new AbortController();
        const timeoutHandle = window.setTimeout(function () {
            abortController.abort();
        }, 8000);

        if (connectivityStatus) {
            connectivityStatus.textContent = '正在从当前浏览器网络测试...';
            connectivityStatus.className = 'block text-xs text-accent';
        }

        try {
            await fetch(siteUrl, {
                method: 'GET',
                mode: 'no-cors',
                cache: 'no-store',
                signal: abortController.signal
            });
            if (applicationForm) {
                applicationForm.dataset.testedUrl = siteUrl;
            }
            if (connectivityStatus) {
                connectivityStatus.textContent = '当前浏览器网络连接成功';
                connectivityStatus.className = 'block text-xs text-green-500';
            }
        } catch (error) {
            if (applicationForm) {
                applicationForm.dataset.testedUrl = '';
            }
            if (connectivityStatus) {
                connectivityStatus.textContent = '连接失败，请检查地址、HTTPS 和站点状态';
                connectivityStatus.className = 'block text-xs text-red-500';
            }
            throw error;
        } finally {
            window.clearTimeout(timeoutHandle);
        }
    }

    async function submitLinkApplication(event) {
        event.preventDefault();
        const applicationForm = event.currentTarget;
        const submitButton = document.getElementById('linkApplicationSubmit');
        const requestBody = {};
        const formData = new FormData(applicationForm);
        for (const formEntry of formData.entries()) {
            requestBody[formEntry[0]] = formEntry[1];
        }

        window.setLoading(submitButton, true, {text: '提交中...'});
        try {
            const normalizedSiteUrl = String(requestBody.url).trim();
            if (applicationForm.dataset.testedUrl !== normalizedSiteUrl) {
                await testLinkConnectivity(normalizedSiteUrl);
            }

            const response = await fetch('/api/link-applications', {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    'X-XSRF-TOKEN': window.getCsrfToken()
                },
                body: JSON.stringify(requestBody)
            });
            const responseBody = await readResponseBody(response);
            if (!response.ok) {
                let errorMessage = responseBody.message;
                if (!errorMessage) {
                    errorMessage = '提交失败';
                }
                throw new Error(errorMessage);
            }
            applicationForm.reset();
            applicationForm.dataset.testedUrl = '';
            document.getElementById('linkPlacementCommonFields').classList.add('hidden');
            resetConnectivityStatus();
            window.WindBlogAnalytics?.trackFormResult('link-application', true);
            window.showToast(responseBody.message, 'success');
        } catch (error) {
            window.WindBlogAnalytics?.trackFormResult('link-application', false);
            window.showToast(readErrorMessage(error, '提交失败，请稍后重试'), 'error');
        } finally {
            window.setLoading(submitButton, false);
        }
    }

    async function readResponseBody(response) {
        try {
            return await response.json();
        } catch (error) {
            return {};
        }
    }

    function readErrorMessage(error, fallbackMessage) {
        if (error && error.name === 'AbortError') {
            return '连接测试超时，请确认站点可以公开访问';
        }
        if (error && error.message) {
            return error.message;
        }
        return fallbackMessage;
    }

    function initPage() {
        initLinkList();
        initLinkCard();
        initLinkApplicationForm();
    }

    document.addEventListener('DOMContentLoaded', initPage);
    document.addEventListener('page:ready', initPage);
})();
