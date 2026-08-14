package dev.bettervillagers;

import dev.bettervillagers.ai.AIService;
import dev.bettervillagers.behavior.BehaviorEngine;
import dev.bettervillagers.building.BuildingManager;
import dev.bettervillagers.config.ConfigManager;
import dev.bettervillagers.debug.DebugMonitor;
import dev.bettervillagers.i18n.MessageService;
import dev.bettervillagers.profession.ProfessionManager;
import dev.bettervillagers.redstone.RegionManager;
import dev.bettervillagers.scheduler.SchedulerAdapter;
import dev.bettervillagers.scheduler.ThreadBoundaryGuard;
import dev.bettervillagers.storage.StorageService;
import dev.bettervillagers.trade.TradeService;
import dev.bettervillagers.village.VillageManager;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 全局服务注册表（静态门面）。
 * <p>
 * 供各模块以 {@code BV.scheduler()} 等方式访问已初始化的单例，避免冗长的构造注入。
 * 生命周期：{@link dev.bettervillagers.BetterVillagersPlugin#onEnable()} 中按依赖顺序装配。
 */
public final class BV {

    private static JavaPlugin plugin;
    private static ConfigManager config;
    private static MessageService messages;
    private static SchedulerAdapter scheduler;
    private static ThreadBoundaryGuard guard;
    private static StorageService storage;
    private static AIService ai;
    private static ProfessionManager professions;
    private static VillageManager villages;
    private static dev.bettervillagers.village.DiplomacyManager diplomacy;
    private static dev.bettervillagers.village.VillageActivityManager activities;
    private static dev.bettervillagers.villager.VillagerManager villagers;
    private static BehaviorEngine behavior;
    private static TradeService trade;
    private static BuildingManager building;
    private static RegionManager regions;
    private static DebugMonitor debug;
    private static dev.bettervillagers.behavior.task.ProfessionTaskEngine taskEngine;
    private static dev.bettervillagers.behavior.social.SocialEngine socialEngine;
    private static dev.bettervillagers.behavior.task.PatrolRouter patrolRouter;

    private BV() {
    }

    public static void init(JavaPlugin p) {
        plugin = p;
    }

    public static JavaPlugin plugin() {
        return plugin;
    }

    public static ConfigManager config() {
        return config;
    }

    public static void config(ConfigManager c) {
        config = c;
    }

    public static MessageService messages() {
        return messages;
    }

    public static void messages(MessageService m) {
        messages = m;
    }

    public static SchedulerAdapter scheduler() {
        return scheduler;
    }

    public static void scheduler(SchedulerAdapter s) {
        scheduler = s;
    }

    public static ThreadBoundaryGuard guard() {
        return guard;
    }

    public static void guard(ThreadBoundaryGuard g) {
        guard = g;
    }

    public static StorageService storage() {
        return storage;
    }

    public static void storage(StorageService s) {
        storage = s;
    }

    public static AIService ai() {
        return ai;
    }

    public static void ai(AIService a) {
        ai = a;
    }

    public static ProfessionManager professions() {
        return professions;
    }

    public static void professions(ProfessionManager p) {
        professions = p;
    }

    public static VillageManager villages() {
        return villages;
    }

    public static void villages(VillageManager v) {
        villages = v;
    }

    public static dev.bettervillagers.village.DiplomacyManager diplomacy() {
        return diplomacy;
    }

    public static void diplomacy(dev.bettervillagers.village.DiplomacyManager d) {
        diplomacy = d;
    }

    public static dev.bettervillagers.village.VillageActivityManager activities() {
        return activities;
    }

    public static void activities(dev.bettervillagers.village.VillageActivityManager manager) {
        activities = manager;
    }

    public static dev.bettervillagers.villager.VillagerManager villagers() {
        return villagers;
    }

    public static void villagers(dev.bettervillagers.villager.VillagerManager v) {
        villagers = v;
    }

    public static BehaviorEngine behavior() {
        return behavior;
    }

    public static void behavior(BehaviorEngine b) {
        behavior = b;
    }

    public static TradeService trade() {
        return trade;
    }

    public static void trade(TradeService t) {
        trade = t;
    }

    public static BuildingManager building() {
        return building;
    }

    public static void building(BuildingManager b) {
        building = b;
    }

    public static RegionManager regions() {
        return regions;
    }

    public static void regions(RegionManager r) {
        regions = r;
    }

    public static DebugMonitor debug() {
        return debug;
    }

    public static void debug(DebugMonitor d) {
        debug = d;
    }

    public static dev.bettervillagers.behavior.task.ProfessionTaskEngine taskEngine() {
        return taskEngine;
    }

    public static void taskEngine(dev.bettervillagers.behavior.task.ProfessionTaskEngine t) {
        taskEngine = t;
    }

    public static dev.bettervillagers.behavior.social.SocialEngine socialEngine() {
        return socialEngine;
    }

    public static void socialEngine(dev.bettervillagers.behavior.social.SocialEngine s) {
        socialEngine = s;
    }

    public static dev.bettervillagers.behavior.task.PatrolRouter patrolRouter() {
        return patrolRouter;
    }

    public static void patrolRouter(dev.bettervillagers.behavior.task.PatrolRouter p) {
        patrolRouter = p;
    }

    /** 释放全部引用（停服时调用）。 */
    public static void shutdown() {
        plugin = null;
        config = null;
        messages = null;
        scheduler = null;
        guard = null;
        storage = null;
        ai = null;
        professions = null;
        villages = null;
        diplomacy = null;
        activities = null;
        villagers = null;
        behavior = null;
        trade = null;
        building = null;
        regions = null;
        debug = null;
        taskEngine = null;
        socialEngine = null;
        patrolRouter = null;
    }
}
