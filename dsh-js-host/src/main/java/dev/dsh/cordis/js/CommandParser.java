package dev.dsh.cordis.js;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令 DSL 的消息解析(Graal {@link JsCtxBridge} 与 Node {@link NodeWorkerBridge} 共用,
 * 避免两份实现漂移)。design §4.2:按空白切 token,首个为命令名;其后 token 命中某 option
 * 的别名(alias,如 {@code -e})或长名({@code --escape})时置 {@code options[name]=true},
 * 否则并入参数(多个参数以单个空格拼回消息文本)。值型 option 留待后续(记 TODO),当前只做布尔 flag。
 */
final class CommandParser {
    private CommandParser() { }

    record Parsed(String name, Map<String, Object> options, String arg) { }

    static Parsed parse(String message, List<Map<String, Object>> optionDefs) {
        String[] tokens = message.trim().split("\\s+");
        if (tokens.length == 0) return new Parsed("", Map.of(), "");
        String name = tokens[0];
        Map<String, Object> options = new LinkedHashMap<>();
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            boolean matched = false;
            for (Map<String, Object> o : optionDefs) {
                String optName = String.valueOf(o.get("name"));
                String alias = String.valueOf(o.get("alias"));
                if (t.equals(alias) || t.equals("--" + optName)) {
                    options.put(optName, true);
                    matched = true;
                    break;
                }
            }
            if (!matched) args.add(t);
        }
        return new Parsed(name, options, String.join(" ", args));
    }
}
