package dev.bettervillagers.village;

import dev.bettervillagers.BV;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 村庄外交系统（规范 3.4）。
 * <p>
 * 由各国王决定与其他村庄的外交关系：
 * <ul>
 *   <li>{@link Relation#ALLY}（同盟）：互帮互助，战斗职业互相支援</li>
 *   <li>{@link Relation#NEUTRAL}（中立）：互不干涉</li>
 *   <li>{@link Relation#ENEMY}（敌对）：互相攻打，村民互相攻击</li>
 * </ul>
 * 关系存储为双向：villageA→villageB 与 villageB→villageA 一致。
 */
public final class DiplomacyManager {

    public enum Relation { ALLY, NEUTRAL, ENEMY }

    /** 关系缓存键：minId:maxId（保证双向一致） */
    private final Map<String, Relation> relations = new ConcurrentHashMap<>();

    /** 设置两个村庄之间的关系（双向）。 */
    public void setRelation(int villageA, int villageB, Relation relation) {
        relations.put(key(villageA, villageB), relation);
    }

    /** 查询两个村庄之间的关系，默认中立。 */
    public Relation getRelation(int villageA, int villageB) {
        if (villageA == villageB) {
            return Relation.ALLY; // 同村庄
        }
        return relations.getOrDefault(key(villageA, villageB), Relation.NEUTRAL);
    }

    /** 两个村庄是否为敌对关系。 */
    public boolean areEnemies(int villageA, int villageB) {
        return getRelation(villageA, villageB) == Relation.ENEMY;
    }

    /** 两个村庄是否为同盟关系。 */
    public boolean areAllies(int villageA, int villageB) {
        return getRelation(villageA, villageB) == Relation.ALLY;
    }

    /** 生成双向一致的缓存键（小 id 在前）。 */
    private String key(int a, int b) {
        return Math.min(a, b) + ":" + Math.max(a, b);
    }

    /**
     * 清理涉及指定村庄的全部外交关系（村庄合并/删除时调用，规范 4.x：避免 stale 条目残留）。
     */
    public void removeVillage(int villageId) {
        relations.entrySet().removeIf(e -> {
            String[] parts = e.getKey().split(":");
            return parts.length == 2
                    && (parts[0].equals(String.valueOf(villageId)) || parts[1].equals(String.valueOf(villageId)));
        });
    }

    /**
     * 国王根据 AI 决策更新外交关系。
     * <p>
     * AI 返回关键词：DECLARE_WAR（宣战）、DECLARE_ALLIANCE（同盟）、MAKE_PEACE（和平）。
     */
    public void applyDiplomacyDecision(int myVillage, int targetVillage, String decision) {
        if (decision == null) return;
        String upper = decision.toUpperCase();
        Relation newRel = switch (upper) {
            case String s when s.contains("WAR") || s.contains("ENEMY") -> Relation.ENEMY;
            case String s when s.contains("ALLIANCE") || s.contains("ALLY") -> Relation.ALLY;
            case String s when s.contains("PEACE") || s.contains("NEUTRAL") -> Relation.NEUTRAL;
            default -> null;
        };
        if (newRel != null) {
            setRelation(myVillage, targetVillage, newRel);
            String relName = BV.messages().raw("diplomacy." + newRel.name().toLowerCase());
            BV.messages().broadcast("diplomacy-changed",
                    "rel", relName);
        }
    }
}
