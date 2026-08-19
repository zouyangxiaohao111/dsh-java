# 实证:dsh 的 cordis 运行时完全可由我们的 Java 核心替代

日期:2026-08-19。实测(非纸面)。

## 实验
把 `vendor/dsh/node_modules/.pnpm/node_modules/@deepseek-ai/cordis` 符号链接改名
(cordis.bak),使 dsh 插件的 `require('@deepseek-ai/cordis')` / `import ... from '@deepseek-ai/cordis'`
**无法解析到真实 cordis**。

## 结果(移除 cordis 后 `./dshj web`)

```
dshj: profile 'web' booted ✅
dshj: profile 'web' is running on the Java harness ✅
dshj: dsh web UI at http://127.0.0.1:3080/ ✅
99 插件注册 ✅
首页 HTTP 200 / client.js HTTP 200 ✅
```

## 结论

**运行时完全不需要 dsh 的 cordis**。node-resolve-hook 精确拦截 `@deepseek-ai/cordis`
→ Java 桥 shim(shortCircuit),dsh 插件的 cordis import 在解析前就被重定向到我们的核心,
**dsh 的 cordis 代码从未被触碰**。实验后已恢复符号链接。

**架构含义**:
- dsh 的 cordis 从"运行时核心"退化为"构建期类型依赖"(让 dsh TS 源码能编译)。
- 我们的 Java 核心(`dev.dsh.cordis`)是运行时唯一 cordis——"一个 Java 核心"原则实证成立。
