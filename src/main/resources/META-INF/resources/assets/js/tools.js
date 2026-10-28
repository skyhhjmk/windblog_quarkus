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

            const netColorClass = 'bg-cyan-500/15 text-cyan-800 border border-cyan-400/40 dark:bg-cyan-500/20 dark:text-cyan-300 dark:border-cyan-500/30';
            const hostColorClass = 'bg-amber-500/15 text-amber-900 border border-amber-400/40 dark:bg-amber-500/20 dark:text-amber-300 dark:border-amber-500/30';
            const maskHostColorClass = 'bg-border/60 text-meta border border-border/80 dark:bg-card dark:text-meta/40 dark:border-border/30';
            const netResultClass = 'bg-accent/25 text-main font-extrabold border border-accent/60 dark:bg-accent/20 dark:text-cyan-200 dark:border-accent/40';
            const netResultHostClass = 'bg-card text-meta/40 border border-border/30 dark:bg-card dark:text-meta/30 dark:border-border/20';

            renderBitmaskRow(bitsContainerIp, ipBinStr, prefix, netColorClass, hostColorClass);
            renderBitmaskRow(bitsContainerMask, maskBinStr, prefix, netColorClass, maskHostColorClass);
            renderBitmaskRow(bitsContainerNet, netBinStr, prefix, netResultClass, netResultHostClass);

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
                card.className = 'p-4 rounded-xl bg-input-bg border border-border space-y-2.5';
                card.innerHTML = `
                    <div class="flex items-center gap-2 text-main font-bold text-xs md:text-sm">
                        <i class="fa-solid fa-calculator text-accent text-xs"></i>
                        <span>${step.title}</span>
                    </div>
                    <p class="text-xs text-body leading-relaxed">${step.desc}</p>
                    <pre class="p-3 rounded-lg bg-bg border border-border text-xs text-cyan-800 dark:text-cyan-300 font-mono overflow-x-auto whitespace-pre leading-normal">${step.formula}</pre>
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
                tr.className = 'hover:bg-accent/10 transition-colors';
                tr.innerHTML = `
                    <td class="p-2.5 text-accent font-bold">#${i + 1}</td>
                    <td class="p-2.5 text-main font-semibold select-all">${longToIp(subNet)} /${targetPrefix}</td>
                    <td class="p-2.5 text-body select-all">${longToIp(subFirst)} ~ ${longToIp(subLast)}</td>
                    <td class="p-2.5 text-meta select-all">${longToIp(subBrd)}</td>
                    <td class="p-2.5 text-right font-bold text-emerald-600 dark:text-emerald-400">${usable.toLocaleString()}</td>
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
    document.addEventListener('page:ready', initSubnetCalculator);
})();
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
    document.addEventListener('page:ready', initTimestampTool);
})();
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
    document.addEventListener('page:ready', initLogFormatter);
})();
/**
 * Base64 及常见 Base 变体 (Base16, Base32, Base58, Base85) 编解码交互脚本
 */
