/**
 * 时间戳与时区转换交互脚本
 */
(function () {
    'use strict';

    function initTimestampTool() {
        const root = document.getElementById('timestampToolApp');
        if (!root) return;

        const currentSecStamp = document.getElementById('currentSecStamp');
        const currentMsStamp = document.getElementById('currentMsStamp');
        const btnPauseClock = document.getElementById('btnPauseClock');
        const inputTimestamp = document.getElementById('inputTimestamp');
        const selectStampUnit = document.getElementById('selectStampUnit');
        const resLocalDate = document.getElementById('resLocalDate');
        const resUtcDate = document.getElementById('resUtcDate');
        const resRelativeTime = document.getElementById('resRelativeTime');

        const inputDateTimeStr = document.getElementById('inputDateTimeStr');
        const btnFillNowDate = document.getElementById('btnFillNowDate');
        const resStampSec = document.getElementById('resStampSec');
        const resStampMs = document.getElementById('resStampMs');
        const resWeekInfo = document.getElementById('resWeekInfo');
        const worldTimezonesGrid = document.getElementById('worldTimezonesGrid');

        let isClockRunning = true;
        let clockTimer = null;

        function pad(n) {
            return String(n).padStart(2, '0');
        }

        function formatDate(d) {
            return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
        }

        function updateClock() {
            if (!isClockRunning) return;
            const now = Date.now();
            currentMsStamp.textContent = now.toString();
            currentSecStamp.textContent = Math.floor(now / 1000).toString();
        }

        if (clockTimer) clearInterval(clockTimer);
        clockTimer = setInterval(updateClock, 1000);
        updateClock();

        btnPauseClock.addEventListener('click', () => {
            isClockRunning = !isClockRunning;
            btnPauseClock.innerHTML = isClockRunning ? '<i class="fa-solid fa-pause"></i>' : '<i class="fa-solid fa-play text-accent"></i>';
        });

        // Relative time helper
        function getRelativeTime(timestampMs) {
            const diffSec = Math.round((timestampMs - Date.now()) / 1000);
            const rtf = new Intl.RelativeTimeFormat('zh-CN', { numeric: 'auto' });
            const abs = Math.abs(diffSec);

            if (abs < 60) return rtf.format(diffSec, 'second');
            if (abs < 3600) return rtf.format(Math.round(diffSec / 60), 'minute');
            if (abs < 86400) return rtf.format(Math.round(diffSec / 3600), 'hour');
            if (abs < 2592000) return rtf.format(Math.round(diffSec / 86400), 'day');
            if (abs < 31536000) return rtf.format(Math.round(diffSec / 2592000), 'month');
            return rtf.format(Math.round(diffSec / 31536000), 'year');
        }

        // Timestamp to Date
        function convertStampToDate() {
            let val = inputTimestamp.value.trim();
            if (!val || isNaN(Number(val))) {
                resLocalDate.textContent = '-';
                resUtcDate.textContent = '-';
                resRelativeTime.textContent = '-';
                return;
            }

            let num = Number(val);
            const unit = selectStampUnit.value;
            let ms = num;

            if (unit === 's' || (unit === 'auto' && val.length <= 10)) {
                ms = num * 1000;
            }

            const date = new Date(ms);
            if (isNaN(date.getTime())) {
                resLocalDate.textContent = '无效时间戳';
                resUtcDate.textContent = '-';
                resRelativeTime.textContent = '-';
                return;
            }

            resLocalDate.textContent = formatDate(date);
            resUtcDate.textContent = date.toISOString().replace('T', ' ').replace('Z', ' UTC');
            resRelativeTime.textContent = getRelativeTime(ms);

            updateWorldTimezones(date);
        }

        // Date to Timestamp
        function convertDateToStamp() {
            let str = inputDateTimeStr.value.trim();
            if (!str) {
                resStampSec.textContent = '-';
                resStampMs.textContent = '-';
                resWeekInfo.textContent = '-';
                return;
            }

            // Normalise date string
            let normalized = str.replace(/\//g, '-');
            let date = new Date(normalized);
            if (isNaN(date.getTime())) {
                // Try parse YYYY-MM-DD HH:mm:ss
                date = new Date(normalized.replace(' ', 'T'));
            }

            if (isNaN(date.getTime())) {
                resStampSec.textContent = '无法解析日期格式';
                resStampMs.textContent = '-';
                resWeekInfo.textContent = '-';
                return;
            }

            const ms = date.getTime();
            const sec = Math.floor(ms / 1000);
            resStampSec.textContent = sec.toString();
            resStampMs.textContent = ms.toString();

            const dayNames = ['周日 (Sunday)', '周一 (Monday)', '周二 (Tuesday)', '周三 (Wednesday)', '周四 (Thursday)', '周五 (Friday)', '周六 (Saturday)'];
            const startOfYear = new Date(date.getFullYear(), 0, 1);
            const pastDays = Math.floor((date - startOfYear) / (24 * 3600 * 1000));
            const weekNumber = Math.ceil((pastDays + startOfYear.getDay() + 1) / 7);

            resWeekInfo.textContent = `${dayNames[date.getDay()]} · 第 ${weekNumber} 周`;

            updateWorldTimezones(date);
        }

        const TIMEZONES = [
            { label: '北京 / 上海 (UTC+8)', tz: 'Asia/Shanghai' },
            { label: '东京 (UTC+9)', tz: 'Asia/Tokyo' },
            { label: '伦敦 (UTC+0 / GMT)', tz: 'Europe/London' },
            { label: '巴黎 / 柏林 (UTC+1)', tz: 'Europe/Paris' },
            { label: '纽约 (UTC-5 / EST)', tz: 'America/New_York' },
            { label: '洛杉矶 (UTC-8 / PST)', tz: 'America/Los_Angeles' },
            { label: '悉尼 (UTC+11)', tz: 'Australia/Sydney' },
            { label: '新加坡 (UTC+8)', tz: 'Asia/Singapore' }
        ];

        function updateWorldTimezones(date) {
            worldTimezonesGrid.innerHTML = '';
            TIMEZONES.forEach(item => {
                const card = document.createElement('div');
                card.className = 'p-3 rounded-xl bg-input-bg border border-border/60 space-y-1';
                try {
                    const timeStr = new Intl.DateTimeFormat('zh-CN', {
                        timeZone: item.tz,
                        year: 'numeric',
                        month: '2-digit',
                        day: '2-digit',
                        hour: '2-digit',
                        minute: '2-digit',
                        second: '2-digit',
                        hour12: false
                    }).format(date);

                    card.innerHTML = `
                        <span class="text-meta text-[11px] block truncate">${item.label}</span>
                        <span class="text-main font-bold font-mono text-xs select-all block">${timeStr}</span>
                    `;
                } catch (e) {
                    card.innerHTML = `<span class="text-meta text-[11px]">${item.label}</span><span class="text-main font-mono text-xs">-</span>`;
                }
                worldTimezonesGrid.appendChild(card);
            });
        }

        // Copy buttons
        document.querySelectorAll('.btn-copy-val').forEach(btn => {
            btn.addEventListener('click', () => {
                const targetId = btn.getAttribute('data-target');
                const text = document.getElementById(targetId)?.textContent;
                if (text && text !== '-') {
                    navigator.clipboard.writeText(text);
                    const orig = btn.innerHTML;
                    btn.innerHTML = '<i class="fa-solid fa-check text-emerald-500"></i>';
                    setTimeout(() => { btn.innerHTML = orig; }, 1500);
                }
            });
        });

        // Quick buttons
        document.querySelectorAll('.btn-stamp-quick').forEach(btn => {
            btn.addEventListener('click', () => {
                const mode = btn.getAttribute('data-val');
                if (mode === 'now') {
                    inputTimestamp.value = Math.floor(Date.now() / 1000).toString();
                } else if (mode === 'today_start') {
                    const d = new Date();
                    d.setHours(0, 0, 0, 0);
                    inputTimestamp.value = Math.floor(d.getTime() / 1000).toString();
                }
                convertStampToDate();
            });
        });

        btnFillNowDate.addEventListener('click', () => {
            inputDateTimeStr.value = formatDate(new Date());
            convertDateToStamp();
        });

        inputTimestamp.addEventListener('input', convertStampToDate);
        selectStampUnit.addEventListener('change', convertStampToDate);
        inputDateTimeStr.addEventListener('input', convertDateToStamp);

        // Initial setup
        const initialDate = new Date();
        inputTimestamp.value = Math.floor(initialDate.getTime() / 1000).toString();
        inputDateTimeStr.value = formatDate(initialDate);
        convertStampToDate();
        convertDateToStamp();
    }

    if (document.readyState !== 'loading') {
        initTimestampTool();
    } else {
        document.addEventListener('DOMContentLoaded', initTimestampTool);
    }
    document.addEventListener('pjax:complete', initTimestampTool);
})();
