# WindBlog 后台 API 文档

## 文档入口
- Swagger UI: `/api/admin/docs`
- OpenAPI JSON: `/api/admin/openapi`

## 鉴权说明
- 登录接口：`POST /api/admin/auth/login`
- 登录成功返回 `token`，后续接口在请求头带上：
  - `Authorization: Bearer <token>`
- Token 默认有效期由配置 `admin.jwt.expire-minutes` 控制。

## 配置项
- `admin.jwt.secret`: JWT 对称签名密钥（生产环境务必修改）
- `admin.jwt.issuer`: JWT 发行者
- `admin.jwt.expire-minutes`: JWT 过期分钟数

## 接口列表

### 1) 鉴权接口
- `POST /api/admin/auth/login`: 登录
- `GET /api/admin/auth/me`: 当前登录用户

### 2) 文章管理接口
- `GET /api/admin/posts`: 分页查询文章
- `GET /api/admin/posts/{id}`: 查询文章详情
- `POST /api/admin/posts`: 创建文章草稿
- `PUT /api/admin/posts/{id}`: 编辑文章（带 `version` 做并发控制）
- `POST /api/admin/posts/{id}/publish`: 发布文章
- `DELETE /api/admin/posts/{id}`: 软删除文章

### 3) 基础后台接口
- `GET /api/admin/base/ping`: 连通性检查
- `GET /api/admin/base/overview`: 概览统计

### 4) 转载管理接口
- `GET /api/admin/repost/policies`: 文章转载协议目录
- `GET /api/admin/repost/stats`: 转载、短链、点击与风控统计
- `GET /api/admin/repost/licenses`: 转载授权分页列表，支持 `status`、`keyword`
- `POST /api/admin/repost/licenses/{id}/revoke`: 撤销转载授权（高风险，需要 step-up 与幂等键）
- `POST /api/admin/repost/licenses/{id}/check`: 检查转载页是否保留授权码、原文地址与 go 链接
- `GET /api/admin/repost/affiliate-links`: 商业链接分页列表
- `POST /api/admin/repost/affiliate-links`: 登记商业或 affiliate 目标链接
- `POST /api/admin/repost/affiliate-links/{id}/status`: 启用或停用商业链接（`status` 为 1/2）
- `GET /api/admin/repost/tokens`: go 短链 token 分页列表，支持 `status`、`tokenType`、`keyword`
- `POST /api/admin/repost/tokens/{id}/revoke`: 撤销短链（高风险，需要 step-up 与幂等键）
- `GET /api/admin/repost/click-events`: 点击证据链分页列表
- `GET /api/admin/repost/referrals`: 转载原文地址 GET 访问追踪列表
- `GET /api/admin/repost/suspicious`: 可疑转载聚合列表
- `GET /api/admin/repost/blocked-domains`: 封禁域名列表
- `POST /api/admin/repost/blocked-domains`: 封禁域名
- `POST /api/admin/repost/blocked-domains/{id}/unblock`: 解除域名封禁
- `GET /api/admin/repost/risk-devices`: 风险设备列表
- `POST /api/admin/repost/risk-devices`: 封禁风险设备
- `POST /api/admin/repost/risk-devices/{id}/unblock`: 解除风险设备封禁
- `GET /api/admin/repost/users`: 用户转载权限列表，支持 `keyword`、`banned`
- `POST /api/admin/repost/users/{id}/ban`: 封禁用户转载权限并撤销其有效授权（高风险，需要 step-up 与幂等键）
- `POST /api/admin/repost/users/{id}/unban`: 恢复用户转载申请权限

商业链接是文章登记的 affiliate 或赞助目标链接；正文中匹配的链接会被改写为 `/r/` go 短链用于统计点击。转载授权生成的商业链接是绑定授权记录的 go 短链：读者点击后先记录来源与授权状态，再进入原文。转载页必须保留该链接。

转载复制内容中的“原文链接”会附加 `wb_repost=<授权码>` 查询参数。读者通过该地址访问文章时，服务端会写入 `repost_referral_events` 转化记录；后台的“原文转化”列表和每条授权的原文访问次数即来自这些记录。用户可在用户中心“转载管理”中查看、修改转载地址、复制转载说明或撤销自己的授权。

## 示例

### 登录
```http
POST /api/admin/auth/login
Content-Type: application/json

{
  "account": "admin",
  "password": "your-password"
}
```

### 创建文章
```http
POST /api/admin/posts
Authorization: Bearer <token>
Content-Type: application/json

{
  "slug": "hello-windblog",
  "title": { "zh-cn": "你好，WindBlog" },
  "summary": { "zh-cn": "一篇测试文章" },
  "aiSummary": { "zh-cn": "AI 摘要内容" },
  "contentMarkdown": { "zh-cn": "# Hello" },
  "status": 0,
  "visibility": 0,
  "editorType": 0
}
```
