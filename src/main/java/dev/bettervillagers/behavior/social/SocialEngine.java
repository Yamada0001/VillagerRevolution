package dev.bettervillagers.behavior.social;

import dev.bettervillagers.BV;
import dev.bettervillagers.ai.AIContext;
import dev.bettervillagers.ai.AIResult;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.storage.TradeJournalRecord;
import dev.bettervillagers.storage.RelationUpdate;
import dev.bettervillagers.villager.BVillager;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** 跨职业村民的社交、聊天和交易展示。 */
public final class SocialEngine {

    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    private final Set<String> engaged = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, List<Entity>> displays = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatSession> chatSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TradeSession> tradeSessions = new ConcurrentHashMap<>();
    private final Set<String> settling = ConcurrentHashMap.newKeySet();
    private volatile ExecutorService ledgerExecutor;
    private volatile int ledgerThreads;
    private final List<ExecutorService> retiredLedgerExecutors = new CopyOnWriteArrayList<>();
    private volatile boolean shutdown;

    public SocialEngine() {
        this(2);
    }

    public SocialEngine(int asyncThreads) {
        ledgerThreads = Math.max(1, asyncThreads);
        ledgerExecutor = newLedgerExecutor(ledgerThreads);
    }

    private static ExecutorService newLedgerExecutor(int threads) {
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "BetterVillagers-TradeLedger");
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void reconfigureAsyncThreads(int requestedThreads) {
        int next = Math.max(1, requestedThreads);
        if (next == ledgerThreads || shutdown) {
            return;
        }
        ExecutorService previous = ledgerExecutor;
        ledgerExecutor = newLedgerExecutor(next);
        ledgerThreads = next;
        previous.shutdown();
        retiredLedgerExecutors.add(previous);
    }

    public boolean shouldSchedule(BVillager bv, long now) {
        if (tradeSessions.containsKey(bv.uuid()) || chatSessions.containsKey(bv.uuid())
                || engaged.contains(bv.uuid()) || settling.contains(bv.uuid())) {
            return true;
        }
        VillagerState state = bv.state();
        return state != VillagerState.COMBAT && state != VillagerState.FLEEING
                && now - bv.lastSocialTime() >= chatCooldownMillis();
    }

    public void tickSocial(BVillager bv) {
        if (shutdown) {
            return;
        }
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        settlePending(bv);
        long now = System.currentTimeMillis();
        TradeSession activeTrade = tradeSessions.get(bv.uuid());
        if (activeTrade != null) {
            if (now >= activeTrade.deadlineMillis) {
                abortTrade(activeTrade);
            }
            return;
        }
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
        if (partnerEntity == null || !tryEngage(bv.uuid(), partner.uuid())) {
            return;
        }
        if (BV.config().feature("auto-trading")
                && ThreadLocalRandom.current().nextDouble() < tradeChance()) {
            startTrade(bv, partner, self, partnerEntity, now);
        } else {
            startChat(bv, partner, self, partnerEntity, now);
        }
    }

