(function () {
    'use strict';

    const RECENT_KEY = 'windblog_recent_tools';
    const PIPE_KEY = 'windblog_tool_pipe_payload';

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function copyText(text, btn) {
        if (!navigator.clipboard) return;
        navigator.clipboard.writeText(String(text == null ? '' : text)).then(() => {
            if (!btn) return;
            const orig = btn.innerHTML;
            btn.textContent = '已复制';
            setTimeout(() => { btn.innerHTML = orig; }, 1200);
        }).catch(() => {});
    }

    function getLangPrefix() {
        const p = window.location.pathname || '';
        const m = p.match(/^\/(zh-cn|en-us)(?=\/|$)/i);
        return m ? '/' + m[1].toLowerCase() : '';
    }

    function navigateToTool(subPath) {
        const target = getLangPrefix() + subPath;
        if (window.WindBlogPjax && typeof window.WindBlogPjax.navigate === 'function') {
            window.WindBlogPjax.navigate(target);
        } else {
            window.location.href = target;
        }
    }

    window.WindBlogToolPipe = {
        send: function (targetSubPath, payload) {
            try {
                sessionStorage.setItem(PIPE_KEY, JSON.stringify({
                    target: targetSubPath,
                    data: payload,
                    ts: Date.now()
                }));
            } catch (e) {}
            navigateToTool(targetSubPath);
        },
        consume: function (expectedSubPath) {
            try {
                const raw = sessionStorage.getItem(PIPE_KEY);
                if (!raw) return null;
                const parsed = JSON.parse(raw);
                if (!parsed || parsed.target !== expectedSubPath || Date.now() - parsed.ts > 60000) {
                    return null;
                }
                sessionStorage.removeItem(PIPE_KEY);
                return parsed.data;
            } catch (e) {
                return null;
            }
        }
    };

    function recordRecentTool(id, name, url, icon) {
        if (!id || !url) return;
        try {
            const list = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
            const filtered = Array.isArray(list) ? list.filter(item => item && item.id !== id) : [];
            filtered.unshift({ id: id, name: name || id, url: url, icon: icon || 'fa-solid fa-wrench' });
            localStorage.setItem(RECENT_KEY, JSON.stringify(filtered.slice(0, 4)));
        } catch (e) {}
    }

    // Track visits when inside any /tools/<slug> page
    function trackCurrentToolVisit() {
        const path = window.location.pathname.replace(/^\/(zh-cn|en-us)/i, '');
        if (!path.startsWith('/tools/')) return;
        const slug = path.slice('/tools/'.length).split('/')[0];
        if (!slug) return;
        const h1 = document.querySelector('#pjax-content-root h1');
        const name = h1 ? h1.textContent.trim() : slug;
        recordRecentTool(slug, name, getLangPrefix() + '/tools/' + slug, 'fa-solid fa-bolt');
    }

    // =========================================================================
    // 1. Tools Index: Search + Category Filter + Recently Used
    // =========================================================================
    function mountToolsIndex() {
        const app = document.getElementById('toolsIndexApp');
        if (!app) return;

        const searchInput = document.getElementById('toolsIndexSearchInput');
        const filterContainer = document.getElementById('toolsIndexTagFilters');
        const cards = Array.from(app.querySelectorAll('[data-tool-card="true"]'));
        const emptyState = document.getElementById('toolsEmptyState');
        const recentSection = document.getElementById('toolsRecentSection');
        const recentGrid = document.getElementById('toolsRecentGrid');

        let activeTag = 'ALL';

        // Build tag buttons from rendered localized cards
        if (filterContainer) {
            const existingAllBtn = filterContainer.querySelector('[data-filter-tag="ALL"]');
            filterContainer.innerHTML = '';
            if (existingAllBtn) filterContainer.appendChild(existingAllBtn);

            const tags = [];
            cards.forEach(card => {
                const tag = (card.getAttribute('data-tool-tag') || '').trim();
                if (tag && !tags.includes(tag)) tags.push(tag);
            });
            tags.forEach(tag => {
                const btn = document.createElement('button');
                btn.type = 'button';
                btn.setAttribute('data-filter-tag', tag);
                btn.className = 'px-3 py-1.5 rounded-full text-xs font-mono bg-input-bg border border-border text-meta hover:text-main transition-all';
                btn.textContent = tag;
                filterContainer.appendChild(btn);
            });

            filterContainer.addEventListener('click', function (e) {
                const btn = e.target.closest('[data-filter-tag]');
                if (!btn) return;
                activeTag = btn.getAttribute('data-filter-tag') || 'ALL';
                Array.from(filterContainer.querySelectorAll('[data-filter-tag]')).forEach(b => {
                    const selected = b.getAttribute('data-filter-tag') === activeTag;
                    b.className = selected
                        ? 'px-3 py-1.5 rounded-full text-xs font-mono bg-accent text-[var(--accent-foreground)] font-bold transition-all'
                        : 'px-3 py-1.5 rounded-full text-xs font-mono bg-input-bg border border-border text-meta hover:text-main transition-all';
                });
                applyFilters();
            });
        }

        function applyFilters() {
            const q = (searchInput ? searchInput.value : '').trim().toLowerCase();
            let visibleCount = 0;
            cards.forEach(card => {
                const tag = card.getAttribute('data-tool-tag') || '';
                const haystack = (card.getAttribute('data-tool-search') || '').toLowerCase();
                const tagMatch = activeTag === 'ALL' || tag === activeTag;
                const textMatch = !q || haystack.includes(q);
                const show = tagMatch && textMatch;
                card.classList.toggle('hidden', !show);
                if (show) visibleCount++;
            });
            if (emptyState) {
                emptyState.classList.toggle('hidden', visibleCount > 0);
            }
        }

        if (searchInput) {
            searchInput.addEventListener('input', applyFilters);
        }

        if (!window.__windblogToolsIndexKeydownBound) {
            window.__windblogToolsIndexKeydownBound = true;
            document.addEventListener('keydown', function (e) {
                const input = document.getElementById('toolsIndexSearchInput');
                if (!input || !document.getElementById('toolsIndexApp')) return;
                if (e.key === '/' && document.activeElement !== input &&
                    !/INPUT|TEXTAREA|SELECT/.test((document.activeElement && document.activeElement.tagName) || '')) {
                    e.preventDefault();
                    input.focus();
                }
            });
        }

        // Record clicks on cards
        cards.forEach(card => {
            card.addEventListener('click', function () {
                recordRecentTool(
                    card.getAttribute('data-tool-id'),
                    card.getAttribute('data-tool-name'),
                    card.getAttribute('data-tool-url'),
                    card.getAttribute('data-tool-icon')
                );
            });
        });

        // Render recently used
        if (recentSection && recentGrid) {
            try {
                const recent = JSON.parse(localStorage.getItem(RECENT_KEY) || '[]');
                if (Array.isArray(recent) && recent.length > 0) {
                    recentGrid.innerHTML = recent.map(item => `
                        <a href="${escapeHtml(item.url)}" class="flex items-center gap-2.5 p-3 rounded-xl bg-card border border-border hover:border-accent/40 transition-colors">
                            <i class="${escapeHtml(item.icon || 'fa-solid fa-bolt')} text-accent text-xs shrink-0"></i>
                            <span class="text-xs font-semibold text-main truncate">${escapeHtml(item.name)}</span>
                        </a>
                    `).join('');
                    recentSection.classList.remove('hidden');
                }
            } catch (e) {}
        }
    }

    // =========================================================================
    // 2. AI Token Calculator & Prompt Playground
    // =========================================================================
    function mountAiToken() {
        const root = document.getElementById('aiTokenToolApp');
        if (!root) return;

        const sysEl = document.getElementById('aiSystemPrompt');
        const userEl = document.getElementById('aiUserPrompt');
        const outTokEl = document.getElementById('aiExpectedOutputTokens');
        const statTokens = document.getElementById('aiStatTokens');
        const statChars = document.getElementById('aiStatChars');
        const statLang = document.getElementById('aiStatLang');
        const varContainer = document.getElementById('aiVariablesContainer');
        const varCount = document.getElementById('aiVarCount');
        const previewEl = document.getElementById('aiRenderedPreview');
        const pricingBody = document.getElementById('aiPricingTableBody');
        const copyBtn = document.getElementById('btnCopyRenderedPrompt');
        const clearBtn = document.getElementById('btnAiTokenClear');

        const varValues = {};

        const MODELS = [
            { name: 'GPT-4o', inPrice: 2.50, outPrice: 10.00 },
            { name: 'GPT-4o mini', inPrice: 0.15, outPrice: 0.60 },
            { name: 'Claude 3.7 Sonnet', inPrice: 3.00, outPrice: 15.00 },
            { name: 'Gemini 2.5 Pro', inPrice: 1.25, outPrice: 10.00 },
            { name: 'Gemini 2.5 Flash', inPrice: 0.15, outPrice: 0.60 },
            { name: 'DeepSeek V3', inPrice: 0.27, outPrice: 1.10 }
        ];

        function estimateTokens(text) {
            if (!text) return { tokens: 0, cjk: 0, words: 0 };
            let cjk = 0, ascii = 0, other = 0;
            for (let i = 0; i < text.length; i++) {
                const code = text.charCodeAt(i);
                if (code >= 0x4E00 && code <= 0x9FFF) cjk++;
                else if (code < 128) ascii++;
                else other++;
            }
            const words = text.trim() ? text.trim().split(/\s+/).length : 0;
            const tokens = Math.max(1, Math.ceil(cjk * 1.45 + ascii / 4.0 + other * 1.1));
            return { tokens, cjk, words };
        }

        function extractVars(text) {
            const set = [];
            const re = /\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g;
            let m;
            while ((m = re.exec(text)) !== null) {
                if (!set.includes(m[1])) set.push(m[1]);
            }
            return set;
        }

        function renderVariables(vars) {
            varCount.textContent = vars.length + ' 个变量';
            if (vars.length === 0) {
                varContainer.innerHTML = '在上方 Prompt 中输入 <code class="text-accent">&#123;&#123;变量名&#125;&#125;</code> 即可在此实时填值测试。';
                return;
            }
            varContainer.innerHTML = vars.map(name => `
                <div class="flex items-center gap-2">
                    <label class="w-28 shrink-0 text-accent truncate" title="${escapeHtml(name)}">{{${escapeHtml(name)}}}</label>
                    <input type="text" data-ai-var="${escapeHtml(name)}" value="${escapeHtml(varValues[name] || '')}"
                           placeholder="输入 ${escapeHtml(name)} 的值..."
                           class="flex-1 bg-input-bg border border-border rounded-lg px-2.5 py-1 text-main outline-none focus:border-accent" />
                </div>
            `).join('');
            varContainer.querySelectorAll('[data-ai-var]').forEach(inp => {
                inp.addEventListener('input', function () {
                    varValues[inp.getAttribute('data-ai-var')] = inp.value;
                    updateStats();
                });
            });
        }

        function applyVars(text) {
            return (text || '').replace(/\{\{\s*([a-zA-Z0-9_.-]+)\s*\}\}/g, function (full, name) {
                return Object.prototype.hasOwnProperty.call(varValues, name) && varValues[name] !== ''
                    ? varValues[name]
                    : full;
            });
        }

        function updateStats() {
            const combinedRaw = [sysEl.value, userEl.value].filter(Boolean).join('\n\n');
            const renderedSys = applyVars(sysEl.value);
            const renderedUser = applyVars(userEl.value);
            const renderedCombined = [renderedSys ? '[System]\n' + renderedSys : '', renderedUser ? '[User]\n' + renderedUser : '']
                .filter(Boolean).join('\n\n');

            previewEl.textContent = renderedCombined || '(空)';
            const plainCombined = [renderedSys, renderedUser].filter(Boolean).join('\n');
            const est = estimateTokens(plainCombined);
            statTokens.textContent = String(est.tokens);
            statChars.textContent = String(plainCombined.length);
            statLang.textContent = est.cjk + ' / ' + est.words;

            const outTok = Math.max(0, parseInt(outTokEl.value || '0', 10) || 0);
            pricingBody.innerHTML = MODELS.map(m => {
                const cost = (est.tokens / 1e6) * m.inPrice + (outTok / 1e6) * m.outPrice;
                return `
                    <tr>
                        <td class="py-2 pr-2 font-bold text-main">${escapeHtml(m.name)}</td>
                        <td class="py-2 pr-2 text-meta">$${m.inPrice} / $${m.outPrice}</td>
                        <td class="py-2 text-right text-accent font-bold">$${cost.toFixed(6)}</td>
                    </tr>
                `;
            }).join('');
        }

        function onPromptInput() {
            const vars = extractVars(sysEl.value + '\n' + userEl.value);
            renderVariables(vars);
            updateStats();
        }

        sysEl.addEventListener('input', onPromptInput);
        userEl.addEventListener('input', onPromptInput);
        outTokEl.addEventListener('input', updateStats);
        if (copyBtn) copyBtn.addEventListener('click', () => copyText(previewEl.textContent, copyBtn));
        if (clearBtn) {
            clearBtn.addEventListener('click', () => {
                sysEl.value = '';
                userEl.value = '';
                onPromptInput();
            });
        }

        root.querySelectorAll('[data-ai-preset]').forEach(btn => {
            btn.addEventListener('click', () => {
                const p = btn.getAttribute('data-ai-preset');
                if (p === 'rag') {
                    sysEl.value = '你是 {{project}} 的技术文档助手。请仅依据提供的参考资料用 {{language}} 简洁回答。';
                    userEl.value = '参考资料：\n{{context}}\n\n用户问题：{{question}}';
                    varValues.project = 'WindBlog';
                    varValues.language = '中文';
                    varValues.context = 'WindBlog 基于 Java 21 和 Quarkus 构建，提供纯前端工具箱与无状态 MCP 接口。';
                    varValues.question = '如何调用 WindBlog 的公开 MCP 端点？';
                } else {
                    sysEl.value = '你是一位资深 {{language}} 架构师，请检查以下代码是否存在安全漏洞或性能问题。';
                    userEl.value = '代码片段：\n```{{language}}\n{{code}}\n```';
                    varValues.language = 'Java';
                    varValues.code = 'public String greet(String name) { return "Hello " + name; }';
                }
                onPromptInput();
            });
        });

        onPromptInput();
    }

    // =========================================================================
    // 3. JWT Decoder & Local Signature Verifier
    // =========================================================================
    function mountJwtTool() {
        const root = document.getElementById('jwtToolApp');
        if (!root) return;

        const tokenEl = document.getElementById('jwtTokenInput');
        const secretEl = document.getElementById('jwtSecretInput');
        const statusBadge = document.getElementById('jwtStatusBadge');
        const verifyBadge = document.getElementById('jwtVerifyBadge');
        const headerOut = document.getElementById('jwtHeaderOut');
        const payloadOut = document.getElementById('jwtPayloadOut');
        const claimsMeta = document.getElementById('jwtClaimsMeta');

        function b64UrlToBytes(b64u) {
            const b64 = b64u.replace(/-/g, '+').replace(/_/g, '/');
            const pad = b64 + '==='.slice((b64.length + 3) % 4);
            const bin = atob(pad);
            const bytes = new Uint8Array(bin.length);
            for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
            return bytes;
        }

        function bytesToB64Url(bytes) {
            let bin = '';
            for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
            return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        }

        function decodeJsonPart(part) {
            const text = new TextDecoder().decode(b64UrlToBytes(part));
            return JSON.parse(text);
        }

        async function verifyHmac(header, signingInput, sigPart, secret) {
            if (!secret) {
                verifyBadge.textContent = '未验证';
                verifyBadge.className = 'px-2 py-0.5 rounded-full bg-input-bg text-meta border border-border';
                return;
            }
            const algMap = { HS256: 'SHA-256', HS384: 'SHA-384', HS512: 'SHA-512' };
            const hashAlg = algMap[header && header.alg];
            if (!hashAlg || !window.crypto || !window.crypto.subtle) {
                verifyBadge.textContent = '仅支持 HS256/384/512 本地校验';
                verifyBadge.className = 'px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/30';
                return;
            }
            try {
                const key = await crypto.subtle.importKey(
                    'raw',
                    new TextEncoder().encode(secret),
                    { name: 'HMAC', hash: hashAlg },
                    false,
                    ['verify']
                );
                const valid = await crypto.subtle.verify(
                    'HMAC',
                    key,
                    b64UrlToBytes(sigPart),
                    new TextEncoder().encode(signingInput)
                );
                verifyBadge.textContent = valid ? '签名有效 (Verified)' : '签名无效 (Invalid)';
                verifyBadge.className = valid
                    ? 'px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-400 border border-emerald-500/30'
                    : 'px-2 py-0.5 rounded-full bg-rose-500/15 text-rose-400 border border-rose-500/30';
            } catch (e) {
                verifyBadge.textContent = '校验失败';
                verifyBadge.className = 'px-2 py-0.5 rounded-full bg-rose-500/15 text-rose-400 border border-rose-500/30';
            }
        }

        async function parseJwt() {
            const raw = (tokenEl.value || '').trim();
            if (!raw) {
                statusBadge.textContent = '等待输入';
                statusBadge.className = 'px-2 py-0.5 rounded-full bg-input-bg text-meta border border-border';
                headerOut.textContent = '{}';
                payloadOut.textContent = '{}';
                claimsMeta.innerHTML = '';
                await verifyHmac(null, '', '', secretEl.value);
                return;
            }
            const parts = raw.split('.');
            if (parts.length !== 3) {
                statusBadge.textContent = '格式错误 (需 3 段)';
                statusBadge.className = 'px-2 py-0.5 rounded-full bg-rose-500/15 text-rose-400 border border-rose-500/30';
                return;
            }
            try {
                const header = decodeJsonPart(parts[0]);
                const payload = decodeJsonPart(parts[1]);
                headerOut.textContent = JSON.stringify(header, null, 2);
                payloadOut.textContent = JSON.stringify(payload, null, 2);

                const nowSec = Math.floor(Date.now() / 1000);
                const lines = [];
                if (typeof payload.iat === 'number') {
                    lines.push(`<div>签发时间 (iat): <span class="text-main">${new Date(payload.iat * 1000).toLocaleString()}</span></div>`);
                }
                if (typeof payload.nbf === 'number') {
                    lines.push(`<div>生效时间 (nbf): <span class="text-main">${new Date(payload.nbf * 1000).toLocaleString()}</span></div>`);
                }
                if (typeof payload.exp === 'number') {
                    const expired = payload.exp < nowSec;
                    lines.push(`<div>过期时间 (exp): <span class="${expired ? 'text-rose-400' : 'text-emerald-400'}">${new Date(payload.exp * 1000).toLocaleString()} (${expired ? '已过期' : '有效期内'})</span></div>`);
                    statusBadge.textContent = expired ? '已过期' : '格式有效';
                    statusBadge.className = expired
                        ? 'px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-400 border border-amber-500/30'
                        : 'px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-400 border border-emerald-500/30';
                } else {
                    statusBadge.textContent = '格式有效';
                    statusBadge.className = 'px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-400 border border-emerald-500/30';
                }
                claimsMeta.innerHTML = lines.join('');
                await verifyHmac(header, parts[0] + '.' + parts[1], parts[2], secretEl.value);
            } catch (e) {
                statusBadge.textContent = '解码失败';
                statusBadge.className = 'px-2 py-0.5 rounded-full bg-rose-500/15 text-rose-400 border border-rose-500/30';
                claimsMeta.innerHTML = `<div class="text-rose-400">${escapeHtml(e.message)}</div>`;
            }
        }

        tokenEl.addEventListener('input', parseJwt);
        secretEl.addEventListener('input', parseJwt);

        document.getElementById('btnCopyJwtHeader').addEventListener('click', function () {
            copyText(headerOut.textContent, this);
        });
        document.getElementById('btnCopyJwtPayload').addEventListener('click', function () {
            copyText(payloadOut.textContent, this);
        });
        document.getElementById('btnJwtSendToDiff').addEventListener('click', function () {
            window.WindBlogToolPipe.send('/tools/json-diff', payloadOut.textContent);
        });
        document.getElementById('btnJwtSample').addEventListener('click', async function () {
            const secret = 'windblog-demo-secret';
            const now = Math.floor(Date.now() / 1000);
            const h = bytesToB64Url(new TextEncoder().encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' })));
            const p = bytesToB64Url(new TextEncoder().encode(JSON.stringify({
                sub: '10086',
                name: 'WindBlog Developer',
                role: 'admin',
                iat: now,
                exp: now + 3600
            })));
            const input = h + '.' + p;
            let sig = 'signature';
            if (window.crypto && window.crypto.subtle) {
                const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
                    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
                const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(input));
                sig = bytesToB64Url(new Uint8Array(mac));
            }
            tokenEl.value = input + '.' + sig;
            secretEl.value = secret;
            parseJwt();
        });

        const piped = window.WindBlogToolPipe.consume('/tools/jwt');
        if (piped) {
            tokenEl.value = piped;
            parseJwt();
        }
    }

    // =========================================================================
    // 4. Config Converter (YAML <-> JSON <-> Properties <-> .env)
    // =========================================================================
    function mountConfigConverter() {
        const root = document.getElementById('configConverterToolApp');
        if (!root) return;

        const srcFmtEl = document.getElementById('configSourceFormat');
        const tgtFmtEl = document.getElementById('configTargetFormat');
        const inputEl = document.getElementById('configInput');
        const outputEl = document.getElementById('configOutput');
        const errEl = document.getElementById('configErrorMsg');

        function parseScalar(v) {
            const t = v.trim();
            if (t === '' || t === 'null' || t === '~') return null;
            if (t === 'true') return true;
            if (t === 'false') return false;
            if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
            if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) {
                return t.slice(1, -1);
            }
            if ((t.startsWith('[') && t.endsWith(']')) || (t.startsWith('{') && t.endsWith('}'))) {
                try { return JSON.parse(t); } catch (e) {}
            }
            return t;
        }

        function setNested(obj, pathParts, val) {
            let cur = obj;
            for (let i = 0; i < pathParts.length - 1; i++) {
                const k = pathParts[i];
                if (typeof cur[k] !== 'object' || cur[k] === null || Array.isArray(cur[k])) {
                    cur[k] = {};
                }
                cur = cur[k];
            }
            cur[pathParts[pathParts.length - 1]] = val;
        }

        function flattenObject(obj, prefix, out) {
            if (obj === null || typeof obj !== 'object') {
                out[prefix] = obj;
                return;
            }
            if (Array.isArray(obj)) {
                out[prefix] = obj.map(v => (typeof v === 'object' ? JSON.stringify(v) : String(v))).join(',');
                return;
            }
            Object.keys(obj).forEach(k => {
                const nextKey = prefix ? prefix + '.' + k : k;
                const val = obj[k];
                if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
                    flattenObject(val, nextKey, out);
                } else {
                    out[nextKey] = val;
                }
            });
        }

        function parseSimpleYaml(text) {
            const lines = text.split(/\r?\n/);
            const rootObj = {};
            const stack = [{ indent: -1, container: rootObj }];

            for (let i = 0; i < lines.length; i++) {
                const raw = lines[i];
                if (!raw.trim() || raw.trim().startsWith('#') || raw.trim() === '---') continue;
                const indent = raw.search(/\S/);
                const trimmed = raw.trim();

                while (stack.length > 1 && indent <= stack[stack.length - 1].indent) {
                    stack.pop();
                }
                const parent = stack[stack.length - 1].container;

                if (trimmed.startsWith('- ')) {
                    const itemVal = trimmed.slice(2).trim();
                    if (Array.isArray(parent)) {
                        parent.push(parseScalar(itemVal));
                    }
                    continue;
                }

                const colonIdx = trimmed.indexOf(':');
                if (colonIdx === -1) {
                    throw new Error('YAML 第 ' + (i + 1) + ' 行缺少冒号: ' + trimmed);
                }
                const key = trimmed.slice(0, colonIdx).trim().replace(/^['"]|['"]$/g, '');
                const rest = trimmed.slice(colonIdx + 1).trim();

                if (rest === '') {
                    // Look ahead to see if next non-empty line is a list item
                    let isArr = false;
                    for (let j = i + 1; j < lines.length; j++) {
                        const nt = lines[j].trim();
                        if (!nt || nt.startsWith('#')) continue;
                        if (nt.startsWith('- ')) isArr = true;
                        break;
                    }
                    const child = isArr ? [] : {};
                    if (!Array.isArray(parent)) parent[key] = child;
                    stack.push({ indent, container: child });
                } else {
                    if (!Array.isArray(parent)) parent[key] = parseScalar(rest);
                }
            }
            return rootObj;
        }

        function toYaml(obj, indent) {
            const pad = '  '.repeat(indent || 0);
            if (Array.isArray(obj)) {
                return obj.map(item => {
                    if (item !== null && typeof item === 'object') {
                        return pad + '-\n' + toYaml(item, (indent || 0) + 1);
                    }
                    return pad + '- ' + formatYamlScalar(item);
                }).join('\n');
            }
            if (obj !== null && typeof obj === 'object') {
                return Object.keys(obj).map(k => {
                    const v = obj[k];
                    if (v !== null && typeof v === 'object') {
                        return pad + k + ':\n' + toYaml(v, (indent || 0) + 1);
                    }
                    return pad + k + ': ' + formatYamlScalar(v);
                }).join('\n');
            }
            return pad + formatYamlScalar(obj);
        }

        function formatYamlScalar(v) {
            if (v === null || v === undefined) return 'null';
            if (typeof v === 'number' || typeof v === 'boolean') return String(v);
            const s = String(v);
            if (/[:#{}[\],&*!|>'"%@`]|^\s|\s$/.test(s) || s === '') {
                return JSON.stringify(s);
            }
            return s;
        }

        function parseInput(text, fmt) {
            if (!text.trim()) return {};
            if (fmt === 'json') return JSON.parse(text);
            if (fmt === 'yaml') return parseSimpleYaml(text);
            const obj = {};
            const lines = text.split(/\r?\n/);
            lines.forEach((line, idx) => {
                const t = line.trim();
                if (!t || t.startsWith('#') || t.startsWith('!')) return;
                const eq = t.indexOf('=');
                if (eq === -1) throw new Error('第 ' + (idx + 1) + ' 行缺少 = : ' + t);
                const rawKey = t.slice(0, eq).trim();
                const rawVal = t.slice(eq + 1).trim();
                if (fmt === 'env') {
                    const normalizedKey = rawKey.toLowerCase().replace(/__/g, '.').replace(/_/g, '.');
                    setNested(obj, normalizedKey.split('.'), parseScalar(rawVal));
                } else {
                    setNested(obj, rawKey.split('.'), parseScalar(rawVal));
                }
            });
            return obj;
        }

        function formatOutput(obj, fmt) {
            if (fmt === 'json') return JSON.stringify(obj, null, 2);
            if (fmt === 'yaml') return toYaml(obj, 0);
            const flat = {};
            flattenObject(obj, '', flat);
            return Object.keys(flat).map(k => {
                const val = flat[k] == null ? '' : flat[k];
                if (fmt === 'env') {
                    const envKey = k.toUpperCase().replace(/[^A-Z0-9]+/g, '_');
                    const s = String(val);
                    return envKey + '=' + (/\s/.test(s) ? JSON.stringify(s) : s);
                }
                return k + '=' + val;
            }).join('\n');
        }

        function convert() {
            errEl.classList.add('hidden');
            try {
                const parsed = parseInput(inputEl.value, srcFmtEl.value);
                outputEl.value = formatOutput(parsed, tgtFmtEl.value);
            } catch (e) {
                errEl.textContent = e.message;
                errEl.classList.remove('hidden');
                outputEl.value = '';
            }
        }

        inputEl.addEventListener('input', convert);
        srcFmtEl.addEventListener('change', convert);
        tgtFmtEl.addEventListener('change', convert);
        document.getElementById('btnCopyConfigOutput').addEventListener('click', function () {
            copyText(outputEl.value, this);
        });
        document.getElementById('btnConfigSendToDiff').addEventListener('click', function () {
            try {
                const parsed = parseInput(inputEl.value, srcFmtEl.value);
                window.WindBlogToolPipe.send('/tools/json-diff', JSON.stringify(parsed, null, 2));
            } catch (e) {
                errEl.textContent = e.message;
                errEl.classList.remove('hidden');
            }
        });
        document.getElementById('btnConfigSample').addEventListener('click', function () {
            srcFmtEl.value = 'yaml';
            tgtFmtEl.value = 'properties';
            inputEl.value = [
                'quarkus:',
                '  http:',
                '    port: 8080',
                '    cors: true',
                '  datasource:',
                '    db-kind: postgresql',
                '    username: windblog'
            ].join('\n');
            convert();
        });
    }

    // =========================================================================
    // 5. cURL Converter & URL Query Inspector
    // =========================================================================
    function mountCurlConverter() {
        const root = document.getElementById('curlConverterToolApp');
        if (!root) return;

        const curlInput = document.getElementById('curlInput');
        const curlOut = document.getElementById('curlCodeOutput');
        const parsedMeta = document.getElementById('curlParsedMeta');
        const tabs = document.getElementById('curlTargetTabs');
        const urlInput = document.getElementById('urlInspectorInput');
        const urlParts = document.getElementById('urlPartsSummary');
        const urlParamsList = document.getElementById('urlParamsList');
        let activeLang = 'fetch';

        function tokenizeShell(cmd) {
            const tokens = [];
            let cur = '';
            let quote = null;
            for (let i = 0; i < cmd.length; i++) {
                const c = cmd[i];
                if (c === '\\' && cmd[i + 1] === '\n') { i++; continue; }
                if (quote) {
                    if (c === quote) quote = null;
                    else if (c === '\\' && quote === '"' && i + 1 < cmd.length) cur += cmd[++i];
                    else cur += c;
                } else if (c === '"' || c === "'") {
                    quote = c;
                } else if (/\s/.test(c)) {
                    if (cur) { tokens.push(cur); cur = ''; }
                } else {
                    cur += c;
                }
            }
            if (cur) tokens.push(cur);
            return tokens;
        }

        function parseCurl(cmd) {
            const tokens = tokenizeShell(cmd.trim());
            let method = null;
            let url = '';
            const headers = {};
            let body = null;

            for (let i = 0; i < tokens.length; i++) {
                const t = tokens[i];
                if (t === 'curl') continue;
                if (t === '-X' || t === '--request') {
                    method = (tokens[++i] || 'GET').toUpperCase();
                } else if (t === '-H' || t === '--header') {
                    const h = tokens[++i] || '';
                    const idx = h.indexOf(':');
                    if (idx > 0) headers[h.slice(0, idx).trim()] = h.slice(idx + 1).trim();
                } else if (t === '-d' || t === '--data' || t === '--data-raw' || t === '--data-binary') {
                    body = tokens[++i] || '';
                } else if (t === '-u' || t === '--user') {
                    headers['Authorization'] = 'Basic ' + btoa(tokens[++i] || '');
                } else if (!t.startsWith('-') && !url) {
                    url = t;
                }
            }
            if (!method) method = body != null ? 'POST' : 'GET';
            return { method, url: url || 'https://api.example.com', headers, body };
        }

        function generateCode(req, lang) {
            const headerEntries = Object.entries(req.headers);
            if (lang === 'fetch') {
                const opts = { method: req.method };
                if (headerEntries.length) opts.headers = req.headers;
                if (req.body != null) opts.body = req.body;
                return `const response = await fetch(${JSON.stringify(req.url)}, ${JSON.stringify(opts, null, 2)});\nconst data = await response.json();`;
            }
            if (lang === 'python') {
                const lines = ['import requests', ''];
                lines.push(`url = ${JSON.stringify(req.url)}`);
                if (headerEntries.length) lines.push(`headers = ${JSON.stringify(req.headers, null, 2)}`);
                if (req.body != null) lines.push(`data = ${JSON.stringify(req.body)}`);
                const args = ['url'];
                if (headerEntries.length) args.push('headers=headers');
                if (req.body != null) args.push('data=data');
                lines.push(`response = requests.${req.method.toLowerCase()}(${args.join(', ')})`);
                lines.push('print(response.status_code, response.text)');
                return lines.join('\n');
            }
            if (lang === 'java') {
                const lines = [
                    'HttpClient client = HttpClient.newHttpClient();',
                    'HttpRequest.Builder builder = HttpRequest.newBuilder()',
                    `        .uri(URI.create(${JSON.stringify(req.url)}))`
                ];
                headerEntries.forEach(([k, v]) => {
                    lines.push(`        .header(${JSON.stringify(k)}, ${JSON.stringify(v)})`);
                });
                const bodyExpr = req.body != null ? `HttpRequest.BodyPublishers.ofString(${JSON.stringify(req.body)})` : 'HttpRequest.BodyPublishers.noBody()';
                lines.push(`        .method(${JSON.stringify(req.method)}, ${bodyExpr});`);
                lines.push('HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());');
                return lines.join('\n');
            }
            // go
            const lines = ['package main', '', 'req, _ := http.NewRequest(' + JSON.stringify(req.method) + ', ' + JSON.stringify(req.url) + ', ' + (req.body != null ? 'strings.NewReader(' + JSON.stringify(req.body) + ')' : 'nil') + ')'];
            headerEntries.forEach(([k, v]) => {
                lines.push(`req.Header.Set(${JSON.stringify(k)}, ${JSON.stringify(v)})`);
            });
            lines.push('resp, err := http.DefaultClient.Do(req)');
            return lines.join('\n');
        }

        function updateCurl() {
            const req = parseCurl(curlInput.value);
            parsedMeta.textContent = req.method + ' • ' + Object.keys(req.headers).length + ' headers';
            curlOut.textContent = generateCode(req, activeLang);
            if (req.url && req.url !== 'https://api.example.com' && document.activeElement !== urlInput) {
                urlInput.value = req.url;
                renderUrlInspector();
            }
        }

        function renderUrlInspector() {
            const raw = (urlInput.value || '').trim();
            if (!raw) {
                urlParts.innerHTML = '';
                urlParamsList.innerHTML = '';
                return;
            }
            try {
                const u = new URL(raw);
                urlParts.innerHTML = [
                    ['Protocol', u.protocol],
                    ['Host', u.host],
                    ['Pathname', u.pathname],
                    ['Hash', u.hash || '(none)']
                ].map(([k, v]) => `
                    <div class="bg-input-bg border border-border rounded-xl p-2.5">
                        <div class="text-[10px] text-meta">${k}</div>
                        <div class="text-main font-bold truncate">${escapeHtml(v)}</div>
                    </div>
                `).join('');

                const entries = Array.from(u.searchParams.entries());
                urlParamsList.innerHTML = entries.map(([k, v], idx) => `
                    <div class="flex items-center gap-2 text-xs font-mono" data-param-row="${idx}">
                        <input type="text" value="${escapeHtml(k)}" data-param-key class="w-1/3 bg-input-bg border border-border rounded-lg px-2.5 py-1.5 text-accent outline-none" />
                        <input type="text" value="${escapeHtml(v)}" data-param-val class="flex-1 bg-input-bg border border-border rounded-lg px-2.5 py-1.5 text-main outline-none" />
                        <button type="button" data-param-del class="px-2 py-1.5 rounded-lg bg-input-bg border border-border text-meta hover:text-rose-400">×</button>
                    </div>
                `).join('');

                urlParamsList.querySelectorAll('input').forEach(inp => inp.addEventListener('input', rebuildUrlFromParams));
                urlParamsList.querySelectorAll('[data-param-del]').forEach(btn => {
                    btn.addEventListener('click', () => {
                        btn.closest('[data-param-row]').remove();
                        rebuildUrlFromParams();
                    });
                });
            } catch (e) {
                urlParts.innerHTML = `<div class="col-span-4 text-rose-400">无效 URL: ${escapeHtml(e.message)}</div>`;
                urlParamsList.innerHTML = '';
            }
        }

        function rebuildUrlFromParams() {
            try {
                const u = new URL(urlInput.value.trim());
                u.search = '';
                urlParamsList.querySelectorAll('[data-param-row]').forEach(row => {
                    const k = row.querySelector('[data-param-key]').value;
                    const v = row.querySelector('[data-param-val]').value;
                    if (k) u.searchParams.append(k, v);
                });
                urlInput.value = u.toString();
            } catch (e) {}
        }

        tabs.addEventListener('click', function (e) {
            const btn = e.target.closest('[data-curl-lang]');
            if (!btn) return;
            activeLang = btn.getAttribute('data-curl-lang');
            tabs.querySelectorAll('[data-curl-lang]').forEach(b => {
                b.className = b.getAttribute('data-curl-lang') === activeLang
                    ? 'px-2.5 py-1 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold'
                    : 'px-2.5 py-1 rounded-lg bg-input-bg border border-border text-meta hover:text-main';
            });
            updateCurl();
        });

        curlInput.addEventListener('input', updateCurl);
        urlInput.addEventListener('input', renderUrlInspector);
        document.getElementById('btnCopyCurlCode').addEventListener('click', function () {
            copyText(curlOut.textContent, this);
        });
        document.getElementById('btnAddUrlParam').addEventListener('click', function () {
            if (!urlInput.value.trim()) urlInput.value = 'https://www.geekcorelab.com/search';
            try {
                const u = new URL(urlInput.value.trim());
                u.searchParams.append('key', 'value');
                urlInput.value = u.toString();
                renderUrlInspector();
            } catch (e) {}
        });
        document.getElementById('btnCurlSample').addEventListener('click', function () {
            curlInput.value = "curl 'https://www.geekcorelab.com/api/public/tools/hash' \\\n  -X POST \\\n  -H 'Content-Type: application/json' \\\n  -d '{\"text\":\"hello windblog\",\"algorithm\":\"SHA-256\"}'";
            updateCurl();
        });
    }

    // =========================================================================
    // 6. UUID / ULID / Snowflake ID Generator & Inspector
    // =========================================================================
    function mountIdGenerator() {
        const root = document.getElementById('idGeneratorToolApp');
        if (!root) return;

        const typeEl = document.getElementById('idGenType');
        const countEl = document.getElementById('idGenCount');
        const workerEl = document.getElementById('idGenWorker');
        const dcEl = document.getElementById('idGenDatacenter');
        const upperEl = document.getElementById('idGenUppercase');
        const outEl = document.getElementById('idGenOutput');
        const inspectEl = document.getElementById('idInspectInput');
        const epochEl = document.getElementById('idInspectEpoch');
        const inspectRes = document.getElementById('idInspectResult');

        const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
        let sfSeq = 0n;
        let sfLastTs = 0n;

        function randomBytes(n) {
            const arr = new Uint8Array(n);
            crypto.getRandomValues(arr);
            return arr;
        }

        function genUuidV4() {
            if (crypto.randomUUID) return crypto.randomUUID();
            const b = randomBytes(16);
            b[6] = (b[6] & 0x0f) | 0x40;
            b[8] = (b[8] & 0x3f) | 0x80;
            const h = Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
            return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
        }

        function genUuidV7() {
            const now = BigInt(Date.now());
            const b = randomBytes(16);
            b[0] = Number((now >> 40n) & 0xffn);
            b[1] = Number((now >> 32n) & 0xffn);
            b[2] = Number((now >> 24n) & 0xffn);
            b[3] = Number((now >> 16n) & 0xffn);
            b[4] = Number((now >> 8n) & 0xffn);
            b[5] = Number(now & 0xffn);
            b[6] = (b[6] & 0x0f) | 0x70;
            b[8] = (b[8] & 0x3f) | 0x80;
            const h = Array.from(b, x => x.toString(16).padStart(2, '0')).join('');
            return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`;
        }

        function genUlid() {
            let ts = Date.now();
            let timeChars = '';
            for (let i = 0; i < 10; i++) {
                timeChars = CROCKFORD[ts % 32] + timeChars;
                ts = Math.floor(ts / 32);
            }
            const rand = randomBytes(16);
            let randChars = '';
            for (let i = 0; i < 16; i++) {
                randChars += CROCKFORD[rand[i] & 31];
            }
            return timeChars + randChars;
        }

        function genSnowflake(worker, dc, epochMs) {
            const now = BigInt(Date.now());
            if (now === sfLastTs) sfSeq = (sfSeq + 1n) & 4095n;
            else { sfSeq = 0n; sfLastTs = now; }
            const tsPart = (now - BigInt(epochMs)) << 22n;
            const dcPart = (BigInt(dc & 31)) << 17n;
            const wPart = (BigInt(worker & 31)) << 12n;
            return (tsPart | dcPart | wPart | sfSeq).toString();
        }

        function generateBatch() {
            const type = typeEl.value;
            const count = Math.max(1, Math.min(100, parseInt(countEl.value || '10', 10) || 10));
            const worker = parseInt(workerEl.value || '1', 10) || 0;
            const dc = parseInt(dcEl.value || '1', 10) || 0;
            const epoch = parseInt(epochEl.value || '1288834974657', 10) || 1288834974657;
            const upper = upperEl.checked;

            const list = [];
            for (let i = 0; i < count; i++) {
                let id = '';
                if (type === 'uuidv7') id = genUuidV7();
                else if (type === 'uuidv4') id = genUuidV4();
                else if (type === 'ulid') id = genUlid();
                else id = genSnowflake(worker, dc, epoch);
                list.push(upper ? id.toUpperCase() : id);
            }
            outEl.value = list.join('\n');
            if (!inspectEl.value.trim() && list.length > 0 && type !== 'uuidv4') {
                inspectEl.value = list[0];
                inspectId();
            }
        }

        function inspectId() {
            const raw = (inspectEl.value || '').trim();
            if (!raw) {
                inspectRes.textContent = '在上方输入任意 Snowflake ID、ULID 或 UUID v7 即可查看解析详情。';
                return;
            }
            if (/^\d{15,20}$/.test(raw)) {
                try {
                    const id = BigInt(raw);
                    const epoch = BigInt(parseInt(epochEl.value || '1288834974657', 10) || 1288834974657);
                    const ts = Number((id >> 22n) + epoch);
                    const dc = Number((id >> 17n) & 31n);
                    const worker = Number((id >> 12n) & 31n);
                    const seq = Number(id & 4095n);
                    const d = new Date(ts);
                    inspectRes.innerHTML = `
                        <div>类型: <span class="text-accent font-bold">Snowflake 64-bit</span></div>
                        <div>UTC 时间: <span class="text-main">${escapeHtml(d.toISOString())}</span></div>
                        <div>本地时间: <span class="text-main">${escapeHtml(d.toLocaleString())}</span></div>
                        <div>毫秒时间戳: <span class="text-main">${ts}</span></div>
                        <div>Datacenter / Worker / Seq: <span class="text-main">${dc} / ${worker} / ${seq}</span></div>
                    `;
                    return;
                } catch (e) {}
            }
            if (/^[0-9A-HJKMNP-TV-Z]{26}$/i.test(raw)) {
                const upper = raw.toUpperCase();
                let ts = 0;
                for (let i = 0; i < 10; i++) {
                    const idx = CROCKFORD.indexOf(upper[i]);
                    if (idx === -1) { inspectRes.textContent = '无效 ULID 字符'; return; }
                    ts = ts * 32 + idx;
                }
                const d = new Date(ts);
                inspectRes.innerHTML = `
                    <div>类型: <span class="text-accent font-bold">ULID</span></div>
                    <div>UTC 时间: <span class="text-main">${escapeHtml(d.toISOString())}</span></div>
                    <div>本地时间: <span class="text-main">${escapeHtml(d.toLocaleString())}</span></div>
                    <div>毫秒时间戳: <span class="text-main">${ts}</span></div>
                `;
                return;
            }
            if (/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(raw)) {
                const hex = raw.replace(/-/g, '').slice(0, 12);
                const ts = parseInt(hex, 16);
                const d = new Date(ts);
                inspectRes.innerHTML = `
                    <div>类型: <span class="text-accent font-bold">UUID v7</span></div>
                    <div>UTC 时间: <span class="text-main">${escapeHtml(d.toISOString())}</span></div>
                    <div>本地时间: <span class="text-main">${escapeHtml(d.toLocaleString())}</span></div>
                    <div>毫秒时间戳: <span class="text-main">${ts}</span></div>
                `;
                return;
            }
            inspectRes.innerHTML = '<span class="text-amber-400">未识别为带时间戳的 Snowflake ID / ULID / UUID v7。</span>';
        }

        document.getElementById('btnGenerateIds').addEventListener('click', generateBatch);
        document.getElementById('btnCopyGeneratedIds').addEventListener('click', function () {
            copyText(outEl.value, this);
        });
        inspectEl.addEventListener('input', inspectId);
        epochEl.addEventListener('input', inspectId);
        root.querySelectorAll('[data-epoch-preset]').forEach(btn => {
            btn.addEventListener('click', () => {
                epochEl.value = btn.getAttribute('data-epoch-preset');
                inspectId();
            });
        });
        generateBatch();
    }

    // =========================================================================
    // 7. JSONL (NDJSON) to Table & Exporter
    // =========================================================================
    function mountJsonlTable() {
        const root = document.getElementById('jsonlTableToolApp');
        if (!root) return;

        const inputEl = document.getElementById('jsonlInput');
        const fileEl = document.getElementById('jsonlFileInput');
        const flattenChk = document.getElementById('chkJsonlFlatten');
        const filterEl = document.getElementById('jsonlFilterInput');
        const parseStats = document.getElementById('jsonlParseStats');
        const visibleStats = document.getElementById('jsonlVisibleStats');
        const errEl = document.getElementById('jsonlErrorInfo');
        const colsBar = document.getElementById('jsonlColumnsBar');
        const thead = document.getElementById('jsonlTableHead');
        const tbody = document.getElementById('jsonlTableBody');

        let rows = [];
        let allCols = [];
        let hiddenCols = new Set();
        let sortCol = null;
        let sortAsc = true;

        function flattenRow(obj, prefix, out) {
            if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) {
                out[prefix || 'value'] = obj;
                return;
            }
            Object.keys(obj).forEach(k => {
                const key = prefix ? prefix + '.' + k : k;
                const val = obj[k];
                if (val !== null && typeof val === 'object' && !Array.isArray(val)) {
                    flattenRow(val, key, out);
                } else {
                    out[key] = val;
                }
            });
        }

        function formatCell(val) {
            if (val === undefined) return '';
            if (val === null) return 'null';
            if (typeof val === 'object') return JSON.stringify(val);
            return String(val);
        }

        function parseJsonl() {
            const lines = (inputEl.value || '').split(/\r?\n/);
            const flatten = flattenChk.checked;
            rows = [];
            const colSet = [];
            const badLines = [];

            lines.forEach((line, idx) => {
                const t = line.trim();
                if (!t) return;
                try {
                    const parsed = JSON.parse(t);
                    let flat;
                    if (flatten && parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                        flat = {};
                        flattenRow(parsed, '', flat);
                    } else if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
                        flat = parsed;
                    } else {
                        flat = { value: parsed };
                    }
                    Object.keys(flat).forEach(k => {
                        if (!colSet.includes(k)) colSet.push(k);
                    });
                    rows.push(flat);
                } catch (e) {
                    badLines.push(idx + 1);
                }
            });

            allCols = colSet;
            parseStats.textContent = rows.length + ' 行 • ' + allCols.length + ' 列';
            if (badLines.length > 0) {
                errEl.textContent = '已跳过 ' + badLines.length + ' 行无效 JSON（行号: ' + badLines.slice(0, 8).join(', ') + (badLines.length > 8 ? '...' : '') + '）';
                errEl.classList.remove('hidden');
            } else {
                errEl.classList.add('hidden');
            }

            renderColumnsBar();
            renderTable();
        }

        function renderColumnsBar() {
            colsBar.innerHTML = allCols.map(col => {
                const active = !hiddenCols.has(col);
                return `<button type="button" data-col-toggle="${escapeHtml(col)}" class="px-2 py-0.5 rounded border ${active ? 'bg-accent/15 text-accent border-accent/30' : 'bg-input-bg text-meta border-border line-through'}">${escapeHtml(col)}</button>`;
            }).join('');
            colsBar.querySelectorAll('[data-col-toggle]').forEach(btn => {
                btn.addEventListener('click', () => {
                    const c = btn.getAttribute('data-col-toggle');
                    if (hiddenCols.has(c)) hiddenCols.delete(c);
                    else hiddenCols.add(c);
                    renderColumnsBar();
                    renderTable();
                });
            });
        }

        function getFilteredSortedRows() {
            const q = (filterEl.value || '').trim().toLowerCase();
            const activeCols = allCols.filter(c => !hiddenCols.has(c));
            let filtered = rows.filter(r => {
                if (!q) return true;
                return activeCols.some(c => formatCell(r[c]).toLowerCase().includes(q));
            });
            if (sortCol) {
                filtered = filtered.slice().sort((a, b) => {
                    const av = a[sortCol], bv = b[sortCol];
                    if (typeof av === 'number' && typeof bv === 'number') return sortAsc ? av - bv : bv - av;
                    return sortAsc
                        ? formatCell(av).localeCompare(formatCell(bv))
                        : formatCell(bv).localeCompare(formatCell(av));
                });
            }
            return { activeCols, filtered };
        }

        function renderTable() {
            const { activeCols, filtered } = getFilteredSortedRows();
            visibleStats.textContent = '显示 ' + filtered.length + ' / ' + rows.length + ' 行';

            if (activeCols.length === 0) {
                thead.innerHTML = '';
                tbody.innerHTML = '<tr><td class="p-6 text-center text-meta">暂无可显示的数据列</td></tr>';
                return;
            }

            thead.innerHTML = '<tr>' + activeCols.map(c => {
                const arrow = sortCol === c ? (sortAsc ? ' ▲' : ' ▼') : '';
                return `<th data-sort-col="${escapeHtml(c)}" class="px-3 py-2 text-left font-bold cursor-pointer hover:text-accent select-none whitespace-nowrap">${escapeHtml(c)}${arrow}</th>`;
            }).join('') + '</tr>';

            thead.querySelectorAll('[data-sort-col]').forEach(th => {
                th.addEventListener('click', () => {
                    const c = th.getAttribute('data-sort-col');
                    if (sortCol === c) sortAsc = !sortAsc;
                    else { sortCol = c; sortAsc = true; }
                    renderTable();
                });
            });

            const displayRows = filtered.slice(0, 500);
            tbody.innerHTML = displayRows.map(r =>
                '<tr class="hover:bg-input-bg/50">' + activeCols.map(c =>
                    `<td class="px-3 py-2 whitespace-nowrap max-w-xs truncate" title="${escapeHtml(formatCell(r[c]))}">${escapeHtml(formatCell(r[c]))}</td>`
                ).join('') + '</tr>'
            ).join('');
        }

        inputEl.addEventListener('input', parseJsonl);
        flattenChk.addEventListener('change', parseJsonl);
        filterEl.addEventListener('input', renderTable);
        if (fileEl) {
            fileEl.addEventListener('change', () => {
                const f = fileEl.files && fileEl.files[0];
                if (!f) return;
                const reader = new FileReader();
                reader.onload = () => {
                    inputEl.value = String(reader.result || '');
                    parseJsonl();
                };
                reader.readAsText(f);
            });
        }

        document.getElementById('btnJsonlClear').addEventListener('click', () => {
            inputEl.value = '';
            hiddenCols.clear();
            parseJsonl();
        });

        document.getElementById('btnJsonlSample').addEventListener('click', () => {
            inputEl.value = [
                '{"ts":"2026-11-15T10:00:01Z","service":"blog-api","status":200,"latency_ms":14,"user":{"id":101,"plan":"pro"}}',
                '{"ts":"2026-11-15T10:00:02Z","service":"search","status":200,"latency_ms":38,"user":{"id":102,"plan":"free"}}',
                '{"ts":"2026-11-15T10:00:04Z","service":"mcp-tools","status":400,"latency_ms":6,"user":{"id":103,"plan":"agent"}}'
            ].join('\n');
            parseJsonl();
        });

        document.getElementById('btnExportCsv').addEventListener('click', () => {
            const { activeCols, filtered } = getFilteredSortedRows();
            if (!activeCols.length) return;
            const csvCell = v => '"' + formatCell(v).replace(/"/g, '""') + '"';
            const csv = [activeCols.map(csvCell).join(',')]
                .concat(filtered.map(r => activeCols.map(c => csvCell(r[c])).join(',')))
                .join('\n');
            const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'jsonl-export.csv';
            a.click();
        });

        document.getElementById('btnExportMarkdown').addEventListener('click', function () {
            const { activeCols, filtered } = getFilteredSortedRows();
            if (!activeCols.length) return;
            const esc = v => formatCell(v).replace(/\|/g, '\\|').replace(/\r?\n/g, ' ');
            const lines = [
                '| ' + activeCols.map(esc).join(' | ') + ' |',
                '| ' + activeCols.map(() => '---').join(' | ') + ' |'
            ];
            filtered.forEach(r => {
                lines.push('| ' + activeCols.map(c => esc(r[c])).join(' | ') + ' |');
            });
            copyText(lines.join('\n'), this);
        });

        document.getElementById('btnExportJsonArray').addEventListener('click', function () {
            const { filtered } = getFilteredSortedRows();
            copyText(JSON.stringify(filtered, null, 2), this);
        });

        const piped = window.WindBlogToolPipe.consume('/tools/jsonl-to-table');
        if (piped) {
            inputEl.value = piped;
            parseJsonl();
        }
    }

    // =========================================================================
    // 8. SEO Preset Query Support for Existing Tools (?preset=...)
    // =========================================================================
    function applyUrlPresetIfPresent() {
        const params = new URLSearchParams(window.location.search);
        const preset = params.get('preset');
        if (!preset) return;
        const cronInput = document.getElementById('cronExprInput');
        if (cronInput) {
            const cronPresets = {
                'every-5-min': '*/5 * * * *',
                'hourly': '0 * * * *',
                'daily-midnight': '0 0 * * *',
                'weekly-monday': '0 9 * * 1'
            };
            cronInput.value = cronPresets[preset] || preset;
            cronInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
        const subnetInput = document.getElementById('subnetInput');
        if (subnetInput) {
            subnetInput.value = preset;
            subnetInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
        const regexInput = document.getElementById('regexPatternInput');
        if (regexInput) {
            regexInput.value = preset;
            regexInput.dispatchEvent(new Event('input', { bubbles: true }));
        }
    }

    window.WindBlogToolsSuite = {
        mount: function () {
            trackCurrentToolVisit();
            mountToolsIndex();
            mountAiToken();
            mountJwtTool();
            mountConfigConverter();
            mountCurlConverter();
            mountIdGenerator();
            mountJsonlTable();
            applyUrlPresetIfPresent();
        },
        unmount: function () {}
    };
})();
