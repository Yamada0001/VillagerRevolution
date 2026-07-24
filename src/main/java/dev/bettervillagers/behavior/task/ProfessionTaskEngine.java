package dev.bettervillagers.behavior.task;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.MovementHelper;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.behavior.block.BlockInteractionEngine;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.village.Village;
import dev.bettervillagers.villager.BVillager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.LivingEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 职业专属任务引擎（规范 3.1 / 3.2 / 3.3：各职业核心职责行为）。
 * <p>
 * 按职业分发专属工作行为，所有职业严格遵循自身职能定位开展活动，
 * 实现跨职业场景下的功能互补：
 * <ul>
 *   <li><b>农民</b>：耕地开垦→作物播种→田间打理→收获仓储 全流程农业生产</li>
 *   <li><b>矿工</b>：挖掘矿石、收集矿产资源</li>
 *   <li><b>厨师</b>：在工作台/烟熏炉/熔炉处制作食物</li>
 *   <li><b>屠夫</b>：处理牲畜、收集肉类原料</li>
 *   <li><b>商人</b>：前往市集区域开展交易</li>
 *   <li><b>建筑师</b>：前往施工场地协助建造</li>
 *   <li><b>普通人</b>：日常劳作、采集与巡视</li>
 *   <li><b>国王</b>：坐镇村庄中心统筹指挥</li>
 *   <li><b>军事职业</b>：委托 {@link MilitaryTask}（边境巡逻 + 协同防卫）</li>
 * </ul>
 * <p>
 * 所有方块操作经 {@link BlockInteractionEngine}（受职业白名单、行动点数、独立冷却约束，
 * 并提交到区域线程），确保不破坏原版平衡性。必须在实体所在区域线程调用。
 */
public final class ProfessionTaskEngine {

    /** 工作任务执行间隔（毫秒，节流避免每次 tick 重复扫描）。 */
    private static final long WORK_INTERVAL_MS = 5000L;
    /** 方块扫描/操作的作用半径（格）。 */
    private static final int OP_RADIUS = 5;
    /** 操作生效距离（格，平方）：村民须接近目标方可执行方块操作。 */
    private static final double OP_REACH_SQ = 16.0;
    /** 移动速度（原版村民步行倍率）。 */
    private static final double WORK_SPEED = 0.4;
    /** 国王坐镇中心判定距离平方（4 格）。 */
    private static final double KING_HOLD_DIST_SQ = 16.0;
    /** 农民开垦判定：水源湿润半径（格）。 */
    private static final int WATER_SEARCH_RADIUS = 4;

    private final MilitaryTask militaryTask;
    private final BlockInteractionEngine blocks;

    public ProfessionTaskEngine(MilitaryTask militaryTask, BlockInteractionEngine blocks) {
        this.militaryTask = militaryTask;
        this.blocks = blocks;
    }

    /**
     * 执行一次职业专属任务（由工作 tick 经实体区域线程调用）。
     * 战斗/逃跑/社交状态下不打断，交由反射层或社交引擎处理。
     */
    public void tickWork(BVillager bv) {
        LivingEntity self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        VillagerState st = bv.state();
        // 战斗/逃跑/社交/休息/交易中不打断，交由反射层/战略层/社交引擎处理
        if (st == VillagerState.COMBAT || st == VillagerState.FLEEING
                || st == VillagerState.SOCIALIZING || st == VillagerState.RESTING
                || st == VillagerState.TRADING) {
            return;
        }
        // 工作任务节流
        long now = System.currentTimeMillis();
        if (now - bv.lastWorkTask() < WORK_INTERVAL_MS) {
            return;
        }
        bv.lastWorkTask(now);

        Profession prof = bv.profession();
        if (MilitaryTask.isMilitary(prof)) {
            militaryTask.execute(bv);
            return;
        }
        switch (prof) {
            case FARMER -> farmerCycle(bv, self);
            case MINER -> minerCycle(bv, self);
            case CHEF -> crafterCycle(bv, self);
            case BUTCHER -> butcherCycle(bv, self);
            case MERCHANT -> merchantCycle(bv, self);
            case BUILDER -> builderCycle(bv, self);
            case KING -> kingCycle(bv, self);
            default -> civilianCycle(bv, self);
        }
    }

