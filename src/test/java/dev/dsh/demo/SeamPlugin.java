package dev.dsh.demo;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * M5 混排 profile 的 Java 插件:提供 {@code llm} ctx 服务 seam,供 dsh JS 插件
 * (agent-loop-driver,node: 宿主)经桥消费 —— 跨语言组合的 Java 服务面。
 *
 * <p>与 M4 {@code AgentLoopFusionTest} 的 LlmStub 同形状(design 诚实边界):
 * {@code llm.stream} 返回实体化 chunk 数组(agent-loop 的 {@code for await} 直接迭代)。
 * {@code llm} 为 public 实例字段,经 {@code ctx.provide} 注册进 registry —— 测试可从
 * {@code root.get} 取回同一实例,预置 stream 响应。
 *
 * <p>M5-NEEDS-tools 起 {@code tools} 不再由 Java 侧 seam 提供:agent-loop-driver 在
 * worker 内挂载真实 {@code @deepseek-ai/dsh-tools} ToolRuntime,由它把 {@code tools}
 * provide 进 Java 核心(worker→Java 的跨语言组合方向)。故本插件只 provide {@code llm},
 * 避免与真实 ToolRuntime 的 {@code tools} 注册冲突(Reflect.provide 拒绝重复服务)。
 *
 * <p>测试仅(通过 cordis.yml 的 {@code source: java:dev.dsh.demo.SeamPlugin})按类名加载,
 * 不直接 import 本类到装配路径。
 */
public class SeamPlugin implements Plugin<Void> {

    public final Llm llm = new Llm();

    @Override
    public String name() {
        return "seams";
    }

    @Override
    public String[] provide() {
        return new String[]{"llm"};
    }

    @Override
    public Object apply(Context ctx, Void config) {
        ctx.provide("llm", llm);
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
}