(function () {
    'use strict';

    function initBaseEncoder() {
        const root = document.getElementById('baseEncoderToolApp');
        if (!root) return;

        const inputText = document.getElementById('baseInputText');
        const outputPrimary = document.getElementById('baseOutputPrimary');
        const btnModeEncode = document.getElementById('btnModeEncode');
        const btnModeDecode = document.getElementById('btnModeDecode');
        const baseInputLabel = document.getElementById('baseInputLabel');
        const baseOutputLabel = document.getElementById('baseOutputLabel');
        const baseInputStats = document.getElementById('baseInputStats');
        const baseOutputStats = document.getElementById('baseOutputStats');
        const btnClear = document.getElementById('btnClearBaseInput');
        const btnCopyPrimary = document.getElementById('btnCopyPrimaryBase');
        const chkUrlSafe = document.getElementById('chkUrlSafe');
        const variantsContainer = document.getElementById('baseVariantsContainer');

        let currentMode = 'encode'; // 'encode' or 'decode'

        // UTF-8 Helpers
        function stringToUtf8Bytes(str) {
            return new TextEncoder().encode(str);
        }

        function utf8BytesToString(bytes) {
            return new TextDecoder().decode(bytes);
        }

        // Standard Base64
        function bytesToBase64(bytes) {
            let binary = '';
            const len = bytes.byteLength;
            for (let i = 0; i < len; i++) {
                binary += String.fromCharCode(bytes[i]);
            }
            return btoa(binary);
        }

        function base64ToBytes(base64Str) {
            // Un-urlsafe if needed
            let normalized = base64Str.replace(/-/g, '+').replace(/_/g, '/');
            while (normalized.length % 4 !== 0) {
                normalized += '=';
            }
            const binary = atob(normalized);
            const bytes = new Uint8Array(binary.length);
            for (let i = 0; i < binary.length; i++) {
                bytes[i] = binary.charCodeAt(i);
            }
            return bytes;
        }

        // Base16 (Hex)
        function bytesToHex(bytes) {
            return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
        }

        function hexToBytes(hex) {
            const clean = hex.replace(/[^0-9a-fA-F]/g, '');
            const bytes = new Uint8Array(clean.length / 2);
            for (let i = 0; i < clean.length; i += 2) {
                bytes[i / 2] = parseInt(clean.substr(i, 2), 16);
            }
            return bytes;
        }

        // Base32 (RFC 4648)
        const B32_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
        function bytesToBase32(bytes) {
            let bits = 0;
            let value = 0;
            let output = '';
            for (let i = 0; i < bytes.length; i++) {
                value = (value << 8) | bytes[i];
                bits += 8;
                while (bits >= 5) {
                    output += B32_ALPHABET[(value >>> (bits - 5)) & 31];
                    bits -= 5;
                }
            }
            if (bits > 0) {
                output += B32_ALPHABET[(value << (5 - bits)) & 31];
            }
            while (output.length % 8 !== 0) {
                output += '=';
            }
            return output;
        }

        function base32ToBytes(str) {
            const clean = str.toUpperCase().replace(/=+$/, '');
            let bits = 0;
            let value = 0;
            let index = 0;
            const bytes = new Uint8Array(Math.floor((clean.length * 5) / 8));
            for (let i = 0; i < clean.length; i++) {
                const val = B32_ALPHABET.indexOf(clean[i]);
                if (val === -1) continue;
                value = (value << 5) | val;
                bits += 5;
                if (bits >= 8) {
                    bytes[index++] = (value >>> (bits - 8)) & 255;
                    bits -= 8;
                }
            }
            return bytes.slice(0, index);
        }

        // Base58 (Bitcoin / IPFS)
        const B58_ALPHABET = '123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz';
        function bytesToBase58(bytes) {
            if (bytes.length === 0) return '';
            let zeros = 0;
            while (zeros < bytes.length && bytes[zeros] === 0) zeros++;
            const digits = [0];
            for (let i = zeros; i < bytes.length; i++) {
                let carry = bytes[i];
                for (let j = 0; j < digits.length; j++) {
                    carry += digits[j] << 8;
                    digits[j] = carry % 58;
                    carry = (carry / 58) | 0;
                }
                while (carry > 0) {
                    digits.push(carry % 58);
                    carry = (carry / 58) | 0;
                }
            }
            let output = '';
            for (let i = 0; i < zeros; i++) output += '1';
            for (let i = digits.length - 1; i >= 0; i--) output += B58_ALPHABET[digits[i]];
            return output;
        }

        function base58ToBytes(str) {
            if (str.length === 0) return new Uint8Array(0);
            let zeros = 0;
            while (zeros < str.length && str[zeros] === '1') zeros++;
            const bytes = [0];
            for (let i = zeros; i < str.length; i++) {
                const val = B58_ALPHABET.indexOf(str[i]);
                if (val === -1) continue;
                let carry = val;
                for (let j = 0; j < bytes.length; j++) {
                    carry += bytes[j] * 58;
                    bytes[j] = carry & 255;
                    carry = carry >> 8;
                }
                while (carry > 0) {
                    bytes.push(carry & 255);
                    carry = carry >> 8;
                }
            }
            const out = new Uint8Array(zeros + bytes.length);
            for (let i = 0; i < bytes.length; i++) {
                out[zeros + i] = bytes[bytes.length - 1 - i];
            }
            return out;
        }

        function executeConversion() {
            const input = inputText.value;
            baseInputStats.textContent = `${input.length} 字符`;

            if (!input) {
                outputPrimary.value = '';
                baseOutputStats.textContent = '0 字符';
                variantsContainer.innerHTML = '<p class="text-meta/60 italic text-center py-4">等待输入...</p>';
                return;
            }

            try {
                if (currentMode === 'encode') {
                    const bytes = stringToUtf8Bytes(input);
                    let b64 = bytesToBase64(bytes);
                    if (chkUrlSafe.checked) {
                        b64 = b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                    }
                    outputPrimary.value = b64;
                    baseOutputStats.textContent = `${b64.length} 字符`;

                    const hex = bytesToHex(bytes);
                    const b32 = bytesToBase32(bytes);
                    const b58 = bytesToBase58(bytes);

                    renderVariants([
                        { name: 'Base16 (十六进制 Hex)', val: hex },
                        { name: 'Base32 (RFC 4648)', val: b32 },
                        { name: 'Base58 (比特币/区块链)', val: b58 },
                        { name: 'Base64 URL-Safe', val: bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') }
                    ]);
                } else {
                    // Decode mode
                    let decodedBytes = null;
                    let decodeType = 'Base64';

                    try {
                        decodedBytes = base64ToBytes(input.trim());
                    } catch (e) {
                        outputPrimary.value = '无法解析为合法的 Base64 编码，请检查输入格式。';
                        baseOutputStats.textContent = '错误';
                        return;
                    }

                    const plainText = utf8BytesToString(decodedBytes);
                    outputPrimary.value = plainText;
                    baseOutputStats.textContent = `${plainText.length} 字符`;

                    renderVariants([
                        { name: 'Hex 十六进制转文本尝试', val: tryDecodeHex(input.trim()) },
                        { name: 'Base32 解码尝试', val: tryDecodeBase32(input.trim()) },
                        { name: 'Base58 解码尝试', val: tryDecodeBase58(input.trim()) }
                    ]);
                }
            } catch (err) {
                outputPrimary.value = `转换发生错误: ${err.message}`;
            }
        }

        function tryDecodeHex(str) {
            try { return utf8BytesToString(hexToBytes(str)); } catch (e) { return '(无法解析为 Hex)'; }
        }
        function tryDecodeBase32(str) {
            try { return utf8BytesToString(base32ToBytes(str)); } catch (e) { return '(无法解析为 Base32)'; }
        }
        function tryDecodeBase58(str) {
            try { return utf8BytesToString(base58ToBytes(str)); } catch (e) { return '(无法解析为 Base58)'; }
        }

        function renderVariants(variants) {
            variantsContainer.innerHTML = '';
            variants.forEach(item => {
                const row = document.createElement('div');
                row.className = 'p-2.5 rounded-xl bg-input-bg border border-border/60 flex items-center justify-between gap-3';
                row.innerHTML = `
                    <div class="min-w-0 flex-1">
                        <span class="text-[10px] text-meta block">${item.name}</span>
                        <span class="text-main font-semibold select-all block truncate">${item.val || '-'}</span>
                    </div>
                    <button type="button" class="btn-copy-var px-2 py-1 rounded bg-card border border-border text-meta hover:text-accent text-[11px] shrink-0" data-val="${item.val}">
                        <i class="fa-regular fa-copy"></i>
                    </button>
                `;
                variantsContainer.appendChild(row);
            });

            variantsContainer.querySelectorAll('.btn-copy-var').forEach(btn => {
                btn.addEventListener('click', () => {
                    const text = btn.getAttribute('data-val');
                    if (text) {
                        navigator.clipboard.writeText(text);
                        btn.innerHTML = '<i class="fa-solid fa-check text-emerald-500"></i>';
                        setTimeout(() => { btn.innerHTML = '<i class="fa-regular fa-copy"></i>'; }, 1500);
                    }
                });
            });
        }

        // Mode switch
        btnModeEncode.addEventListener('click', () => {
            currentMode = 'encode';
            btnModeEncode.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnModeDecode.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            baseInputLabel.textContent = '输入明文字符串 (Plain Text):';
            baseOutputLabel.textContent = '标准 Base64 编码结果:';
            executeConversion();
        });

        btnModeDecode.addEventListener('click', () => {
            currentMode = 'decode';
            btnModeDecode.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnModeEncode.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            baseInputLabel.textContent = '输入已编码的 Base64 字符串:';
            baseOutputLabel.textContent = '解码后的明文 (Plain Text):';
            executeConversion();
        });

        inputText.addEventListener('input', executeConversion);
        chkUrlSafe.addEventListener('change', executeConversion);

        btnClear.addEventListener('click', () => {
            inputText.value = '';
            executeConversion();
        });

        btnCopyPrimary.addEventListener('click', () => {
            if (outputPrimary.value) {
                navigator.clipboard.writeText(outputPrimary.value);
                const orig = btnCopyPrimary.innerHTML;
                btnCopyPrimary.innerHTML = '<i class="fa-solid fa-check text-emerald-500 mr-1"></i>已复制';
                setTimeout(() => { btnCopyPrimary.innerHTML = orig; }, 1500);
            }
        });

        // Initialize with default demo
        inputText.value = 'Hello WindBlog! 欢迎使用新一代在线实用工具箱 🚀';
        executeConversion();
    }

    if (document.readyState !== 'loading') {
        initBaseEncoder();
    } else {
        document.addEventListener('DOMContentLoaded', initBaseEncoder);
    }
    document.addEventListener('page:ready', initBaseEncoder);
})();
