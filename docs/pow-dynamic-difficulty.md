# PoW 动态难度提升机制说明

WindBlog 的工作量证明（Proof-of-Work, PoW）由 [ProofOfWorkService.java](file:///home/hhjmk/codes/windblog_quarkus/src/main/java/com/biliwind/blog/service/security/ProofOfWorkService.java) 统一提供。系统会根据**请求 IP 频次、行为者与会话特征、异常 User-Agent、失败次数以及服务器实时负载**动态提升挑战难度（前导零比特数）。

---

## 1. 难度范围与场景默认配置

- **全局难度上下限**：`MIN_DIFFICULTY = 12`，`MAX_DIFFICULTY = 24`，`DEFAULT_DIFFICULTY = 16`。
- **挑战有效期**：默认 `300s`（范围 `60s ~ 1800s`），单次有效（Redis `windblog:pow-used:` 去重）。

| 场景 (`PowScene`) | 默认基础难度 (`defaultBaseDifficulty`) | 场景突发统计窗口 (`burstWindow`) |
| --- | --- | --- |
| `REGISTER`（注册） | `16` | `1 小时` |
| `LOGIN`（登录） | `14` | `15 分钟` |
| `COMMENT`（评论 / 纠错） | `16`（可通过 `COMMENT_SECURITY.pow_difficulty` 配置） | `10 分钟` |
| `REACTION`（文章表态） | `12` | `5 分钟` |
| `GENERAL`（通用） | `16` | `10 分钟` |

---

## 2. 总难度计算公式

每次签发请求级挑战（`issueForRequest`）时，最终难度按以下公式计算并裁剪到 `[12, 24]`：

```text
abuseBoost      = min(8, max(ipBoost, actorBoost) + (ipBoost > 0 && actorBoost > 0 ? 1 : 0))
finalDifficulty = clamp(clamp(baseDifficulty) + abuseBoost + loadBoost, 12, 24)
```

- 当 **IP 维度** 与 **行为者/会话维度** 同时触发提权（均 `> 0`）时，会额外叠加 `+1` 关联惩罚，`abuseBoost` 上限为 `+8`。
- `loadBoost` 为系统负载提权（最高 `+6`）。

---

## 3. 自动提升难度的具体条件

### 3.1 IP 维度提权（`ipBoost`，最高 `+6`）

取以下三项中的最大值：

1. **IP 全局高频突发**（统计窗口：`GLOBAL_BURST_WINDOW = 5 分钟`，键 `pow:ip:global:<ip>`）
   - 计数 `> 15`：`+2`
   - 计数 `> 30`：`+4`
   - 计数 `> 60`：`+6`
2. **IP 分场景高频突发**（统计窗口：见各场景 `burstWindow`，键 `pow:ip:<scene>:<ip>`）
   - `REGISTER`：`> 2` (`+2`)、`> 4` (`+4`)、`> 8` (`+6`)
   - `LOGIN`：`> 5` (`+2`)、`> 10` (`+4`)、`> 20` (`+6`)
   - `COMMENT`：`> 5` (`+2`)、`> 12` (`+4`)、`> 25` (`+6`)
   - `REACTION`：`> 20` (`+2`)、`> 40` (`+4`)、`> 80` (`+6`)
   - `GENERAL`：`> 5` (`+2`)、`> 12` (`+4`)、`> 25` (`+6`)
3. **IP 校验/业务失败惩罚**（统计窗口：`FAILURE_WINDOW = 15 分钟`，键 `pow:ip:fail:<ip>`）
   - 失败 `> 2`：`+2`
   - 失败 `> 6`：`+4`
   - 失败 `> 12`：`+6`

> 注：签发挑战、校验通过、校验失败或登录/注册业务失败均会累计相应计数。

---

### 3.2 行为者指纹、会话与 User-Agent 提权（`actorBoost`，最高 `+6`）

计算方式：`actorBoost = min(6, max(fingerprintBoost, sessionBoost) + uaBoost)`

1. **行为者指纹（`actorFingerprint`）**
   - 由请求头 `User-Agent | Accept-Language | Sec-CH-UA | Sec-CH-UA-Platform` 转小写拼接后取 SHA-256 前 8 字节十六进制（全部为空时记为 `anon`）。
   - 取以下三项最大值：
     - **全局高频**（`5 分钟` 窗口，`pow:actor:global:<fp>`）：`> 25` (`+2`)、`> 50` (`+4`)、`> 100` (`+6`)
     - **分场景高频**（各场景 `burstWindow`，`pow:actor:<scene>:<fp>`）：
       - `REGISTER`：`> 4` (`+2`)、`> 10` (`+4`)
       - `LOGIN`：`> 10` (`+2`)、`> 25` (`+4`)
       - `COMMENT`：`> 8` (`+2`)、`> 20` (`+4`)
       - `REACTION`：`> 40` (`+2`)、`> 80` (`+4`)
       - `GENERAL`：`> 8` (`+2`)、`> 20` (`+4`)
     - **失败惩罚**（`15 分钟` 窗口，`pow:actor:fail:<fp>`）：`> 5` (`+2`)、`> 15` (`+4`)

2. **会话指纹（`sessionFingerprint`）**
   - 取 `XSRF-TOKEN`（若无则取 `user_token`）Cookie 的 SHA-256 摘要，按各场景 `burstWindow` 统计（`pow:session:<scene>:<sessionFp>`）：
     - `REGISTER`：`> 3` (`+2`)、`> 6` (`+4`)
     - `LOGIN`：`> 8` (`+2`)、`> 16` (`+4`)
     - `COMMENT`：`> 6` (`+2`)、`> 12` (`+4`)
     - `REACTION`：`> 30` (`+2`)、`> 60` (`+4`)
     - `GENERAL`：`> 7` (`+2`)、`> 15` (`+4`)

3. **可疑 User-Agent 惩罚（`uaBoost`）**
   - 若 `User-Agent` 为空，或（忽略大小写）包含以下任意特征，额外 `+2`：
     `curl/`、`wget/`、`python-requests`、`python-urllib`、`aiohttp`、`httpx`、`go-http-client`、`okhttp`、`libwww-perl`、`headlesschrome`

---

### 3.3 系统负载提权（`loadBoost`，最高 `+6`）

系统实时探测以下指标（`loadRatio = systemLoadAverage / availableProcessors`）：

| 提权档位 | 触发条件（满足任一项即触发，按最高档位生效） |
| --- | --- |
| **`+6`** | `loadRatio >= 2.0` 或 `cpuLoad >= 0.97` |
| **`+4`** | `loadRatio >= 1.2` 或 `cpuLoad >= 0.90` 或 `heapUsageRatio >= 0.92` |
| **`+2`** | `loadRatio >= 0.8` 或 `cpuLoad >= 0.75` 或 `heapUsageRatio >= 0.85` |
| **`+0`** | 未达到以上阈值 |

---

## 4. 提交时的强度升级拦截

在 `verifyForRequest` 校验阶段，服务端还会重新计算当前实时所需难度（不增加请求计数）：

- 若 `currentRequired - challengeDifficulty >= STALE_UPGRADE_TOLERANCE (4)`：
  - 判定旧挑战已无法满足当前安全强度；
  - 拒绝本次提交，返回错误信息 **`安全校验强度已升级，请刷新校验后重试`**，并记录一次失败计数。
