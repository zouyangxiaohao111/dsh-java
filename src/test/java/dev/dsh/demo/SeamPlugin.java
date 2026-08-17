package dev.dsh.demo;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * M5 混排 profile 的 Java 插件:提供 {@code llm}/{@code tools} 两个 ctx 服务 seam,
 * 供 dsh JS 插件(agent-loop-driver,node: 宿主)经桥消费 —— 跨语言组合的 Java 服务面。
 *
 * <p>与 M4 {@code AgentLoopFusionTest} 的 LlmStub/ToolsStub 同形状(design 诚实边界):
 * {@code llm.stream} 返回实体化 chunk 数组(agent-loop 的 {@code for await} 直接迭代),
 * {@code tools} 为排他调度桩(记录调用、返回罐装结果)。{@code llm}/{@code tools} 均为
 * public 实例字段,经 {@code ctx.provide} 注册进 registry —— 测试可从 {@code root.get}
 * 取回同一实例,预置 stream 响应。
 *
 * <p>测试仅(通过 cordis.yml 的 {@code source: java:dev.dsh.demo.SeamPlugin})按类名加载,
 * 不直接 import 本类到装配路径。
 */
public class SeamPlugin implements Plugin<Void> {

    public final Llm llm = new Llm();
    public final Tools tools = new Tools(toolResult("tool says hi"));

    @Override
    public String name() {
        return "seams";
    }

    @Override
    public String[] provide() {
        return new String[]{"llm", "tools"};
    }

    @Override
    public Object apply(Context ctx, Void config) {
        ctx.provide("llm", llm);
        ctx.provide("tools", tools);
        return null;
    }

    /** ctx.llm seam:stream 按序弹出预置 chunk 序列(空则回退),记录每次请求。 */
    public static final class Llm {
        public final List<Map<String, Object>> requests = new ArrayList<>();
        private final List<List<Map<String, Object>>> responses = new LinkedList<>();
        public int streamCalls = 0;
        public int resolveCalls = 0;

        public Llm response(List<Map<String, Object>> chunks) {
            responses.add(chunks);
            return this;
        }

        public Object stream(Map<String, Object> request) {
            streamCalls++;
            requests.add(request);
            if (!responses.isEmpty()) return responses.remove(0);
            return List.of(textChunk("fallback text"), finishChunk());
        }

        public Object resolveModelInfo(Map<String, Object> options) {
            resolveCalls++;
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("provider", options.get("provider"));
            info.put("model", options.get("model"));
            return info;
        }
    }

    /** ctx.tools seam:排他调度桩,记录每次调度调用并返回罐装结果。 */
    public static final class Tools {
        public final List<Map<String, Object>> calls = new ArrayList<>();
        public final Map<String, Object> result;
        public int prepareCalls = 0;

        public Tools(Map<String, Object> result) {
            this.result = result;
        }

        public Object executionMode(Map<String, Object> exec) {
            Map<String, Object> mode = new LinkedHashMap<>();
            mode.put("kind", "exclusive");
            return mode;
        }

        public Object schedulerPrepare(Map<String, Object> exec) {
            prepareCalls++;
            calls.add(exec);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", "final-result");
            out.put("exec", exec);
            out.put("result", result);
            return out;
        }

        public Object schedulerDispatch(Map<String, Object> exec) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", result);
            return out;
        }

        public Object schedulerFinish(Map<String, Object> exec, Map<String, Object> result) {
            return result;
        }

        public Object schedulerFinalize(Map<String, Object> exec, Map<String, Object> result) {
            return result;
        }
    }

    // ---- chunk / block builders(与 AgentLoopFusionTest 相同的 StreamChunk JSON 形状)----

    public static Map<String, Object> textChunk(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        return blockEnd(block);
    }

    public static Map<String, Object> toolCallChunk(String id, String name, String arguments) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool-call");
        block.put("id", id);
        block.put("name", name);
        block.put("arguments", arguments);
        return blockEnd(block);
    }

    public static Map<String, Object> blockEnd(Map<String, Object> block) {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("type", "block-end");
        chunk.put("index", 0);
        chunk.put("block", block);
        return chunk;
    }

    public static Map<String, Object> finishChunk() {
        Map<String, Object> chunk = new LinkedHashMap<>();
        chunk.put("type", "finish");
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("kind", "stop");
        chunk.put("reason", reason);
        return chunk;
    }

    public static List<Map<String, Object>> chunks(Map<String, Object>... cs) {
        return List.of(cs);
    }

    public static Map<String, Object> toolResult(String text) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "text");
        block.put("text", text);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", List.of(block));
        result.put("isError", false);
        return result;
    }
}
