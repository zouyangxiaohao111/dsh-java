# M8-1 curl 证据(实测,2026-08-19)

服务器:`./dshj web boot` → `dshj: dsh web UI at http://127.0.0.1:3080/`。

## 1. 首页返回真实前端 HTML(非状态页)

```
$ curl http://127.0.0.1:3080/
<!doctype html>
<html lang="zh-CN">
  <head><script>window.__DSH_BOOT__ = {"rev":"72056e8c3de2","entries":[{"id":"@deepseek-ai/dsh-typert-registry","url":"/plugins/@deepseek-ai/dsh-typert-registry/client.js?rev=f41d56e0b747",...},{"id":"@deepseek-ai/dsh-client-ui-conversation","url":"/plugins/@deepseek-ai/dsh-client-ui-conversation/client.js?rev=dc880d2413c3",...},...]}</script>
    <title>DeepSeek Harness</title>
    <script type="module" crossorigin src="/assets/index-C-1AiF3k.js"></script>
    ...
```

- 真实 DeepSeek Harness shell HTML(非 M6-5a 状态页)。
- `window.__DSH_BOOT__` 注入完整客户端插件花名册:client-modules / client-connection /
  client-runtime / ui-theme / ui-conversation / ui-tool / ui-sidebar / ui-settings / …(全部
  带 rev 与 inject 边)—— dsh-client-modules 的 index tap 跨桥注入。

## 2. boot 证据

`docs/m8-1/m8-1-boot.log`:`dshj: profile 'web' booted (dsh profile ...)` + 99 插件逐行
`[NODE]` 宿主(webserver / api-gateway / web-runtime / modules / connection / storage /
workspace / session-stats / 全部 ui-* / agent-presets …)+ `dsh web UI at http://127.0.0.1:3080/`。

## 3. 边界(HTTP 传输跨 worker 死锁)

`/plugins/<id>/client.js` 与 `/api/*` 在"每插件一个 worker"下跨 worker 路由死锁(route handler
与 node:http req/res 跨 worker 边界);首页(frontend-static fallback,同样跨 worker)能 serve,
证明跨 worker 调用链通,死锁集中在 route handler 处理 req/res 的路径。详见 §5。