    private BVillager findPartner(BVillager bv, LivingEntity self) {
        double range = BV.config().raw().getDouble("social.encounter-range", 3.5D);
        double rangeSquared = range * range;
        for (Entity nearby : self.getNearbyEntities(range,
                BV.config().raw().getDouble("social.encounter-height", 2.0D), range)) {
            if (!(nearby instanceof Villager villager) || BV.villagers() == null) {
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
            BVillager.PositionSnapshot position = other.lastKnownPosition();
            Location selfLocation = self.getLocation();
            if (position != null && selfLocation.getWorld() != null
                    && selfLocation.getWorld().getName().equals(position.world())) {
                double dx = selfLocation.getX() - position.x();
                double dy = selfLocation.getY() - position.y();
                double dz = selfLocation.getZ() - position.z();
                if (dx * dx + dy * dy + dz * dz <= rangeSquared) {
                    return other;
                }
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
        firstEntity.setRotation(yawTowards(firstEntity.getLocation(), second.lastKnownPosition()), 0f);
        BV.scheduler().runForEntity(secondEntity, () -> {
            if (shutdown || System.currentTimeMillis() >= session.deadlineMillis()
                    || !secondEntity.isValid() || secondEntity.isDead()) {
                finishSession(session);
                return;
            }
            second.state(VillagerState.SOCIALIZING);
            second.lastSocialTime(now);
            secondEntity.setRotation(yawTowards(secondEntity.getLocation(), first.lastKnownPosition()), 0f);
            requestChatContent(session, first, second);
        }, () -> finishSession(session));
    }

    private void startTrade(BVillager first, BVillager second, LivingEntity firstEntity, LivingEntity secondEntity, long now) {
        if (!(firstEntity instanceof Villager traderFirst) || !(secondEntity instanceof Villager traderSecond)
                || BV.trade() == null || !traderFirst.isValid()) {
            endTrade(first);
            endTrade(second);
            return;
        }
        TradeSession session = new TradeSession(first, second, traderFirst, traderSecond, now + 30_000L);
        if (tradeSessions.putIfAbsent(first.uuid(), session) != null) {
            releasePair(first.uuid(), second.uuid());
            return;
        }
        if (tradeSessions.putIfAbsent(second.uuid(), session) != null) {
            tradeSessions.remove(first.uuid(), session);
            releasePair(first.uuid(), second.uuid());
            return;
        }
        first.state(VillagerState.TRADING);
        first.lastSocialTime(now);
        BV.scheduler().runForEntity(secondEntity, () -> {
            if (!isTradeEntityValid(session, traderSecond)) {
                abortTrade(session);
                return;
            }
            List<MerchantRecipe> recipes = traderSecond.getRecipes();
            List<MerchantRecipe> available = recipes.stream()
                    .filter(recipe -> recipe.getUses() < recipe.getMaxUses())
                    .toList();
            if (available.isEmpty()) {
                abortTrade(session);
                return;
            }
            MerchantRecipe recipe = available.get(ThreadLocalRandom.current().nextInt(available.size()));
            List<ItemStack> ingredients = recipe.getIngredients().stream().map(ItemStack::clone).toList();
            ItemStack result = recipe.getResult().clone();
            session.ingredients = ingredients;
            session.result = result;
            second.state(VillagerState.TRADING);
            second.lastSocialTime(now);
            requestTradeApproval(session, recipe, ingredients, result, now);
        }, () -> abortTrade(session));
    }

    private void requestTradeApproval(TradeSession session, MerchantRecipe recipe,
                                      List<ItemStack> ingredients, ItemStack result, long now) {
        relationAffinity(session.buyer.uuid(), session.seller.uuid()).whenComplete((affinity, relationFailure) -> {
            List<ItemStack> pricedIngredients = discountedIngredients(
                    ingredients, relationFailure == null ? affinity : 0);
            session.ingredients = pricedIngredients;
            String system = BV.messages().raw("ai-prompt.merchant-system");
            String user = BV.messages().raw("ai-prompt.merchant-user")
                    .replace("{goods}", result.getType().translationKey())
                    .replace("{quantity}", String.valueOf(result.getAmount()))
                    .replace("{price}", String.valueOf(
                            pricedIngredients.stream().mapToInt(ItemStack::getAmount).sum()));
            AIContext context = new AIContext(session.seller.uuid(), session.seller.name(),
                    session.seller.profession().id(), "trade", system, user);
            BV.ai().decide(context).whenComplete((decision, failure) ->
                    BV.scheduler().runForEntity(session.sellerEntity, () -> {
                        if (!isTradeEntityValid(session, session.sellerEntity)
                                || failure != null
                                || decision != null && decision.isUsable()
                                && decision.text().toUpperCase(java.util.Locale.ROOT).contains("REJECT")) {
                            abortTrade(session);
                            return;
                        }
                        TradeJournalRecord record = new TradeJournalRecord(
                                session.tradeId, session.buyer.uuid(), session.seller.uuid(),
                                encodeItems(pricedIngredients), encodeItem(result),
                                TradeJournalRecord.State.PREPARED, now, now);
                        ledger(() -> BV.storage().tradeJournals().create(record))
                                .whenComplete((ignored, journalFailure) -> {
                                    if (journalFailure != null || shutdown) {
                                        abortTrade(session);
                                        return;
                                    }
                                    session.journalCreated.set(true);
                                    BV.scheduler().runForEntity(session.buyerEntity,
                                            () -> debitBuyer(session, recipe), () -> abortTrade(session));
                                });
                    }, () -> abortTrade(session)));
        });
    }

    private CompletableFuture<Integer> relationAffinity(String first, String second) {
        return CompletableFuture.supplyAsync(() -> BV.storage().relations().affinity(first, second), ledgerExecutor);
    }

    private List<ItemStack> discountedIngredients(List<ItemStack> ingredients, int affinity) {
        if (ingredients.isEmpty() || affinity <= 0) {
            return ingredients;
        }
        int maxPercent = Math.clamp(BV.config().raw().getInt("social.max-discount-percent", 25), 0, 90);
        double percent = maxPercent * Math.clamp(affinity, 0, 100) / 100.0;
        List<ItemStack> discounted = ingredients.stream().map(ItemStack::clone)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ItemStack primary = discounted.getFirst();
        primary.setAmount(Math.max(1, (int) Math.ceil(primary.getAmount() * (1.0 - percent / 100.0))));
        return List.copyOf(discounted);
    }

    private void debitBuyer(TradeSession session, MerchantRecipe recipe) {
        Villager traderFirst = session.buyerEntity;
        Villager traderSecond = session.sellerEntity;
        List<ItemStack> ingredients = session.ingredients;
        if (!isTradeEntityValid(session, traderFirst) || !canPay(traderFirst.getInventory(), ingredients)) {
            abortTrade(session);
            return;
        }
        for (ItemStack ingredient : ingredients) {
            remove(traderFirst.getInventory(), ingredient);
        }
        addMarker(traderFirst, debitMarker(session.tradeId));
        session.buyerDebited.set(true);
        ledger(() -> BV.storage().tradeJournals().markDebited(session.tradeId))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        abortTrade(session);
                        return;
                    }
                    BV.scheduler().runForEntity(traderSecond, () -> {
                        if (!isTradeEntityValid(session, traderSecond)
                                || recipe.getUses() >= recipe.getMaxUses()) {
                            abortTrade(session);
                            return;
                        }
                        BV.scheduler().runForEntity(traderFirst, () -> commitBuyer(session),
                                () -> abortTrade(session));
                    }, () -> abortTrade(session));
                });
    }

