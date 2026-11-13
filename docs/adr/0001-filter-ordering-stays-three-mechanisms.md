# 过滤器排序保持三种机制，不强行统一

请求过滤器链今天并存三种排序机制（Vert.x `filters.register(..., order)`、JAX-RS `@Priority`、`@ServerRequestFilter(preMatching=...)`/`@PreMatching`），看起来应当收拢为一个声明式阶段接缝。我们决定**不**统一：RESTEasy Reactive 无法把 preMatching、容器过滤器与路由处理器置于同一排序接口之下，强行收拢只会在框架之上再造一层伪接缝。

真正的风险是顺序不变量不可见：`LangRequestFilter` 读 `RegionContext` 却没有 `@Priority`（靠默认 USER=5000 恰好排在 `RegionFilter` 之后）；`AdminPathGuardFilter`/`HoneypotRequestFilter` 一旦被从 `preMatching` 降级，`%2e%2e` 路径绕过（见 `16f619d`）就会静默复发。我们以 `FilterOrderingTest`（纯反射，无需 DB）钉住这两类不变量作为 canary，而不是重架构。

## Consequences

- 新过滤器作者仍须自行选择三种机制之一；正确性由 canary 测试兜底，而非编译期保证。
- 若未来有人提议"统一 filter 排序"，先重读本 ADR 的框架约束，再考虑是否值得连 Quarkus 上游一起改。
