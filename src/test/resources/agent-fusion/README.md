# agent-fusion — M4 node_modules overlay + cordis shim

The node_modules overlay that makes the real dsh agent-fusion packages
require-able inside the `NodeWorkerJsHost` worker, with ctx bridged to the
Java cordis core (the only non-replaceable component).

## The four packages

| package | form | what it is |
|---|---|---|
| `@deepseek-ai/cordis` | **shim** | `Service` base class whose `super(ctx, name)` registers ctx/name/check into the JAVA core over the node-bridge RPC (bound public methods cross as fn handles and round-trip callable); `Context` static surface + brand. See `node_modules/@deepseek-ai/cordis/lib/index.js` for the deviation notes. |
| `@deepseek-ai/dsh-llm` | overlay | real `message.ts` / `brand.ts` / `call-config.ts` type-stripped (erasable-only) under `lib/`; the root re-exports them. The full `LlmRuntime` adapter machinery (pulls `@deepseek-ai/schemastery` + a peer chain) is out of overlay scope — documented. |
| `@deepseek-ai/dsh-session-projection` | overlay | real `index.ts` type-stripped: `SessionProjectionRegistry extends Service` — the object under test for the cordis Service bridge. |
| `@deepseek-ai/dsh-session-stats` | overlay | real `index.ts` + `projection.ts` type-stripped; value deps `zod` (real npm) + `@deepseek-ai/dsh-llm/message` (overlay). |

## Layout

```
agent-fusion/
├── package.json            (type: module — Node require(esm) is exercised)
├── fusion-plugin/index.js  (fusion driver: SessionProjectionRegistry + session-stats on one ctx)
├── require-probe/index.js  (imports all four by bare specifier, reports loaded surface)
└── node_modules/
    ├── zod/                (real npm dependency — reinstalled, gitignored)
    └── @deepseek-ai/       (committed overlays)
        ├── cordis/
        ├── dsh-llm/
        ├── dsh-session-projection/
        └── dsh-session-stats/
```

## Reinstalling zod

`npm install` prunes unrecognized directories under `node_modules`, so install
zod FIRST and never re-run it here (the `@deepseek-ai/*` overlays would be
removed):

```
cd src/test/resources/agent-fusion
npm install --no-save --proxy http://127.0.0.1:7897 --https-proxy http://127.0.0.1:7897 zod@^4.4.3
```

## Known bridge simplifications (documented, not asserted)

- `ctx.provide(name, value)` forwards no separate predicate argument, so
  Java-side dependency gating uses the default (always-true) predicate; the JS
  `check` still crosses as a fn handle on the registered value.
- The host effect model defers JS effect bodies (e.g. the registry's
  generator-based `register`) to fiber unload — same simplification as the
  GraalJS host. The projection fold behavior is covered by
  `NodeWorkerFusionSpikeTest`.
- `Context.is(ctx)` reads false on the bridged ctx (a plain proxy, not a real
  `Context` instance); the brand applies to genuine cordis contexts.
