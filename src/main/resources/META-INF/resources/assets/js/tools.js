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

    /* ==========================================================================
       5. Cron 表达式生成与执行时间模拟器 (Cron Expression Generator & Simulator)
       ========================================================================== */
    function initCronGenerator() {
        const root = document.getElementById('cronToolApp');
        if (!root) return;

        const exprInput = document.getElementById('cronExpressionInput');
        const formatBadge = document.getElementById('cronFormatBadge');
        const statusBadge = document.getElementById('cronStatusBadge');
        const humanDesc = document.getElementById('cronHumanDesc');
        const btnMode5 = document.getElementById('btnCronMode5');
        const btnMode6 = document.getElementById('btnCronMode6');
        const btnCopyCron = document.getElementById('btnCopyCron');
        const btnResetCron = document.getElementById('btnResetCron');
        const nextRunsContainer = document.getElementById('cronNextRunsList');
        const codeSnippetPre = document.getElementById('cronCodeSnippetPre');
        const selSnippetLang = document.getElementById('selCodeSnippetLang');
        const btnCopySnippet = document.getElementById('btnCopySnippet');
        const userTzDisplay = document.getElementById('userTimezoneDisplay');

        if (userTzDisplay) {
            try {
                userTzDisplay.textContent = Intl.DateTimeFormat().resolvedOptions().timeZone || '本地系统时区';
            } catch (e) {
                userTzDisplay.textContent = '本地系统时区';
            }
        }

        let is6Field = true; // default 6 fields (Quartz / Spring)

        // Field specific checkbox grids
        function populateNumberGrid(containerId, start, end, formatFn) {
            const container = document.getElementById(containerId);
            if (!container) return;
            container.innerHTML = '';
            for (let i = start; i <= end; i++) {
                const label = document.createElement('label');
                label.className = 'flex items-center gap-1 text-[11px] font-mono text-main cursor-pointer hover:text-accent p-1 rounded hover:bg-input-bg';
                const input = document.createElement('input');
                input.type = 'checkbox';
                input.value = i;
                input.className = 'accent-cyan-500 rounded';
                label.appendChild(input);
                const span = document.createElement('span');
                span.textContent = formatFn ? formatFn(i) : (i < 10 ? '0' + i : i);
                label.appendChild(span);
                container.appendChild(label);
            }
        }

        populateNumberGrid('secSpecificList', 0, 59);
        populateNumberGrid('minSpecificList', 0, 59);
        populateNumberGrid('hourSpecificList', 0, 23);
        populateNumberGrid('daySpecificList', 1, 31, n => (n < 10 ? '0' + n : n) + '日');
        populateNumberGrid('monthSpecificList', 1, 12, n => n + '月');

        const weekNames = [
            { val: 'SUN', label: '周日 (SUN/1)' },
            { val: 'MON', label: '周一 (MON/2)' },
            { val: 'TUE', label: '周二 (TUE/3)' },
            { val: 'WED', label: '周三 (WED/4)' },
            { val: 'THU', label: '周四 (THU/5)' },
            { val: 'FRI', label: '周五 (FRI/6)' },
            { val: 'SAT', label: '周六 (SAT/7)' }
        ];
        const weekGrid = document.getElementById('weekSpecificList');
        if (weekGrid) {
            weekGrid.innerHTML = '';
            weekNames.forEach(w => {
                const label = document.createElement('label');
                label.className = 'flex items-center gap-1.5 text-xs font-mono text-main cursor-pointer hover:text-accent p-1.5 rounded hover:bg-input-bg';
                const input = document.createElement('input');
                input.type = 'checkbox';
                input.value = w.val;
                input.className = 'accent-cyan-500 rounded';
                if (['MON', 'TUE', 'WED', 'THU', 'FRI'].includes(w.val)) input.checked = true;
                label.appendChild(input);
                const span = document.createElement('span');
                span.textContent = w.label;
                label.appendChild(span);
                weekGrid.appendChild(label);
            });
        }

        // Tabs switching
        const tabBtns = document.querySelectorAll('#cronFieldTabs .cron-tab-btn');
        tabBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                tabBtns.forEach(b => {
                    b.className = 'cron-tab-btn flex-1 py-1.5 px-2 rounded-lg text-meta hover:text-main text-center transition-all';
                });
                btn.className = 'cron-tab-btn flex-1 py-1.5 px-2 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold text-center transition-all';
                const target = btn.getAttribute('data-tab');
                document.querySelectorAll('#cronFieldPanels .cron-panel').forEach(p => p.classList.add('hidden'));
                const panel = document.getElementById('panel-' + target);
                if (panel) panel.classList.remove('hidden');
            });
        });

        // Mode switching: 5-field vs 6-field
        function setCronMode(sixFields) {
            is6Field = sixFields;
            const tabSecondsBtn = document.getElementById('tabSecondsBtn');
            if (sixFields) {
                btnMode6.className = 'px-3.5 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
                btnMode5.className = 'px-3.5 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
                formatBadge.textContent = '6 字段 (秒 分 时 日 月 周)';
                if (tabSecondsBtn) tabSecondsBtn.classList.remove('hidden');
            } else {
                btnMode5.className = 'px-3.5 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
                btnMode6.className = 'px-3.5 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
                formatBadge.textContent = '5 字段 (分 时 日 月 周)';
                if (tabSecondsBtn) tabSecondsBtn.classList.add('hidden');
            }
            updateExpressionFromPanels();
        }

        btnMode5.addEventListener('click', () => setCronMode(false));
        btnMode6.addEventListener('click', () => setCronMode(true));

        // Build single field from panel inputs
        function getFieldCron(prefix, defaultVal, isSecOrMin) {
            const mode = document.querySelector(`input[name="cron-${prefix}-mode"]:checked`)?.value;
            if (mode === 'every') return '*';
            if (mode === 'unspecified') return '?';
            if (mode === 'range') {
                const s = document.getElementById(`${prefix}RangeStart`)?.value || '0';
                const e = document.getElementById(`${prefix}RangeEnd`)?.value || '1';
                return `${s}-${e}`;
            }
            if (mode === 'step') {
                const s = document.getElementById(`${prefix}StepStart`)?.value || '0';
                const i = document.getElementById(`${prefix}StepInterval`)?.value || '1';
                return `${s}/${i}`;
            }
            if (mode === 'specific') {
                const checked = Array.from(document.querySelectorAll(`#${prefix}SpecificList input:checked`)).map(c => c.value);
                return checked.length > 0 ? checked.join(',') : defaultVal;
            }
            return defaultVal;
        }

        // Update overall expression from UI
        function updateExpressionFromPanels() {
            let expr = '';
            const min = getFieldCron('min', '*', true);
            const hour = getFieldCron('hour', '*', false);
            const day = getFieldCron('day', '*', false);
            const month = getFieldCron('month', '*', false);
            let week = getFieldCron('week', '?', false);

            if (is6Field) {
                const sec = getFieldCron('sec', '0', true);
                // In 6-field quartz, if day is specified, week should be ?; if week is specified, day should be ?
                let finalDay = day;
                let finalWeek = week;
                if (finalDay !== '?' && finalDay !== '*' && finalWeek !== '?') {
                    finalWeek = '?';
                }
                expr = `${sec} ${min} ${hour} ${finalDay} ${month} ${finalWeek}`;
            } else {
                // 5-field crontab: min hour day month week
                let finalDay = day === '?' ? '*' : day;
                let finalWeek = week === '?' ? '*' : week;
                expr = `${min} ${hour} ${finalDay} ${month} ${finalWeek}`;
            }

            exprInput.value = expr;
            onExpressionChanged();
        }

        // Event listeners for radio & inputs inside panels
        document.getElementById('cronFieldPanels').addEventListener('change', updateExpressionFromPanels);
        document.getElementById('cronFieldPanels').addEventListener('input', (e) => {
            if (e.target.type === 'number') updateExpressionFromPanels();
        });

        // Translate expression into readable Chinese description
        function translateCronToChinese(expr) {
            const parts = expr.trim().split(/\s+/);
            if (parts.length < 5 || parts.length > 7) {
                return 'Cron 表达式格式有误（需为 5 或 6/7 个由空格分隔的字段）';
            }

            let sec = '0', min, hour, day, month, week;
            if (parts.length === 5) {
                [min, hour, day, month, week] = parts;
            } else {
                [sec, min, hour, day, month, week] = parts;
            }

            let desc = '';
            // Month
            if (month !== '*' && month !== '?') desc += `在 ${month} 月 `;
            // Week or Day
            if (week !== '*' && week !== '?') {
                const wMap = { 'SUN': '周日', 'MON': '周一', 'TUE': '周二', 'WED': '周三', 'THU': '周四', 'FRI': '周五', 'SAT': '周六', '1': '周日', '2': '周一', '3': '周二', '4': '周三', '5': '周四', '6': '周五', '7': '周六', 'MON-FRI': '周一至周五' };
                desc += `在每${wMap[week] || ('周 ' + week)} `;
            } else if (day !== '*' && day !== '?') {
                desc += `在每月的第 ${day} 号 `;
            } else {
                desc += '每天 ';
            }

            // Hour, Minute, Second
            if (hour === '*' && min === '*' && sec === '*') {
                desc += '每秒钟持续触发一次';
            } else if (hour === '*' && min === '*') {
                desc += `每分钟的第 ${sec} 秒触发`;
            } else if (hour === '*' && min.includes('/')) {
                const step = min.split('/')[1];
                desc += `每隔 ${step} 分钟触发一次`;
            } else if (hour !== '*' && min !== '*') {
                const hText = hour.length === 1 ? '0' + hour : hour;
                const mText = min.length === 1 ? '0' + min : min;
                const sText = sec.length === 1 ? '0' + sec : sec;
                desc += `在 ${hText}:${mText}:${sText} 准时触发执行`;
            } else if (hour !== '*') {
                desc += `在 ${hour} 点的每分钟触发`;
            } else {
                desc += `在第 ${min} 分钟触发`;
            }

            return desc;
        }

        // Calculate next runs
        function calculateNextRuns(expr, count = 8) {
            const parts = expr.trim().split(/\s+/);
            if (parts.length < 5 || parts.length > 7) return [];

            let hasSec = parts.length >= 6;
            let secPart = hasSec ? parts[0] : '0';
            let minPart = hasSec ? parts[1] : parts[0];
            let hourPart = hasSec ? parts[2] : parts[1];
            let dayPart = hasSec ? parts[3] : parts[2];
            let monthPart = hasSec ? parts[4] : parts[3];
            let weekPart = hasSec ? parts[5] : parts[4];

            function matchPart(val, pattern, minVal, maxVal) {
                if (pattern === '*' || pattern === '?') return true;
                if (pattern.includes(',')) {
                    return pattern.split(',').some(p => matchPart(val, p, minVal, maxVal));
                }
                if (pattern.includes('/')) {
                    const [startStr, stepStr] = pattern.split('/');
                    const start = startStr === '*' ? minVal : parseInt(startStr, 10);
                    const step = parseInt(stepStr, 10);
                    return val >= start && ((val - start) % step === 0);
                }
                if (pattern.includes('-')) {
                    const [s, e] = pattern.split('-').map(x => parseInt(x, 10));
                    return val >= s && val <= e;
                }
                const num = parseInt(pattern, 10);
                if (!isNaN(num)) return val === num;
                // Text week matching
                const weekMap = { 'SUN': 0, 'MON': 1, 'TUE': 2, 'WED': 3, 'THU': 4, 'FRI': 5, 'SAT': 6 };
                if (weekMap[pattern.toUpperCase()] !== undefined) {
                    return val === weekMap[pattern.toUpperCase()];
                }
                return false;
            }

            const results = [];
            let curr = new Date(Date.now() + 1000);
            if (!hasSec) curr.setSeconds(0, 0);

            let maxSteps = 150000;
            while (results.length < count && maxSteps-- > 0) {
                const s = curr.getSeconds();
                const m = curr.getMinutes();
                const h = curr.getHours();
                const d = curr.getDate();
                const mo = curr.getMonth() + 1;
                const w = curr.getDay();

                const secMatch = matchPart(s, secPart, 0, 59);
                const minMatch = matchPart(m, minPart, 0, 59);
                const hourMatch = matchPart(h, hourPart, 0, 23);
                const dayMatch = matchPart(d, dayPart, 1, 31);
                const monthMatch = matchPart(mo, monthPart, 1, 12);
                const weekMatch = matchPart(w, weekPart, 0, 6);

                if (secMatch && minMatch && hourMatch && dayMatch && monthMatch && weekMatch) {
                    results.push(new Date(curr));
                    if (hasSec) {
                        curr = new Date(curr.getTime() + 1000);
                    } else {
                        curr = new Date(curr.getTime() + 60000);
                    }
                } else {
                    if (hasSec) {
                        curr = new Date(curr.getTime() + 1000);
                    } else {
                        curr = new Date(curr.getTime() + 60000);
                    }
                }
            }

            return results;
        }

        function formatRelativeTime(targetDate) {
            const diffMs = targetDate.getTime() - Date.now();
            const diffSec = Math.floor(diffMs / 1000);
            if (diffSec < 60) return `${diffSec} 秒后`;
            const diffMin = Math.floor(diffSec / 60);
            if (diffMin < 60) return `${diffMin} 分钟后`;
            const diffHours = Math.floor(diffMin / 60);
            const remainMin = diffMin % 60;
            if (diffHours < 24) return `${diffHours} 小时 ${remainMin} 分钟后`;
            const diffDays = Math.floor(diffHours / 24);
            return `${diffDays} 天后`;
        }

        const weekdayCn = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

        function renderNextRuns(runs) {
            nextRunsContainer.innerHTML = '';
            if (runs.length === 0) {
                nextRunsContainer.innerHTML = '<div class="text-meta text-xs py-4 text-center font-mono">无法解析或近期无匹配的触发时间</div>';
                return;
            }

            runs.forEach((d, idx) => {
                const year = d.getFullYear();
                const month = String(d.getMonth() + 1).padStart(2, '0');
                const date = String(d.getDate()).padStart(2, '0');
                const hours = String(d.getHours()).padStart(2, '0');
                const mins = String(d.getMinutes()).padStart(2, '0');
                const secs = String(d.getSeconds()).padStart(2, '0');
                const timeStr = `${year}-${month}-${date} ${hours}:${mins}:${secs}`;
                const relTime = formatRelativeTime(d);
                const weekText = weekdayCn[d.getDay()];

                const row = document.createElement('div');
                row.className = 'flex items-center justify-between p-2.5 rounded-xl bg-input-bg border border-border/80 text-xs font-mono text-main hover:border-accent transition-colors';
                row.innerHTML = `
                    <div class="flex items-center gap-2">
                        <span class="w-5 h-5 rounded-full bg-accent/10 text-accent flex items-center justify-center text-[10px] font-bold">${idx + 1}</span>
                        <span class="font-bold tracking-wide">${timeStr}</span>
                        <span class="text-meta text-[11px] px-1.5 py-0.5 rounded bg-card border border-border">${weekText}</span>
                    </div>
                    <span class="text-[11px] font-semibold text-accent">${relTime}</span>
                `;
                nextRunsContainer.appendChild(row);
            });
        }

        function updateCodeSnippet(expr) {
            const lang = selSnippetLang.value;
            let snippet = '';
            if (lang === 'spring') {
                snippet = `@Scheduled(cron = "${expr}")\npublic void scheduledJob() {\n    // 定时执行业务逻辑\n    log.info("Cron executed at: {}", LocalDateTime.now());\n}`;
            } else if (lang === 'crontab') {
                const p = expr.split(/\s+/);
                const p5 = p.length === 6 ? p.slice(1).join(' ') : expr;
                snippet = `# Linux Crontab (通过 crontab -e 编辑)\n${p5} /usr/local/bin/backup.sh >> /var/log/cron.log 2>&1`;
            } else if (lang === 'node') {
                snippet = `const cron = require('node-cron');\n\n// 安排定时任务\ncron.schedule('${expr}', () => {\n  console.log('Task executed at:', new Date().toISOString());\n});`;
            } else if (lang === 'python') {
                snippet = `from apscheduler.schedulers.blocking import BlockingScheduler\n\nsched = BlockingScheduler()\n\n# 支持标准 Cron 调度\n@sched.scheduled_job('cron', ...)\ndef my_job():\n    print("Task running...")\n\nsched.start()`;
            }
            codeSnippetPre.textContent = snippet;
        }

        function onExpressionChanged() {
            const expr = exprInput.value.trim();
            const parts = expr.split(/\s+/);
            const isValid = (is6Field && parts.length === 6) || (!is6Field && parts.length === 5);

            if (isValid) {
                statusBadge.textContent = '格式有效 ✓';
                statusBadge.className = 'px-2 py-0.5 rounded text-[11px] bg-emerald-500/10 text-emerald-500 border border-emerald-500/20 font-bold';
                humanDesc.textContent = translateCronToChinese(expr);
                const runs = calculateNextRuns(expr, 8);
                renderNextRuns(runs);
                updateCodeSnippet(expr);
            } else {
                statusBadge.textContent = '字段数不匹配 ✗';
                statusBadge.className = 'px-2 py-0.5 rounded text-[11px] bg-red-500/10 text-red-500 border border-red-500/20 font-bold';
                humanDesc.textContent = `表达式必须包含 ${is6Field ? '6' : '5'} 个由空格分隔的字段 (当前为 ${parts.length} 个)`;
            }
        }

        exprInput.addEventListener('input', onExpressionChanged);
        selSnippetLang.addEventListener('change', () => updateCodeSnippet(exprInput.value.trim()));

        btnCopyCron.addEventListener('click', () => {
            navigator.clipboard.writeText(exprInput.value.trim());
            const orig = btnCopyCron.innerHTML;
            btnCopyCron.innerHTML = '<i class="fa-solid fa-check text-emerald-500"></i><span>已复制</span>';
            setTimeout(() => { btnCopyCron.innerHTML = orig; }, 1500);
        });

        btnCopySnippet.addEventListener('click', () => {
            navigator.clipboard.writeText(codeSnippetPre.textContent);
            const orig = btnCopySnippet.textContent;
            btnCopySnippet.textContent = '已复制';
            setTimeout(() => { btnCopySnippet.textContent = orig; }, 1500);
        });

        btnResetCron.addEventListener('click', () => {
            if (is6Field) {
                exprInput.value = '0 30 9 ? * MON-FRI';
            } else {
                exprInput.value = '30 9 * * 1-5';
            }
            onExpressionChanged();
        });

        // Preset buttons
        document.querySelectorAll('.cron-preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const c = is6Field ? btn.getAttribute('data-cron') : btn.getAttribute('data-cron5');
                if (c) {
                    exprInput.value = c;
                    onExpressionChanged();
                }
            });
        });

        onExpressionChanged();
    }

    if (document.readyState !== 'loading') {
        initCronGenerator();
    } else {
        document.addEventListener('DOMContentLoaded', initCronGenerator);
    }
    document.addEventListener('page:ready', initCronGenerator);

    /* ==========================================================================
       6. 正则表达式在线测试与速查工具 (RegExp Online Tester & Explainer)
       ========================================================================== */
    function initRegexTester() {
        const root = document.getElementById('regexToolApp');
        if (!root) return;

        const patternInput = document.getElementById('regexPatternInput');
        const testText = document.getElementById('regexTestText');
        const activeFlagsDisplay = document.getElementById('activeFlagsDisplay');
        const highlightDisplay = document.getElementById('regexHighlightDisplay');
        const matchesContainer = document.getElementById('regexMatchesContainer');
        const matchCountText = document.getElementById('regexMatchCountText');
        const durationText = document.getElementById('regexDurationText');
        const matchDetailSummary = document.getElementById('matchDetailSummary');
        const errorAlert = document.getElementById('regexErrorAlert');
        const errorMessage = document.getElementById('regexErrorMessage');
        const replacementInput = document.getElementById('regexReplacementInput');
        const replacedOutput = document.getElementById('regexReplacedOutput');
        const btnCopyReplaced = document.getElementById('btnCopyReplaced');
        const btnClearTestText = document.getElementById('btnClearTestText');

        const flagInputs = {
            g: document.getElementById('flag-g'),
            i: document.getElementById('flag-i'),
            m: document.getElementById('flag-m'),
            s: document.getElementById('flag-s'),
            u: document.getElementById('flag-u')
        };

        function getActiveFlags() {
            let flags = '';
            ['g', 'i', 'm', 's', 'u'].forEach(f => {
                if (flagInputs[f] && flagInputs[f].checked) flags += f;
            });
            return flags;
        }

        function escapeHtml(str) {
            return str
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/"/g, '&quot;')
                .replace(/'/g, '&#039;');
        }

        const PRESETS = {
            phone: {
                pattern: '(?:\+?86)?1[3-9]\\d{9}',
                flags: 'g',
                sample: '客户咨询电话：13800138000 与 18612345678，备用号码 +8613988889999。座机 010-12345678 不应匹配。'
            },
            email: {
                pattern: '[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+',
                flags: 'gi',
                sample: '欢迎联系 support@geekcorelab.com，技术支持 dev.team+filter@company.org，无效格式 bad_email@.com。'
            },
            ipv4: {
                pattern: '(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)',
                flags: 'g',
                sample: '服务器内网 IP: 192.168.1.100，网关: 10.0.0.1，公网 DNS: 114.114.114.114 与 8.8.8.8，非法 999.999.1.1。'
            },
            url: {
                pattern: 'https?:\\/\\/(?:www\\.)?[-a-zA-Z0-9@:%._\\+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b(?:[-a-zA-Z0-9()@:%_\\+.~#?&//=]*)',
                flags: 'gi',
                sample: '访问我们的主站 https://www.geekcorelab.com/tools 查看工具，或者 https://github.com/windblog 项目主页。'
            },
            idcard: {
                pattern: '[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]',
                flags: 'g',
                sample: '验证二代身份证示例：11010519900307239X 与 310104198511201234。'
            },
            chinese: {
                pattern: '[\\u4e00-\\u9fa5]+',
                flags: 'g',
                sample: 'WindBlog 极客博客与实用工具箱 (GeekCoreLab)，纯前端 JavaScript 高性能即时运算！'
            },
            date: {
                pattern: '\\d{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])',
                flags: 'g',
                sample: '系统发布日期：2026-10-28，计划下次审计时间：2026-11-15 与 2027-01-01。'
            },
            color: {
                pattern: '#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})\\b',
                flags: 'g',
                sample: '主题主色调: #06b6d4，卡片背景: #ffffff，深色文字: #0f172a，警示红: #ef4444，三位缩写: #000。'
            }
        };

        function executeRegex() {
            const pattern = patternInput.value;
            const flags = getActiveFlags();
            activeFlagsDisplay.textContent = flags || '(无)';
            const text = testText.value;

            errorAlert.classList.add('hidden');

            if (!pattern) {
                highlightDisplay.innerHTML = escapeHtml(text) || '<span class="text-meta">暂无测试文本</span>';
                matchesContainer.innerHTML = '<div class="text-center py-8 text-meta text-xs font-mono">请输入正则表达式开始匹配</div>';
                matchCountText.textContent = '0 处匹配';
                durationText.textContent = '0 ms';
                matchDetailSummary.textContent = '共 0 项';
                replacedOutput.textContent = text;
                return;
            }

            let regex;
            try {
                regex = new RegExp(pattern, flags);
            } catch (err) {
                errorAlert.classList.remove('hidden');
                errorMessage.textContent = err.message || '正则表达式语法错误';
                highlightDisplay.innerHTML = escapeHtml(text);
                matchesContainer.innerHTML = `<div class="p-3 text-red-500 text-xs font-mono bg-red-500/10 rounded-xl">${escapeHtml(err.message)}</div>`;
                return;
            }

            const t0 = performance.now();
            const matches = [];

            if (flags.includes('g')) {
                let match;
                let lastIdx = -1;
                while ((match = regex.exec(text)) !== null) {
                    if (match.index === lastIdx) {
                        regex.lastIndex++;
                    }
                    lastIdx = match.index;
                    matches.push({
                        index: match.index,
                        length: match[0].length,
                        value: match[0],
                        groups: match.slice(1)
                    });
                    if (match[0].length === 0) {
                        regex.lastIndex++;
                    }
                    if (matches.length > 500) break; // prevent infinite loop
                }
            } else {
                const match = regex.exec(text);
                if (match) {
                    matches.push({
                        index: match.index,
                        length: match[0].length,
                        value: match[0],
                        groups: match.slice(1)
                    });
                }
            }

            const t1 = performance.now();
            const duration = (t1 - t0).toFixed(1);

            matchCountText.textContent = `${matches.length} 处匹配`;
            durationText.textContent = `${duration} ms`;
            matchDetailSummary.textContent = `共 ${matches.length} 项`;

            // Render live highlight
            if (matches.length === 0) {
                highlightDisplay.innerHTML = escapeHtml(text) || '<span class="text-meta">暂无匹配内容</span>';
            } else {
                let html = '';
                let cursor = 0;
                matches.forEach((m, idx) => {
                    if (m.index > cursor) {
                        html += escapeHtml(text.slice(cursor, m.index));
                    }
                    const isEven = idx % 2 === 0;
                    const markClass = isEven
                        ? 'bg-accent/20 text-accent border-b-2 border-accent font-bold px-0.5 rounded'
                        : 'bg-amber-500/20 text-amber-500 border-b-2 border-amber-500 font-bold px-0.5 rounded';
                    html += `<mark class="${markClass}" title="Match #${idx + 1}">${escapeHtml(m.value)}</mark>`;
                    cursor = m.index + m.length;
                });
                if (cursor < text.length) {
                    html += escapeHtml(text.slice(cursor));
                }
                highlightDisplay.innerHTML = html;
            }

            // Render matches list
            if (matches.length === 0) {
                matchesContainer.innerHTML = '<div class="text-center py-8 text-meta text-xs font-mono">未找到任何匹配项</div>';
            } else {
                matchesContainer.innerHTML = '';
                matches.forEach((m, idx) => {
                    const card = document.createElement('div');
                    card.className = 'p-3 rounded-xl bg-input-bg border border-border/80 space-y-1.5 text-xs font-mono text-main hover:border-accent transition-colors';
                    let groupsHtml = '';
                    if (m.groups && m.groups.length > 0 && m.groups.some(g => g !== undefined)) {
                        groupsHtml = '<div class="pt-1 border-t border-border/40 text-[11px] space-y-1 text-meta">';
                        m.groups.forEach((g, gIdx) => {
                            if (g !== undefined) {
                                groupsHtml += `<div><span class="font-bold text-accent">Group ${gIdx + 1}:</span> <span class="text-main">${escapeHtml(g)}</span></div>`;
                            }
                        });
                        groupsHtml += '</div>';
                    }

                    card.innerHTML = `
                        <div class="flex items-center justify-between">
                            <span class="font-bold text-accent">#${idx + 1} 匹配项:</span>
                            <span class="text-[11px] text-meta">位置 [${m.index} .. ${m.index + m.length}] (${m.length} 字符)</span>
                        </div>
                        <div class="bg-card p-2 rounded-lg border border-border break-all font-semibold select-all">${escapeHtml(m.value)}</div>
                        ${groupsHtml}
                    `;
                    matchesContainer.appendChild(card);
                });
            }

            // Execute replacement
            try {
                const rep = replacementInput.value;
                const replaced = text.replace(regex, rep);
                replacedOutput.textContent = replaced;
            } catch (err) {
                replacedOutput.textContent = '替换执行出错: ' + err.message;
            }
        }

        patternInput.addEventListener('input', executeRegex);
        testText.addEventListener('input', executeRegex);
        replacementInput.addEventListener('input', executeRegex);

        Object.values(flagInputs).forEach(inp => {
            if (inp) inp.addEventListener('change', executeRegex);
        });

        btnClearTestText.addEventListener('click', () => {
            testText.value = '';
            executeRegex();
        });

        btnCopyReplaced.addEventListener('click', () => {
            if (replacedOutput.textContent) {
                navigator.clipboard.writeText(replacedOutput.textContent);
                const orig = btnCopyReplaced.innerHTML;
                btnCopyReplaced.innerHTML = '<i class="fa-solid fa-check text-emerald-500 mr-1"></i>已复制';
                setTimeout(() => { btnCopyReplaced.innerHTML = orig; }, 1500);
            }
        });

        // Presets handler
        document.querySelectorAll('.regex-preset-btn').forEach(btn => {
            btn.addEventListener('click', () => {
                const key = btn.getAttribute('data-key');
                const preset = PRESETS[key];
                if (preset) {
                    patternInput.value = preset.pattern;
                    ['g', 'i', 'm', 's', 'u'].forEach(f => {
                        if (flagInputs[f]) flagInputs[f].checked = preset.flags.includes(f);
                    });
                    testText.value = preset.sample;
                    executeRegex();
                }
            });
        });

        // Initialize with default phone demo
        testText.value = PRESETS.phone.sample;
        executeRegex();
    }

    if (document.readyState !== 'loading') {
        initRegexTester();
    } else {
        document.addEventListener('DOMContentLoaded', initRegexTester);
    }
    document.addEventListener('page:ready', initRegexTester);

    /* ==========================================================================
       7. 现代哈希计算与本地文件校验器 (Hash Calculator & File Verifier)
       ========================================================================== */
    function initHashCalculator() {
        const root = document.getElementById('hashToolApp');
        if (!root) return;

        const btnSourceText = document.getElementById('btnHashSourceText');
        const btnSourceFile = document.getElementById('btnHashSourceFile');
        const textPanel = document.getElementById('hashTextPanel');
        const filePanel = document.getElementById('hashFilePanel');
        const textInput = document.getElementById('hashTextInput');
        const textStats = document.getElementById('hashTextStats');
        const btnClearText = document.getElementById('btnClearHashText');
        const dropZone = document.getElementById('hashDropZone');
        const fileInput = document.getElementById('hashFileInput');
        const btnClearFile = document.getElementById('btnClearHashFile');
        const dropZoneTitle = document.getElementById('hashDropZoneTitle');
        const dropZoneDesc = document.getElementById('hashDropZoneDesc');
        const fileMetaInfo = document.getElementById('hashFileMetaInfo');
        const metaFileName = document.getElementById('hashMetaFileName');
        const metaFileSize = document.getElementById('hashMetaFileSize');
        const metaFileType = document.getElementById('hashMetaFileType');
        const chkEnableHmac = document.getElementById('chkEnableHmac');
        const hmacContainer = document.getElementById('hmacKeyContainer');
        const hmacKeyInput = document.getElementById('hmacKeyInput');
        const btnCaseLower = document.getElementById('btnCaseLower');
        const btnCaseUpper = document.getElementById('btnCaseUpper');
        const btnCaseBase64 = document.getElementById('btnCaseBase64');
        const verifyInput = document.getElementById('hashVerifyInput');
        const verifyBadge = document.getElementById('hashVerifyResultBadge');

        const outSha256 = document.getElementById('hash-sha256');
        const outSm3 = document.getElementById('hash-sm3');
        const outMd5 = document.getElementById('hash-md5');
        const outSha1 = document.getElementById('hash-sha1');
        const outSha512 = document.getElementById('hash-sha512');

        let currentMode = 'text'; // 'text' | 'file'
        let currentCase = 'lower'; // 'lower' | 'upper' | 'base64'
        let currentFileBytes = null;
        let lastComputed = {};

        // MD5 implementation
        function md5(input) {
            let bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
            let n = bytes.length;
            let bitLen = n * 8;
            let padLen = ((n + 8) >>> 6 << 6) + 64;
            let msg = new Uint8Array(padLen);
            msg.set(bytes);
            msg[n] = 0x80;
            let view = new DataView(msg.buffer);
            view.setUint32(padLen - 8, bitLen >>> 0, true);
            view.setUint32(padLen - 4, Math.floor(bitLen / 0x100000000), true);

            let a = 0x67452301, b = 0xefcdab89, c = 0x98badcfe, d = 0x10325476;
            const K = [
                0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
                0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
                0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
                0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
                0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
                0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
                0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
                0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391
            ];
            const S = [
                7, 12, 17, 22,  7, 12, 17, 22,  7, 12, 17, 22,  7, 12, 17, 22,
                5,  9, 14, 20,  5,  9, 14, 20,  5,  9, 14, 20,  5,  9, 14, 20,
                4, 11, 16, 23,  4, 11, 16, 23,  4, 11, 16, 23,  4, 11, 16, 23,
                6, 10, 15, 21,  6, 10, 15, 21,  6, 10, 15, 21,  6, 10, 15, 21
            ];

            for (let offset = 0; offset < padLen; offset += 64) {
                let M = new Uint32Array(16);
                for (let j = 0; j < 16; j++) {
                    M[j] = view.getUint32(offset + j * 4, true);
                }
                let aa = a, bb = b, cc = c, dd = d;
                for (let i = 0; i < 64; i++) {
                    let f, g;
                    if (i < 16) {
                        f = (b & c) | ((~b) & d);
                        g = i;
                    } else if (i < 32) {
                        f = (d & b) | ((~d) & c);
                        g = (5 * i + 1) % 16;
                    } else if (i < 48) {
                        f = b ^ c ^ d;
                        g = (3 * i + 5) % 16;
                    } else {
                        f = c ^ (b | (~d));
                        g = (7 * i) % 16;
                    }
                    let temp = (d + 0) >>> 0;
                    d = c;
                    c = b;
                    let sum = (a + f + K[i] + M[g]) >>> 0;
                    let rot = ((sum << S[i]) | (sum >>> (32 - S[i]))) >>> 0;
                    b = (b + rot) >>> 0;
                    a = temp;
                }
                a = (a + aa) >>> 0;
                b = (b + bb) >>> 0;
                c = (c + cc) >>> 0;
                d = (d + dd) >>> 0;
            }
            let res = new Uint8Array(16);
            let rv = new DataView(res.buffer);
            rv.setUint32(0, a, true);
            rv.setUint32(4, b, true);
            rv.setUint32(8, c, true);
            rv.setUint32(12, d, true);
            return res;
        }

        // SM3 implementation
        function sm3(input) {
            let bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
            let n = bytes.length;
            let bitLen = n * 8;
            let padLen = ((n + 9 + 63) >>> 6) << 6;
            let msg = new Uint8Array(padLen);
            msg.set(bytes);
            msg[n] = 0x80;
            let view = new DataView(msg.buffer);
            view.setUint32(padLen - 8, Math.floor(bitLen / 0x100000000), false);
            view.setUint32(padLen - 4, bitLen >>> 0, false);

            let V = [
                0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600,
                0xa96f30bc, 0x163138aa, 0xe38dee4d, 0xb0fb0e4e
            ];

            function rotl(x, n) { return ((x << n) | (x >>> (32 - n))) >>> 0; }
            function P0(x) { return (x ^ rotl(x, 9) ^ rotl(x, 17)) >>> 0; }
            function P1(x) { return (x ^ rotl(x, 15) ^ rotl(x, 23)) >>> 0; }
            function FF(X, Y, Z, j) { return j < 16 ? (X ^ Y ^ Z) >>> 0 : ((X & Y) | (X & Z) | (Y & Z)) >>> 0; }
            function GG(X, Y, Z, j) { return j < 16 ? (X ^ Y ^ Z) >>> 0 : ((X & Y) | ((~X) & Z)) >>> 0; }

            let W = new Uint32Array(68);
            let W1 = new Uint32Array(64);

            for (let offset = 0; offset < padLen; offset += 64) {
                for (let j = 0; j < 16; j++) {
                    W[j] = view.getUint32(offset + j * 4, false);
                }
                for (let j = 16; j < 68; j++) {
                    W[j] = (P1(W[j - 16] ^ W[j - 9] ^ rotl(W[j - 3], 15)) ^ rotl(W[j - 13], 7) ^ W[j - 6]) >>> 0;
                }
                for (let j = 0; j < 64; j++) {
                    W1[j] = (W[j] ^ W[j + 4]) >>> 0;
                }

                let [A, B, C, D, E, F, G, H] = V;
                for (let j = 0; j < 64; j++) {
                    let T = j < 16 ? 0x79cc4519 : 0x7a879d8a;
                    let SS1 = rotl((rotl(A, 12) + E + rotl(T, j % 32)) >>> 0, 7);
                    let SS2 = (SS1 ^ rotl(A, 12)) >>> 0;
                    let TT1 = (FF(A, B, C, j) + D + SS2 + W1[j]) >>> 0;
                    let TT2 = (GG(E, F, G, j) + H + SS1 + W[j]) >>> 0;
                    D = C;
                    C = rotl(B, 9);
                    B = A;
                    A = TT1;
                    H = G;
                    G = rotl(F, 19);
                    F = E;
                    E = P0(TT2);
                }
                V[0] = (V[0] ^ A) >>> 0;
                V[1] = (V[1] ^ B) >>> 0;
                V[2] = (V[2] ^ C) >>> 0;
                V[3] = (V[3] ^ D) >>> 0;
                V[4] = (V[4] ^ E) >>> 0;
                V[5] = (V[5] ^ F) >>> 0;
                V[6] = (V[6] ^ G) >>> 0;
                V[7] = (V[7] ^ H) >>> 0;
            }

            let res = new Uint8Array(32);
            let rv = new DataView(res.buffer);
            for (let i = 0; i < 8; i++) {
                rv.setUint32(i * 4, V[i], false);
            }
            return res;
        }

        // Generic HMAC wrapper
        function hmacCustom(hashFn, blockSize, keyBytes, msgBytes) {
            let k = keyBytes;
            if (k.length > blockSize) {
                k = hashFn(k);
            }
            let kPad = new Uint8Array(blockSize);
            kPad.set(k);
            let oPad = new Uint8Array(blockSize);
            let iPad = new Uint8Array(blockSize);
            for (let i = 0; i < blockSize; i++) {
                oPad[i] = kPad[i] ^ 0x5c;
                iPad[i] = kPad[i] ^ 0x36;
            }
            let inner = new Uint8Array(blockSize + msgBytes.length);
            inner.set(iPad);
            inner.set(msgBytes, blockSize);
            let innerHash = hashFn(inner);

            let outer = new Uint8Array(blockSize + innerHash.length);
            outer.set(oPad);
            outer.set(innerHash, blockSize);
            return hashFn(outer);
        }

        // Pure JS SHA-1 fallback
        function sha1(input) {
            let bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
            let n = bytes.length;
            let bitLen = n * 8;
            let padLen = ((n + 9 + 63) >>> 6) << 6;
            let msg = new Uint8Array(padLen);
            msg.set(bytes);
            msg[n] = 0x80;
            let view = new DataView(msg.buffer);
            view.setUint32(padLen - 8, Math.floor(bitLen / 0x100000000), false);
            view.setUint32(padLen - 4, bitLen >>> 0, false);
            let H = [0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0];
            function rotl(x, n) { return (x << n) | (x >>> (32 - n)); }
            let W = new Uint32Array(80);
            for (let offset = 0; offset < padLen; offset += 64) {
                for (let t = 0; t < 16; t++) W[t] = view.getUint32(offset + t * 4, false);
                for (let t = 16; t < 80; t++) W[t] = rotl(W[t - 3] ^ W[t - 8] ^ W[t - 14] ^ W[t - 16], 1);
                let [a, b, c, d, e] = H;
                for (let t = 0; t < 80; t++) {
                    let f, k;
                    if (t < 20) { f = (b & c) | ((~b) & d); k = 0x5a827999; }
                    else if (t < 40) { f = b ^ c ^ d; k = 0x6ed9eba1; }
                    else if (t < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8f1bbcdc; }
                    else { f = b ^ c ^ d; k = 0xca62c1d6; }
                    let temp = (rotl(a, 5) + f + e + k + W[t]) >>> 0;
                    e = d; d = c; c = rotl(b, 30); b = a; a = temp;
                }
                H[0] = (H[0] + a) >>> 0;
                H[1] = (H[1] + b) >>> 0;
                H[2] = (H[2] + c) >>> 0;
                H[3] = (H[3] + d) >>> 0;
                H[4] = (H[4] + e) >>> 0;
            }
            let res = new Uint8Array(20);
            let rv = new DataView(res.buffer);
            for (let i = 0; i < 5; i++) rv.setUint32(i * 4, H[i], false);
            return res;
        }

        // Pure JS SHA-256 fallback
        function sha256(input) {
            let bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
            let n = bytes.length;
            let bitLen = n * 8;
            let padLen = ((n + 9 + 63) >>> 6) << 6;
            let msg = new Uint8Array(padLen);
            msg.set(bytes);
            msg[n] = 0x80;
            let view = new DataView(msg.buffer);
            view.setUint32(padLen - 8, Math.floor(bitLen / 0x100000000), false);
            view.setUint32(padLen - 4, bitLen >>> 0, false);
            let H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
            const K = [
                0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
                0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
                0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
                0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
                0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
                0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
                0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
                0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
            ];
            function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }
            let W = new Uint32Array(64);
            for (let offset = 0; offset < padLen; offset += 64) {
                for (let t = 0; t < 16; t++) W[t] = view.getUint32(offset + t * 4, false);
                for (let t = 16; t < 64; t++) {
                    let s0 = rotr(W[t - 15], 7) ^ rotr(W[t - 15], 18) ^ (W[t - 15] >>> 3);
                    let s1 = rotr(W[t - 2], 17) ^ rotr(W[t - 2], 19) ^ (W[t - 2] >>> 10);
                    W[t] = (W[t - 16] + s0 + W[t - 7] + s1) >>> 0;
                }
                let [a, b, c, d, e, f, g, h] = H;
                for (let t = 0; t < 64; t++) {
                    let S1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
                    let ch = (e & f) ^ ((~e) & g);
                    let temp1 = (h + S1 + ch + K[t] + W[t]) >>> 0;
                    let S0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
                    let maj = (a & b) ^ (a & c) ^ (b & c);
                    let temp2 = (S0 + maj) >>> 0;
                    h = g; g = f; f = e; e = (d + temp1) >>> 0; d = c; c = b; b = a; a = (temp1 + temp2) >>> 0;
                }
                H[0] = (H[0] + a) >>> 0;
                H[1] = (H[1] + b) >>> 0;
                H[2] = (H[2] + c) >>> 0;
                H[3] = (H[3] + d) >>> 0;
                H[4] = (H[4] + e) >>> 0;
                H[5] = (H[5] + f) >>> 0;
                H[6] = (H[6] + g) >>> 0;
                H[7] = (H[7] + h) >>> 0;
            }
            let res = new Uint8Array(32);
            let rv = new DataView(res.buffer);
            for (let i = 0; i < 8; i++) rv.setUint32(i * 4, H[i], false);
            return res;
        }

        function bufferToHex(buf) {
            const arr = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
            let s = '';
            for (let i = 0; i < arr.length; i++) {
                s += arr[i].toString(16).padStart(2, '0');
            }
            return s;
        }

        function bufferToBase64(buf) {
            const arr = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
            let binary = '';
            for (let i = 0; i < arr.length; i++) {
                binary += String.fromCharCode(arr[i]);
            }
            return window.btoa(binary);
        }

        function formatHash(rawBytes) {
            if (!rawBytes) return '-';
            const hex = bufferToHex(rawBytes);
            if (currentCase === 'upper') return hex.toUpperCase();
            if (currentCase === 'base64') return bufferToBase64(rawBytes);
            return hex.toLowerCase();
        }

        async function computeHashes() {
            let dataBytes;
            if (currentMode === 'text') {
                const text = textInput ? textInput.value : '';
                dataBytes = new TextEncoder().encode(text);
                if (textStats) textStats.textContent = `${text.length} 字符 / ${dataBytes.length} 字节`;
            } else {
                dataBytes = currentFileBytes || new Uint8Array(0);
            }

            const useHmac = chkEnableHmac ? chkEnableHmac.checked : false;
            const hmacKeyStr = hmacKeyInput ? hmacKeyInput.value : '';
            const hmacKeyBytes = new TextEncoder().encode(hmacKeyStr);

            const cryptoObj = window.crypto || window.msCrypto;
            const hasSubtle = cryptoObj && cryptoObj.subtle;

            // 1. MD5 (Pure JS)
            try {
                lastComputed['hash-md5'] = useHmac ? hmacCustom(md5, 64, hmacKeyBytes, dataBytes) : md5(dataBytes);
            } catch (err) {
                console.error('MD5 calculation error:', err);
            }

            // 2. SM3 (Pure JS)
            try {
                lastComputed['hash-sm3'] = useHmac ? hmacCustom(sm3, 64, hmacKeyBytes, dataBytes) : sm3(dataBytes);
            } catch (err) {
                console.error('SM3 calculation error:', err);
            }

            // 3. SHA-1 (Web Crypto with Pure JS fallback)
            try {
                if (hasSubtle && !useHmac) {
                    lastComputed['hash-sha1'] = new Uint8Array(await cryptoObj.subtle.digest('SHA-1', dataBytes));
                } else if (hasSubtle && useHmac) {
                    const key = await cryptoObj.subtle.importKey('raw', hmacKeyBytes, { name: 'HMAC', hash: 'SHA-1' }, false, ['sign']);
                    lastComputed['hash-sha1'] = new Uint8Array(await cryptoObj.subtle.sign('HMAC', key, dataBytes));
                } else {
                    lastComputed['hash-sha1'] = useHmac ? hmacCustom(sha1, 64, hmacKeyBytes, dataBytes) : sha1(dataBytes);
                }
            } catch (err) {
                try {
                    lastComputed['hash-sha1'] = useHmac ? hmacCustom(sha1, 64, hmacKeyBytes, dataBytes) : sha1(dataBytes);
                } catch (e2) {
                    console.error('SHA-1 fallback error:', e2);
                }
            }

            // 4. SHA-256 (Web Crypto with Pure JS fallback)
            try {
                if (hasSubtle && !useHmac) {
                    lastComputed['hash-sha256'] = new Uint8Array(await cryptoObj.subtle.digest('SHA-256', dataBytes));
                } else if (hasSubtle && useHmac) {
                    const key = await cryptoObj.subtle.importKey('raw', hmacKeyBytes, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
                    lastComputed['hash-sha256'] = new Uint8Array(await cryptoObj.subtle.sign('HMAC', key, dataBytes));
                } else {
                    lastComputed['hash-sha256'] = useHmac ? hmacCustom(sha256, 64, hmacKeyBytes, dataBytes) : sha256(dataBytes);
                }
            } catch (err) {
                try {
                    lastComputed['hash-sha256'] = useHmac ? hmacCustom(sha256, 64, hmacKeyBytes, dataBytes) : sha256(dataBytes);
                } catch (e2) {
                    console.error('SHA-256 fallback error:', e2);
                }
            }

            // 5. SHA-512 (Web Crypto)
            try {
                if (hasSubtle && !useHmac) {
                    lastComputed['hash-sha512'] = new Uint8Array(await cryptoObj.subtle.digest('SHA-512', dataBytes));
                } else if (hasSubtle && useHmac) {
                    const key = await cryptoObj.subtle.importKey('raw', hmacKeyBytes, { name: 'HMAC', hash: 'SHA-512' }, false, ['sign']);
                    lastComputed['hash-sha512'] = new Uint8Array(await cryptoObj.subtle.sign('HMAC', key, dataBytes));
                }
            } catch (err) {
                console.error('SHA-512 calculation error:', err);
            }

            renderOutputs();
            performVerification();
        }

        function renderOutputs() {
            if (outSha256) outSha256.textContent = formatHash(lastComputed['hash-sha256']);
            if (outSm3) outSm3.textContent = formatHash(lastComputed['hash-sm3']);
            if (outMd5) outMd5.textContent = formatHash(lastComputed['hash-md5']);
            if (outSha1) outSha1.textContent = formatHash(lastComputed['hash-sha1']);
            if (outSha512) outSha512.textContent = formatHash(lastComputed['hash-sha512']);
        }

        function performVerification() {
            const query = verifyInput.value.trim().toLowerCase();
            const cards = ['card-sha256', 'card-sm3', 'card-md5', 'card-sha1', 'card-sha512'];
            const targetIds = ['hash-sha256', 'hash-sm3', 'hash-md5', 'hash-sha1', 'hash-sha512'];

            cards.forEach(c => {
                const cardEl = document.getElementById(c);
                if (cardEl) {
                    cardEl.classList.remove('border-emerald-500', 'bg-emerald-500/5');
                    cardEl.querySelector('.verify-match-tag')?.classList.add('hidden');
                }
            });

            if (!query) {
                verifyBadge.classList.add('hidden');
                return;
            }

            let matchAlgo = null;
            for (let i = 0; i < targetIds.length; i++) {
                const id = targetIds[i];
                const rawBytes = lastComputed[id];
                if (!rawBytes) continue;
                const hexVal = bufferToHex(rawBytes).toLowerCase();
                const b64Val = bufferToBase64(rawBytes);
                if (hexVal === query || b64Val === query) {
                    matchAlgo = id;
                    const cardEl = document.getElementById(cards[i]);
                    if (cardEl) {
                        cardEl.classList.add('border-emerald-500', 'bg-emerald-500/5');
                        cardEl.querySelector('.verify-match-tag')?.classList.remove('hidden');
                    }
                    break;
                }
            }

            verifyBadge.classList.remove('hidden');
            if (matchAlgo) {
                const algoNames = {
                    'hash-sha256': 'SHA-256',
                    'hash-sm3': '国密 SM3',
                    'hash-md5': 'MD5',
                    'hash-sha1': 'SHA-1',
                    'hash-sha512': 'SHA-512'
                };
                verifyBadge.className = 'px-3 py-1 rounded-full text-xs font-mono font-bold flex items-center gap-1.5 bg-emerald-500/10 text-emerald-500 border border-emerald-500/30';
                verifyBadge.innerHTML = `<i class="fa-solid fa-check"></i><span>校验一致！匹配算法: ${algoNames[matchAlgo]}</span>`;
            } else {
                verifyBadge.className = 'px-3 py-1 rounded-full text-xs font-mono font-bold flex items-center gap-1.5 bg-red-500/10 text-red-500 border border-red-500/30';
                verifyBadge.innerHTML = '<i class="fa-solid fa-xmark"></i><span>与当前所有算法结果均不匹配</span>';
            }
        }

        // Event listeners
        btnSourceText.addEventListener('click', () => {
            currentMode = 'text';
            btnSourceText.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnSourceFile.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            textPanel.classList.remove('hidden');
            filePanel.classList.add('hidden');
            computeHashes();
        });

        btnSourceFile.addEventListener('click', () => {
            currentMode = 'file';
            btnSourceFile.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnSourceText.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            filePanel.classList.remove('hidden');
            textPanel.classList.add('hidden');
            computeHashes();
        });

        textInput.addEventListener('input', computeHashes);
        btnClearText.addEventListener('click', () => {
            textInput.value = '';
            computeHashes();
        });

        // Case buttons
        function updateCaseButtons(c) {
            currentCase = c;
            [btnCaseLower, btnCaseUpper, btnCaseBase64].forEach(b => {
                b.className = 'px-2.5 py-1 rounded text-meta hover:text-main font-medium transition-all';
            });
            if (c === 'lower') btnCaseLower.className = 'px-2.5 py-1 rounded bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            if (c === 'upper') btnCaseUpper.className = 'px-2.5 py-1 rounded bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            if (c === 'base64') btnCaseBase64.className = 'px-2.5 py-1 rounded bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            renderOutputs();
        }

        btnCaseLower.addEventListener('click', () => updateCaseButtons('lower'));
        btnCaseUpper.addEventListener('click', () => updateCaseButtons('upper'));
        btnCaseBase64.addEventListener('click', () => updateCaseButtons('base64'));

        chkEnableHmac.addEventListener('change', () => {
            if (chkEnableHmac.checked) {
                hmacContainer.classList.remove('hidden');
                hmacContainer.classList.add('flex');
            } else {
                hmacContainer.classList.add('hidden');
                hmacContainer.classList.remove('flex');
            }
            computeHashes();
        });
        hmacKeyInput.addEventListener('input', computeHashes);
        verifyInput.addEventListener('input', performVerification);

        // Copy buttons
        document.querySelectorAll('.btn-copy-hash').forEach(btn => {
            btn.addEventListener('click', () => {
                const targetId = btn.getAttribute('data-target');
                const targetEl = document.getElementById(targetId);
                if (targetEl && targetEl.textContent && targetEl.textContent !== '-') {
                    navigator.clipboard.writeText(targetEl.textContent.trim());
                    const orig = btn.innerHTML;
                    btn.innerHTML = '<i class="fa-solid fa-check text-emerald-500 mr-1"></i>已复制';
                    setTimeout(() => { btn.innerHTML = orig; }, 1500);
                }
            });
        });

        // File drag and drop
        dropZone.addEventListener('click', () => fileInput.click());
        dropZone.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropZone.classList.add('border-accent');
        });
        dropZone.addEventListener('dragleave', () => dropZone.classList.remove('border-accent'));
        dropZone.addEventListener('drop', (e) => {
            e.preventDefault();
            dropZone.classList.remove('border-accent');
            if (e.dataTransfer.files.length > 0) {
                handleFile(e.dataTransfer.files[0]);
            }
        });
        fileInput.addEventListener('change', () => {
            if (fileInput.files.length > 0) {
                handleFile(fileInput.files[0]);
            }
        });

        function formatBytes(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return (bytes / Math.pow(k, i)).toFixed(2) + ' ' + sizes[i];
        }

        function handleFile(file) {
            currentMode = 'file';
            btnSourceFile.className = 'px-4 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnSourceText.className = 'px-4 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            filePanel.classList.remove('hidden');
            textPanel.classList.add('hidden');
            metaFileName.textContent = file.name;
            metaFileSize.textContent = formatBytes(file.size);
            metaFileType.textContent = file.type || '二进制/未知文件类型';
            fileMetaInfo.classList.remove('hidden');
            btnClearFile.classList.remove('hidden');
            dropZoneTitle.textContent = '已选取文件: ' + file.name;
            dropZoneDesc.textContent = '正在本地极速读取并计算哈希散列...';

            const reader = new FileReader();
            reader.onload = function (e) {
                currentFileBytes = new Uint8Array(e.target.result);
                dropZoneDesc.textContent = '计算完成！数据未离开您的设备';
                computeHashes();
            };
            reader.readAsArrayBuffer(file);
        }

        btnClearFile.addEventListener('click', () => {
            fileInput.value = '';
            currentFileBytes = null;
            fileMetaInfo.classList.add('hidden');
            btnClearFile.classList.add('hidden');
            dropZoneTitle.textContent = '点击选择文件，或直接拖拽文件至此';
            dropZoneDesc.textContent = '支持任意格式文件 (ISO、安装包、固件、压缩包等)';
            computeHashes();
        });

        // Initial default text
        textInput.value = 'WindBlog 极客博客 - 纯前端高性能在线哈希散列计算器';
        computeHashes();
    }

    if (document.readyState !== 'loading') {
        initHashCalculator();
    } else {
        document.addEventListener('DOMContentLoaded', initHashCalculator);
    }
    document.addEventListener('page:ready', initHashCalculator);

    /* ==========================================================================
       8. JSON 格式化与可视化差分对比 (JSON Formatter & Visual Diff)
       ========================================================================== */
    function initJsonDiff() {
        const root = document.getElementById('jsonDiffToolApp');
        if (!root) return;

        const btnTabFormat = document.getElementById('btnJsonTabFormat');
        const btnTabDiff = document.getElementById('btnJsonTabDiff');
        const formatContainer = document.getElementById('jsonFormatModeContainer');
        const diffContainer = document.getElementById('jsonDiffModeContainer');

        const selIndent = document.getElementById('selJsonIndent');
        const btnFormat = document.getElementById('btnFormatJson');
        const btnMinify = document.getElementById('btnMinifyJson');
        const btnFix = document.getElementById('btnFixJson');
        const btnEscape = document.getElementById('btnEscapeJson');
        const btnUnescape = document.getElementById('btnUnescapeJson');
        const btnLoadDemo = document.getElementById('btnLoadJsonDemo');
        const btnClearFormat = document.getElementById('btnClearJsonFormat');
        const btnCopyFormat = document.getElementById('btnCopyJsonFormat');
        const formatTextarea = document.getElementById('jsonFormatTextarea');
        const formatStats = document.getElementById('jsonFormatStats');
        const validBadge = document.getElementById('jsonValidBadge');
        const errorAlert = document.getElementById('jsonFormatErrorAlert');
        const errorMessage = document.getElementById('jsonFormatErrorMessage');
        const pathInput = document.getElementById('jsonPathQueryInput');
        const btnExecPath = document.getElementById('btnExecJsonPath');
        const btnResetPath = document.getElementById('btnResetJsonPath');

        // Diff mode elements
        const btnRunDiff = document.getElementById('btnRunDiff');
        const btnSwapDiff = document.getElementById('btnSwapDiff');
        const btnLoadDiffDemo = document.getElementById('btnLoadDiffDemo');
        const btnClearDiff = document.getElementById('btnClearDiff');
        const diffLeftInput = document.getElementById('diffLeftInput');
        const diffRightInput = document.getElementById('diffRightInput');
        const btnFormatLeft = document.getElementById('btnFormatLeft');
        const btnFormatRight = document.getElementById('btnFormatRight');
        const diffVisualOutput = document.getElementById('diffVisualOutput');
        const statAdded = document.getElementById('diffStatAdded');
        const statRemoved = document.getElementById('diffStatRemoved');
        const statModified = document.getElementById('diffStatModified');

        let originalUnfilteredJson = null;

        // Tab Switching
        btnTabFormat.addEventListener('click', () => {
            btnTabFormat.className = 'px-3.5 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnTabDiff.className = 'px-3.5 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            formatContainer.classList.remove('hidden');
            diffContainer.classList.add('hidden');
        });

        btnTabDiff.addEventListener('click', () => {
            btnTabDiff.className = 'px-3.5 py-1.5 rounded-lg bg-accent text-[var(--accent-foreground)] font-bold transition-all';
            btnTabFormat.className = 'px-3.5 py-1.5 rounded-lg text-meta hover:text-main font-medium transition-all';
            diffContainer.classList.remove('hidden');
            formatContainer.classList.add('hidden');
        });

        function getIndentValue() {
            const v = selIndent.value;
            if (v === 'tab') return '\t';
            return parseInt(v, 10);
        }

        function updateFormatStats() {
            const val = formatTextarea.value;
            const lines = val ? val.split('\n').length : 0;
            formatStats.textContent = `${val.length} 字符 / ${lines} 行`;

            try {
                if (val.trim()) {
                    JSON.parse(val);
                    validBadge.classList.remove('hidden');
                    errorAlert.classList.add('hidden');
                } else {
                    validBadge.classList.add('hidden');
                    errorAlert.classList.add('hidden');
                }
            } catch (err) {
                validBadge.classList.add('hidden');
            }
        }

        formatTextarea.addEventListener('input', updateFormatStats);

        btnFormat.addEventListener('click', () => {
            const raw = formatTextarea.value.trim();
            if (!raw) return;
            try {
                const parsed = JSON.parse(raw);
                formatTextarea.value = JSON.stringify(parsed, null, getIndentValue());
                errorAlert.classList.add('hidden');
                updateFormatStats();
            } catch (err) {
                errorAlert.classList.remove('hidden');
                errorMessage.textContent = '格式化失败: ' + err.message;
            }
        });

        btnMinify.addEventListener('click', () => {
            const raw = formatTextarea.value.trim();
            if (!raw) return;
            try {
                const parsed = JSON.parse(raw);
                formatTextarea.value = JSON.stringify(parsed);
                errorAlert.classList.add('hidden');
                updateFormatStats();
            } catch (err) {
                errorAlert.classList.remove('hidden');
                errorMessage.textContent = '压缩失败: ' + err.message;
            }
        });

        btnFix.addEventListener('click', () => {
            let s = formatTextarea.value;
            if (!s.trim()) return;
            // Remove comments
            s = s.replace(/\/\/.*$/gm, '');
            s = s.replace(/\/\*[\s\S]*?\*\//g, '');
            // Replace single quotes around values
            s = s.replace(/'([^'\\]*(\\.[^'\\]*)*)'/g, '"$1"');
            // Remove trailing commas
            s = s.replace(/,\s*([}\]])/g, '$1');
            // Wrap unquoted keys
            s = s.replace(/([{,]\s*)([a-zA-Z0-9_$-]+)\s*:/g, '$1"$2":');

            try {
                const parsed = JSON.parse(s);
                formatTextarea.value = JSON.stringify(parsed, null, getIndentValue());
                errorAlert.classList.add('hidden');
                updateFormatStats();
            } catch (err) {
                errorAlert.classList.remove('hidden');
                errorMessage.textContent = '智能容错修复部分完成，但仍有未识别错误: ' + err.message;
                formatTextarea.value = s;
                updateFormatStats();
            }
        });

        btnEscape.addEventListener('click', () => {
            const raw = formatTextarea.value;
            if (!raw) return;
            formatTextarea.value = JSON.stringify(raw);
            updateFormatStats();
        });

        btnUnescape.addEventListener('click', () => {
            const raw = formatTextarea.value.trim();
            if (!raw) return;
            try {
                if ((raw.startsWith('"') && raw.endsWith('"')) || (raw.startsWith("'") && raw.endsWith("'"))) {
                    formatTextarea.value = JSON.parse(raw);
                } else {
                    formatTextarea.value = raw.replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\');
                }
                updateFormatStats();
            } catch (e) {
                formatTextarea.value = raw.replace(/\\"/g, '"').replace(/\\n/g, '\n').replace(/\\\\/g, '\\');
                updateFormatStats();
            }
        });

        btnClearFormat.addEventListener('click', () => {
            formatTextarea.value = '';
            errorAlert.classList.add('hidden');
            updateFormatStats();
        });

        btnCopyFormat.addEventListener('click', () => {
            if (formatTextarea.value) {
                navigator.clipboard.writeText(formatTextarea.value);
                const orig = btnCopyFormat.innerHTML;
                btnCopyFormat.innerHTML = '<i class="fa-solid fa-check text-emerald-500 mr-1"></i>已复制';
                setTimeout(() => { btnCopyFormat.innerHTML = orig; }, 1500);
            }
        });

        const DEMO_JSON = {
            appName: "WindBlog",
            version: "2.1.0",
            features: [
                { id: "subnet", name: "子网掩码推导", active: true },
                { id: "timestamp", name: "时间戳转换", active: true },
                { id: "base", name: "Base64全家桶", active: true },
                { id: "cron", name: "Cron模拟器", active: true },
                { id: "regex", name: "正则调试器", active: true },
                { id: "hash", name: "哈希与国密SM3", active: true },
                { id: "diff", name: "JSON Diff", active: true }
            ],
            settings: {
                theme: "system",
                pjaxEnabled: true,
                localComputeOnly: true
            }
        };

        btnLoadDemo.addEventListener('click', () => {
            formatTextarea.value = JSON.stringify(DEMO_JSON, null, getIndentValue());
            errorAlert.classList.add('hidden');
            updateFormatStats();
        });

        // JSON Path extraction
        btnExecPath.addEventListener('click', () => {
            const query = pathInput.value.trim();
            if (!query) return;
            try {
                const parsed = JSON.parse(formatTextarea.value);
                if (originalUnfilteredJson === null) {
                    originalUnfilteredJson = formatTextarea.value;
                }
                const parts = query.replace(/^\$\.?/, '').split(/\.|\/|\[|\]/).filter(Boolean);
                let curr = parsed;
                for (const p of parts) {
                    if (curr === undefined || curr === null) break;
                    curr = curr[p];
                }
                if (curr !== undefined) {
                    formatTextarea.value = typeof curr === 'object' ? JSON.stringify(curr, null, getIndentValue()) : String(curr);
                    btnResetPath.classList.remove('hidden');
                    updateFormatStats();
                } else {
                    alert('未在 JSON 中找到指定属性路径: ' + query);
                }
            } catch (err) {
                alert('解析 JSON 失败: ' + err.message);
            }
        });

        pathInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') btnExecPath.click();
        });

        btnResetPath.addEventListener('click', () => {
            if (originalUnfilteredJson !== null) {
                formatTextarea.value = originalUnfilteredJson;
                originalUnfilteredJson = null;
                btnResetPath.classList.add('hidden');
                updateFormatStats();
            }
        });

        // Diff Mode Logic
        function formatInput(textarea) {
            try {
                const parsed = JSON.parse(textarea.value.trim());
                textarea.value = JSON.stringify(parsed, null, 2);
            } catch (e) {}
        }

        btnFormatLeft.addEventListener('click', () => formatInput(diffLeftInput));
        btnFormatRight.addEventListener('click', () => formatInput(diffRightInput));

        btnSwapDiff.addEventListener('click', () => {
            const temp = diffLeftInput.value;
            diffLeftInput.value = diffRightInput.value;
            diffRightInput.value = temp;
            executeDiff();
        });

        btnClearDiff.addEventListener('click', () => {
            diffLeftInput.value = '';
            diffRightInput.value = '';
            diffVisualOutput.innerHTML = '<div class="text-center py-8 text-meta text-xs">两侧已清空</div>';
            statAdded.textContent = '+0 新增';
            statRemoved.textContent = '-0 删除';
            statModified.textContent = '~0 修改';
        });

        const DIFF_DEMO_LEFT = {
            id: 1001,
            title: "微服务部署规范",
            author: "admin",
            tags: ["docker", "k8s"],
            config: {
                replicas: 2,
                autoScale: false,
                port: 8080
            }
        };

        const DIFF_DEMO_RIGHT = {
            id: 1001,
            title: "微服务集群与高可用部署规范 v2",
            author: "admin",
            tags: ["docker", "k8s", "quarkus"],
            config: {
                replicas: 5,
                autoScale: true,
                port: 8080,
                ssl: true
            }
        };

        btnLoadDiffDemo.addEventListener('click', () => {
            diffLeftInput.value = JSON.stringify(DIFF_DEMO_LEFT, null, 2);
            diffRightInput.value = JSON.stringify(DIFF_DEMO_RIGHT, null, 2);
            executeDiff();
        });

        function executeDiff() {
            formatInput(diffLeftInput);
            formatInput(diffRightInput);

            const lines1 = diffLeftInput.value.split('\n');
            const lines2 = diffRightInput.value.split('\n');
            const n = lines1.length, m = lines2.length;

            const dp = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
            for (let i = 0; i < n; i++) {
                for (let j = 0; j < m; j++) {
                    if (lines1[i] === lines2[j]) {
                        dp[i + 1][j + 1] = dp[i][j] + 1;
                    } else {
                        dp[i + 1][j + 1] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                    }
                }
            }

            let i = n, j = m;
            const diff = [];
            while (i > 0 || j > 0) {
                if (i > 0 && j > 0 && lines1[i - 1] === lines2[j - 1]) {
                    diff.unshift({ type: 'same', val: lines1[i - 1] });
                    i--; j--;
                } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                    diff.unshift({ type: 'add', val: lines2[j - 1] });
                    j--;
                } else if (i > 0 && (j === 0 || dp[i][j - 1] < dp[i - 1][j])) {
                    diff.unshift({ type: 'del', val: lines1[i - 1] });
                    i--;
                }
            }

            // Post-process to merge consecutive del + add as mod
            let addedCount = 0;
            let removedCount = 0;
            let modifiedCount = 0;

            const merged = [];
            for (let k = 0; k < diff.length; k++) {
                if (diff[k].type === 'del' && diff[k + 1] && diff[k + 1].type === 'add') {
                    merged.push({ type: 'mod', oldVal: diff[k].val, newVal: diff[k + 1].val });
                    modifiedCount++;
                    k++;
                } else if (diff[k].type === 'add') {
                    merged.push(diff[k]);
                    addedCount++;
                } else if (diff[k].type === 'del') {
                    merged.push(diff[k]);
                    removedCount++;
                } else {
                    merged.push(diff[k]);
                }
            }

            statAdded.textContent = `+${addedCount} 新增`;
            statRemoved.textContent = `-${removedCount} 删除`;
            statModified.textContent = `~${modifiedCount} 修改`;

            function escapeHtml(str) {
                return (str || '')
                    .replace(/&/g, '&amp;')
                    .replace(/</g, '&lt;')
                    .replace(/>/g, '&gt;');
            }

            diffVisualOutput.innerHTML = '';
            let lineNum = 1;
            merged.forEach(item => {
                const row = document.createElement('div');
                row.className = 'flex items-start px-2 py-0.5 rounded leading-relaxed transition-colors font-mono';

                if (item.type === 'same') {
                    row.className += ' text-main hover:bg-card';
                    row.innerHTML = `<span class="w-8 shrink-0 text-meta text-[11px] select-none text-right mr-3">${lineNum++}</span><span class="w-4 select-none text-meta"> </span><span class="flex-1 whitespace-pre-wrap break-all">${escapeHtml(item.val)}</span>`;
                } else if (item.type === 'add') {
                    row.className += ' bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 font-semibold';
                    row.innerHTML = `<span class="w-8 shrink-0 text-emerald-500 text-[11px] select-none text-right mr-3">${lineNum++}</span><span class="w-4 select-none text-emerald-500 font-bold">+</span><span class="flex-1 whitespace-pre-wrap break-all">${escapeHtml(item.val)}</span>`;
                } else if (item.type === 'del') {
                    row.className += ' bg-red-500/10 text-red-600 dark:text-red-400 line-through';
                    row.innerHTML = `<span class="w-8 shrink-0 text-red-500 text-[11px] select-none text-right mr-3">-</span><span class="w-4 select-none text-red-500 font-bold">-</span><span class="flex-1 whitespace-pre-wrap break-all">${escapeHtml(item.val)}</span>`;
                } else if (item.type === 'mod') {
                    row.className += ' bg-amber-500/10 text-amber-600 dark:text-amber-400 font-medium flex-col gap-0.5';
                    row.innerHTML = `
                        <div class="flex items-start w-full opacity-70 line-through">
                            <span class="w-8 shrink-0 text-amber-500 text-[11px] select-none text-right mr-3">-</span>
                            <span class="w-4 select-none text-amber-500 font-bold">~</span>
                            <span class="flex-1 whitespace-pre-wrap break-all">${escapeHtml(item.oldVal)}</span>
                        </div>
                        <div class="flex items-start w-full font-bold">
                            <span class="w-8 shrink-0 text-amber-500 text-[11px] select-none text-right mr-3">${lineNum++}</span>
                            <span class="w-4 select-none text-amber-500 font-bold">~</span>
                            <span class="flex-1 whitespace-pre-wrap break-all">${escapeHtml(item.newVal)}</span>
                        </div>
                    `;
                }
                diffVisualOutput.appendChild(row);
            });
        }

        btnRunDiff.addEventListener('click', executeDiff);

        // Initial default in format mode
        formatTextarea.value = JSON.stringify(DEMO_JSON, null, 2);
        updateFormatStats();
    }

    if (document.readyState !== 'loading') {
        initJsonDiff();
    } else {
        document.addEventListener('DOMContentLoaded', initJsonDiff);
    }
    document.addEventListener('page:ready', initJsonDiff);



})();
