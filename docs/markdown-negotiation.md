# Markdown content negotiation

Public blog pages support `Accept: text/markdown`. Requests without an explicit
Markdown preference keep the existing HTML representation. An explicit HTML
preference with a higher `q` value takes precedence; `q=0` disables Markdown.
API routes, administration pages and static assets are outside this negotiation.

The existing controller renders the page, including its language and content
access checks, before conversion. Markdown contains headings, links, lists,
tables and code, while scripts, styles, navigation, forms and other page chrome
are removed. Responses declare `text/markdown; charset=utf-8` and `Vary: Accept`.
Original body validators and lengths are removed after conversion. No approximate
token count is presented as an exact `x-markdown-tokens` count.

Local checks:

```sh
curl -i -H 'Accept: text/markdown' http://127.0.0.1:58080/
curl -i -H 'Accept: text/html' http://127.0.0.1:58080/
curl -i -H 'Accept: text/markdown' http://127.0.0.1:58080/terms
bash mvnw -Dtest=MarkdownNegotiationFilterTest,PjaxResponseFilterTest test
```

References: [implementation skill](https://isitagentready.com/.well-known/agent-skills/markdown-negotiation/SKILL.md)
and [Cloudflare Markdown for Agents](https://developers.cloudflare.com/fundamentals/reference/markdown-for-agents/).