    private void commitBuyer(TradeSession session) {
        if (!isTradeEntityValid(session, session.buyerEntity) || session.result == null) {
            abortTrade(session);
            return;
        }
        if (!hasMarker(session.buyerEntity, committedMarker(session.tradeId))) {
            addItems(session.buyerEntity, List.of(session.result));
            removeMarker(session.buyerEntity, debitMarker(session.tradeId));
            addMarker(session.buyerEntity, committedMarker(session.tradeId));
        }
        session.buyerCommitted.set(true);
        session.buyerEntity.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                session.buyerEntity.getLocation().add(0, 2, 0), 2);
        showTradeDisplay(session.buyer.uuid(), session.buyerEntity, List.of(session.result));
        ledger(() -> BV.storage().tradeJournals().markCommitted(session.tradeId))
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        BV.scheduler().runForEntity(session.buyerEntity,
                                () -> removeMarker(session.buyerEntity, committedMarker(session.tradeId)), null);
                        settlePending(session.seller);
                    }
                    finishTrade(session);
                });
    }

    private boolean isTradeEntityValid(TradeSession session, Villager entity) {
        return !shutdown && BV.config().feature("social-interaction")
                && BV.config().feature("auto-trading")
                && !session.finished.get() && entity != null && entity.isValid() && !entity.isDead();
    }

    private void abortTrade(TradeSession session) {
        if (!session.finished.compareAndSet(false, true)) {
            return;
        }
        if (session.journalCreated.get() && session.buyerDebited.get()
                && !session.buyerCommitted.get() && session.ingredients != null) {
            BV.scheduler().runForEntity(session.buyerEntity,
                    () -> refundBuyer(session), null);
        } else if (session.journalCreated.get() && !session.buyerCommitted.get()) {
            ledger(() -> BV.storage().tradeJournals().markRefunded(session.tradeId));
        }
        closeTradeSession(session);
    }

    private void finishTrade(TradeSession session) {
        if (!session.finished.compareAndSet(false, true)) {
            return;
        }
        closeTradeSession(session);
    }

    private void closeTradeSession(TradeSession session) {
        tradeSessions.remove(session.buyer.uuid(), session);
        tradeSessions.remove(session.seller.uuid(), session);
        finishTradeParticipant(session.buyer, session.buyerEntity);
        finishTradeParticipant(session.seller, session.sellerEntity);
    }

    private void finishTradeParticipant(BVillager villager, Villager entity) {
        if (entity != null && !shutdown) {
            BV.scheduler().runForEntity(entity, () -> endTrade(villager), () -> releaseTradeState(villager));
        } else {
            releaseTradeState(villager);
        }
    }

    private void releaseTradeState(BVillager villager) {
        if (villager.state() == VillagerState.TRADING) {
            villager.state(VillagerState.IDLE);
        }
        engaged.remove(villager.uuid());
        clearDisplays(villager.uuid());
    }

    private void refundBuyer(TradeSession session) {
        Villager buyer = session.buyerEntity;
        String debit = debitMarker(session.tradeId);
        String refunded = refundedMarker(session.tradeId);
        if (buyer.isValid() && hasMarker(buyer, debit)) {
            addItems(buyer, session.ingredients);
            removeMarker(buyer, debit);
            addMarker(buyer, refunded);
        }
        ledger(() -> BV.storage().tradeJournals().markRefunded(session.tradeId))
                .thenRun(() -> BV.scheduler().runForEntity(buyer,
                        () -> removeMarker(buyer, refunded), null));
    }

    /** Reconciles durable trade records whenever a participant is loaded. */
    public void settlePending(BVillager bv) {
        LivingEntity entity = bv.entity();
        if (shutdown || !(entity instanceof Villager villager) || !settling.add(bv.uuid())) {
            return;
        }
        CompletableFuture.supplyAsync(
                        () -> BV.storage().tradeJournals().findUnresolvedFor(bv.uuid()), ledgerExecutor)
                .whenComplete((records, failure) -> {
                    if (failure != null || shutdown) {
                        settling.remove(bv.uuid());
                        return;
                    }
                    BV.scheduler().runForEntity(villager, () -> {
                        try {
                            reconcileRecords(bv, villager, records);
                        } finally {
                            settling.remove(bv.uuid());
                        }
                    }, () -> settling.remove(bv.uuid()));
                });
    }

    private void reconcileRecords(BVillager bv, Villager entity, List<TradeJournalRecord> records) {
        for (TradeJournalRecord record : records) {
            if (record.buyerUuid().equals(bv.uuid())) {
                reconcileBuyer(entity, record);
            }
            if (record.sellerUuid().equals(bv.uuid())
                    && record.state() == TradeJournalRecord.State.COMMITTED) {
                reconcileSeller(bv, entity, record);
            }
        }
    }

    private void reconcileBuyer(Villager buyer, TradeJournalRecord record) {
        String debit = debitMarker(record.tradeId());
        String committed = committedMarker(record.tradeId());
        if (record.state() == TradeJournalRecord.State.COMMITTED) {
            removeMarker(buyer, committed);
            removeMarker(buyer, debit);
            return;
        }
        if (hasMarker(buyer, committed)) {
            ledger(() -> BV.storage().tradeJournals().markCommitted(record.tradeId()))
                    .thenRun(() -> BV.scheduler().runForEntity(buyer,
                            () -> removeMarker(buyer, committed), null));
            return;
        }
        String refunded = refundedMarker(record.tradeId());
        if (hasMarker(buyer, debit)) {
            addItems(buyer, decodeItems(record.ingredients()));
            removeMarker(buyer, debit);
            addMarker(buyer, refunded);
        }
        ledger(() -> BV.storage().tradeJournals().markRefunded(record.tradeId()))
                .thenRun(() -> BV.scheduler().runForEntity(buyer,
                        () -> removeMarker(buyer, refunded), null));
    }

    private void reconcileSeller(BVillager bv, Villager seller, TradeJournalRecord record) {
        String settled = settledMarker(record.tradeId());
        List<ItemStack> ingredients = decodeItems(record.ingredients());
        ItemStack result = decodeItem(record.resultItem());
        if (!hasMarker(seller, settled)) {
            addItems(seller, ingredients);
            dev.bettervillagers.profession.EquipmentDurability.repair(seller, bv.professionData(),
                    Math.max(0.0, BV.config().raw().getDouble(
                            "gameplay.equipment.repair-per-trade", 10.0)));
            recordAutonomousInteraction(record, bv, seller);
            if (result != null) {
                incrementMatchingRecipe(seller, result);
            }
            addMarker(seller, settled);
            seller.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                    seller.getLocation().add(0, 2, 0), 2);
            showTradeDisplay(bv.uuid(), seller, ingredients);
        }
        ledger(() -> BV.storage().tradeJournals().markSettled(record.tradeId()))
                .thenRun(() -> BV.scheduler().runForEntity(seller,
                        () -> removeMarker(seller, settled), null));
    }

    private void recordAutonomousInteraction(TradeJournalRecord record, BVillager seller, Villager sellerEntity) {
        recordInteraction(record.tradeId(), record.buyerUuid(), record.sellerUuid(), true)
                .thenAccept(update -> {
                    if (update != null && update.breedingReady()) {
                        spawnRelationshipChild(record.buyerUuid(), seller, sellerEntity);
                    }
                });
    }

    public void recordPlayerTrade(Player player, BVillager villager, Villager entity, MerchantRecipe recipe) {
        String eventId = "player-" + UUID.randomUUID();
        recordInteraction(eventId, player.getUniqueId().toString(), villager.uuid(), false)
                .whenComplete((update, failure) -> BV.scheduler().runForEntity(entity, () -> {
                    dev.bettervillagers.profession.EquipmentDurability.repair(entity, villager.professionData(),
                            Math.max(0.0, BV.config().raw().getDouble(
                                    "gameplay.equipment.repair-per-trade", 10.0)));
                    if (failure == null && update != null) {
                        int points = Math.max(1, BV.config().raw().getInt(
                                "social.affinity-points-per-special-price", 10));
                        int discount = update.affinity() / points;
                        recipe.setSpecialPrice(Math.min(recipe.getSpecialPrice(), -discount));
                    }
                }, null));
    }

    private CompletableFuture<RelationUpdate> recordInteraction(String eventId, String first, String second,
                                                                 boolean allowBreeding) {
        int gain = Math.max(0, BV.config().raw().getInt("social.affinity-per-trade", 5));
        int threshold = Math.clamp(BV.config().raw().getInt("social.breeding-threshold", 80), 1, 100);
        long cooldown = TimeUnit.SECONDS.toMillis(Math.max(0L,
                BV.config().raw().getLong("social.breeding-cooldown-seconds", 1200L)));
        return CompletableFuture.supplyAsync(() -> BV.storage().relations().recordInteraction(
                eventId, first, second, gain, threshold, cooldown,
                System.currentTimeMillis(), allowBreeding), ledgerExecutor)
                .exceptionally(failure -> {
                    BV.plugin().getLogger().warning("Unable to update villager affinity: " + failure.getMessage());
                    return null;
                });
    }

    private void spawnRelationshipChild(String buyerUuid, BVillager seller, Villager sellerEntity) {
        BV.scheduler().runForEntity(sellerEntity, () -> {
            if (shutdown || !sellerEntity.isValid() || sellerEntity.isDead() || !sellerEntity.isAdult()
                    || BV.regions() != null && BV.regions().isProtected(sellerEntity.getLocation())) {
                return;
            }
            var villagerManager = BV.villagers();
            if (villagerManager == null) {
                return;
            }
            sellerEntity.getWorld().spawnParticle(org.bukkit.Particle.HEART,
                    sellerEntity.getLocation().add(0, 2, 0), 6);
            Villager child = sellerEntity.getWorld().spawn(sellerEntity.getLocation(), Villager.class,
                    Villager::setBaby);
            villagerManager.registerOffspring(child, buyerUuid, seller.uuid());
            villagerManager.get(buyerUuid).map(BVillager::entity).ifPresent(buyerEntity ->
                    BV.scheduler().runForEntity(buyerEntity, () -> buyerEntity.getWorld().spawnParticle(
                            org.bukkit.Particle.HEART, buyerEntity.getLocation().add(0, 2, 0), 6), null));
        }, null);
    }

    private void addItems(Villager villager, List<ItemStack> items) {
        for (ItemStack item : items) {
            villager.getInventory().addItem(item.clone()).values().forEach(leftover ->
                    villager.getWorld().dropItemNaturally(villager.getLocation(), leftover));
        }
    }

    private CompletableFuture<Void> ledger(LedgerAction action) {
        try {
            return CompletableFuture.runAsync(() -> {
                try {
                    action.run();
                } catch (Throwable t) {
                    throw new java.util.concurrent.CompletionException(t);
                }
            }, ledgerExecutor);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String encodeItems(List<ItemStack> items) {
        return items.stream().map(SocialEngine::encodeItem)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static String encodeItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    private static List<ItemStack> decodeItems(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        List<ItemStack> items = new ArrayList<>();
        for (String value : encoded.split("\\|")) {
            ItemStack item = decodeItem(value);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    private static ItemStack decodeItem(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
    }

    private static String debitMarker(String tradeId) {
        return "D:" + tradeId;
    }

    private static String committedMarker(String tradeId) {
        return "C:" + tradeId;
    }

    private static String refundedMarker(String tradeId) {
        return "R:" + tradeId;
    }

    private static String settledMarker(String tradeId) {
        return "S:" + tradeId;
    }

    private static NamespacedKey markerKey() {
        return new NamespacedKey(BV.plugin(), "trade_markers");
    }

    private static boolean hasMarker(Villager villager, String marker) {
        return markers(villager).contains(marker);
    }

    private static void addMarker(Villager villager, String marker) {
        Set<String> values = markers(villager);
        values.add(marker);
        villager.getPersistentDataContainer().set(
                markerKey(), PersistentDataType.STRING, String.join("\n", values));
    }

    private static void removeMarker(Villager villager, String marker) {
        Set<String> values = markers(villager);
        if (!values.remove(marker)) {
            return;
        }
        if (values.isEmpty()) {
            villager.getPersistentDataContainer().remove(markerKey());
        } else {
            villager.getPersistentDataContainer().set(
                    markerKey(), PersistentDataType.STRING, String.join("\n", values));
        }
    }

    private static Set<String> markers(Villager villager) {
        String raw = villager.getPersistentDataContainer().get(markerKey(), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) {
            return new HashSet<>();
        }
        return new HashSet<>(List.of(raw.split("\\n")));
    }

    private void incrementMatchingRecipe(Villager villager, ItemStack result) {
        List<MerchantRecipe> recipes = new ArrayList<>(villager.getRecipes());
        for (MerchantRecipe recipe : recipes) {
            if (recipe.getResult().isSimilar(result) && recipe.getUses() < recipe.getMaxUses()) {
                recipe.setUses(recipe.getUses() + 1);
                recipe.setDemand(recipe.getDemand() + 1);
                villager.setRecipes(recipes);
                return;
            }
        }
    }

    private boolean canPay(Inventory inventory, List<ItemStack> ingredients) {
        for (int i = 0; i < ingredients.size(); i++) {
            ItemStack ingredient = ingredients.get(i);
            boolean alreadyCounted = false;
            int required = 0;
            for (int j = 0; j < ingredients.size(); j++) {
                ItemStack other = ingredients.get(j);
                if (ingredient.isSimilar(other)) {
                    required += other.getAmount();
                    if (j < i) {
                        alreadyCounted = true;
                    }
                }
            }
            if (!alreadyCounted && countSimilar(inventory, ingredient) < required) {
                return false;
            }
        }
        return true;
    }

    private int countSimilar(Inventory inventory, ItemStack wanted) {
        int total = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.isSimilar(wanted)) {
                total += stack.getAmount();
            }
        }
        return total;
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
        if (bv.state() == VillagerState.TRADING) {
            bv.state(VillagerState.IDLE);
        }
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
        TradeSession tradeSession = tradeSessions.get(uuid);
        if (tradeSession != null) {
            abortTrade(tradeSession);
        }
        ChatSession session = chatSessions.get(uuid);
        if (session != null) {
            finishSession(session);
        }
        engaged.remove(uuid);
        clearDisplays(uuid);
    }

    private float yawTowards(Location from, BVillager.PositionSnapshot to) {
        if (to == null || from.getWorld() == null || !from.getWorld().getName().equals(to.world())) {
            return from.getYaw();
        }
        return (float) Math.toDegrees(Math.atan2(-(to.x() - from.getX()), to.z() - from.getZ()));
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
        if (speakerEntity == null) {
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
                || !BV.config().feature("social-interaction")
                || chatSessions.get(session.first().uuid()) != session
                || chatSessions.get(session.second().uuid()) != session
                || isEmergencyState(session.first()) || isEmergencyState(session.second())) {
            return false;
        }
        return session.first().entity() != null && session.second().entity() != null;
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
        if (shutdown || entity == null) {
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
        return line.contains(partnerName) ? line : partnerName + "，" + line;
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
        int maxLength = BV.config().raw().getInt("social.chat-max-length", 32);
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
        Component component = MessageService.deserialize(BV.messages().raw("social.chat-bubble-format")
                .replace("{text}", MessageService.escapeUntrusted(text)));
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
            double offset = index * BV.config().raw().getDouble("social.bubble.item-spacing", 0.35D);
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
        for (TradeSession session : Set.copyOf(tradeSessions.values())) {
            abortTrade(session);
        }
        tradeSessions.clear();
        for (ChatSession session : Set.copyOf(chatSessions.values())) {
            finishSession(session);
        }
        chatSessions.clear();
        engaged.clear();
        for (String ownerUuid : Set.copyOf(displays.keySet())) {
            clearDisplays(ownerUuid);
        }
        ledger(() -> {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7);
            BV.storage().tradeJournals().deleteTerminalBefore(cutoff);
            BV.storage().relations().deleteEventsBefore(cutoff);
        });
        List<ExecutorService> executors = new ArrayList<>(retiredLedgerExecutors);
        executors.add(ledgerExecutor);
        executors.forEach(ExecutorService::shutdown);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15L);
            for (ExecutorService executor : executors) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L || !executor.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executors.forEach(ExecutorService::shutdownNow);
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

    private static final class TradeSession {
        private final String tradeId = UUID.randomUUID().toString();
        private final BVillager buyer;
        private final BVillager seller;
        private final Villager buyerEntity;
        private final Villager sellerEntity;
        private final long deadlineMillis;
        private final AtomicBoolean buyerDebited = new AtomicBoolean();
        private final AtomicBoolean buyerCommitted = new AtomicBoolean();
        private final AtomicBoolean journalCreated = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile List<ItemStack> ingredients;
        private volatile ItemStack result;

        private TradeSession(BVillager buyer, BVillager seller, Villager buyerEntity,
                             Villager sellerEntity, long deadlineMillis) {
            this.buyer = buyer;
            this.seller = seller;
            this.buyerEntity = buyerEntity;
            this.sellerEntity = sellerEntity;
            this.deadlineMillis = deadlineMillis;
        }
    }

    @FunctionalInterface
    private interface LedgerAction {
        void run() throws Exception;
    }
}
