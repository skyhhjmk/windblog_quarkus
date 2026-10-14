# Fake AI API

本站提供两个公开、无需鉴权的模拟接口，不调用任何外部 AI 服务。输入内容不影响输出；每次生成等待 5 秒，然后返回 Markdown `text` 代码块，以各个字母拼出 GeekCoreLab 大字。Token 用量固定为 0，模型名称为虚构名称。

| 协议 | Base URL | 模型列表 | 生成接口 | 模型 ID |
| --- | --- | --- | --- | --- |
| OpenAI Chat Completions | `/fake-ai/openai/v1` | `GET /models` | `POST /chat/completions` | `GPT-100` |
| Claude Messages | `/fake-ai/claude/v1` | `GET /models` | `POST /messages` | `Claude Opus 100` |

SDK 配置时使用本站域名加上 Base URL；要求 API Key 的客户端可填写 `fake`。仅模拟上述接口，不提供工具调用、图片生成或真实推理。额外的生成参数不影响固定输出。

```sh
curl -N https://YOUR_SITE/fake-ai/openai/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{"model":"GPT-100","messages":[{"role":"user","content":"hello"}],"stream":true}'

curl -N https://YOUR_SITE/fake-ai/claude/v1/messages \
  -H 'Content-Type: application/json' \
  -H 'anthropic-version: 2023-06-01' \
  -d '{"model":"Claude Opus 100","max_tokens":1024,"messages":[{"role":"user","content":"hello"}],"stream":true}'
```

省略 `stream` 或设为 `false` 返回普通 JSON。流式 OpenAI 返回 assistant 开始事件、模拟的 `reasoning_content: "Thinking..."`（客户端扩展字段）、5 秒后的内容事件、stop 事件和 `[DONE]`。Claude 使用标准的 `message_start`、`ping`、`content_block_*`、`message_delta`、`message_stop` 事件；等待期间没有内容增量，不伪造签名的 Claude thinking block。

错误模型、空 messages、错误 stream 类型或缺少 Claude 正整数 max_tokens 返回对应协议的 400 错误。路径与协议不匹配返回 404。

协议参考：[OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat)、[Claude streaming](https://platform.claude.com/docs/en/build-with-claude/streaming)、[Claude models](https://platform.claude.com/docs/en/api/models/list)。
