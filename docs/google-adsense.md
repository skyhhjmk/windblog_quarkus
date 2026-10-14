# Google AdSense

后台「系统设置」的 `advertising` 分组包含 Google AdSense 设置，沿用动态表单，无需升级 Admin。默认关闭，发布商 ID 已预填 `ca-pub-4006222603298393`。

只要配置中存在有效发布商 ID，站点就会生成 `/ads.txt`（`google.com, pub-4006222603298393, DIRECT, f08c47fec0942fa0`），并在所有页面的 `<head>` 输出 Google 所需的 `google-adsense-account` 验证 meta。两者与广告展示开关分离，关闭广告展示时仍可完成站点验证。

启用并保存后刷新博客页面。开启自动广告时，还需在 AdSense 控制台为站点启用自动广告。自动广告脚本在空闲时加载；手动广告块在接近视口时才加载脚本并请求广告，两者同时开启时脚本可能提前加载。

后台的「手动广告块」使用图形化表单添加和编辑广告位，不需要手动填写 JSON。下列结构仅用于说明配置格式（将 slot 换成真实广告位 ID）：

```json
[
  {"slot":"1234567890","placement":"sidebar","format":"auto","responsive":true,"minHeight":250,"device":"desktop"},
  {"slot":"2345678901","placement":"content_bottom","format":"horizontal","responsive":true,"minHeight":100,"device":"all"}
]
```

支持 `content_top`、`content_bottom`、`sidebar` 三个位置，每个位置可以多个广告块，最多读取 20 个。`enabled:false` 可停用单块；`device` 为 `all`、`mobile`、`desktop`（768px 分界）。`format` 支持 `auto`、`rectangle`、`horizontal`、`vertical`、`fluid`、`fixed`。固定尺寸使用 `width`、`height`；fluid 可设置 `layout`、`layoutKey`，按 Google 生成的代码填写。

`minHeight` 预留布局空间（0–1200px），`rootMargin` 控制懒加载预加载距离（0–2000px）。没有填写有效广告位时不会创建手动广告，也不会仅因占位容器加载第三方脚本。PJAX 切页会初始化新广告，保留的已初始化广告不会重复请求。未填充广告可自动折叠，网络错误不会阻塞页面。

发布商脚本本身不包含手动广告位 ID，不能据此推断 slot。实际展示取决于 Google 的站点审核、广告填充和浏览器拦截情况。如果部署层另行设置 CSP，需允许 Google AdSense 使用的脚本、连接、图片及 iframe 来源。当前应用 SecurityHeadersFilter 未输出 CSP。

响应式参数参考：https://support.google.com/adsense/answer/9183460
