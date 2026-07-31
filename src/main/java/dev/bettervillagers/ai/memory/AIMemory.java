package dev.bettervillagers.ai.memory;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.bettervillagers.ai.AIRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * 村民短期 AI 记忆（规范 3.1 / 4.2：最近事件分层存储，长期记忆定期摘要落库）。
 * <p>
 * 维护对话历史，发送前裁剪为最近 N 条，控制 token；支持序列化为 JSON 持久化到 {@code ai_memory}。
 */
public final class AIMemory {

    private final int maxHistory;
    private final List<AIRequest.Message> messages = new java.util.concurrent.CopyOnWriteArrayList<>();

    public AIMemory(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    /** 追加一轮对话（用户问 + 助手答）。 */
    public void append(String user, String assistant) {
        if (user != null && !user.isBlank()) {
            messages.add(AIRequest.Message.user(user));
        }
        if (assistant != null && !assistant.isBlank()) {
            messages.add(AIRequest.Message.assistant(assistant));
        }
        prune(maxHistory);
    }

    /**
     * 裁剪：保留最近 keepRecent 条对话消息，system 关键事实始终保留。
     * <p>
     * CopyOnWriteArrayList 的单次写操作各自原子，但 clear + addAll 多步之间不保证原子性，
     * 期间 {@link #messages()} 可能读到空列表。给两个方法加 synchronized 保证裁剪过程的可见性一致。
     */
    public synchronized void prune(int keepRecent) {
        List<AIRequest.Message> facts = new ArrayList<>();
        List<AIRequest.Message> dialog = new ArrayList<>();
        for (AIRequest.Message m : messages) {
            if ("system".equals(m.role())) {
                facts.add(m);
            } else {
                dialog.add(m);
            }
        }
        if (dialog.size() > keepRecent) {
            dialog = new ArrayList<>(dialog.subList(dialog.size() - keepRecent, dialog.size()));
        }
        messages.clear();
        messages.addAll(facts);
        messages.addAll(dialog);
    }

    public synchronized List<AIRequest.Message> messages() {
        return List.copyOf(messages);
    }

    /** 序列化为 JSON 文本（规范 8.2 ai_memory 字段）。 */
    public String toJson() {
        JsonArray arr = new JsonArray();
        for (AIRequest.Message m : messages) {
            JsonArray pair = new JsonArray();
            pair.add(m.role());
            pair.add(m.content());
            arr.add(pair);
        }
        return arr.toString();
    }

    public static AIMemory fromJson(String json, int maxHistory) {
        AIMemory mem = new AIMemory(maxHistory);
        if (json == null || json.isBlank()) {
            return mem;
        }
        try {
            JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
            for (var e : arr) {
                JsonArray pair = e.getAsJsonArray();
                // 越界防护：损坏/不完整条目直接跳过，避免 get(0/1) 抛异常
                if (pair == null || pair.size() < 2) {
                    continue;
                }
                mem.messages.add(new AIRequest.Message(pair.get(0).getAsString(), pair.get(1).getAsString()));
            }
        } catch (Exception e) {
            // 容错：损坏的记忆记录到日志（含原因），便于排查数据损坏（规范 3.3：禁止静默吞异常）
            String template = dev.bettervillagers.BV.messages() != null
                    ? dev.bettervillagers.BV.messages().raw("log.ai-memory-parse-fail")
                    : "log.ai-memory-parse-fail";
            dev.bettervillagers.BV.plugin().getLogger().warning(
                    template.replace("{error}", String.valueOf(e.getMessage())));
        }
        return mem;
    }
}