    // ==================== 农民：全流程农业生产 ====================

    /**
     * 农业生产全流程：耕地开垦→作物播种→田间打理→收获。
     * 优先级：收获成熟作物 > 播种空地 > 开垦新地。
     */
    private void farmerCycle(BVillager bv, LivingEntity self) {
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        bv.state(VillagerState.WORKING);

        // 1. 收获：寻找半径内成熟作物
        Block harvestTarget = scanFor(world, origin, OP_RADIUS, ProfessionTaskEngine::isMatureCrop);
        if (harvestTarget != null) {
            if (withinReach(origin, harvestTarget.getLocation())) {
                blocks.breakAt(bv, harvestTarget.getLocation());
            } else {
                MovementHelper.moveToward(self, harvestTarget.getLocation(), WORK_SPEED);
            }
            return;
        }
        // 2. 播种：寻找空耕地（FARMLAND 上方为 AIR）
        Block plantTarget = scanFor(world, origin, OP_RADIUS, ProfessionTaskEngine::isEmptyFarmland);
        if (plantTarget != null) {
            Location cropLoc = plantTarget.getLocation();
            if (withinReach(origin, cropLoc)) {
                Material seed = pickCropMaterial();
                blocks.placeAt(bv, cropLoc, seed);
            } else {
                MovementHelper.moveToward(self, cropLoc, WORK_SPEED);
            }
            return;
        }
        // 3. 开垦：寻找水源附近的草地/泥土，翻耕为耕地
        // D6 修复：先扫水源再在其附近找可开垦地块，避免对每个草地块做 81 次 nearWater 读取。
        Block tillTarget = findTillableNearWater(world, origin, OP_RADIUS);
        if (tillTarget != null) {
            if (withinReach(origin, tillTarget.getLocation())) {
                blocks.placeAt(bv, tillTarget.getLocation(), Material.FARMLAND);
            } else {
                MovementHelper.moveToward(self, tillTarget.getLocation(), WORK_SPEED);
            }
            return;
        }
        // 4. 田间打理：在田间区域巡视，发现并走向已有作物
        wanderInVillage(bv, self, 0.35);
    }

    /** 判断方块是否为可收获的成熟作物。 */
    private static boolean isMatureCrop(Block b) {
        Material m = b.getType();
        if (m != Material.WHEAT && m != Material.CARROTS
                && m != Material.POTATOES && m != Material.BEETROOTS) {
            return false;
        }
        if (b.getBlockData() instanceof Ageable age) {
            return age.getAge() >= age.getMaximumAge();
        }
        return false;
    }

    /** 判断方块是否为可播种的空耕地（FARMLAND 上方为 AIR）。 */
    private static boolean isEmptyFarmland(Block b) {
        if (b.getType() != Material.FARMLAND) {
            return false;
        }
        Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
        return above.getType() == Material.AIR;
    }

    /** 判断方块是否可开垦为耕地（草地/泥土且上方为空气）。 */
    private boolean isTillable(Block b) {
        Material m = b.getType();
        if (m != Material.GRASS_BLOCK && m != Material.DIRT && m != Material.COARSE_DIRT) {
            return false;
        }
        Block above = b.getRelative(org.bukkit.block.BlockFace.UP);
        return above.getType() == Material.AIR;
    }

