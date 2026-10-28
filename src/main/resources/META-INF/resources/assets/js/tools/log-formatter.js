/**
 * 日志格式化与结构化分析器
 */
(function () {
    'use strict';

    function initLogFormatter() {
        const root = document.getElementById('logFormatterToolApp');
        if (!root) return;

        const rawTextarea = document.getElementById('rawLogTextarea');
        const renderContainer = document.getElementById('logRenderContainer');
        const btnFormat = document.getElementById('btnFormatLog');
        const btnClear = document.getElementById('btnClearLog');
        const btnCopy = document.getElementById('btnCopyLogResult');
        const levelFilter = document.getElementById('logLevelFilter');
        const keywordSearch = document.getElementById('logKeywordSearch');
        const rawLogStats = document.getElementById('rawLogStats');
        const formattedLogStats = document.getElementById('formattedLogStats');
        const logSummaryBadge = document.getElementById('logSummaryBadge');

        const EXAMPLES = {
            json: `{"timestamp":"2026-10-28T10:45:12.345Z","level":"INFO","thread":"vert.x-eventloop-thread-1","logger":"com.biliwind.blog.controller.ToolsController","message":"Serving subnet calculator request","clientIp":"127.0.0.1","durationMs":12}
{"timestamp":"2026-10-28T10:45:13.120Z","level":"WARN","thread":"executor-thread-5","logger":"com.biliwind.blog.service.security.HoneypotService","message":"Unauthenticated probe detected on admin endpoint","path":"/api/admin/system/hidden","sourceIp":"198.51.100.4"}
{"timestamp":"2026-10-28T10:45:14.002Z","level":"ERROR","thread":"executor-thread-8","logger":"com.biliwind.blog.service.storage.MinioClient","message":"Connection timed out when syncing media asset","bucket":"windblog-uploads","key":"2026/10/banner.webp","retryAttempts":3}`,
            stacktrace: `2026-10-28 10:45:20,123 ERROR [c.b.b.c.GlobalExceptionMapper] (executor-thread-14) 未捕获的全局异常: io.quarkus.qute.TemplateException: Rendering error in template [tools/index.html]
\tat io.quarkus.qute.EvaluatorImpl.propertyNotFound(EvaluatorImpl.java:237)
\tat io.quarkus.qute.EvaluatorImpl.resolve(EvaluatorImpl.java:207)
\tat io.quarkus.qute.ExpressionNode.resolve(ExpressionNode.java:36)
\tat java.base/java.lang.Thread.run(Thread.java:1583)
Caused by: java.lang.NullPointerException: Cannot invoke "String.length()" because "url" is null
\tat com.biliwind.blog.controller.ToolsController.render(ToolsController.java:42)
\t... 32 more`,
            nginx: `192.168.1.105 - - [28/Oct/2026:10:45:22 +0800] "GET /tools/subnet HTTP/2.0" 200 26876 "https://www.geekcorelab.com/tools" "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
198.51.100.2 - - [28/Oct/2026:10:45:23 +0800] "POST /wp-login.php HTTP/1.1" 404 153 "-" "curl/7.88.1"
10.0.0.88 - - [28/Oct/2026:10:45:25 +0800] "GET /api/v1/health HTTP/1.1" 500 52 "-" "Consul-HealthCheck/1.16"`
        };

        function parseAndRenderLogs() {
            const raw = rawTextarea.value;
            if (!raw.trim()) {
                rawLogStats.textContent = '0 行 · 0 字符';
                formattedLogStats.textContent = '显示 0 / 0 行';
                renderContainer.innerHTML = '<p class="text-meta/60 italic text-center py-12">在左侧粘贴日志或点击右上角示例体验...</p>';
                logSummaryBadge.textContent = '空内容';
                return;
            }

            const lines = raw.split('\n');
            rawLogStats.textContent = `${lines.length} 行 · ${raw.length} 字符`;

            const filterLvl = levelFilter.value;
            const searchKw = keywordSearch.value.trim().toLowerCase();

            renderContainer.innerHTML = '';
            let displayedCount = 0;

            lines.forEach((line, idx) => {
                const trimmed = line.trim();
                if (!trimmed) return;

                // Detect level
                let lineLvl = 'OTHER';
                const upper = line.toUpperCase();
                if (upper.includes('ERROR') || upper.includes('FATAL') || upper.includes('SEVERE') || upper.includes('EXCEPTION') || line.includes('" 500') || line.includes('" 502')) {
                    lineLvl = 'ERROR';
                } else if (upper.includes('WARN') || line.includes('" 404') || line.includes('" 403')) {
                    lineLvl = 'WARN';
                } else if (upper.includes('INFO')) {
                    lineLvl = 'INFO';
                } else if (upper.includes('DEBUG') || upper.includes('TRACE')) {
                    lineLvl = 'DEBUG';
                }

                // Level Filter
                if (filterLvl === 'ERROR' && lineLvl !== 'ERROR') return;
                if (filterLvl === 'WARN' && lineLvl !== 'ERROR' && lineLvl !== 'WARN') return;
                if (filterLvl === 'INFO' && (lineLvl === 'DEBUG' || lineLvl === 'OTHER')) return;

                // Keyword Search
                if (searchKw && !line.toLowerCase().includes(searchKw)) return;

                displayedCount++;
                const lineEl = document.createElement('div');
                lineEl.className = 'py-1 px-2 rounded hover:bg-accent/10 transition-colors flex items-start gap-2.5 font-mono text-xs leading-normal';

                let badgeClass = 'bg-border text-meta';
                if (lineLvl === 'ERROR') badgeClass = 'bg-red-500/15 text-red-600 dark:text-red-400 border border-red-500/30';
                else if (lineLvl === 'WARN') badgeClass = 'bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30';
                else if (lineLvl === 'INFO') badgeClass = 'bg-cyan-500/15 text-cyan-700 dark:text-cyan-300 border border-cyan-500/30';
                else if (lineLvl === 'DEBUG') badgeClass = 'bg-border/60 text-meta/80';

                // Attempt pretty JSON if single line JSON
                let contentText = line;
                if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
                    try {
                        const parsed = JSON.parse(trimmed);
                        contentText = JSON.stringify(parsed, null, 2);
                    } catch (e) {}
                }

                const lineNum = document.createElement('span');
                lineNum.className = 'text-meta/40 text-[10px] w-6 shrink-0 select-none text-right';
                lineNum.textContent = (idx + 1).toString();

                const badge = document.createElement('span');
                badge.className = `px-1.5 py-0.2 rounded text-[10px] font-bold shrink-0 ${badgeClass}`;
                badge.textContent = lineLvl;

                const textPre = document.createElement('pre');
                textPre.className = 'flex-1 text-main whitespace-pre-wrap break-all font-mono leading-relaxed';
                textPre.textContent = contentText;

                lineEl.appendChild(lineNum);
                lineEl.appendChild(badge);
                lineEl.appendChild(textPre);
                renderContainer.appendChild(lineEl);
            });

            formattedLogStats.textContent = `显示 ${displayedCount} / ${lines.length} 行`;
            logSummaryBadge.textContent = displayedCount > 0 ? `匹配 ${displayedCount} 行` : '无匹配';
        }

        btnFormat.addEventListener('click', parseAndRenderLogs);
        rawTextarea.addEventListener('input', parseAndRenderLogs);
        levelFilter.addEventListener('change', parseAndRenderLogs);
        keywordSearch.addEventListener('input', parseAndRenderLogs);

        btnClear.addEventListener('click', () => {
            rawTextarea.value = '';
            parseAndRenderLogs();
        });

        btnCopy.addEventListener('click', () => {
            const lines = [];
            renderContainer.querySelectorAll('pre').forEach(p => lines.push(p.textContent));
            if (lines.length > 0) {
                navigator.clipboard.writeText(lines.join('\n'));
                btnCopy.innerHTML = '<i class="fa-solid fa-check text-emerald-500"></i>';
                setTimeout(() => { btnCopy.innerHTML = '<i class="fa-regular fa-copy"></i>'; }, 1500);
            }
        });

        document.querySelectorAll('.btn-log-example').forEach(btn => {
            btn.addEventListener('click', () => {
                const type = btn.getAttribute('data-type');
                if (EXAMPLES[type]) {
                    rawTextarea.value = EXAMPLES[type];
                    parseAndRenderLogs();
                }
            });
        });

        // Initialize with default example
        rawTextarea.value = EXAMPLES.json;
        parseAndRenderLogs();
    }

    if (document.readyState !== 'loading') {
        initLogFormatter();
    } else {
        document.addEventListener('DOMContentLoaded', initLogFormatter);
    }
    document.addEventListener('pjax:complete', initLogFormatter);
})();
