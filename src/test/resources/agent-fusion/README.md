# agent-fusion — M4 node_modules overlay + cordis shim

The node_modules overlay that makes the real dsh agent-fusion packages
require-able inside the `NodeWorkerJsHost` worker, with ctx bridged to the
Java cordis core (the only non-replaceable component).

## The packages

| package | form | what it is |
|---|---|---|
| `@deepseek-ai/cordis` | **shim** | `Service` base class whose `super(ctx, name)` registers ctx/name/check into the JAVA core over the node-bridge RPC (bound public methods cross as fn handles and round-trip callable); `Context` static surface + brand. See `node_modules/@deepseek-ai/cordis/lib/index.js` for the deviation notes. |
| `@deepseek-ai/dsh-llm` | overlay | real `message.ts` / `brand.ts` / `call-config.ts` type-stripped (erasable-only) under `lib/`; the root re-exports them. The full `LlmRuntime` adapter machinery (pulls `@deepseek-ai/schemastery` + a peer chain) is out of overlay scope — documented. |
| `@deepseek-ai/dsh-session-projection` | overlay | real `index.ts` type-stripped: `SessionProjectionRegistry extends Service` — the object under test for the cordis Service bridge. |
| `@deepseek-ai/dsh-session-stats` | overlay | real `index.ts` + `projection.ts` type-stripped; value deps `zod` (real npm) + `@deepseek-ai/dsh-llm/message` (overlay). |
| `@deepseek-ai/dsh-system-prompt` | overlay | real `index.ts` (packages/core/system-prompt) type-stripped: the `SystemPrompt` registry service — section/context/tool/variable registration + `assemble()` over the `system-prompt/assemble` waterfall. Value deps `cordis` (shim) + `dsh-scope` + `schemastery` (overlays). |
| `@deepseek-ai/dsh-scope` | overlay | real `index.ts` + `store.ts` type-stripped: `ScopedLayers` / `NamedEntries` / `AnonymousEntries` / `scopeTarget` — the scope-aware registry primitives `SystemPrompt` is built on. |
| `@deepseek-ai/schemastery` | overlay | mini ESM surface for `z.object/boolean/string/array` with `.default()`/`.parse` (fills defaults) + `.merge`. |

## Layout

```
agent-fusion/
├── package.json            (type: module — Node require(esm) is exercised)
├── fusion-plugin/index.js  (fusion driver: SessionProjectionRegistry + session-stats on one ctx)
├── require-probe/index.js  (imports all four by bare specifier, reports loaded surface)
├── system-prompt-driver/index.js  (SystemPrompt fusion driver — see SystemPromptFusionTest)
└── node_modules/
    ├── zod/                (real npm dependency — reinstalled, gitignored)
    └── @deepseek-ai/       (committed overlays)
        ├── cordis/
        ├── dsh-llm/
        ├── dsh-session-projection/
        ├── dsh-session-stats/
        ├── dsh-system-prompt/
        ├── dsh-scope/
        └── schemastery/
```

## Reinstalling zod

`npm install` prunes unrecognized directories under `node_modules`, so install
zod FIRST and never re-run it here (the `@deepseek-ai/*` overlays would be
removed):

```
cd src/test/resources/agent-fusion
npm install --no-save --proxy http://127.0.0.1:7897 --https-proxy http://127.0.0.1:7897 zod@^4.4.3
```

## ctx seams (node-bridge)

The worker's ctx shim (`src/main/resources/js/node-bridge.js`) exposes the
contract surface the real dsh packages touch:

- `effect` runs the effect body **now** (cordis semantics) and registers the
  yielded disposer with the Java fiber: generator bodies (`ScopedLayers.effect`,
  `sessionProjections.register`) are stepped to their first yield so the layer
  tables are actually populated, and the generator tail is resumed after the
  undo runs at unload.
- `waterfall` resolves the ordered JS listener chain for the dispatch and folds
  it **locally** in the worker (Java hands back the JS fn handles; the worker
  calls them directly, so no cross-bridge round-trip deadlocks the sync host).
  Java-native listeners mixed into a worker-initiated waterfall cannot be folded
  synchronously — `NodeWorkerBridge` reports an explicit error (**NEEDS**).

## Known bridge simplifications (documented, not asserted)

- `ctx.provide(name, value)` forwards no separate predicate argument, so
  Java-side dependency gating uses the default (always-true) predicate; the JS
  `check` still crosses as a fn handle on the registered value.
- The projection fold behavior is covered by `NodeWorkerFusionSpikeTest`; the
  system-prompt section merge + waterfall fold by `SystemPromptFusionTest`.
- `Context.is(ctx)` reads false on the bridged ctx (a plain proxy, not a real
  `Context` instance); the brand applies to genuine cordis contexts.