    /**
     * D6 优化：先扫水源再在其附近找可开垦地块。
     * <p>
     * 原实现 {@code scanFor(..., b -> isTillable(world,b))} 会对每个草地块再触发
     * 9×9=81 次水源扫描，最坏 ~49000 次方块读取。改为先以 O(n²) 找水源，
     * 再仅在水方块附近 9×9 局部找可开垦地块，避免重复扫描水源。
     */
    private Block findTillableNearWater(World world, Location origin, int radius) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (world.getBlockAt(ox + dx, oy, oz + dz).getType() != Material.WATER) {
                    continue;
                }
                // 水源附近局部寻找可开垦地块（贴合原版耕地湿润半径）
                for (int lx = -WATER_SEARCH_RADIUS; lx <= WATER_SEARCH_RADIUS; lx++) {
                    for (int lz = -WATER_SEARCH_RADIUS; lz <= WATER_SEARCH_RADIUS; lz++) {
                        Block b = world.getBlockAt(ox + dx + lx, oy, oz + dz + lz);
                        if (isTillable(b)) {
                            return b;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 随机选择播种的作物类型（贴合原版村民种植多样性）。 */
    private Material pickCropMaterial() {
        int r = ThreadLocalRandom.current().nextInt(4);
        return switch (r) {
            case 0 -> Material.WHEAT;
            case 1 -> Material.CARROTS;
            case 2 -> Material.POTATOES;
            default -> Material.BEETROOTS;
        };
    }

    // ==================== 矿工：挖掘矿石 ====================

    private void minerCycle(BVillager bv, LivingEntity self) {
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        bv.state(VillagerState.WORKING);
        // 寻找半径内的矿石（白名单内）
        Block ore = scanFor(world, origin, OP_RADIUS, b -> {
            String name = b.getType().name();
            return name.endsWith("_ORE");
        });
        if (ore != null) {
            if (withinReach(origin, ore.getLocation())) {
                blocks.breakAt(bv, ore.getLocation());
            } else {
                MovementHelper.moveToward(self, ore.getLocation(), WORK_SPEED);
            }
            return;
        }
        // 无矿石时向地下/山体方向探索
        wanderInVillage(bv, self, WORK_SPEED);
    }

    // ==================== 厨师：制作食物 ====================

    private void crafterCycle(BVillager bv, LivingEntity self) {
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        bv.state(VillagerState.WORKING);
        // 寻找工作台/烟熏炉/熔炉
        Block station = scanFor(world, origin, OP_RADIUS, b ->
                b.getType() == Material.CRAFTING_TABLE
                        || b.getType() == Material.SMOKER
                        || b.getType() == Material.FURNACE);
        if (station != null) {
            if (withinReach(origin, station.getLocation())) {
                // 到达工作站，执行交互（合成食物，消耗行动点数）
                blocks.interactAt(bv, station.getLocation(), station.getType());
            } else {
                MovementHelper.moveToward(self, station.getLocation(), WORK_SPEED);
            }
            return;
        }
        wanderInVillage(bv, self, WORK_SPEED);
    }

    // ==================== 屠夫：处理牲畜 ====================

    private void butcherCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.WORKING);
        // 寻找附近的牲畜（牛/猪/羊/鸡），前往处理
        for (org.bukkit.entity.Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (nearby instanceof org.bukkit.entity.Animals animal && !nearby.isDead()) {
                MovementHelper.moveToward(self, animal.getLocation(), WORK_SPEED);
                return;
            }
        }
        wanderInVillage(bv, self, WORK_SPEED);
    }

    // ==================== 商人：前往市集交易 ====================

    private void merchantCycle(BVillager bv, LivingEntity self) {
        // 商人聚集到村庄中心区域（市集），实际交易由自动交易系统调度
        bv.state(VillagerState.WORKING);
        moveToVillageCenter(bv, self, WORK_SPEED);
    }

    // ==================== 建筑师：协助建造 ====================

    private void builderCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.WORKING);
        // 建筑师前往村庄中心附近待命/采集建材，实际施工由战略层建造系统调度
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world != null) {
            // 采集建材（白名单内的木头/石头类方块）
            Block mat = scanFor(world, origin, OP_RADIUS, b -> {
                String name = b.getType().name();
                return name.endsWith("_LOG") || name.endsWith("_PLANKS")
                        || b.getType() == Material.COBBLESTONE;
            });
            if (mat != null && !withinReach(origin, mat.getLocation())) {
                MovementHelper.moveToward(self, mat.getLocation(), WORK_SPEED);
                return;
            }
        }
        moveToVillageCenter(bv, self, WORK_SPEED);
    }

    // ==================== 国王：坐镇指挥 ====================

    private void kingCycle(BVillager bv, LivingEntity self) {
        // 国王坐镇村庄中心统筹指挥（战略层规划由 BehaviorEngine.tickStrategic 负责）
        bv.state(VillagerState.WORKING);
        Village v = BV.villages() != null ? BV.villages().get(bv.villageId()).orElse(null) : null;
        if (v == null) {
            return;
        }
        World world = org.bukkit.Bukkit.getWorld(v.world());
        if (world == null) {
            return;
        }
        Location center = new Location(world, v.centerX(), v.centerY(), v.centerZ());
        // 跨世界 distanceSquared 会抛 IllegalArgumentException，先校验世界一致（与 withinReach/MilitaryTask 一致）
        if (!self.getWorld().equals(world)) {
            return;
        }
        if (self.getLocation().distanceSquared(center) > KING_HOLD_DIST_SQ) {
            MovementHelper.moveToward(self, center, WORK_SPEED * 0.8);
        }
    }

    // ==================== 普通人：日常劳作 ====================

    private void civilianCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.IDLE);
        wanderInVillage(bv, self, WORK_SPEED * 0.6);
    }

    // ==================== 公共辅助 ====================

    /** 在半径内扫描满足条件的第一个方块。 */
    private Block scanFor(World world, Location origin, int radius, java.util.function.Predicate<Block> test) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (test.test(b)) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    private boolean withinReach(Location a, Location b) {
        return a.getWorld() == b.getWorld()
                && a.distanceSquared(b) <= OP_REACH_SQ;
    }

    /** 在村庄范围内巡视/游走（贴合原版村民白天劳作游荡行为）。 */
    private void wanderInVillage(BVillager bv, LivingEntity self, double speed) {
        Village v = BV.villages() != null ? BV.villages().get(bv.villageId()).orElse(null) : null;
        Location loc = self.getLocation();
        if (v != null) {
            World world = org.bukkit.Bukkit.getWorld(v.world());
            if (world != null) {
                double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
                double r = ThreadLocalRandom.current().nextDouble(4, Math.max(5, v.radius() * 0.5));
                Location target = new Location(world,
                        v.centerX() + Math.cos(angle) * r,
                        v.centerY(),
                        v.centerZ() + Math.sin(angle) * r);
                MovementHelper.moveToward(self, target, speed);
                return;
            }
        }
        // 无村庄时原地小范围游荡
        Location target = loc.clone().add(
                ThreadLocalRandom.current().nextDouble(-6, 6), 0,
                ThreadLocalRandom.current().nextDouble(-6, 6));
        MovementHelper.moveToward(self, target, speed);
    }

    /** 向村庄中心移动。 */
    private void moveToVillageCenter(BVillager bv, LivingEntity self, double speed) {
        Village v = BV.villages() != null ? BV.villages().get(bv.villageId()).orElse(null) : null;
        if (v == null) {
            wanderInVillage(bv, self, speed);
            return;
        }
        World world = org.bukkit.Bukkit.getWorld(v.world());
        if (world == null) {
            return;
        }
        // 在中心附近随机点聚集（市集效应）
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double r = ThreadLocalRandom.current().nextDouble(2, 8);
        Location target = new Location(world,
                v.centerX() + Math.cos(angle) * r,
                v.centerY(),
                v.centerZ() + Math.sin(angle) * r);
        MovementHelper.moveToward(self, target, speed);
    }
}
