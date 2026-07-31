package dev.bettervillagers.ai.memory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 村民记忆存储（规范 3.1 / 4.2）。
 * <p>
 * 运行期以 {@link ConcurrentHashMap} 承载每个村民的 {@link AIMemory}；
 * 定期（WAL 保存）将 JSON 落库到 {@code villagers.ai_memory}。
 */
public final class MemoryStore {

    private final int maxHistory;
    private final ConcurrentHashMap<String, AIMemory> store = new ConcurrentHashMap<>();

    public MemoryStore(int maxHistory) {
        this.maxHistory = maxHistory;
    }

    public AIMemory get(String uuid) {
        return store.computeIfAbsent(uuid, ignored -> new AIMemory(maxHistory));
    }

    /** 以已持久化的 JSON 恢复记忆（村民加载时调用）。 */
    public void load(String uuid, String json) {
        store.put(uuid, AIMemory.fromJson(json, maxHistory));
    }

    public void remove(String uuid) {
        store.remove(uuid);
    }

    /** 导出指定村民记忆 JSON（保存时调用）。 */
    public String export(String uuid) {
        AIMemory m = store.get(uuid);
        return m == null ? "[]" : m.toJson();
    }

}
