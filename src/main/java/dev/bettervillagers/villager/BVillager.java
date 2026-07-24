package dev.bettervillagers.villager;

import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.profession.Defense;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.profession.ProfessionData;
import org.bukkit.entity.Villager;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 村民运行期包装（规范 3.x）。
 * <p>
 * 承载持久化数据 {@link VillagerData}、职业、FSM 状态与对 Bukkit 实体的弱引用。
 * 弱引用避免区块卸载后仍持有实体（规范 4.2：弱引用存储非关键数据）。
 */
public final class BVillager {

    /** 方块操作类型索引（规范 3.2：读取/破坏/放置/交互四类独立冷却）。 */
    public static final int OP_READ = 0, OP_BREAK = 1, OP_PLACE = 2, OP_INTERACT = 3;

    private final String uuid;
    // 多线程读写：异步 AI 回调/合并线程写入，主/区域线程读取，须 volatile 保证可见性
    private volatile String name;
    private volatile Profession profession;
    private volatile ProfessionData professionData;
    private volatile int villageId;
    private volatile boolean aiEnabled;
    private volatile boolean professionLocked;   // 管理员指派锁定
    private volatile VillagerState state = VillagerState.IDLE;
    private volatile long lastTactical = 0L;
    private volatile long lastStrategic = 0L;
    private final WeakReference<Villager> entityRef;

    // 规范 2.2：转职冷却（非管理员指派需间隔 ≥ 1 游戏日 = 24000 tick）
    private volatile long lastProfessionChangeTick = Long.MIN_VALUE;

    // 规范 3.2：方块操作行动点数与四类独立冷却
    private volatile double actionPoints = 100.0;
    // 四类操作独立冷却时间戳（AtomicLong 保证跨线程读写的可见性与原子性）
    private final AtomicLong[] lastBlockOp = {
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong()
    };

    // 规范 2.3：装备破损状态（耐久归零降级为破损，属性减半）
    private volatile boolean equipmentBroken = false;

    // 巡逻锚点：基于 UUID 哈希计算的固定世界坐标，避免巡逻目标随移动漂移导致绕圈
    private volatile org.bukkit.Location patrolAnchor = null;

    // 规范 3.3：编组化边境巡逻进度索引（军事职业沿村庄边境巡逻路线推进的当前航点序号）
    private volatile int patrolIndex = 0;
    // 规范 3.3：上次执行专属职业任务的时间戳（工作 tick 节流，避免每 tick 重复扫描）
    private volatile long lastWorkTask = 0L;
    // 规范 3.3：上次参与跨职业社交的时间戳（攀谈冷却，贴合原版村民社交间隔）
    private volatile long lastSocialTime = 0L;

    public BVillager(VillagerData data, Villager entity, Profession profession, ProfessionData pd) {
        this.uuid = data.uuid();
        this.name = data.name();
        this.profession = profession;
        this.professionData = pd;
        this.villageId = data.villageId();
        this.aiEnabled = data.aiEnabled();
        this.entityRef = new WeakReference<>(entity);
    }

    public String uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public Profession profession() {
        return profession;
    }

    public void profession(Profession profession, ProfessionData data, boolean locked) {
        this.profession = profession;
        this.professionData = data;
        this.professionLocked = locked;
    }

    public ProfessionData professionData() {
        return professionData;
    }

    public int villageId() {
        return villageId;
    }

    public void villageId(int id) {
        this.villageId = id;
    }

    public boolean aiEnabled() {
        return aiEnabled;
    }

    public void aiEnabled(boolean enabled) {
        this.aiEnabled = enabled;
    }

    public boolean professionLocked() {
        return professionLocked;
    }

    public VillagerState state() {
        return state;
    }

    public void state(VillagerState state) {
        this.state = state;
    }

    public long lastTactical() {
        return lastTactical;
    }

    public void lastTactical(long t) {
        this.lastTactical = t;
    }

    public long lastStrategic() {
        return lastStrategic;
    }

    public void lastStrategic(long t) {
        this.lastStrategic = t;
    }

    // ===== 规范 2.2：转职冷却 =====

    public long lastProfessionChangeTick() {
        return lastProfessionChangeTick;
    }

