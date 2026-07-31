package dev.bettervillagers.behavior.social;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.villager.BVillager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;

/** 跨职业村民的社交、聊天和交易展示。 */
public final class SocialEngine {

    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    private final Set<String> engaged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, List<Entity>> displays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatSession> chatSessions = new ConcurrentHashMap<>();
    private volatile boolean shutdown;

    public void tickSocial(BVillager bv) {
        if (shutdown) {
            return;
        }
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        long now = System.currentTimeMillis();
        ChatSession activeSession = chatSessions.get(bv.uuid());
        if (activeSession != null) {
            if (now >= activeSession.deadlineMillis()
                    || bv.state() == VillagerState.COMBAT || bv.state() == VillagerState.FLEEING) {
                finishSession(activeSession);
            } else if (bv.state() != VillagerState.SOCIALIZING) {
                bv.state(VillagerState.SOCIALIZING);
            }
            return;
        }
        if (engaged.contains(bv.uuid())
                && bv.state() != VillagerState.SOCIALIZING
                && now - bv.lastSocialTime() > chatDurationMillis() * 2) {
            engaged.remove(bv.uuid());
            clearDisplays(bv.uuid());
        }
        if (bv.state() == VillagerState.SOCIALIZING) {
            if (now - bv.lastSocialTime() >= chatDurationMillis()) {
                endChat(bv);
            }
            return;
        }
        VillagerState state = bv.state();
        if (state == VillagerState.COMBAT || state == VillagerState.FLEEING) {
            return;
        }
        long worldTime = self.getWorld().getTime();
        if (worldTime >= NIGHT_START_TICKS && worldTime <= NIGHT_END_TICKS) {
            return;
        }
        if (now - bv.lastSocialTime() < chatCooldownMillis() || engaged.contains(bv.uuid())) {
            return;
        }
        BVillager partner = findPartner(bv, self);
        if (partner == null) {
            return;
        }
        LivingEntity partnerEntity = partner.entity();
        if (partnerEntity == null || partnerEntity.isDead() || !tryEngage(bv.uuid(), partner.uuid())) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble() < tradeChance()) {
            startTrade(bv, partner, self, partnerEntity, now);
        } else {
            startChat(bv, partner, self, partnerEntity, now);
        }
    }

    private BVillager findPartner(BVillager bv, LivingEntity self) {
        double range = BV.config().raw().getDouble("social.encounter-range");
        double rangeSquared = range * range;
        for (Entity nearby : self.getNearbyEntities(range, BV.config().raw().getDouble("social.encounter-height"), range)) {
            if (!(nearby instanceof Villager villager) || villager.isDead() || BV.villagers() == null) {
                continue;
            }
            BVillager other = BV.villagers().get(villager.getUniqueId().toString()).orElse(null);
            if (other == null || other.uuid().equals(bv.uuid()) || other.profession() == bv.profession()) {
                continue;
            }
            VillagerState otherState = other.state();
            if (otherState == VillagerState.COMBAT || otherState == VillagerState.FLEEING
                    || otherState == VillagerState.SOCIALIZING
                    || System.currentTimeMillis() - other.lastSocialTime() < chatCooldownMillis()
                    || engaged.contains(other.uuid())) {
                continue;
            }
            if (self.getLocation().distanceSquared(villager.getLocation()) <= rangeSquared) {
                return other;
            }
        }
        return null;
    }

    private boolean tryEngage(String first, String second) {
        if (!engaged.add(first)) {
            return false;
        }
        if (!engaged.add(second)) {
            engaged.remove(first);
            return false;
        }
        return true;
    }

    private void startChat(BVillager first, BVillager second, LivingEntity firstEntity, LivingEntity secondEntity, long now) {
        ChatSession session = new ChatSession(first, second, now + chatDurationMillis());
        if (chatSessions.putIfAbsent(first.uuid(), session) != null) {
            releasePair(first.uuid(), second.uuid());
            return;
        }
        if (chatSessions.putIfAbsent(second.uuid(), session) != null) {
            chatSessions.remove(first.uuid(), session);
            releasePair(first.uuid(), second.uuid());
            return;
        }
        first.state(VillagerState.SOCIALIZING);
        first.lastSocialTime(now);
        firstEntity.setRotation(yawTowards(firstEntity.getLocation(), secondEntity.getLocation()), 0f);
        BV.scheduler().runForEntity(secondEntity, () -> {
            if (shutdown || System.currentTimeMillis() >= session.deadlineMillis()
                    || !firstEntity.isValid() || !secondEntity.isValid()) {
                finishSession(session);
                return;
            }
            second.state(VillagerState.SOCIALIZING);
            second.lastSocialTime(now);
            secondEntity.setRotation(yawTowards(secondEntity.getLocation(), firstEntity.getLocation()), 0f);
            requestChatContent(session, first, second);
        }, () -> finishSession(session));
    }

    private void startTrade(BVillager first, BVillager second, LivingEntity firstEntity, LivingEntity secondEntity, long now) {
        first.state(VillagerState.TRADING);
        first.lastSocialTime(now);
        if (!(firstEntity instanceof Villager traderFirst) || !(secondEntity instanceof Villager traderSecond)
                || BV.trade() == null || !traderFirst.isValid() || !traderSecond.isValid()) {
            endTrade(first);
            return;
        }
        BV.scheduler().runForEntity(secondEntity, () -> {
            if (!traderSecond.isValid()) {
                endTrade(second);
                return;
            }
            List<MerchantRecipe> recipes = traderSecond.getRecipes();
            if (recipes.isEmpty()) {
                endTrade(second);
                BV.scheduler().runForEntity(firstEntity, () -> endTrade(first), () -> release(first.uuid()));
                return;
            }
            MerchantRecipe recipe = recipes.get(ThreadLocalRandom.current().nextInt(recipes.size()));
            if (recipe.getUses() >= recipe.getMaxUses()) {
                endTrade(second);
                BV.scheduler().runForEntity(firstEntity, () -> endTrade(first), () -> release(first.uuid()));
                return;
            }
            List<ItemStack> ingredients = recipe.getIngredients().stream().map(ItemStack::clone).toList();
            ItemStack result = recipe.getResult().clone();
            second.state(VillagerState.TRADING);
            second.lastSocialTime(now);
            BV.scheduler().runForEntity(firstEntity, () -> {
                if (!traderFirst.isValid() || !traderSecond.isValid() || !canPay(traderFirst.getInventory(), ingredients)) {
                    endTrade(first);
                    BV.scheduler().runForEntity(secondEntity, () -> endTrade(second), () -> release(second.uuid()));
                    return;
                }
                for (ItemStack ingredient : ingredients) {
                    remove(traderFirst.getInventory(), ingredient);
                }
                traderFirst.getInventory().addItem(result);
                traderFirst.getWorld().spawnParticle(org.bukkit.Particle.HEART, traderFirst.getLocation().add(0, 2, 0), 2);
                showTradeDisplay(first.uuid(), traderFirst, List.of(result));
                BV.scheduler().runForEntity(secondEntity, () -> {
                    if (!traderSecond.isValid()) {
                        return;
                    }
                    for (ItemStack ingredient : ingredients) {
                        traderSecond.getInventory().addItem(ingredient.clone());
                    }
                    recipe.setUses(recipe.getUses() + 1);
                    recipe.setDemand(recipe.getDemand() + 1);
                    traderSecond.setRecipes(recipes);
                    traderSecond.getWorld().spawnParticle(org.bukkit.Particle.HEART, traderSecond.getLocation().add(0, 2, 0), 2);
                    showTradeDisplay(second.uuid(), traderSecond, ingredients);
                    endTrade(second);
                }, () -> release(second.uuid()));
                endTrade(first);
            }, () -> release(first.uuid()));
        }, () -> release(second.uuid()));
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
            if (stack != null && stack.isSimilar(wanted)) {
                total += stack.getAmount();
            }
        }
        return total >= wanted.getAmount();
    }

    private void remove(Inventory inventory, ItemStack wanted) {
        int remaining = wanted.getAmount();
        for (ItemStack stack : inventory.getContents()) {
            if (remaining == 0) {
                return;
            }
            if (stack != null && stack.isSimilar(wanted)) {
                int used = Math.min(remaining, stack.getAmount());
                stack.setAmount(stack.getAmount() - used);
                remaining -= used;
            }
        }
    }

    private void endTrade(BVillager bv) {
        bv.state(VillagerState.IDLE);
        engaged.remove(bv.uuid());
        if (BV.villagers() != null) {
            BV.villagers().updateDisplayName(bv);
        }
    }

    private void endChat(BVillager bv) {
        if (bv.state() == VillagerState.SOCIALIZING) {
            bv.state(VillagerState.IDLE);
        }
        engaged.remove(bv.uuid());
        clearDisplays(bv.uuid());
        if (BV.villagers() != null) {
            BV.villagers().updateDisplayName(bv);
        }
    }

    /** 释放占位和该村民拥有的展示实体（区块卸载、死亡时调用）。 */
    public void release(String uuid) {
        ChatSession session = chatSessions.get(uuid);
        if (session != null) {
            finishSession(session);
        }
        engaged.remove(uuid);
        clearDisplays(uuid);
    }

    private float yawTowards(Location from, Location to) {
        return (float) Math.toDegrees(Math.atan2(-(to.getX() - from.getX()), to.getZ() - from.getZ()));
    }

    private void requestChatContent(ChatSession session, BVillager speaker, BVillager partner) {
        if (!isSessionActive(session)) {
            finishSession(session);
            return;
        }
        String system = BV.messages().raw("ai-prompt.social-chat-system");
        String user = BV.messages().raw("ai-prompt.social-chat-user")
                .replace("{name}", speaker.name())
                .replace("{profession}", speaker.profession().id())
                .replace("{partner}", partner.name())
                .replace("{partner-profession}", partner.profession().id());
        AIContext context = new AIContext(speaker.uuid(), speaker.name(), speaker.profession().id(), "social", system, user);
        BV.ai().decide(context)
                .handle((result, error) -> error == null ? result : null)
                .thenAccept(result -> applyChatLine(session, speaker, partner, result));
    }

    private void applyChatLine(ChatSession session, BVillager speaker, BVillager partner, AIResult result) {
        if (!isSessionActive(session)) {
            finishSession(session);
            return;
        }
        String line = ensurePartnerName(extractLine(result, partner), partner.name());
        LivingEntity speakerEntity = speaker.entity();
        if (speakerEntity == null || !speakerEntity.isValid()) {
            finishSession(session);
            return;
        }
        BV.scheduler().runForEntity(speakerEntity, () -> {
            if (!isSessionActive(session)) {
                finishSession(session);
                return;
            }
            showChatDisplay(speaker.uuid(), speakerEntity, line);
            BV.scheduler().runAtRegionDelayed(speakerEntity.getLocation(), () -> {
                if (isSessionActive(session)) {
                    requestChatContent(session, partner, speaker);
                } else {
                    finishSession(session);
                }
            }, replyIntervalTicks());
        }, () -> finishSession(session));
    }

    private boolean isSessionActive(ChatSession session) {
        if (shutdown || System.currentTimeMillis() >= session.deadlineMillis()
                || chatSessions.get(session.first().uuid()) != session
                || chatSessions.get(session.second().uuid()) != session
                || isEmergencyState(session.first()) || isEmergencyState(session.second())) {
            return false;
        }
        LivingEntity first = session.first().entity();
        LivingEntity second = session.second().entity();
        return first != null && first.isValid() && !first.isDead()
                && second != null && second.isValid() && !second.isDead();
    }

    private boolean isEmergencyState(BVillager villager) {
        return villager.state() == VillagerState.COMBAT || villager.state() == VillagerState.FLEEING;
    }

    private void finishSession(ChatSession session) {
        if (!chatSessions.remove(session.first().uuid(), session)) {
            return;
        }
        chatSessions.remove(session.second().uuid(), session);
        finishVillager(session.first());
        finishVillager(session.second());
    }

    private void finishVillager(BVillager villager) {
        LivingEntity entity = villager.entity();
        if (shutdown || entity == null || !entity.isValid()) {
            endChat(villager);
            return;
        }
        BV.scheduler().runForEntity(entity, () -> endChat(villager), () -> {
            engaged.remove(villager.uuid());
            clearDisplays(villager.uuid());
        });
    }

    private void releasePair(String first, String second) {
        engaged.remove(first);
        engaged.remove(second);
    }

    private String extractLine(AIResult result, BVillager partner) {
        if (result != null && result.isUsable()) {
            return sanitize(result.text());
        }
        return fallbackLine(partner);
    }

    private String ensurePartnerName(String line, String partnerName) {
        return line.contains(partnerName) ? line : partnerName + line;
    }

    private String fallbackLine(BVillager partner) {
        List<String> lines = BV.messages().rawList("social.chat-lines");
        String template = lines.isEmpty()
                ? BV.messages().raw("social.chat-default")
                : lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        String profession = partner == null ? "" : BV.messages().raw("professions." + partner.profession().id());
        String name = partner == null ? "" : partner.name();
        return template.replace("{profession}", profession).replace("{name}", name);
    }

    private String sanitize(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("[\"'`\\n\\r]", "");
        int maxLength = BV.config().raw().getInt("social.chat-max-length");
        if (value.length() > maxLength) {
            value = value.substring(0, maxLength);
        }
        return value.isBlank() ? BV.messages().raw("social.chat-default") : value;
    }

    private void showChatDisplay(String ownerUuid, LivingEntity owner, String text) {
        showChatDisplay(ownerUuid, owner, text, chatBubbleDurationTicks());
    }

    private void showChatDisplay(String ownerUuid, LivingEntity owner, String text, long durationTicks) {
        clearDisplays(ownerUuid);
        if (bubbleDisabled() || owner.isDead()) {
            return;
        }
        Component component = MessageService.deserialize(BV.messages().raw("social.chat-bubble-format").replace("{text}", text));
        TextDisplay display = owner.getWorld().spawn(owner.getLocation(), TextDisplay.class, spawned -> {
            spawned.text(component);
            spawned.setBillboard(Display.Billboard.CENTER);
            spawned.setSeeThrough(true);
            spawned.setShadowed(true);
            spawned.setDefaultBackground(false);
            var transformation = spawned.getTransformation();
            transformation.getTranslation().set(0F, (float) (owner.getHeight() + bubbleHeight()), 0F);
            spawned.setTransformation(transformation);
        });
        owner.addPassenger(display);
        trackDisplay(ownerUuid, display);
        scheduleClear(ownerUuid, owner, List.of(display), durationTicks);
    }

    private void showTradeDisplay(String ownerUuid, LivingEntity owner, List<ItemStack> received) {
        clearDisplays(ownerUuid);
        if (bubbleDisabled() || owner.isDead()) {
            return;
        }
        List<ItemStack> items = received.stream()
                .filter(item -> item != null && !item.getType().isAir())
                .map(ItemStack::clone)
                .toList();
        if (items.isEmpty()) {
            showChatDisplay(ownerUuid, owner, BV.messages().raw("social.trade-fallback"), tradeBubbleDurationTicks());
            return;
        }
        for (int index = 0; index < items.size(); index++) {
            ItemStack item = items.get(index);
            double offset = index * BV.config().raw().getDouble("social.bubble.item-spacing");
            ItemDisplay display = owner.getWorld().spawn(bubbleLocation(owner, offset), ItemDisplay.class, spawned -> {
                spawned.setItemStack(item);
                spawned.setBillboard(Display.Billboard.CENTER);
            });
            trackDisplay(ownerUuid, display);
        }
        scheduleClear(ownerUuid, owner, List.copyOf(displays.getOrDefault(ownerUuid, List.of())), tradeBubbleDurationTicks());
    }

    private Location bubbleLocation(LivingEntity owner, double extraHeight) {
        return owner.getLocation().add(0D, owner.getHeight() + bubbleHeight() + extraHeight, 0D);
    }

    private void trackDisplay(String ownerUuid, Entity display) {
        displays.computeIfAbsent(ownerUuid, ignored -> new CopyOnWriteArrayList<>()).add(display);
    }

    private void scheduleClear(String ownerUuid, LivingEntity owner, List<Entity> expected, long durationTicks) {
        BV.scheduler().runAtRegionDelayed(owner.getLocation(), () -> clearDisplays(ownerUuid, expected), durationTicks);
    }

    private void clearDisplays(String ownerUuid, List<Entity> expected) {
        List<Entity> current = displays.get(ownerUuid);
        if (!Objects.equals(current, expected)) {
            return;
        }
        if (displays.remove(ownerUuid, current)) {
            removeDisplays(current);
        }
    }

    private void clearDisplays(String ownerUuid) {
        removeDisplays(displays.remove(ownerUuid));
    }

    private void removeDisplays(List<Entity> ownerDisplays) {
        if (ownerDisplays == null || ownerDisplays.isEmpty()) {
            return;
        }
        for (Entity display : ownerDisplays) {
            Runnable remove = () -> {
                Entity vehicle = display.getVehicle();
                if (vehicle != null) {
                    vehicle.removePassenger(display);
                }
                if (display.isValid()) {
                    display.remove();
                }
            };
            BV.scheduler().runForEntity(display, remove, null);
        }
    }

    public void shutdown() {
        shutdown = true;
        for (ChatSession session : Set.copyOf(chatSessions.values())) {
            finishSession(session);
        }
        chatSessions.clear();
        engaged.clear();
        for (String ownerUuid : Set.copyOf(displays.keySet())) {
            clearDisplays(ownerUuid);
        }
    }

    private boolean bubbleDisabled() {
        return !BV.config().raw().getBoolean("social.bubble.enabled", true);
    }

    private double bubbleHeight() {
        return BV.config().raw().getDouble("social.bubble.height", 0.6D);
    }

    private long chatDurationMillis() {
        long ticks = BV.config().raw().getLong("social.bubble.chat-duration-ticks", -1L);
        if (ticks > 0L) {
            return ticks * 50L;
        }
        return BV.config().raw().getLong("social.chat-duration-seconds", 5L) * 1000L;
    }

    private long replyIntervalTicks() {
        return Math.max(1L, BV.config().raw().getLong("social.bubble.reply-interval-ticks", 40L));
    }

    private long chatBubbleDurationTicks() {
        return Math.max(1L, BV.config().raw().getLong("social.bubble.chat-bubble-duration-ticks", 35L));
    }

    private long chatCooldownMillis() {
        return BV.config().raw().getLong("social.chat-cooldown-seconds", 30L) * 1000L;
    }

    private long tradeBubbleDurationTicks() {
        return BV.config().raw().getLong("social.bubble.trade-duration-ticks", 60L);
    }

    private double tradeChance() {
        return BV.config().raw().getDouble("social.trade-chance", 0.35D);
    }

    private record ChatSession(BVillager first, BVillager second, long deadlineMillis) {
    }
}
