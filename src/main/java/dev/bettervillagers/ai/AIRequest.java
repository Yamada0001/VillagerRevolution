package dev.bettervillagers.ai;

import java.util.List;

/**
 * 大模型请求（规范 1.2 / 1.3）。
 * <p>
 * 不可变对象，在线程间传递（规范 1.3：AI 决策结果通过不可变对象传递）。
 */
public record AIRequest(
        List<Message> messages,
        String model,
        double temperature,
        int maxTokens,
        long timeoutSeconds
) {
    /** 对话消息。role: system / user / assistant。 */
    public record Message(String role, String content) {
        public static Message system(String text) {
            return new Message("system", text);
        }

        public static Message user(String text) {
            return new Message("user", text);
        }

        public static Message assistant(String text) {
            return new Message("assistant", text);
        }
    }
}
