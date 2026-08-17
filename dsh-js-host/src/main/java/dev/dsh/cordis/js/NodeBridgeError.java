package dev.dsh.cordis.js;

/** Node worker RPC/桥错误:worker 报错、进程崩溃、超时、hostile 消息校验失败等。 */
public final class NodeBridgeError extends RuntimeException {
    public NodeBridgeError(String message) { super(message); }
    public NodeBridgeError(String message, Throwable cause) { super(message, cause); }
}
