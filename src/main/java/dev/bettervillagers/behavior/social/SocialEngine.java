package dev.bettervillagers.behavior.social;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.villager.BVillager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 跨职业社交引擎（规范 3.3：村民 AI 跨职业相遇交互）。
 * <p>
 * 当任意两个<b>不同职业</b>的村民 AI 在移动路径中发生交汇（距离 ≤ 相遇半径）时，
 * 自动触发驻足攀谈的社交行为：
 * <ul>
 *   <li>双方停止移动，转为 {@link VillagerState#SOCIALIZING} 状态并面朝对方；</li>
 *   <li>异步调用预设 AI 模型生成简短攀谈内容（AI 不可用时回退到 i18n 模板短语）；</li>
 *   <li>攀谈期间在村民头顶显示标签（标签为聊天内容），交互时长贴合原版村民社交基础规则；</li>
 *   <li>交互结束后恢复头顶名称显示，进入攀谈冷却。</li>
 * </ul>
 * <p>
 * 触发条件遵循原版村民社交基础规则：仅在白天/非威胁环境下攀谈，战斗/逃跑中不社交。
 * 全部 AI 请求与名称写操作均异步/区域线程安全，不阻塞主线程 tick。
 */
public final class SocialEngine {

    /** 相遇判定半径（格）。 */
    private static final double ENCOUNTER_RANGE = 3.5;
    private static final double ENCOUNTER_RANGE_SQ = ENCOUNTER_RANGE * ENCOUNTER_RANGE;
    /** 攀谈持续时长（毫秒，贴合原版村民 gossip 约 5 秒）。 */
    private static final long CHAT_DURATION_MS = 5000L;
    /** 攀谈冷却（毫秒，避免同一对村民频繁攀谈）。 */
    private static final long CHAT_COOLDOWN_MS = 30_000L;
    /** 攀谈内容最大长度。 */
    private static final int MAX_CHAT_LEN = 32;
    /** Minecraft 夜间起始 tick（13000）与结束 tick（23000），与 StrategicAI 共用语义。 */
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    /** 正在攀谈中的村民 UUID 集合（原子占位，防止并发重复触发）。 */
    private final Set<String> engaged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Integer> friendship = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> breedingCooldown = new ConcurrentHashMap<>();
    private static final int BREEDING_THRESHOLD = 100;
    private static final long BREEDING_COOLDOWN_MS = 120_000L;

    /**
     * 执行一次社交检测（由社交 tick 经实体区域线程调用）。
     */
    public void tickSocial(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        long now = System.currentTimeMillis();

        // D7 修复：超时兜底，防止异常路径导致 UUID 永久残留于 engaged（被卡住的村民此后无法社交）。
        if (engaged.contains(bv.uuid())
                && bv.state() != VillagerState.SOCIALIZING
                && now - bv.lastSocialTime() > CHAT_DURATION_MS * 2) {
            engaged.remove(bv.uuid());
        }

        // 正在攀谈：检查是否到时结束
        if (bv.state() == VillagerState.SOCIALIZING) {
            if (now - bv.lastSocialTime() >= CHAT_DURATION_MS) {
                endChat(bv);
            }
            return;
        }
        // 战斗/逃跑中不社交
        VillagerState st = bv.state();
        if (st == VillagerState.COMBAT || st == VillagerState.FLEEING) {
            return;
        }
        // 夜间不攀谈（贴合原版村民夜间不社交）
        long worldTime = self.getWorld().getTime();
        if (worldTime >= NIGHT_START_TICKS && worldTime <= NIGHT_END_TICKS) {
            return;
        }
        // 攀谈冷却
        if (now - bv.lastSocialTime() < CHAT_COOLDOWN_MS) {
            return;
        }
        // 已被占位（正在被其他村民触发攀谈）
        if (engaged.contains(bv.uuid())) {
            return;
        }
        // 扫描附近不同职业的村民
        BVillager partner = findPartner(bv, self);
        if (partner == null) {
            return;
        }
        LivingEntity partnerEnt = partner.entity();
        if (partnerEnt == null || partnerEnt.isDead()) {
            return;
        }
        if (!tryEngage(bv.uuid(), partner.uuid())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < 0.35) {
            startTrade(bv, partner, self, partnerEnt, now);
        } else {
            startChat(bv, partner, self, partnerEnt, now);
        }
    }

    /** 寻找附近可攀谈的不同职业村民。 */
    private BVillager findPartner(BVillager bv, LivingEntity self) {
        for (org.bukkit.entity.Entity nearby : self.getNearbyEntities(ENCOUNTER_RANGE, 2, ENCOUNTER_RANGE)) {
            if (!(nearby instanceof Villager v) || v.isDead()) {
                continue;
            }
            if (BV.villagers() == null) {
                continue;
            }
            BVillager other = BV.villagers().get(v.getUniqueId().toString()).orElse(null);
            if (other == null || other.uuid().equals(bv.uuid())) {
                continue;
            }
            // 仅跨职业攀谈（同职业不触发专属社交标签）
            if (other.profession() == bv.profession()) {
                continue;
            }
            // 对方须可社交
            VillagerState ost = other.state();
            if (ost == VillagerState.COMBAT || ost == VillagerState.FLEEING || ost == VillagerState.SOCIALIZING) {
                continue;
            }
            if (System.currentTimeMillis() - other.lastSocialTime() < CHAT_COOLDOWN_MS) {
                continue;
            }
            if (engaged.contains(other.uuid())) {
                continue;
            }
            double d = self.getLocation().distanceSquared(v.getLocation());
            if (d <= ENCOUNTER_RANGE_SQ) {
                return other;
            }
        }
        return null;
    }

    /** 原子占位两个村民，确保不并发重复触发。 */
    private boolean tryEngage(String a, String b) {
        if (!engaged.add(a)) {
            return false;
        }
        if (!engaged.add(b)) {
            engaged.remove(a);
            return false;
        }
        return true;
    }

    /** 启动攀谈：双方驻足、面朝对方、生成内容并显示标签。 */
    private void startChat(BVillager a, BVillager b, LivingEntity aEnt, LivingEntity bEnt, long now) {
        a.state(VillagerState.SOCIALIZING);
        a.lastSocialTime(now);
        // A 在自身区域线程内面朝 B（当前位置快照即可）
        Location bLoc = bEnt.getLocation();
        aEnt.setRotation(yawTowards(aEnt.getLocation(), bLoc), 0f);
        // D2 修复：B 可能位于另一区域线程，其状态写入与朝向操控须 dispatch 到 B 自身区域线程。
        BV.scheduler().runForEntity(bEnt, () -> {
            b.state(VillagerState.SOCIALIZING);
            b.lastSocialTime(now);
            Location aLoc = aEnt.getLocation();
            bEnt.setRotation(yawTowards(bEnt.getLocation(), aLoc), 0f);
        }, null);
        // 异步生成攀谈内容（AI 不可用时回退 i18n 模板）
        requestChatContent(a, b);
    }

    private void startTrade(BVillager a, BVillager b, LivingEntity aEnt, LivingEntity bEnt, long now) {
        a.state(VillagerState.TRADING);
        a.lastSocialTime(now);
        if (!(aEnt instanceof Villager traderA) || !(bEnt instanceof Villager traderB)
                || BV.trade() == null || !traderA.isValid() || !traderB.isValid()) {
            endTrade(a);
            return;
        }
        BV.scheduler().runForEntity(bEnt, () -> {
            if (!traderB.isValid()) {
                endTrade(b);
                return;
            }
            List<MerchantRecipe> recipes = traderB.getRecipes();
            if (recipes.isEmpty()) {
                endTrade(b);
                BV.scheduler().runForEntity(aEnt, () -> endTrade(a), null);
                return;
            }
            MerchantRecipe recipe = recipes.get(ThreadLocalRandom.current().nextInt(recipes.size()));
            if (recipe.getUses() >= recipe.getMaxUses()) {
                endTrade(b);
                BV.scheduler().runForEntity(aEnt, () -> endTrade(a), null);
                return;
            }
            List<ItemStack> ingredients = recipe.getIngredients().stream().map(ItemStack::clone).toList();
            ItemStack result = recipe.getResult().clone();
            b.state(VillagerState.TRADING);
            b.lastSocialTime(now);
            BV.scheduler().runForEntity(aEnt, () -> {
                if (!traderA.isValid() || !traderB.isValid() || !canPay(traderA.getInventory(), ingredients)) {
                    endTrade(a);
                    BV.scheduler().runForEntity(bEnt, () -> endTrade(b), null);
                    return;
                }
                for (ItemStack ingredient : ingredients) {
                    remove(traderA.getInventory(), ingredient);
                }
                traderA.getInventory().addItem(result);
                traderA.getWorld().spawnParticle(org.bukkit.Particle.HEART, traderA.getLocation().add(0, 2, 0), 2);
                BV.scheduler().runForEntity(bEnt, () -> {
                    if (!traderB.isValid()) {
                        return;
                    }
                    for (ItemStack ingredient : ingredients) {
                        traderB.getInventory().addItem(ingredient.clone());
                    }
                    recipe.setUses(recipe.getUses() + 1);
                    recipe.setDemand(recipe.getDemand() + 1);
                    traderB.setRecipes(recipes);
                    traderB.getWorld().spawnParticle(org.bukkit.Particle.HEART, traderB.getLocation().add(0, 2, 0), 2);
                    recordTrade(a, 10);
                    recordTrade(b, 10);
                    endTrade(b);
                }, null);
                endTrade(a);
            }, null);
        }, null);
    }

    private boolean canPay(Inventory inventory, List<ItemStack> ingredients) {
        for (ItemStack ingredient : ingredients) {
            if (!contains(inventory, ingredient)) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(Inventory inventory, ItemStack wanted) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.isSimilar(wanted)) total += stack.getAmount();
        }
        return total >= wanted.getAmount();
    }

    private void remove(Inventory inventory, ItemStack wanted) {
        int remaining = wanted.getAmount();
        for (ItemStack stack : inventory.getContents()) {
            if (remaining == 0) return;
            if (stack != null && stack.isSimilar(wanted)) {
                int used = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - used);
                remaining -= used;
            }
        }
    }

    public void recordTrade(BVillager villager, int amount) {
        if (villager == null) return;
        String key = pairKey(villager.uuid(), villager.uuid());
        friendship.merge(key, Math.max(0, amount), Integer::sum);
    }

    private String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
    }

    private void endTrade(BVillager bv) {
        bv.state(VillagerState.IDLE);
        engaged.remove(bv.uuid());
        if (BV.villagers() != null) BV.villagers().updateDisplayName(bv);
    }

    /** 结束攀谈：恢复头顶名称显示与状态。 */
    private void endChat(BVillager bv) {
        bv.state(VillagerState.IDLE);
        engaged.remove(bv.uuid());
        if (BV.villagers() != null) {
            BV.villagers().updateDisplayName(bv);
        }
    }

    /** 释放占位（村民卸载时调用）。 */
    public void release(String uuid) {
        engaged.remove(uuid);
        friendship.keySet().removeIf(key -> key.startsWith(uuid + ":") || key.endsWith(":" + uuid));
        breedingCooldown.keySet().removeIf(key -> key.startsWith(uuid + ":") || key.endsWith(":" + uuid));
    }

    /** 双方面朝对方（贴合原版村民 gossip 面对面）。 */
    private float yawTowards(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // ==================== 攀谈内容生成（异步 AI + i18n 回退） ====================

    private void requestChatContent(BVillager a, BVillager b) {
        String system = BV.messages().raw("ai-prompt.social-chat-system");
        String user = BV.messages().raw("ai-prompt.social-chat-user")
                .replace("{name}", a.name())
                .replace("{profession}", a.profession().id())
                .replace("{partner}", b.name())
                .replace("{partner-profession}", b.profession().id());
        AIContext ctx = new AIContext(a.uuid(), a.name(), a.profession().id(), "social", system, user);
        BV.ai().decide(ctx)
                .thenAccept(result -> applyChatLabel(a, b, result))
                .exceptionally(ex -> {
                    applyChatLabel(a, b, null);
                    return null;
                });
    }

    private void applyChatLabel(BVillager a, BVillager b, AIResult result) {
        String line = extractLine(result);
        String ack = fallbackLine(b);
        // 在各自实体区域线程写头顶标签（customName 临时替换为攀谈内容）
        // 仅当村民仍处于攀谈状态时显示（避免 AI 延迟返回时攀谈已结束导致标签残留）
        if (a.state() == VillagerState.SOCIALIZING) {
            LivingEntity aEnt = a.entity();
            if (aEnt != null) {
                BV.scheduler().runForEntity(aEnt, () -> showLabel(aEnt, line), null);
            }
        }
        if (b.state() == VillagerState.SOCIALIZING) {
            LivingEntity bEnt = b.entity();
            if (bEnt != null) {
                BV.scheduler().runForEntity(bEnt, () -> showLabel(bEnt, ack), null);
            }
        }
    }

    /** 提取 AI 返回的攀谈内容；失败/降级时回退 i18n 模板。 */
    private String extractLine(AIResult result) {
        if (result != null && result.isUsable()) {
            return sanitize(result.text());
        }
        return fallbackLine(null);
    }

    /** 从 i18n 模板随机抽取一句攀谈内容（用户规则：禁止硬编码）。 */
    private String fallbackLine(BVillager bv) {
        List<String> lines = BV.messages().rawList("social.chat-lines");
        if (lines.isEmpty()) {
            return BV.messages().raw("social.chat-default");
        }
        String tpl = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        String prof = bv != null ? BV.messages().raw("professions." + bv.profession().id()) : "";
        return tpl.replace("{profession}", prof);
    }

    private String sanitize(String raw) {
        String s = raw.trim().replaceAll("[\"'`\n\r]", "");
        if (s.length() > MAX_CHAT_LEN) {
            s = s.substring(0, MAX_CHAT_LEN);
        }
        return s.isBlank() ? BV.messages().raw("social.chat-default") : s;
    }

    /** 在实体头顶显示攀谈标签（临时替换 customName）。 */
    private void showLabel(LivingEntity ent, String text) {
        if (ent == null || ent.isDead()) {
            return;
        }
        // 攀谈标签格式经 i18n 模板，避免硬编码颜色码（用户规则：禁止硬编码可见文本）
        Component label = MessageService.deserialize(
                BV.messages().raw("social.chat-label-format").replace("{text}", text));
        ent.customName(label);
        ent.setCustomNameVisible(true);
    }
}
