# WindBlog 错误码

页面错误提示统一显示“具体原因。错误码：`WB-...`”。API 错误同时返回 `errorCode` 字段，并通过 `X-WindBlog-Error-Code` 响应头提供同一代码。浏览器可直接把错误码交给维护者定位；错误码只标识原因类型，不包含用户名、邮箱、IP 或请求内容。

## 评论、验证码与 CSRF

| 错误码 | 具体原因 | 常见处理 |
| --- | --- | --- |
| `WB-SEC-CSRF-001` | CSRF 验证失败：请求头令牌缺失，或与当前 `XSRF-TOKEN` Cookie 不匹配。评论提交、注册及其他写请求都可能触发。 | 刷新页面后重试；若持续出现，检查浏览器是否阻止 Cookie、页面或脚本是否被缓存。 |
| `WB-COMMENT-CAPTCHA-001` | 评论请求没有有效的验证码 ID，或验证码尚未加载。 | 点击换一道题，等题目加载后再提交。 |
| `WB-COMMENT-CAPTCHA-002` | 验证码答案错误、已过期或已被使用。验证码只能成功校验一次，且有效期为 5 分钟。 | 重新获取验证码并填写新答案。 |
| `WB-COMMENT-CAPTCHA-003` | 服务端无法读写 Redis 验证码记录。 | 稍后重试；持续出现时检查 Redis 服务。 |
| `WB-COMMENT-RATE-001` | 评论或评论验证码请求超过 IP 频率限制。 | 按提示等待后重试。 |
| `WB-COMMENT-DISABLED-001` | 站点当前关闭了评论功能。 | 无需重复提交；联系站点维护者。 |
| `WB-COMMENT-POST-001` | 目标文章不存在、未发布、已删除或不可公开评论。 | 刷新文章页面；如果文章仍可见，请把文章链接和错误码提供给维护者。 |
| `WB-COMMENT-PARENT-001` | 回复目标不存在、已删除，或不属于当前文章。 | 刷新评论区后重新选择回复对象。 |
| `WB-COMMENT-INPUT-001` | 昵称、邮箱、站点 URL 或评论正文未通过服务端校验。屏幕上的错误文本会指出具体字段。 | 按提示修正字段后重试。 |
| `WB-COMMENT-REQUEST-001` | 评论请求缺少必需字段或格式不正确，且没有匹配到更具体的校验原因。 | 按提示检查评论内容和访客资料；仍失败时提供错误文本和码。 |
| `WB-COMMENT-SERVICE-001` | 评论接口发生服务端错误。 | 稍后重试；持续出现时提供错误码和文章链接。 |
| `WB-COMMENT-SUBMIT-001` | 评论接口拒绝了请求，但错误没有匹配到已登记的细分原因。 | 保留屏幕中的具体错误文本，并提供错误码。 |

## 注册、登录与密码

| 错误码 | 具体原因 | 常见处理 |
| --- | --- | --- |
| `WB-AUTH-LOGIN-001` | 登录账号或密码不匹配。 | 检查账号和密码；不要在反馈中发送密码。 |
| `WB-AUTH-REGISTER-001` | 站点关闭了新用户注册。 | 稍后再试或联系站点维护者。 |
| `WB-AUTH-REGISTER-002` | 注册请求未同意用户协议或隐私政策。 | 勾选协议后再提交。 |
| `WB-AUTH-REGISTER-003` | 注册密码与确认密码不一致。 | 重新填写两次密码。 |
| `WB-AUTH-REGISTER-004` | 用户名已被其他账号使用。 | 更换用户名。 |
| `WB-AUTH-REGISTER-005` | 邮箱已关联其他账号。 | 使用其他邮箱，或转到登录/找回密码流程。 |
| `WB-AUTH-REGISTER-006` | 注册请求超过频率限制。 | 等待提示时长后重试。 |
| `WB-AUTH-REGISTER-007` | 注册字段未通过必填、长度或邮箱格式校验；屏幕错误文本会给出具体字段。 | 按错误文本修正注册表单。 |
| `WB-AUTH-REGISTER-008` | 注册处理发生服务端错误。 | 稍后重试；持续出现时提供错误码。 |
| `WB-AUTH-RESET-001` | 密码重置链接无效/过期，或新密码字段校验失败。 | 重新申请重置邮件，并按页面要求填写密码。 |

## 其他 API 错误

下列通用码只在服务端没有更具体的业务码时使用。提示文字仍包含该请求的具体业务原因。

| 错误码 | 对应 HTTP 状态 / 含义 |
| --- | --- |
| `WB-HTTP-400-001` | 400：请求字段缺失、格式无效或校验失败。 |
| `WB-HTTP-401-001` | 401：缺少有效登录状态、凭据无效或登录已过期。 |
| `WB-HTTP-403-001` | 403：权限、功能开关或业务策略拒绝请求；CSRF 拒绝使用 `WB-SEC-CSRF-001`。 |
| `WB-HTTP-404-001` | 404：请求的页面或资源不存在。 |
| `WB-HTTP-409-001` | 409：请求与当前数据状态冲突，例如重复名称或版本冲突。 |
| `WB-HTTP-422-001` | 422：请求格式可读，但不满足业务规则。 |
| `WB-HTTP-429-001` | 429：请求超过频率限制。 |
| `WB-HTTP-5XX-001` | 5xx：服务端或依赖服务异常；若提示同时包含追踪 ID，请一并提供。 |
| `WB-HTTP-OTHER-001` | 其他未分类的 API 拒绝响应。 |

## 页面和管理后台提示

这些错误由浏览器或管理页面生成，后端没有返回更具体的 `errorCode` 时使用。对应动作和失败位置如下。

