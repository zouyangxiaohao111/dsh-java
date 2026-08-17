package dev.dsh.demo;

import dev.dsh.cordis.Context;
import dev.dsh.cordis.Plugin;

/**
 * M5 profile 演示插件:提供一个自增计数器服务 {@code counter}。经 cordis.yml 的
 * {@code source: java:dev.dsh.demo.CounterPlugin} 由 {@code PluginLoaderService}
 * 按类名加载并注册进 registry。
 */
public class CounterPlugin implements Plugin<Void> {

    /** 计数器服务:跨语言可调用({@code next()} 返回自增后的值)。 */
    public static final class Counter {
        private int value;

        public int next() {
            return ++value;
        }
    }

    @Override
    public String name() {
        return "counter";
    }

    @Override
    public String[] provide() {
        return new String[]{"counter"};
    }

    @Override
    public Object apply(Context ctx, Void config) {
        ctx.provide("counter", new Counter());
        return null;
    }
}