    public void lastProfessionChangeTick(long tick) {
        this.lastProfessionChangeTick = tick;
    }

    /** 检查是否满足非管理员转职冷却（≥ 1 游戏日 = 24000 tick）。 */
    public boolean canChangeNonAdmin(long nowTick) {
        if (lastProfessionChangeTick == Long.MIN_VALUE) {
            return true;
        }
        return nowTick - lastProfessionChangeTick >= 24000L;
    }

    // ===== 规范 3.2：行动点数与方块操作冷却 =====

    public double actionPoints() {
        return actionPoints;
    }

    public void actionPoints(double points) {
        this.actionPoints = points;
    }

    /** 消耗行动点数，不足返回 false（synchronized 保证 check-then-act 原子性）。 */
    public synchronized boolean consumeActionPoints(double cost) {
        if (actionPoints < cost) {
            return false;
        }
        actionPoints -= cost;
        return true;
    }

    /** 回复行动点数（上限 100）。 */
    public synchronized void restoreActionPoints(double amount) {
        actionPoints = Math.min(100.0, actionPoints + amount);
    }

    public long lastBlockOp(int opKind) {
        return lastBlockOp[opKind].get();
    }

    public void lastBlockOp(int opKind, long time) {
        lastBlockOp[opKind].set(time);
    }

    // ===== 规范 2.3：装备破损状态 =====

    public boolean equipmentBroken() {
        return equipmentBroken;
    }

    public void equipmentBroken(boolean broken) {
        this.equipmentBroken = broken;
    }

    // ===== 实体访问 =====

    /** 获取 Bukkit 实体（可能因区块卸载而为 null）。 */
    public Villager entity() {
        return entityRef.get();
    }

    public boolean isAlive() {
        Villager v = entityRef.get();
        return v != null && !v.isDead() && v.isValid();
    }

    /**
     * 获取该村民的固定巡逻锚点（修复绕圈）。
     * <p>
     * 首次调用时以当前出生位置为基准，按 UUID 哈希偏移生成固定坐标；
     * 后续调用返回同一坐标，确保 WORK/PATROL 目标不随移动漂移。
     * 实体或世界为空时返回 null。
     */
    public org.bukkit.Location patrolAnchor() {
        if (patrolAnchor != null) {
            return patrolAnchor;
        }
        synchronized (this) {
            if (patrolAnchor != null) {
                return patrolAnchor;
            }
            Villager v = entityRef.get();
            if (v == null || v.getWorld() == null) {
                return null;
            }
            long hash = uuid.hashCode() & 0xFFFFFFFFL;
            double angle = (hash % 360) * Math.PI / 180.0;
            double radius = 6.0 + (hash % 12); // 6~18 格半径
            patrolAnchor = v.getLocation().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            return patrolAnchor;
        }
    }

    // ===== 规范 3.3：职业任务 / 巡逻 / 社交 =====

    public int patrolIndex() {
        return patrolIndex;
    }

    public void patrolIndex(int index) {
        this.patrolIndex = index;
    }

    public long lastWorkTask() {
        return lastWorkTask;
    }

    public void lastWorkTask(long t) {
        this.lastWorkTask = t;
    }

    public long lastSocialTime() {
        return lastSocialTime;
    }

    public void lastSocialTime(long t) {
        this.lastSocialTime = t;
    }

    /** 转为持久化数据（位置/记忆由管理器在保存时刷新）。 */
    public VillagerData toData(String aiMemoryJson, long createdAt) {
        Villager v = entity();
        long now = System.currentTimeMillis();
        Defense defense = professionData != null ? professionData.stats().defense() : Defense.LOW;
        return new VillagerData(
                uuid, name, profession.id(),
                professionData != null ? professionData.stats().health() : 40,
                professionData != null ? professionData.stats().attack() : 5,
                defense.name(),
                v != null ? v.getWorld().getName() : "world",
                v != null ? v.getLocation().getX() : 0,
                v != null ? v.getLocation().getY() : 0,
                v != null ? v.getLocation().getZ() : 0,
                villageId, aiEnabled, aiMemoryJson,
                createdAt > 0 ? createdAt : now, now
        );
    }
}
