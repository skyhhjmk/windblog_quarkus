(function () {
    'use strict';

    // 存储当前选中的文本以及锚点数据
    let activeSelection = null;
    // 存储匹配失败的引用/纠错评论ID，用以在评论区中添加失效字样
    const failedQuoteIds = new Set();
    // 存储活跃的连线元素关系，便于 resize/scroll 时重新绘制
    const connectionPairs = [];

    function ready(fn) {
        if (document.readyState !== 'loading') {
            fn();
        } else {
            document.addEventListener('DOMContentLoaded', fn);
        }
    }

    // 获取块级父元素，限制引用划线操作只在单个段落/块内进行，保障排版完整性
    function getBlockParent(node) {
        let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        while (el) {
            const tag = el.tagName;
            if (tag === 'P' || tag === 'LI' || tag === 'H1' || tag === 'H2' || tag === 'H3' || tag === 'H4' || tag === 'H5' || tag === 'H6' || tag === 'PRE' || tag === 'ARTICLE') {
                return el;
            }
            el = el.parentElement;
        }
        return null;
    }

    // 计算简易编辑距离，用于普通引用的容错匹配
    function editDistance(s1, s2) {
        const str1 = s1.toLowerCase();
        const str2 = s2.toLowerCase();
        const costs = [];
        for (let i = 0; i <= str1.length; i = i + 1) {
            let lastValue = i;
            for (let j = 0; j <= str2.length; j = j + 1) {
                if (i === 0) {
                    costs[j] = j;
                } else {
                    if (j > 0) {
                        let newValue = costs[j - 1];
                        if (str1.charAt(i - 1) !== str2.charAt(j - 1)) {
                            const minVal = Math.min(newValue, lastValue);
                            newValue = Math.min(minVal, costs[j]) + 1;
                        }
                        costs[j - 1] = lastValue;
                        lastValue = newValue;
                    }
                }
            }
            if (i > 0) {
                costs[str2.length] = lastValue;
            }
        }
        return costs[str2.length];
    }

    // 获取相似度分数
    function getSimilarity(s1, s2) {
        let longer = s1;
        let shorter = s2;
        if (s1.length < s2.length) {
            longer = s2;
            shorter = s1;
        }
        const longerLength = longer.length;
        if (longerLength === 0) {
            return 1.0;
        }
        const dist = editDistance(longer, shorter);
        return (longerLength - dist) / parseFloat(longerLength);
    }

    // 在指定元素中定位 exact 文字的最佳 Range，结合上下文字符相似度打分
    function getRangeOfTextInElement(element, exact, prefix, suffix) {
        const textNodes = [];
        const walk = document.createTreeWalker(element, NodeFilter.SHOW_TEXT, null, false);
        let node;
        let fullText = "";
        const nodeOffsets = [];

        while (node = walk.nextNode()) {
            nodeOffsets.push({node: node, start: fullText.length});
            fullText = fullText + node.textContent;
        }

        let bestIndex = -1;
        let maxScore = -1;
        let index = fullText.indexOf(exact);

        // 如果连精确的 core text 都找不到，返回 null
        if (index === -1) {
            return null;
        }

        while (index !== -1) {
            let score = 0;
            if (prefix) {
                const actualPrefix = fullText.substring(Math.max(0, index - prefix.length), index);
                if (actualPrefix === prefix) {
                    score = score + 10;
                } else if (actualPrefix.includes(prefix) || prefix.includes(actualPrefix)) {
                    score = score + 5;
                }
            }
            if (suffix) {
                const actualSuffix = fullText.substring(index + exact.length, index + exact.length + suffix.length);
                if (actualSuffix === suffix) {
                    score = score + 10;
                } else if (actualSuffix.includes(suffix) || suffix.includes(actualSuffix)) {
                    score = score + 5;
                }
            }

            if (score > maxScore) {
                maxScore = score;
                bestIndex = index;
            }
            index = fullText.indexOf(exact, index + 1);
        }

        if (bestIndex === -1) {
            return null;
        }

        const startPos = bestIndex;
        const endPos = bestIndex + exact.length;

        let startNode = null;
        let startOffset = 0;
        let endNode = null;
        let endOffset = 0;

        for (let i = 0; i < nodeOffsets.length; i = i + 1) {
            const item = nodeOffsets[i];
            const len = item.node.textContent.length;
            if (startPos >= item.start && startPos <= item.start + len) {
                startNode = item.node;
                startOffset = startPos - item.start;
            }
            if (endPos >= item.start && endPos <= item.start + len) {
                endNode = item.node;
                endOffset = endPos - item.start;
            }
        }

        if (startNode && endNode) {
            const range = document.createRange();
            range.setStart(startNode, startOffset);
            range.setEnd(endNode, endOffset);
            return range;
        }
        return null;
    }

    // 初始化选中文本悬浮菜单 (Popcard)
    function initSelectionListener() {
        const bodyContent = document.getElementById('post-body-content');
        if (!bodyContent) {
            return;
        }

        let popcard = document.getElementById('post-quote-popcard');
        if (!popcard) {
            popcard = document.createElement('div');
            popcard.id = 'post-quote-popcard';
            popcard.className = 'post-quote-popcard';
            popcard.innerHTML = `
                <button type="button" class="post-quote-popbtn primary-accent" id="popbtn-quote">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path></svg>
                    引用评论
                </button>
                <button type="button" class="post-quote-popbtn text-red-400 hover:text-red-300" id="popbtn-correct">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>
                    纠错反馈
                </button>
                <button type="button" class="post-quote-popbtn" id="popbtn-copy">
                    <svg xmlns="http://www.w3.org/2000/svg" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
                    复制内容
                </button>
            `;
            document.body.appendChild(popcard);

            // 复制事件
            document.getElementById('popbtn-copy').onclick = function () {
                if (activeSelection) {
                    navigator.clipboard.writeText(activeSelection.exact).then(function () {
                        window.showToast("复制成功", "success");
                    });
                    hidePopcard();
                }
            };

            // 引用评论事件
            document.getElementById('popbtn-quote').onclick = function () {
                if (activeSelection) {
                    setupQuotePreview(activeSelection);
                    hidePopcard();
                }
            };

            // 纠错反馈事件
            document.getElementById('popbtn-correct').onclick = function () {
                if (activeSelection) {
                    showCorrectionModal(activeSelection);
                    hidePopcard();
                }
            };
        }

        // 隐藏卡片的处理
        function hidePopcard() {
            popcard.classList.remove('active');
        }

        // 处理选中文本
        function handleSelection() {
            const selection = window.getSelection();
            const text = selection.toString().trim();

            if (text.length < 2 || text.length > 1000) {
                hidePopcard();
                return;
            }

            const range = selection.getRangeAt(0);
            if (!bodyContent.contains(range.commonAncestorContainer)) {
                hidePopcard();
                return;
            }

            const startEl = getBlockParent(range.startContainer);
            const endEl = getBlockParent(range.endContainer);

            // 限制不能跨段落选择以防止破坏 HTML 结构
            if (!startEl || !endEl || startEl !== endEl) {
                hidePopcard();
                return;
            }

            // 获取定位元数据
            const paragraphs = Array.from(bodyContent.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, pre'));
            const paragraphIndex = paragraphs.indexOf(startEl);
            const fullText = startEl.textContent;
            const startOffset = fullText.indexOf(text);

            if (startOffset === -1) {
                hidePopcard();
                return;
            }

            const prefixStart = Math.max(0, startOffset - 20);
            const prefix = fullText.substring(prefixStart, startOffset);
            const suffixEnd = Math.min(fullText.length, startOffset + text.length + 20);
            const suffix = fullText.substring(startOffset + text.length, suffixEnd);

            activeSelection = {
                exact: text,
                prefix: prefix,
                suffix: suffix,
                paragraphIndex: paragraphIndex,
                postSlug: bodyContent.parentElement.getAttribute('data-article-transition-slug') || document.getElementById('comment-section')?.getAttribute('data-post-slug')
            };

            // 获取定位位置
            const rect = range.getBoundingClientRect();
            const popWidth = popcard.offsetWidth || 280;
            const popHeight = popcard.offsetHeight || 42;

            const left = rect.left + window.scrollX + (rect.width / 2) - (popWidth / 2);
            const top = rect.top + window.scrollY - popHeight - 8;

            popcard.style.left = left + 'px';
            popcard.style.top = top + 'px';
            popcard.classList.add('active');
        }

        // 绑定事件
        document.addEventListener('mouseup', function () {
            // 给一小段延时，确保 Selection 数据就绪
            setTimeout(handleSelection, 10);
        });

        document.addEventListener('selectionchange', function () {
            const selection = window.getSelection();
            if (selection.toString().trim() === "") {
                hidePopcard();
            }
        });
    }

    // 设置评论框上方的引用预览
    function setupQuotePreview(selection) {
        const commentSection = document.getElementById('comment-section');
        if (!commentSection) {
            window.showToast("评论区加载失败", "error");
            return;
        }

        let container = commentSection.querySelector('.comment-form-container');
        if (!container) {
            return;
        }

        let preview = container.querySelector('#comment-quote-preview');
        if (!preview) {
            preview = document.createElement('div');
            preview.id = 'comment-quote-preview';
            preview.className = 'comment-quote-preview-card';
            container.insertBefore(preview, container.querySelector('form'));
        }

        preview.innerHTML = `
            <div class="comment-quote-preview-text">
                <span class="text-xs text-[var(--accent)] font-bold block mb-1">正在引用内容：</span>
                “${selection.exact}”
            </div>
            <button type="button" class="comment-quote-preview-close" id="btn-close-quote-preview">
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
            </button>
        `;

        // 存到表单 dataset 中
        const form = container.querySelector('#comment-form');
        if (form) {
            form.dataset.quoteType = 'QUOTE';
            form.dataset.quoteText = selection.exact;
            form.dataset.anchorData = JSON.stringify({
                exact: selection.exact,
                prefix: selection.prefix,
                suffix: selection.suffix,
                paragraphIndex: selection.paragraphIndex
            });
        }

        document.getElementById('btn-close-quote-preview').onclick = function () {
            clearFormQuote(form, preview);
        };

        // 平滑滚动
        commentSection.scrollIntoView({behavior: 'smooth'});
    }

    function clearFormQuote(form, preview) {
        if (form) {
            delete form.dataset.quoteType;
            delete form.dataset.quoteText;
            delete form.dataset.anchorData;
        }
        if (preview) {
            preview.remove();
        }
    }

    // 弹出纠错提交模态框
    function showCorrectionModal(selection) {
        let overlay = document.getElementById('post-quote-modal-overlay');
        if (!overlay) {
            overlay = document.createElement('div');
            overlay.id = 'post-quote-modal-overlay';
            overlay.className = 'post-quote-modal-overlay';
            overlay.innerHTML = `
                <div class="post-quote-modal">
                    <div class="post-quote-modal-title">反馈文章内容争议/错误</div>
                    <div class="post-quote-modal-label">被引用文字：</div>
                    <div class="post-quote-modal-text-preview" id="correction-preview-text"></div>
                    <div class="post-quote-modal-label">您的修改意见 / 纠错说明：</div>
                    <textarea class="post-quote-modal-textarea" id="correction-textarea" rows="4" placeholder="请详细写下这里存在的错误或修改建议..."></textarea>
                    <div class="post-quote-modal-actions">
                        <button type="button" class="post-quote-modal-btn cancel" id="correction-btn-cancel">取消</button>
                        <button type="button" class="post-quote-modal-btn submit" id="correction-btn-submit">提交反馈</button>
                    </div>
                </div>
            `;
            document.body.appendChild(overlay);

            document.getElementById('correction-btn-cancel').onclick = function () {
                overlay.classList.remove('active');
            };
        }

        document.getElementById('correction-preview-text').textContent = selection.exact;
        const textarea = document.getElementById('correction-textarea');
        textarea.value = '';
        overlay.classList.add('active');

        document.getElementById('correction-btn-submit').onclick = async function () {
            const explanation = textarea.value.trim();
            if (!explanation) {
                window.showToast("请输入纠错说明", "error");
                return;
            }

            const commentSection = document.getElementById('comment-section');
            const postSlug = selection.postSlug || commentSection?.getAttribute('data-post-slug');

            if (!postSlug) {
                window.showToast("未能获取到文章标识", "error");
                return;
            }

            const payload = {
                postSlug: postSlug,
                content: "[读者纠错反馈] 指出错误: “" + selection.exact + "” \n\n纠错理由: " + explanation,
                quoteType: 'CORRECTION',
                quoteText: selection.exact,
                anchorDataJson: JSON.stringify({
                    exact: selection.exact,
                    prefix: selection.prefix,
                    suffix: selection.suffix,
                    paragraphIndex: selection.paragraphIndex
                })
            };

            const submitBtn = document.getElementById('correction-btn-submit');
            submitBtn.disabled = true;
            submitBtn.textContent = "提交中...";

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
                    const msg = result && result.message ? result.message : "提交纠错失败";
                    throw new Error(msg);
                }

                window.showToast("纠错成功，已提交审核，感谢反馈！", "success");
                overlay.classList.remove('active');

                // 如果页面上有评论模块，重新加载一下评论
                if (commentSection) {
                    const initMethod = window.initComments;
                    if (typeof initMethod === 'function') {
                        initMethod();
                    } else {
                        // 兜底刷新页面
                        setTimeout(function () {
                            window.location.reload();
                        }, 1000);
                    }
                }
            } catch (error) {
                window.showToast(error.message || "网络提交纠错失败", "error");
            } finally {
                submitBtn.disabled = false;
                submitBtn.textContent = "提交反馈";
            }
        };
    }

    // 劫持现有评论表单提交以注入引用数据
    function hijackCommentForm() {
        const form = document.querySelector('#comment-form');
        if (!form || form.dataset.quoteHijacked === 'true') {
            return;
        }
        form.dataset.quoteHijacked = 'true';

        // 我们在 form 提交事件之前通过代理或者在 payload 发送端做拦截
        // 发现 comment.js 中的 submit 按钮是绑定在 form.onsubmit。
        // 为了完美注入数据，我们可以通过重写 window.fetch 来对评论 API 请求注入参数。
        const originalFetch = window.fetch;
        window.fetch = async function (url, options) {
            if (url === '/api/comments' && options && options.method === 'POST') {
                try {
                    const body = JSON.parse(options.body);
                    if (form.dataset.quoteType) {
                        body.quoteType = form.dataset.quoteType;
                        body.quoteText = form.dataset.quoteText;
                        body.anchorDataJson = form.dataset.anchorData;
                    }
                    options.body = JSON.stringify(body);

                    // 发送后清理表单上的引用暂存
                    setTimeout(function () {
                        const preview = document.getElementById('comment-quote-preview');
                        clearFormQuote(form, preview);
                    }, 100);
                } catch (e) {
                    // 忽略 JSON 解析异常
                }
            }
            return originalFetch.apply(this, arguments);
        };
    }

    // 核心定位划线与折线渲染逻辑
    async function loadAndRenderQuotes() {
        const bodyContent = document.getElementById('post-body-content');
        if (!bodyContent) {
            return;
        }

        const commentSection = document.getElementById('comment-section');
        const postSlug = bodyContent.parentElement.getAttribute('data-article-transition-slug') || commentSection?.getAttribute('data-post-slug');
        if (!postSlug) {
            return;
        }

        // 清理已有状态
        clearRenderedElements();

        try {
            const res = await fetch(`/api/comments/quotes/post/${encodeURIComponent(postSlug)}`);
            const json = await res.json();
            if (!res.ok || !json || !json.success || !Array.isArray(json.data)) {
                return;
            }

            const quotes = json.data;
            const paragraphs = Array.from(bodyContent.querySelectorAll('p, li, h1, h2, h3, h4, h5, h6, pre'));

            for (let i = 0; i < quotes.length; i = i + 1) {
                const item = quotes[i];
                const quoteId = item.id;
                const quoteType = item.quoteType;
                const quoteText = item.quoteText;
                const anchor = item.anchorData || {};
                const commentId = item.commentId;
                const userName = item.userName;
                const content = item.content;

                let matchedRange = null;

                // 1. 在原段落位置进行精确定位匹配
                const originParagraph = paragraphs[anchor.paragraphIndex];
                if (originParagraph) {
                    matchedRange = getRangeOfTextInElement(originParagraph, anchor.exact, anchor.prefix, anchor.suffix);
                }

                // 2. 如果没找到，且是普通引用 QUOTE，在周围段落进行模糊相似度定位
                if (!matchedRange && quoteType === 'QUOTE') {
                    let bestParagraph = null;
                    let bestScore = -1;
                    const maxSearchDistance = 5;
                    const startIdx = Math.max(0, anchor.paragraphIndex - maxSearchDistance);
                    const endIdx = Math.min(paragraphs.length - 1, anchor.paragraphIndex + maxSearchDistance);

                    for (let pIdx = startIdx; pIdx <= endIdx; pIdx = pIdx + 1) {
                        const pEl = paragraphs[pIdx];
                        const textContent = pEl.textContent;
                        const score = getSimilarity(textContent, quoteText);
                        if (score > bestScore && score >= 0.6) {
                            bestScore = score;
                            bestParagraph = pEl;
                        }
                    }

                    if (bestParagraph) {
                        // 在相似度最高的段落中寻找最相近的内容
                        // 由于是模糊匹配，我们优先取该段落的整个文本，或者尝试按最长公共子串找到最近的 Range
                        // 这里我们选择包裹该段落中首次出现的 quoteText 简易逻辑，或者包裹整段。
                        // 稳妥起见，我们尝试在 bestParagraph 中寻找 quoteText：
                        const exactText = quoteText;
                        matchedRange = getRangeOfTextInElement(bestParagraph, exactText, null, null);

                        // 实在找不到，就包裹整个段落
                        if (!matchedRange) {
                            matchedRange = document.createRange();
                            matchedRange.selectNodeContents(bestParagraph);
                        }
                    }
                }

                // 3. 渲染划线或记录失效状态
                if (matchedRange) {
                    const span = document.createElement('span');
                    span.className = quoteType === 'CORRECTION' ? 'post-correction-line' : 'post-quote-line';
                    span.dataset.quoteId = quoteId;
                    span.dataset.commentId = commentId;
                    span.title = quoteType === 'CORRECTION' ? `争议内容（${userName}反馈：${content}）` : `引用评论（${userName}）`;

                    try {
                        matchedRange.surroundContents(span);

                        // 双击或点击滚动到评论
                        span.onclick = function () {
                            const cEl = document.getElementById(`comment-${commentId}`);
                            if (cEl) {
                                cEl.scrollIntoView({behavior: 'smooth'});
                                cEl.classList.add('highlight-comment-item'); // 在 theme.css 或全局中可提供短暂高亮
                                setTimeout(function () {
                                    cEl.classList.remove('highlight-comment-item');
                                }, 2000);
                            }
                        };

                        // 如果是纠错类型，进行连线和提示卡片处理
                        if (quoteType === 'CORRECTION') {
                            renderCorrectionInterface(span, quoteId, userName, content, commentId);
                        }
                    } catch (e) {
                        // 若跨越了其他 DOM 结构 surroundContents 报错，转为匹配失效
                        failedQuoteIds.add(String(commentId));
                    }
                } else {
                    failedQuoteIds.add(String(commentId));
                }
            }

            // 更新评论区里的失效提示
            applyFailedQuotesUi();
            // 开始绘制 SVG 折线
            drawCorrectionLines();
        } catch (error) {
            // 忽略错误
        }
    }

    // 渲染纠错外侧卡片或 Mobile info 按钮
    function renderCorrectionInterface(spanElement, quoteId, userName, explanation, commentId) {
        const bodyContent = document.getElementById('post-body-content');
        const isWideScreen = window.innerWidth > 1200;

        if (isWideScreen) {
            // 宽屏模式：在左侧空白渲染争议提示卡片
            let sidebar = document.getElementById('post-correction-sidebar');
            if (!sidebar) {
                sidebar = document.createElement('div');
                sidebar.id = 'post-correction-sidebar';
                sidebar.style.position = 'absolute';
                sidebar.style.left = '-290px'; // 定位在文章内容区左侧
                sidebar.style.top = '0';
                sidebar.style.width = '270px';
                bodyContent.style.position = 'relative'; // 确保父容器定位
                bodyContent.appendChild(sidebar);
            }

            const tipBox = document.createElement('div');
            tipBox.className = 'post-correction-tip-box';
            tipBox.dataset.quoteId = quoteId;
            tipBox.innerHTML = `
                <div class="post-correction-tip-header">
                    <svg class="post-correction-warning-icon" xmlns="http://www.w3.org/2000/svg" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polygon points="7.86 2 16.14 2 22 7.86 22 16.14 16.14 22 7.86 22 2 16.14 2 7.86 7.86 2"></polygon><line x1="12" y1="8" x2="12" y2="12"></line><line x1="12" y1="16" x2="12.01" y2="16"></line></svg>
                    正文内容有争议
                </div>
                <div class="post-correction-tip-author">由 <strong>${userName}</strong> 提出纠错：</div>
                <div class="post-correction-tip-body">“${explanation}”</div>
                <button type="button" class="post-correction-tip-btn" id="btn-tip-go-${commentId}">
                    查看讨论
                    <svg xmlns="http://www.w3.org/2000/svg" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="7" y1="17" x2="17" y2="7"></line><polyline points="7 7 17 7 17 17"></polyline></svg>
                </button>
            `;
            sidebar.appendChild(tipBox);

            // 按钮事件
            document.getElementById(`btn-tip-go-${commentId}`).onclick = function () {
                const cEl = document.getElementById(`comment-${commentId}`);
                if (cEl) {
                    cEl.scrollIntoView({behavior: 'smooth'});
                }
            };

            // 计算卡片 top 位置，使之在 Y 轴上对准划线 span 元素
            const spanTop = spanElement.offsetTop;
            tipBox.style.top = spanTop + 'px';

            // 存储绑定关系用于画折线
            connectionPairs.push({
                span: spanElement,
                box: tipBox
            });
        } else {
            // 窄屏模式：在虚线末尾加入一个 info 图标
            const infoBtn = document.createElement('span');
            infoBtn.className = 'post-correction-info-btn';
            infoBtn.textContent = 'i';
            infoBtn.title = "正文有争议，点击查看详情";
            // 插在划线元素的后面
            spanElement.parentNode.insertBefore(infoBtn, spanElement.nextSibling);

            infoBtn.onclick = function (e) {
                e.stopPropagation();
                window.showModal({
                    title: "正文内容争议反馈",
                    message: `读者“${userName}”提交了对此处内容的纠错反馈：\n\n“${explanation}”\n\n您可以平滑滚动到评论区查看对此反馈的讨论。`,
                    showCancel: true
                }).then(function (confirmed) {
                    if (confirmed) {
                        const cEl = document.getElementById(`comment-${commentId}`);
                        if (cEl) {
                            cEl.scrollIntoView({behavior: 'smooth'});
                        }
                    }
                });
            };
        }
    }

    // 绘制横平竖直的红色折线
    function drawCorrectionLines() {
        const bodyContent = document.getElementById('post-body-content');
        if (!bodyContent || connectionPairs.length === 0) {
            return;
        }

        let svg = document.getElementById('correction-svg-canvas');
        if (!svg) {
            svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.id = 'correction-svg-canvas';
            bodyContent.appendChild(svg);
        } else {
            svg.innerHTML = '';
        }

        // 用于计算绝对坐标的基准
        const bodyRect = document.body.getBoundingClientRect();

        for (let i = 0; i < connectionPairs.length; i = i + 1) {
            const pair = connectionPairs[i];
            const spanRect = pair.span.getBoundingClientRect();
            const boxRect = pair.box.getBoundingClientRect();

            // 划线左边缘
            const x1 = spanRect.left - bodyRect.left;
            const y1 = spanRect.top - bodyRect.top + (spanRect.height / 2);

            // 卡片右边缘
            const x2 = boxRect.right - bodyRect.left;
            const y2 = boxRect.top - bodyRect.top + (boxRect.height / 2);

            // 折折点：横向从 x1 向左延伸到 x_mid (在 x1 和 x2 之间偏 x2 侧处折线，例如在正文左侧边缘)
            const x_mid = x2 + 15;

            // 绘制横平竖直折线路径: M x1 y1 L x_mid y1 L x_mid y2 L x2 y2
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', `M ${x1} ${y1} L ${x_mid} ${y1} L ${x_mid} ${y2} L ${x2} ${y2}`);
            path.setAttribute('class', 'correction-path');
            svg.appendChild(path);
        }
    }

    // 清理渲染的所有高亮和提示卡片
    function clearRenderedElements() {
        // 清理 SVG 画布
        const svg = document.getElementById('correction-svg-canvas');
        if (svg) {
            svg.remove();
        }

        // 清理左侧提示卡片
        const sidebar = document.getElementById('post-correction-sidebar');
        if (sidebar) {
            sidebar.remove();
        }

        // 清理窄屏 info 按钮
        const infoBtns = document.querySelectorAll('.post-correction-info-btn');
        infoBtns.forEach(function (btn) {
            btn.remove();
        });

        // 还原被包裹的 Text 节点以防止重复加载
        const lines = document.querySelectorAll('.post-quote-line, .post-correction-line');
        lines.forEach(function (span) {
            const parent = span.parentNode;
            if (parent) {
                while (span.firstChild) {
                    parent.insertBefore(span.firstChild, span);
                }
                span.remove();
            }
        });

        // 重置状态
        connectionPairs.length = 0;
        failedQuoteIds.clear();
    }

    // 应用评论区引用失效 UI 提示
    function applyFailedQuotesUi() {
        if (failedQuoteIds.size === 0) {
            return;
        }

        // 遍历所有匹配失效的评论
        failedQuoteIds.forEach(function (commentId) {
            const item = document.getElementById(`comment-${commentId}`);
            if (item) {
                // 如果已经加了失效提示，跳过
                if (item.querySelector('.status-warning')) {
                    return;
                }

                // 寻找 comment-meta 区域放入小字警告
                const meta = item.querySelector('.comment-meta');
                if (meta) {
                    const warning = document.createElement('span');
                    warning.className = 'status-warning text-xs ml-2 opacity-80';
                    warning.innerHTML = `（原引用内容被编辑/删除）`;
                    meta.appendChild(warning);
                }
            }
        });
    }

    // 处理评论列表中已存在引用的 HTML 呈现（主要是渲染引用盒子）
    function renderCommentsQuotePreviews() {
        const commentSection = document.getElementById('comment-section');
        if (!commentSection) {
            return;
        }

        // 从公开接口获取评论引用数据，以便在列表里渲染对应的引用块
        const bodyContent = document.getElementById('post-body-content');
        const postSlug = bodyContent?.parentElement?.getAttribute('data-article-transition-slug') || commentSection.getAttribute('data-post-slug');
        if (!postSlug) {
            return;
        }

        fetch(`/api/comments/quotes/post/${encodeURIComponent(postSlug)}`)
            .then(function (r) {
                return r.json();
            })
            .then(function (json) {
                if (json && json.success && Array.isArray(json.data)) {
                    json.data.forEach(function (q) {
                        const commentEl = document.getElementById(`comment-${q.commentId}`);
                        if (commentEl && !commentEl.querySelector('.comment-item-quote-box')) {
                            const body = commentEl.querySelector('.comment-body');
                            if (body) {
                                const quoteBox = document.createElement('div');
                                const isCorr = q.quoteType === 'CORRECTION';
                                quoteBox.className = `comment-item-quote-box ${isCorr ? 'correction' : ''}`;

                                const typeBadge = isCorr
                                    ? `<span class="badge-correction">纠错</span>`
                                    : `<span class="badge-quote">引用</span>`;

                                // 是否已失效
                                const isFailed = failedQuoteIds.has(String(q.commentId));
                                const failedText = isFailed
                                    ? `<span class="status-warning ml-2 font-bold">（原引用内容已被编辑或删除）</span>`
                                    : '';

                                quoteBox.innerHTML = `
                                    <div class="comment-item-quote-meta">
                                        ${typeBadge}
                                        <span>原文引用</span>
                                        ${failedText}
                                    </div>
                                    <div class="italic">“${q.quoteText}”</div>
                                `;
                                body.parentNode.insertBefore(quoteBox, body);
                            }
                        }
                    });

                    // 双重校验匹配失效状态的渲染
                    applyFailedQuotesUi();
                }
            }).catch(function () {
        });
    }

    // 初始化整个引用和纠错系统
    function initPostQuoteSystem() {
        initSelectionListener();
        hijackCommentForm();

        // 延时加载以确保段落 DOM 渲染就绪
        setTimeout(async function () {
            await loadAndRenderQuotes();
            // 在评论列表加载完成后渲染引用的卡片
            renderCommentsQuotePreviews();
        }, 100);
    }

    // 监听 resize & scroll 事件以便重绘 SVG 折线
    window.addEventListener('resize', drawCorrectionLines);
    window.addEventListener('scroll', drawCorrectionLines);

    // 页面完全载入或 PJAX 无刷新跳转载入时触发初始化
    ready(function () {
        initPostQuoteSystem();
        document.addEventListener('pjax:complete', initPostQuoteSystem);
    });

})();