| 错误码 | 具体失败位置 |
| --- | --- |
| `WB-UI-AUTH-LOGIN-001` | 登录接口返回拒绝，但没有提供后端错误码。 |
| `WB-UI-AUTH-LOGIN-002` | 登录请求未能收到或解析服务端响应。 |
| `WB-UI-AUTH-REGISTER-001` | 注册接口返回拒绝，但没有提供后端错误码。 |
| `WB-UI-AUTH-REGISTER-009` | 注册请求未能收到或解析服务端响应。 |
| `WB-UI-AUTH-FORGOT-001` / `WB-UI-AUTH-FORGOT-002` | 找回密码接口返回拒绝 / 找回密码请求网络失败。 |
| `WB-UI-AUTH-RESET-001` | 密码重置页面的请求或响应处理失败。 |
| `WB-UI-AUTH-RESEND-001` / `WB-UI-AUTH-RESEND-002` | 重发验证邮件接口返回拒绝 / 网络失败。 |
| `WB-UI-USER-SUBSCRIPTIONS-001` / `WB-UI-USER-SUBSCRIPTIONS-LOAD-001` | 保存订阅偏好 / 加载订阅偏好失败。 |
| `WB-UI-USER-EMAIL-001` | 邮箱换绑请求失败。 |
| `WB-UI-USER-PASSWORD-001` | 修改密码请求失败。 |
| `WB-UI-USER-FAVORITE-001` / `WB-UI-USER-FAVORITES-LOAD-001` | 收藏操作 / 加载收藏列表失败。 |
| `WB-UI-USER-CHECKIN-001` / `WB-UI-USER-CHECKIN-002` | 签到被接口拒绝 / 签到网络失败。 |
| `WB-UI-USER-NOTIFICATIONS-LOAD-001` | 加载站内通知失败。 |
| `WB-UI-USER-WALLET-LOAD-001` | 加载积分变动记录失败。 |
| `WB-UI-USER-POST-001` / `WB-UI-USER-POST-LIST-001` | 用户投稿编辑区未分类错误 / 加载投稿列表失败。 |
| `WB-UI-USER-POST-LOAD-001` / `WB-UI-USER-POST-SAVE-001` / `WB-UI-USER-POST-REVIEW-001` | 加载投稿 / 保存草稿 / 提交审核失败。 |
| `WB-UI-USER-INVENTORY-001` / `WB-UI-USER-INVENTORY-LOAD-001` / `WB-UI-USER-INVENTORY-SAVE-001` | 用户仓库未分类错误 / 加载仓库 / 保存物品位置失败。 |
| `WB-UI-COMMENT-CONTENT-001` | 评论正文为空。 |
| `WB-UI-COMMENT-LOAD-001` | 加载文章评论列表失败。 |
| `WB-UI-COMMENT-CAPTCHA-001` / `WB-UI-COMMENT-CAPTCHA-002` | 提交前验证码尚未加载 / 页面加载评论验证码失败。 |
| `WB-UI-COMMENT-SUBMIT-001` | 评论提交失败且没有后端错误码，例如网络中断或响应无法解析。 |
| `WB-UI-QUOTE-SECTION-001` | 引用/纠错交互未能找到文章评论区。 |
| `WB-UI-CORRECTION-REQUIRED-001` / `WB-UI-CORRECTION-POST-001` / `WB-UI-CORRECTION-SUBMIT-001` | 纠错说明为空 / 未取得文章标识 / 纠错提交网络或响应失败。 |
| `WB-UI-LINK-PROBE-001` / `WB-UI-LINK-APPLY-001` | 友链连通性检测 / 友链申请失败。 |
| `WB-UI-NAVIGATION-001` | PJAX 页面局部加载失败，浏览器已回退到完整页面导航。 |
| `WB-UI-PREMIUM-AUTH-001` / `WB-UI-PREMIUM-NETWORK-001` / `WB-UI-PREMIUM-PURCHASE-001` | 未登录而尝试解锁 / 解锁网络失败 / 解锁接口拒绝请求。 |
| `WB-UI-ADMIN-SESSION-001` / `WB-UI-ADMIN-IMAGE-UPLOAD-001` | 管理后台图片上传会话失效 / 图片上传失败。 |
| `WB-UI-ADMIN-QUEUE-STATUS-001` / `WB-UI-ADMIN-QUEUE-EXAMPLE-001` / `WB-UI-ADMIN-QUEUE-PUSH-001` | 加载队列状态 / 生成队列示例 / 推送队列消息失败。 |
| `WB-UI-ADMIN-DLX-LOAD-001` / `WB-UI-ADMIN-DLX-RETRY-001` / `WB-UI-ADMIN-DLX-BATCH-RETRY-001` | 加载死信列表 / 重试单条死信 / 批量重试死信失败。 |
| `WB-UI-ADMIN-DLX-DISMISS-001` / `WB-UI-ADMIN-DLX-DELETE-001` | 标记死信已处理 / 删除死信记录失败。 |
| `WB-UI-UNCLASSIFIED-001` | 旧版或新增前端错误提示没有指定细分码，且服务端没有返回错误码。请同时提供提示原文；后续应为该错误补充专用码。 |

## 排查顺序

1. 先按错误码查本页，错误提示中的文本补充具体字段、状态或服务原因。
2. 如果是 `WB-SEC-CSRF-001`，刷新页面再提交；持续出现时检查 `XSRF-TOKEN` Cookie、请求头 `X-XSRF-TOKEN` 和缓存版本。
3. 如果是 `WB-UI-*`，按码定位到具体页面动作；网络类代码同时检查浏览器 Network 面板中的请求状态。
4. 如果是 `WB-HTTP-5XX-001`，连同提示里的追踪 ID 一起反馈。不要提交密码、Cookie、验证码答案或完整请求头。
