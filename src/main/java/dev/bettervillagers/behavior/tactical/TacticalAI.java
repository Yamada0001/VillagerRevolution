package dev.bettervillagers.behavior.tactical;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.behavior.threat.Threat;
import dev.bettervillagers.behavior.threat.ThreatDetector;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.profession.ProfessionData;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Locale;

/**
 * 战术层 AI（规范 3.1：秒级，周期性调用大模型处理中频决策）。
 * <p>
 * 组装压缩战场快照 → 异步请求大模型 → 解析动作关键词 → 区域线程应用。
 */
public final class TacticalAI {

    private static final String[] ACTIONS = {"ATTACK", "FLEE", "PATROL", "WORK", "REST", "TRADE"};
    /** 战术移动速度（与各引擎步行速度统一）。 */
    private static final double TACTICAL_SPEED = 0.4;

    private final ThreatDetector threatDetector;
    private final long intervalMillis;
    private volatile boolean shutdown;

    public TacticalAI(ThreatDetector threatDetector, long intervalMillis) {
        this.threatDetector = threatDetector;
        this.intervalMillis = intervalMillis;
    }

    /** 触发一次战术决策（由行为引擎 tick 调用，运行于全局区域线程）。 */
    public void decide(BVillager bv) {
        if (!isRunning() || isSocialState(bv)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - bv.lastTactical() < intervalMillis) {
            return;
        }
        LivingEntity self = bv.entity();
        if (self == null) {
            return;
        }
        bv.lastTactical(now);

        // 威胁快照（区域线程内安全读取）
        List<Threat> threats = threatDetector.scan(self, bv.villageId());
        ProfessionData pd = bv.professionData();
        String system = buildSystemPrompt(bv.profession(), pd);
        String user = buildUserPrompt(bv, self.getLocation(), threats);

        AIContext ctx = new AIContext(bv.uuid(), bv.name(), bv.profession().id(), "tactical", system, user);
        BV.ai().decide(ctx)
                .thenAccept(result -> {
                    if (isRunning()) {
                        applyResult(bv, result);
                    }
                })
                .exceptionally(ex -> {
                    if (isRunning()) {
                        BV.plugin().getLogger().warning(
                                BV.messages().raw("log.tactical-tick-error")
                                        .replace("{uuid}", bv.uuid()).replace("{error}", String.valueOf(ex)));
                    }
                    return null;
                });
    }

    private String buildSystemPrompt(Profession prof, ProfessionData pd) {
        double bravery = pd != null ? pd.personality().bravery() : 0.3;
        return BV.messages().raw("ai-prompt.tactical-system")
                .replace("{profession}", prof.id())
                .replace("{bravery}", String.format("%.2f", bravery));
    }

    private String buildUserPrompt(BVillager bv, Location loc, List<Threat> threats) {
        StringBuilder threatSummary = new StringBuilder();
        for (Threat t : threats) {
            threatSummary.append("[").append(t.type()).append(" ")
                    .append(BV.messages().raw("ai-prompt.tactical-distance"))
                    .append((int) Math.sqrt(t.distance())).append("] ");
        }
        String threatsText = threats.isEmpty()
                ? BV.messages().raw("ai-prompt.tactical-no-threat")
                : BV.messages().raw("ai-prompt.tactical-has-threat") + threatSummary;
        String locText = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        return BV.messages().raw("ai-prompt.tactical-user")
                .replace("{name}", bv.name())
                .replace("{profession}", bv.profession().id())
                .replace("{state}", bv.state().name())
                .replace("{loc}", locText)
                .replace("{threats}", threatsText);
    }

    private void applyResult(BVillager bv, AIResult result) {
        if (!isRunning() || isSocialState(bv)) {
            return;
        }
        // 修复问题2：AI 降级/不可用时默认执行 WORK（巡逻锚点），而非什么都不做卡住
        String action;
        if (result == null || !result.isUsable()) {
            action = "WORK";
        } else {
            action = parseAction(result.text());
        }
        LivingEntity entity = bv.entity();
        if (entity != null && isRunning() && !isSocialState(bv)) {
            BV.scheduler().runForEntity(entity, () -> {
                if (isRunning() && !isSocialState(bv)) {
                    doAction(bv, action);
                }
            }, null);
        }
    }

    private boolean isSocialState(BVillager bv) {
        return bv.state() == VillagerState.SOCIALIZING || bv.state() == VillagerState.TRADING;
    }

    private boolean isRunning() {
        return !shutdown && BV.plugin() != null && BV.plugin().isEnabled()
                && BV.scheduler() != null && BV.messages() != null
                && BV.config().feature("ai-behavior");
    }

    public void shutdown() {
        shutdown = true;
    }

    private String parseAction(String text) {
        String upper = text == null ? "" : text.strip().toUpperCase(Locale.ROOT);
        String first = upper.split("[^A-Z]+", 2)[0];
        for (String action : ACTIONS) {
            if (action.equals(first)) {
                return action;
            }
        }
        return "WORK";
    }

    private void doAction(BVillager bv, String action) {
        if (!isRunning() || isSocialState(bv)) {
            return;
        }
        LivingEntity self = bv.entity();
        if (self == null) {
            return;
        }
        switch (action) {
            case "ATTACK" -> {
                if (!dev.bettervillagers.behavior.reflex.ReflexEngine.isCombatant(bv.profession())) {
                    fleeFromNearestThreat(bv, self);
                    return;
                }
                // 修复问题5：战术层仅设置战斗状态并接近敌人，不直接造成伤害。
                // 实际攻击伤害由反射层 combatTick（每0.5s，1s冷却）负责，确保一下一下攻击。
                bv.state(VillagerState.COMBAT);
                List<Threat> threats = threatDetector.scan(self, bv.villageId());
                if (!threats.isEmpty()) {
                    org.bukkit.entity.Entity src = threats.getFirst().source();
                    if (src instanceof LivingEntity enemy && !enemy.isDead()) {
                        dev.bettervillagers.behavior.MovementHelper.moveToward(self, enemy.getLocation(), TACTICAL_SPEED);
                    }
                }
            }
            case "FLEE" -> fleeFromNearestThreat(bv, self);
            case "PATROL", "WORK" -> {
                // 修复绕圈：使用固定巡逻锚点（BVillager.patrolAnchor），不再相对当前位置漂移。
                bv.state(VillagerState.WORKING);
                Location anchor = bv.patrolAnchor();
                if (anchor != null) {
                    dev.bettervillagers.behavior.MovementHelper.patrolTo(self, anchor, TACTICAL_SPEED);
                }
            }
            case "REST" -> bv.state(VillagerState.RESTING);
            // 交易只能由 SocialEngine 创建会话；战术层不直接制造无主 TRADING 状态。
            case "TRADE" -> bv.state(VillagerState.IDLE);
        }
    }

    private void fleeFromNearestThreat(BVillager bv, LivingEntity self) {
        LivingEntity enemy = threatDetector.nearestEnemy(self, bv.villageId());
        if (enemy == null || enemy.getWorld() != self.getWorld()) {
            bv.state(VillagerState.IDLE);
            return;
        }
        bv.state(VillagerState.FLEEING);
        dev.bettervillagers.behavior.MovementHelper.flee(self, enemy.getLocation(), TACTICAL_SPEED);
    }
}
