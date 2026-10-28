/**
 * 新一代智能子网掩码计算器 (Next-Gen Subnet Calculator)
 * 纯客户端原生 JS，支持 IPv4/IPv6、按位与运算推演、32位彩色位图解构、VLSM 划分等
 */
(function () {
    'use strict';

    function initSubnetCalculator() {
        const root = document.getElementById('subnetCalculatorApp');
        if (!root) return;

        // Elements
        const ipInput = document.getElementById('subnetIpInput');
        const maskSelect = document.getElementById('subnetMaskSelect');
        const cidrSlider = document.getElementById('cidrSlider');
        const sliderValueText = document.getElementById('sliderValueText');
        const sliderUsableHostsText = document.getElementById('sliderUsableHostsText');
        const cidrBadge = document.getElementById('cidrBadge');
        const ipTypeBadge = document.getElementById('ipTypeBadge');
        const btnCalculate = document.getElementById('btnCalculate');
        const btnCopyResult = document.getElementById('btnCopyResult');
        const clearIpBtn = document.getElementById('clearIpBtn');
        const errorAlert = document.getElementById('calcErrorAlert');
        const errorMessage = document.getElementById('calcErrorMessage');
        const subdivideSelect = document.getElementById('subdividePrefixSelect');

        // Results elements
        const resNetworkIp = document.getElementById('resNetworkIp');
        const resBroadcastIp = document.getElementById('resBroadcastIp');
        const resHostRange = document.getElementById('resHostRange');
        const resUsableHosts = document.getElementById('resUsableHosts');
        const resTotalAddresses = document.getElementById('resTotalAddresses');
        const bitNetworkCount = document.getElementById('bitNetworkCount');
        const bitHostCount = document.getElementById('bitHostCount');
        const labelIpDec = document.getElementById('labelIpDec');
        const labelMaskDec = document.getElementById('labelMaskDec');
        const labelNetDec = document.getElementById('labelNetDec');
        const bitsContainerIp = document.getElementById('bitsContainerIp');
        const bitsContainerMask = document.getElementById('bitsContainerMask');
        const bitsContainerNet = document.getElementById('bitsContainerNet');
        const stepByStepContainer = document.getElementById('stepByStepContainer');
        const specsTableBody = document.getElementById('specsTableBody');
        const vlsmSubnetsTbody = document.getElementById('vlsmSubnetsTbody');

        // Populate IPv4 mask options
        function populateMaskOptions(currentPrefix) {
            maskSelect.innerHTML = '';
            for (let i = 1; i <= 32; i++) {
                const maskLong = prefixToMaskLong(i);
                const maskDotted = longToIp(maskLong);
                const hosts = i === 32 ? 1 : (i === 31 ? 2 : Math.pow(2, 32 - i) - 2);
                const opt = document.createElement('option');
                opt.value = i;
                opt.textContent = `/${i} — ${maskDotted} (${hosts > 0 ? hosts.toLocaleString() : 0} 可用主机)`;
                if (i === currentPrefix) {
                    opt.selected = true;
                }
                maskSelect.appendChild(opt);
            }
        }

        // Helper calculations
        function ipToLong(ipStr) {
            const parts = ipStr.trim().split('.');
            if (parts.length !== 4) return null;
            let res = 0;
            for (let i = 0; i < 4; i++) {
                const part = Number(parts[i]);
                if (isNaN(part) || part < 0 || part > 255 || parts[i].trim() === '') return null;
                res = (res << 8) | part;
                res = res >>> 0;
            }
            return res;
        }

        function longToIp(longVal) {
            longVal = longVal >>> 0;
            return [
                (longVal >>> 24) & 255,
                (longVal >>> 16) & 255,
                (longVal >>> 8) & 255,
                longVal & 255
            ].join('.');
        }

        function prefixToMaskLong(prefix) {
            if (prefix === 0) return 0;
            return ((0xFFFFFFFF << (32 - prefix)) >>> 0);
        }

        function to8BitBinary(num) {
            return (num >>> 0).toString(2).padStart(8, '0');
        }

        function longToBinaryString(longVal) {
            const ipDotted = longToIp(longVal).split('.');
            return ipDotted.map(octet => to8BitBinary(Number(octet))).join('');
        }

        function getIpClass(firstOctet) {
            if (firstOctet >= 1 && firstOctet <= 126) return 'A 类 (Class A)';
            if (firstOctet === 127) return '环回地址段 (Loopback / 127.0.0.0/8)';
            if (firstOctet >= 128 && firstOctet <= 191) return 'B 类 (Class B)';
            if (firstOctet >= 192 && firstOctet <= 223) return 'C 类 (Class C)';
            if (firstOctet >= 224 && firstOctet <= 239) return 'D 类组播 (Multicast)';
            if (firstOctet >= 240 && firstOctet <= 255) return 'E 类保留实验地址 (Experimental)';
            return '未知';
        }

        function getIpTypeDescription(ipLong) {
            const oct1 = (ipLong >>> 24) & 255;
            const oct2 = (ipLong >>> 16) & 255;
            if (oct1 === 10) return '私有地址 (RFC 1918, 10.0.0.0/8)';
            if (oct1 === 172 && oct2 >= 16 && oct2 <= 31) return '私有地址 (RFC 1918, 172.16.0.0/12)';
            if (oct1 === 192 && oct2 === 168) return '私有地址 (RFC 1918, 192.168.0.0/16)';
            if (oct1 === 127) return '本地环回地址 (Loopback)';
            if (oct1 === 169 && oct2 === 254) return 'APIPA / 链路本地 (Link-Local)';
            if (oct1 >= 224 && oct1 <= 239) return '组播地址 (Multicast)';
            if (oct1 === 100 && oct2 >= 64 && oct2 <= 127) return '运营商级 NAT (Carrier-Grade NAT / RFC 6598)';
            return '公网可路由地址 (Public Internet)';
        }

        // Render Bit Breakdown
        function renderBitmaskRow(container, binaryStr, prefix, colorNet, colorHost) {
            container.innerHTML = '';
            for (let b = 0; b < 4; b++) {
                const octetGroup = document.createElement('div');
                octetGroup.className = 'flex gap-0.5 items-center';
                
                for (let bitIdx = 0; bitIdx < 8; bitIdx++) {
                    const globalIdx = b * 8 + bitIdx;
                    const isNetworkBit = globalIdx < prefix;
                    const char = binaryStr[globalIdx] || '0';
                    const bitSpan = document.createElement('span');
                    bitSpan.className = `w-4 h-6 text-xs flex items-center justify-center font-bold rounded ${
                        isNetworkBit ? colorNet : colorHost
                    }`;
                    bitSpan.textContent = char;
                    bitSpan.title = `第 ${globalIdx + 1} 位: ${isNetworkBit ? '网络位' : '主机位'} (${char})`;
                    octetGroup.appendChild(bitSpan);
                }
                container.appendChild(octetGroup);
                if (b < 3) {
                    const dot = document.createElement('span');
                    dot.className = 'text-meta font-bold px-0.5';
                    dot.textContent = '.';
                    container.appendChild(dot);
                }
            }
        }

        // Main calculation logic
        function calculate() {
            errorAlert.classList.add('hidden');
            let rawInput = ipInput.value.trim();
            if (!rawInput) {
                showError('请输入 IP 地址');
                return;
            }

            // Check if CIDR is entered in IP input (e.g., 10.0.0.1/24)
            let prefix = parseInt(maskSelect.value, 10) || 24;
            if (rawInput.includes('/')) {
                const slashParts = rawInput.split('/');
                rawInput = slashParts[0].trim();
                const p = parseInt(slashParts[1].trim(), 10);
                if (!isNaN(p) && p >= 1 && p <= 32) {
                    prefix = p;
                    maskSelect.value = prefix;
                    cidrSlider.value = prefix;
                }
            }

            // Check IPv6
            if (rawInput.includes(':')) {
                calculateIpv6(rawInput, prefix);
                return;
            }

            ipTypeBadge.textContent = 'IPv4';
            const ipLong = ipToLong(rawInput);
            if (ipLong === null) {
                showError(`无效的 IPv4 地址: "${rawInput}"。请输入合法的点分十进制 IP（如 192.168.1.1）。`);
                return;
            }

            const maskLong = prefixToMaskLong(prefix);
            const wildcardLong = (~maskLong) >>> 0;
            const networkLong = (ipLong & maskLong) >>> 0;
            const broadcastLong = (networkLong | wildcardLong) >>> 0;

            const totalHosts = Math.pow(2, 32 - prefix);
            let usableHosts = 0;
            let firstHostLong = 0;
            let lastHostLong = 0;

            if (prefix === 32) {
                usableHosts = 1;
                firstHostLong = networkLong;
                lastHostLong = networkLong;
            } else if (prefix === 31) {
                // RFC 3021 Point-to-Point
                usableHosts = 2;
                firstHostLong = networkLong;
                lastHostLong = broadcastLong;
            } else {
                usableHosts = totalHosts - 2;
                firstHostLong = networkLong + 1;
                lastHostLong = broadcastLong - 1;
            }

            // Update Primary Metrics
            resNetworkIp.textContent = `${longToIp(networkLong)} /${prefix}`;
            resBroadcastIp.textContent = prefix >= 31 ? 'N/A (P2P/Host)' : longToIp(broadcastLong);
            resHostRange.textContent = `${longToIp(firstHostLong)} ~ ${longToIp(lastHostLong)}`;
            resUsableHosts.textContent = usableHosts.toLocaleString();
            resTotalAddresses.textContent = `总地址: ${totalHosts.toLocaleString()} 个`;

            sliderValueText.textContent = `/${prefix}`;
            cidrBadge.textContent = `/${prefix}`;
            sliderUsableHostsText.textContent = `可用主机数: ${usableHosts.toLocaleString()}`;

            // Update Bit Labels & Counts
            bitNetworkCount.textContent = prefix;
            bitHostCount.textContent = 32 - prefix;
            labelIpDec.textContent = longToIp(ipLong);
            labelMaskDec.textContent = `${longToIp(maskLong)} (/${prefix})`;
            labelNetDec.textContent = `${longToIp(networkLong)} /${prefix}`;

            // Render 32-bit bitmaps
            const ipBinStr = longToBinaryString(ipLong);
            const maskBinStr = longToBinaryString(maskLong);
            const netBinStr = longToBinaryString(networkLong);

            renderBitmaskRow(bitsContainerIp, ipBinStr, prefix, 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40', 'bg-amber-500/20 text-amber-300 border border-amber-500/40');
            renderBitmaskRow(bitsContainerMask, maskBinStr, prefix, 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40', 'bg-border text-meta border-border/40');
            renderBitmaskRow(bitsContainerNet, netBinStr, prefix, 'bg-cyan-500/30 text-cyan-200 font-extrabold border border-cyan-400', 'bg-card text-meta/40 border border-border/30');

            // Render Step-by-Step Derivation
            renderStepByStep(ipLong, maskLong, networkLong, broadcastLong, prefix, totalHosts, usableHosts, firstHostLong, lastHostLong);

            // Render Specifications Table
            renderSpecsTable(ipLong, maskLong, wildcardLong, networkLong, broadcastLong, prefix, totalHosts, usableHosts, firstHostLong, lastHostLong);

            // Update Subdivide VLSM
            updateVlsm(networkLong, prefix);
        }

        function calculateIpv6(rawInput, prefix) {
            ipTypeBadge.textContent = 'IPv6';
            cidrBadge.textContent = `/${prefix > 32 ? prefix : 64}`;
            resNetworkIp.textContent = rawInput;
            resBroadcastIp.textContent = 'IPv6 无广播 (采用组播 Multicast)';
            resHostRange.textContent = '前缀根据 /64 标准自动匹配';
            resUsableHosts.textContent = '2^(128 - ' + (prefix > 32 ? prefix : 64) + ')';
            resTotalAddresses.textContent = '巨大空间 (庞大不可数)';

            stepByStepContainer.innerHTML = `
                <div class="p-4 rounded-xl bg-card border border-border space-y-2">
                    <div class="text-accent font-bold text-sm">IPv6 规则解析</div>
                    <p class="text-body text-xs leading-relaxed">
                        IPv6 地址共 128 位，以 16 进制 8 组冒号分隔表示。IPv6 取消了 IPv4 的广播机制 (Broadcast)，改为通过多播 (Multicast Anycast) 工作。
                        标准单子网推荐长度为 <b>/64</b>（留有 64 位给 SLAAC 无状态自动配置）。
                    </p>
                    <div class="p-2.5 rounded bg-input-bg text-main text-xs font-mono">
                        当前输入 IPv6: ${rawInput}
                    </div>
                </div>
            `;
            specsTableBody.innerHTML = `
                <tr><td class="p-3 text-meta">IP 协议版本</td><td class="p-3 text-main font-bold">IPv6 (128-bit)</td></tr>
                <tr><td class="p-3 text-meta">前缀规范推荐</td><td class="p-3 text-main">/64 接口 ID 标准分配</td></tr>
                <tr><td class="p-3 text-meta">常用保留网段</td><td class="p-3 text-main">fe80::/10 链路本地，::1/128 本地环回</td></tr>
            `;
            vlsmSubnetsTbody.innerHTML = '<tr><td colspan="5" class="p-4 text-center text-meta">IPv6 子网层次划分过大，建议查看各前缀层级标准分配规范。</td></tr>';
        }

        function renderStepByStep(ipLong, maskLong, networkLong, broadcastLong, prefix, totalHosts, usableHosts, firstHostLong, lastHostLong) {
            stepByStepContainer.innerHTML = '';

            const steps = [
                {
                    title: '步骤 1：将 IP 与子网掩码转换为 32 位二进制',
                    desc: '每个 IPv4 地址由 4 个 8 位组（八位位组 Octet）组成，数值为 0~255。子网掩码连续的 1 表示网络位。',
                    formula: `IP:   ${longToBinaryString(ipLong)}\n掩码: ${longToBinaryString(maskLong)} (前缀 /${prefix})`
                },
                {
                    title: '步骤 2：逻辑“按位与”运算 (Bitwise AND) 求得网络地址',
                    desc: '规则：1 AND 1 = 1，其他均为 0。主机位与 0 相与全被清零，保留唯一的网络前缀。',
                    formula: `  ${longToIp(ipLong)}  ->  ${longToBinaryString(ipLong)}\n& ${longToIp(maskLong)}  ->  ${longToBinaryString(maskLong)}\n------------------------------------------------------------\n= ${longToIp(networkLong)}  ->  ${longToBinaryString(networkLong)} (网络地址)`
                },
                {
                    title: '步骤 3：反转子网掩码求通配符 (Wildcard) 与广播地址 (Broadcast)',
                    desc: '广播地址是子网内主机位全为 1 的地址。可以通过“网络地址 OR 通配符掩码”计算得出。',
                    formula: `通配符掩码 = ~掩码 = ${longToIp((~maskLong) >>> 0)}\n广播地址   = 网络地址 | 通配符 = ${longToIp(broadcastLong)}`
                },
                {
                    title: '步骤 4：计算地址容量与可用主机范围',
                    desc: `主机位数 h = 32 - ${prefix} = ${32 - prefix} 位。总地址数 = 2^h = ${totalHosts.toLocaleString()}。排除网络地址与广播地址，可用主机数 = 2^h - 2 = ${usableHosts.toLocaleString()}。`,
                    formula: `可用主机起始 = 网络地址 + 1 = ${longToIp(firstHostLong)}\n可用主机结束 = 广播地址 - 1 = ${longToIp(lastHostLong)}`
                }
            ];

            steps.forEach(step => {
                const card = document.createElement('div');
                card.className = 'p-3.5 rounded-xl bg-input-bg/60 border border-border/50 space-y-2';
                card.innerHTML = `
                    <div class="flex items-center gap-2 text-main font-bold text-xs md:text-sm">
                        <i class="fa-solid fa-calculator text-accent text-xs"></i>
                        <span>${step.title}</span>
                    </div>
                    <p class="text-[11px] md:text-xs text-body leading-relaxed">${step.desc}</p>
                    <pre class="p-2.5 rounded-lg bg-bg/80 border border-border/40 text-[11px] text-accent font-mono overflow-x-auto whitespace-pre leading-normal">${step.formula}</pre>
                `;
                stepByStepContainer.appendChild(card);
            });
        }

        function renderSpecsTable(ipLong, maskLong, wildcardLong, networkLong, broadcastLong, prefix, totalHosts, usableHosts, firstHostLong, lastHostLong) {
            const firstOctet = (ipLong >>> 24) & 255;
            const hexIp = '0x' + ipLong.toString(16).toUpperCase().padStart(8, '0');
            const reverseDns = longToIp(ipLong).split('.').reverse().join('.') + '.in-addr.arpa';

            const specs = [
                { label: '输入 IP 地址 (Dotted-Decimal)', val: longToIp(ipLong) },
                { label: 'IP 类别与作用域 (Scope)', val: `${getIpClass(firstOctet)} · ${getIpTypeDescription(ipLong)}` },
                { label: '子网掩码 (Subnet Mask)', val: `${longToIp(maskLong)} (CIDR /${prefix})` },
                { label: '通配符掩码 (Wildcard Mask / Cisco ACL)', val: longToIp(wildcardLong) },
                { label: '网络地址 (Network Address)', val: `${longToIp(networkLong)} /${prefix}` },
                { label: '广播地址 (Broadcast Address)', val: prefix >= 31 ? '无' : longToIp(broadcastLong) },
                { label: '第一个可用主机 (First Usable Host)', val: longToIp(firstHostLong) },
                { label: '最后一个可用主机 (Last Usable Host)', val: longToIp(lastHostLong) },
                { label: '可用主机数 (Usable Hosts)', val: `${usableHosts.toLocaleString()} 台` },
                { label: '子网总地址数 (Total Addresses)', val: `${totalHosts.toLocaleString()} 个` },
                { label: '整数表达形式 (Integer / Decimal)', val: ipLong.toString() },
                { label: '十六进制形式 (Hexadecimal)', val: hexIp },
                { label: '反向 DNS PTR 域名 (Reverse DNS)', val: reverseDns }
            ];

            specsTableBody.innerHTML = '';
            specs.forEach(item => {
                const tr = document.createElement('tr');
                tr.innerHTML = `
                    <td class="py-2.5 px-3 text-meta font-medium w-1/3">${item.label}</td>
                    <td class="py-2.5 px-3 text-main font-bold select-all flex items-center justify-between group">
                        <span class="truncate">${item.val}</span>
                        <button type="button" class="btn-copy-field opacity-0 group-hover:opacity-100 text-meta hover:text-accent ml-2 text-xs transition-opacity" data-val="${item.val}" title="复制">
                            <i class="fa-regular fa-copy"></i>
                        </button>
                    </td>
                `;
                specsTableBody.appendChild(tr);
            });

            // Bind click to copy individual fields
            specsTableBody.querySelectorAll('.btn-copy-field').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    const text = btn.getAttribute('data-val');
                    copyText(text, btn);
                });
            });
        }

        function updateVlsm(networkLong, currentPrefix) {
            subdivideSelect.innerHTML = '';
            if (currentPrefix >= 30) {
                subdivideSelect.innerHTML = '<option value="">子网已过小</option>';
                vlsmSubnetsTbody.innerHTML = '<tr><td colspan="5" class="p-4 text-center text-meta">当前掩码前缀为 /' + currentPrefix + '，无需进一步再划分。</td></tr>';
                return;
            }

            for (let targetP = currentPrefix + 1; targetP <= Math.min(currentPrefix + 6, 30); targetP++) {
                const subnetsCount = Math.pow(2, targetP - currentPrefix);
                const hostsPerSubnet = Math.pow(2, 32 - targetP) - 2;
                const opt = document.createElement('option');
                opt.value = targetP;
                opt.textContent = `/${targetP} (划分为 ${subnetsCount} 个子网，每网段 ${hostsPerSubnet} 可用主机)`;
                subdivideSelect.appendChild(opt);
            }

            renderVlsmTable(networkLong, currentPrefix, parseInt(subdivideSelect.value, 10));
        }

        function renderVlsmTable(baseNetLong, currentPrefix, targetPrefix) {
            vlsmSubnetsTbody.innerHTML = '';
            if (!targetPrefix || targetPrefix <= currentPrefix) return;

            const subnetCount = Math.pow(2, targetPrefix - currentPrefix);
            const blockSize = Math.pow(2, 32 - targetPrefix);
            const displayLimit = Math.min(subnetCount, 64);

            for (let i = 0; i < displayLimit; i++) {
                const subNet = (baseNetLong + (i * blockSize)) >>> 0;
                const subBrd = (subNet + blockSize - 1) >>> 0;
                const subFirst = (subNet + 1) >>> 0;
                const subLast = (subBrd - 1) >>> 0;
                const usable = blockSize > 2 ? blockSize - 2 : (blockSize === 2 ? 2 : 1);

                const tr = document.createElement('tr');
                tr.className = 'hover:bg-accent/5 transition-colors';
                tr.innerHTML = `
                    <td class="p-2.5 text-accent font-bold">#${i + 1}</td>
                    <td class="p-2.5 text-main font-semibold select-all">${longToIp(subNet)} /${targetPrefix}</td>
                    <td class="p-2.5 text-body select-all">${longToIp(subFirst)} ~ ${longToIp(subLast)}</td>
                    <td class="p-2.5 text-meta select-all">${longToIp(subBrd)}</td>
                    <td class="p-2.5 text-right font-bold text-emerald-400">${usable.toLocaleString()}</td>
                `;
                vlsmSubnetsTbody.appendChild(tr);
            }

            if (subnetCount > displayLimit) {
                const tr = document.createElement('tr');
                tr.innerHTML = `<td colspan="5" class="p-2.5 text-center text-meta text-[11px]">... 还有 ${(subnetCount - displayLimit).toLocaleString()} 个划分网段已省略显示</td>`;
                vlsmSubnetsTbody.appendChild(tr);
            }
        }

        function showError(msg) {
            errorMessage.textContent = msg;
            errorAlert.classList.remove('hidden');
        }

        function copyText(str, buttonEl) {
            if (!navigator.clipboard) {
                const textArea = document.createElement('textarea');
                textArea.value = str;
                document.body.appendChild(textArea);
                textArea.select();
                document.execCommand('copy');
                document.body.removeChild(textArea);
            } else {
                navigator.clipboard.writeText(str);
            }
            if (buttonEl) {
                const originalHtml = buttonEl.innerHTML;
                buttonEl.innerHTML = '<i class="fa-solid fa-check text-emerald-400"></i>';
                setTimeout(() => {
                    buttonEl.innerHTML = originalHtml;
                }, 1500);
            }
        }

        // Event Listeners
        populateMaskOptions(24);

        maskSelect.addEventListener('change', () => {
            const prefix = parseInt(maskSelect.value, 10);
            cidrSlider.value = prefix;
            calculate();
        });

        cidrSlider.addEventListener('input', () => {
            const prefix = parseInt(cidrSlider.value, 10);
            maskSelect.value = prefix;
            sliderValueText.textContent = `/${prefix}`;
            calculate();
        });

        ipInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                calculate();
            }
        });

        ipInput.addEventListener('input', () => {
            // Live responsive calculation
            calculate();
        });

        btnCalculate.addEventListener('click', calculate);

        clearIpBtn.addEventListener('click', () => {
            ipInput.value = '';
            ipInput.focus();
        });

        subdivideSelect.addEventListener('change', () => {
            let rawInput = ipInput.value.trim().split('/')[0];
            const ipLong = ipToLong(rawInput);
            if (ipLong !== null) {
                const prefix = parseInt(maskSelect.value, 10) || 24;
                const maskLong = prefixToMaskLong(prefix);
                const networkLong = (ipLong & maskLong) >>> 0;
                renderVlsmTable(networkLong, prefix, parseInt(subdivideSelect.value, 10));
            }
        });

        // Quick presets
        document.querySelectorAll('.btn-preset').forEach(btn => {
            btn.addEventListener('click', () => {
                const ip = btn.getAttribute('data-ip');
                const mask = btn.getAttribute('data-mask');
                ipInput.value = ip;
                if (!isNaN(parseInt(mask, 10))) {
                    const p = parseInt(mask, 10);
                    maskSelect.value = p;
                    cidrSlider.value = p;
                }
                calculate();
            });
        });

        // Copy all results summary
        btnCopyResult.addEventListener('click', () => {
            const summary = [
                `【子网掩码计算结果报告】`,
                `网络地址: ${resNetworkIp.textContent}`,
                `广播地址: ${resBroadcastIp.textContent}`,
                `可用范围: ${resHostRange.textContent}`,
                `可用主机: ${resUsableHosts.textContent}`,
                `总地址数: ${resTotalAddresses.textContent}`,
                `子网掩码: ${labelMaskDec.textContent}`
            ].join('\n');
            copyText(summary, btnCopyResult);
        });

        // Run initial calculation
        calculate();
    }

    if (document.readyState !== 'loading') {
        initSubnetCalculator();
    } else {
        document.addEventListener('DOMContentLoaded', initSubnetCalculator);
    }

    // PJAX compatibility
    document.addEventListener('pjax:complete', initSubnetCalculator);
})();
