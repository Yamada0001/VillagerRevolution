package dev.bettervillagers.behavior.task;

import dev.bettervillagers.BV;
import dev.bettervillagers.behavior.MovementHelper;
import dev.bettervillagers.behavior.VillagerState;
import dev.bettervillagers.behavior.block.BlockInteractionEngine;
import dev.bettervillagers.profession.Profession;
import dev.bettervillagers.village.Village;
import dev.bettervillagers.villager.BVillager;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.Particle;

import java.util.Map;
import java.util.List;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private static final long NIGHT_START_TICKS = 12500L;
    private static final long NIGHT_END_TICKS = 23500L;

    private final MilitaryTask militaryTask;
    private final BlockInteractionEngine blocks;
    private final Map<UUID, Long> milkCooldown = new ConcurrentHashMap<>();

    public ProfessionTaskEngine(MilitaryTask militaryTask, BlockInteractionEngine blocks) {
        this.militaryTask = militaryTask;
        this.blocks = blocks;
    }

    /**
     * 执行一次职业专属任务（由工作 tick 经实体区域线程调用）。
     * 战斗/逃跑/社交状态下不打断，交由反射层或社交引擎处理。
     */
    public void tickWork(BVillager bv) {
        Villager self = bv.entity();
        if (self == null || self.isDead()) {
            return;
        }
        long milkCutoff = System.currentTimeMillis() - WORK_INTERVAL_MS * 4;
        milkCooldown.entrySet().removeIf(entry -> entry.getValue() < milkCutoff);
        VillagerState st = bv.state();
        // 紧急和社交状态不打断，交由反射层/社交引擎处理。
        if (st == VillagerState.COMBAT || st == VillagerState.FLEEING
                || st == VillagerState.SOCIALIZING || st == VillagerState.TRADING) {
            return;
        }
        Profession prof = bv.profession();
        if (!MilitaryTask.isMilitary(prof) && handleDailyNeeds(bv, self)) {
            return;
        }
        // 工作任务节流
        long now = System.currentTimeMillis();
        if (now - bv.lastWorkTask() < WORK_INTERVAL_MS) {
            return;
        }
        bv.lastWorkTask(now);

        if (prof != Profession.DOCTOR && seekDoctor(bv, self)) {
            return;
        }
        if (joinVillageActivity(bv, self, prof)) {
            return;
        }
        if (manageContainer(bv, self, prof)) {
            return;
        }
        if (MilitaryTask.isMilitary(prof)) {
            militaryTask.execute(bv);
            return;
        }
        switch (prof) {
            case FARMER -> farmerCycle(bv, self);
            case DOCTOR -> doctorCycle(bv, self);
            case FISHERMAN -> fishermanCycle(bv, self);
            case ENCHANTER -> enchanterCycle(bv, self);
            case BLACKSMITH -> blacksmithCycle(bv, self);
            case MINER -> minerCycle(bv, self);
            case CHEF -> crafterCycle(bv, self);
            case BUTCHER -> butcherCycle(bv, self);
            case MERCHANT -> merchantCycle(bv, self);
            case BUILDER -> builderCycle(bv, self);
            case KING -> kingCycle(bv, self);
            default -> civilianCycle(bv, self);
        }
    }

    private boolean handleDailyNeeds(BVillager bv, LivingEntity self) {
        if (!(self instanceof Villager villager)) {
            return false;
        }
        World world = self.getWorld();
        long time = world.getTime();
        boolean night = time >= NIGHT_START_TICKS && time <= NIGHT_END_TICKS;
        boolean shelterWeather = world.hasStorm();
        if (!night && !shelterWeather) {
            if (bv.state() == VillagerState.RESTING) {
                if (villager.isSleeping()) {
                    villager.wakeup();
                }
                bv.state(VillagerState.IDLE);
            }
            eatIfNeeded(villager);
            return false;
        }

        Block bed = scanFor(world, self.getLocation(), block -> block.getType().name().endsWith("_BED"));
        bv.state(VillagerState.RESTING);
        if (bed != null) {
            if (withinReach(self.getLocation(), bed.getLocation())) {
                if (night && !villager.isSleeping()) {
                    villager.sleep(bed.getLocation());
                }
            } else {
                MovementHelper.moveToward(self, bed.getLocation(), WORK_SPEED);
            }
            return true;
        }
        if (shelterWeather) {
            Location shelter = findShelter(world, self.getLocation());
            if (shelter != null) {
                MovementHelper.moveToward(self, shelter, WORK_SPEED);
            }
        }
        return true;
    }

    private void eatIfNeeded(Villager villager) {
        AttributeInstance maxHealth = maxHealthAttribute(villager);
        if (maxHealth == null || villager.getHealth() >= maxHealth.getValue() * 0.75) {
            return;
        }
        for (ItemStack stack : villager.getInventory().getContents()) {
            if (stack == null || stack.getAmount() <= 0 || !isFood(stack.getType())) {
                continue;
            }
            stack.setAmount(stack.getAmount() - 1);
            villager.setHealth(Math.min(maxHealth.getValue(), villager.getHealth() + maxHealth.getValue() * 0.1));
            return;
        }
    }

    private boolean isFood(Material material) {
        return switch (material) {
            case BREAD, CARROT, POTATO, BAKED_POTATO, BEETROOT,
                    COOKED_BEEF, COOKED_PORKCHOP, COOKED_CHICKEN, COOKED_MUTTON,
                    COOKED_COD, COOKED_SALMON, APPLE -> true;
            default -> false;
        };
    }

    private Location findShelter(World world, Location origin) {
        for (int dx = -OP_RADIUS; dx <= OP_RADIUS; dx++) {
            for (int dz = -OP_RADIUS; dz <= OP_RADIUS; dz++) {
                if (!sameChunk(origin, origin.getBlockX() + dx, origin.getBlockZ() + dz)) {
                    continue;
                }
                Block feet = world.getBlockAt(origin.getBlockX() + dx, origin.getBlockY(), origin.getBlockZ() + dz);
                if (!feet.isPassable() || !feet.getRelative(org.bukkit.block.BlockFace.UP).isPassable()) {
                    continue;
                }
                for (int dy = 2; dy <= 5; dy++) {
                    if (feet.getRelative(0, dy, 0).getType().isSolid()) {
                        return feet.getLocation();
                    }
                }
            }
        }
        return null;
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
        Block harvestTarget = scanFor(world, origin, ProfessionTaskEngine::isMatureCrop);
        if (harvestTarget != null) {
            if (withinReach(origin, harvestTarget.getLocation())) {
                blocks.breakAt(bv, harvestTarget.getLocation());
            } else {
                MovementHelper.moveToward(self, harvestTarget.getLocation(), WORK_SPEED);
            }
            return;
        }
        // 2. 播种：寻找空耕地（FARMLAND 上方为 AIR）
        Block plantTarget = scanFor(world, origin, ProfessionTaskEngine::isEmptyFarmland);
        if (plantTarget != null) {
            Location cropLoc = plantTarget.getRelative(org.bukkit.block.BlockFace.UP).getLocation();
            if (withinReach(origin, cropLoc)) {
                if (self instanceof Villager villager) {
                    SeedChoice seed = findSeed(villager);
                    if (seed != null) {
                        blocks.placeAt(bv, cropLoc, seed.crop(),
                                () -> removeOne(villager, seed.seed()));
                    }
                }
            } else {
                MovementHelper.moveToward(self, cropLoc, WORK_SPEED);
            }
            return;
        }
        // 3. 开垦：寻找水源附近的草地/泥土，翻耕为耕地
        // D6 修复：先扫水源再在其附近找可开垦地块，避免对每个草地块做 81 次 nearWater 读取。
        Block tillTarget = findTillableNearWater(world, origin);
        if (tillTarget != null) {
            if (withinReach(origin, tillTarget.getLocation())) {
                blocks.placeAt(bv, tillTarget.getLocation(), Material.FARMLAND);
            } else {
                MovementHelper.moveToward(self, tillTarget.getLocation(), WORK_SPEED);
            }
            return;
        }
        // 4. 田间打理：在田间区域巡视，发现并走向已有作物
        gatherFarmGoods(self);
        wanderInVillage(bv, self, 0.35);
    }

    private void gatherFarmGoods(LivingEntity self) {
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (nearby instanceof Sheep sheep && !sheep.isDead() && !sheep.isSheared()) {
                Material wool = woolFor(sheep.getColor());
                self.getWorld().dropItemNaturally(sheep.getLocation(), new ItemStack(wool, 1));
                sheep.setSheared(true);
                return;
            }
            if (nearby instanceof Animals animal && animal.getType().name().equals("COW") && !animal.isDead()
                    && self instanceof Villager villager) {
                long now = System.currentTimeMillis();
                long last = milkCooldown.getOrDefault(animal.getUniqueId(), 0L);
                if (now - last >= WORK_INTERVAL_MS && removeBucket(villager)) {
                    milkCooldown.put(animal.getUniqueId(), now);
                    giveItem(villager, new ItemStack(Material.MILK_BUCKET));
                    return;
                }
            }
        }
    }

    private static Material woolFor(DyeColor color) {
        if (color == null) {
            return Material.WHITE_WOOL;
        }
        return switch (color) {
            case BLACK -> Material.BLACK_WOOL;
            case BLUE -> Material.BLUE_WOOL;
            case BROWN -> Material.BROWN_WOOL;
            case CYAN -> Material.CYAN_WOOL;
            case GRAY -> Material.GRAY_WOOL;
            case GREEN -> Material.GREEN_WOOL;
            case LIGHT_BLUE -> Material.LIGHT_BLUE_WOOL;
            case LIGHT_GRAY -> Material.LIGHT_GRAY_WOOL;
            case LIME -> Material.LIME_WOOL;
            case MAGENTA -> Material.MAGENTA_WOOL;
            case ORANGE -> Material.ORANGE_WOOL;
            case PINK -> Material.PINK_WOOL;
            case PURPLE -> Material.PURPLE_WOOL;
            case RED -> Material.RED_WOOL;
            case YELLOW -> Material.YELLOW_WOOL;
            default -> Material.WHITE_WOOL;
        };
    }

    private boolean seekDoctor(BVillager bv, LivingEntity self) {
        AttributeInstance maxHealth = maxHealthAttribute(self);
        if (maxHealth == null || self.getHealth() > maxHealth.getValue() * 0.4) {
            return false;
        }
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS * 2, OP_RADIUS, OP_RADIUS * 2)) {
            if (!(nearby instanceof Villager doctor) || doctor.isDead()) {
                continue;
            }
            BVillager doctorBv = BV.villagers() == null ? null
                    : BV.villagers().get(doctor.getUniqueId().toString()).orElse(null);
            if (doctorBv == null || doctorBv.profession() != Profession.DOCTOR) {
                continue;
            }
            MovementHelper.moveToward(self, doctor.getLocation(), WORK_SPEED);
            bv.state(VillagerState.WORKING);
            return true;
        }
        return false;
    }

    private void doctorCycle(BVillager bv, LivingEntity self) {
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (!(nearby instanceof Villager villager) || villager == self || villager.isDead()) {
                continue;
            }
            AttributeInstance maxHealth = maxHealthAttribute(villager);
            if (maxHealth == null || villager.getHealth() > maxHealth.getValue() * 0.8
                    || villager.getHealth() >= maxHealth.getValue()) {
                continue;
            }
            double d = self.getLocation().distanceSquared(villager.getLocation());
            if (d < best) {
                best = d;
                target = villager;
            }
        }
        if (target == null) {
            return;
        }
        bv.state(VillagerState.WORKING);
        if (withinReach(self.getLocation(), target.getLocation())) {
            LivingEntity healed = target;
            BV.scheduler().runForEntity(healed, () -> {
                if (!healed.isDead()) {
                    AttributeInstance maxHealth = maxHealthAttribute(healed);
                    if (maxHealth == null) {
                        return;
                    }
                    double max = maxHealth.getValue();
                    healed.setHealth(Math.min(max, healed.getHealth() + Math.max(0.5, max * 0.05)));
                    healed.getWorld().spawnParticle(Particle.HEART, healed.getLocation().add(0, 1.5, 0), 1);
                }
            }, null);
        } else {
            MovementHelper.moveToward(self, target.getLocation(), WORK_SPEED);
        }
    }

    private void fishermanCycle(BVillager bv, LivingEntity self) {
        Location water = findWater(self);
        if (water == null) {
            wanderInVillage(bv, self, WORK_SPEED);
            return;
        }
        bv.state(VillagerState.WORKING);
        if (withinReach(self.getLocation(), water)) {
            if (self.getEquipment() != null && self.getEquipment().getItemInMainHand().getType() != Material.FISHING_ROD) {
                self.getEquipment().setItemInMainHand(new ItemStack(Material.FISHING_ROD));
            }
            // FishingHook 与 PlayerFishEvent 仅支持玩家操作者，Villager 不伪造玩家钓鱼事件。
            self.swingMainHand();
            if (self instanceof Villager villager && ThreadLocalRandom.current().nextDouble() < 0.35) {
                Material catchType = switch (ThreadLocalRandom.current().nextInt(10)) {
                    case 0 -> Material.SALMON;
                    case 1 -> Material.PUFFERFISH;
                    default -> Material.COD;
                };
                giveItem(villager, new ItemStack(catchType));
            }
        } else {
            MovementHelper.moveToward(self, water, WORK_SPEED);
        }
    }

    private boolean removeBucket(Villager villager) {
        for (ItemStack stack : villager.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.BUCKET && stack.getAmount() > 0) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private Location findWater(LivingEntity self) {
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world == null) {
            return null;
        }
        Block water = scanFor(world, origin, b -> b.getType() == Material.WATER);
        return water == null ? null : water.getLocation();
    }

    private void enchanterCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.WORKING);
        ItemStack item = self.getEquipment() == null ? null : self.getEquipment().getItemInMainHand();
        if (item != null && item.getType() != Material.AIR && item.getItemMeta() instanceof Damageable damageable
                && damageable.hasDamage()) {
            item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
            self.getWorld().spawnParticle(Particle.ENCHANT, self.getLocation().add(0, 1, 0), 5);
        }
    }

    private boolean consumeIronIngot(LivingEntity self) {
        if (!(self instanceof Villager villager)) {
            return false;
        }
        ItemStack[] contents = villager.getInventory().getContents();
        for (ItemStack stack : contents) {
            if (stack != null && stack.getType() == Material.IRON_INGOT && stack.getAmount() > 0) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private void blacksmithCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.WORKING);
        if (repairVillagerEquipment(bv, self)) {
            return;
        }
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (nearby instanceof IronGolem golem && !golem.isDead()) {
                AttributeInstance maxHealth = maxHealthAttribute(golem);
                if (maxHealth == null || golem.getHealth() >= maxHealth.getValue()) {
                    continue;
                }
                if (withinReach(self.getLocation(), golem.getLocation()) && consumeIronIngot(self)) {
                    BV.scheduler().runForEntity(golem, () -> {
                        AttributeInstance repairedMaxHealth = maxHealthAttribute(golem);
                        if (repairedMaxHealth == null) {
                            return;
                        }
                        double max = repairedMaxHealth.getValue();
                        golem.setHealth(Math.min(max, golem.getHealth() + 2.0));
                        golem.getWorld().spawnParticle(Particle.CRIT, golem.getLocation().add(0, 1, 0), 2);
                    }, null);
                } else {
                    MovementHelper.moveToward(self, golem.getLocation(), WORK_SPEED);
                }
                return;
            }
        }
    }

    private boolean repairVillagerEquipment(BVillager blacksmith, LivingEntity self) {
        double repairAmount = Math.max(0.0, BV.config().raw().getDouble(
                "gameplay.equipment.blacksmith-repair-per-ingot", 15.0));
        if (repairAmount <= 0.0 || BV.villagers() == null) {
            return false;
        }
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (!(nearby instanceof Villager villager) || villager == self || villager.isDead()
                    || !org.bukkit.Bukkit.isOwnedByCurrentRegion(villager)) {
                continue;
            }
            BVillager target = BV.villagers().get(villager.getUniqueId().toString()).orElse(null);
            if (target == null || target.villageId() != blacksmith.villageId()
                    || dev.bettervillagers.profession.EquipmentDurability.currentValue(villager) >= 100.0) {
                continue;
            }
            if (!withinReach(self.getLocation(), villager.getLocation())) {
                MovementHelper.moveToward(self, villager.getLocation(), WORK_SPEED);
                return true;
            }
            if (!consumeIronIngot(self)) {
                return false;
            }
            dev.bettervillagers.profession.EquipmentDurability.repair(
                    villager, target.professionData(), repairAmount);
            villager.getWorld().spawnParticle(
                    Particle.CRIT, villager.getLocation().add(0, 1, 0), 3);
            return true;
        }
        return false;
    }

    /** 判断方块是否为可收获的成熟作物。 */
    private static boolean isMatureCrop(Block b) {
        Material m = b.getType();
        return (m == Material.WHEAT || m == Material.CARROTS
                || m == Material.POTATOES || m == Material.BEETROOTS)
                && b.getBlockData() instanceof Ageable age
                && age.getAge() >= age.getMaximumAge();
    }

    /** 判断方块是否为可播种的空耕地（FARMLAND 上方为 AIR）。 */
    private static boolean isEmptyFarmland(Block b) {
        if (b.getType() != Material.FARMLAND) {
            return false;
        }
        return b.getRelative(org.bukkit.block.BlockFace.UP).getType() == Material.AIR;
    }

    /** 判断方块是否可开垦为耕地（草地/泥土且上方为空气）。 */
    private static AttributeInstance maxHealthAttribute(LivingEntity entity) {
        try {
            Attribute attr = RegistryAccess.registryAccess()
                    .getRegistry(RegistryKey.ATTRIBUTE)
                    .get(NamespacedKey.minecraft("max_health"));
            return attr == null ? null : entity.getAttribute(attr);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private boolean isTillable(Block b) {
        Material m = b.getType();
        return (m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT)
                && b.getRelative(org.bukkit.block.BlockFace.UP).getType() == Material.AIR;
    }

    /**
     * D6 优化：先扫水源再在其附近找可开垦地块。
     * <p>
     * 原实现 {@code scanFor(..., b -> isTillable(world,b))} 会对每个草地块再触发
     * 9×9=81 次水源扫描，最坏 ~49000 次方块读取。改为先以 O(n²) 找水源，
     * 再仅在水方块附近 9×9 局部找可开垦地块，避免重复扫描水源。
     */
    private Block findTillableNearWater(World world, Location origin) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int dx = -OP_RADIUS; dx <= OP_RADIUS; dx++) {
            for (int dz = -OP_RADIUS; dz <= OP_RADIUS; dz++) {
                if (!sameChunk(origin, ox + dx, oz + dz)) {
                    continue;
                }
                if (world.getBlockAt(ox + dx, oy, oz + dz).getType() != Material.WATER) {
                    continue;
                }
                // 水源附近局部寻找可开垦地块（贴合原版耕地湿润半径）
                for (int lx = -WATER_SEARCH_RADIUS; lx <= WATER_SEARCH_RADIUS; lx++) {
                    for (int lz = -WATER_SEARCH_RADIUS; lz <= WATER_SEARCH_RADIUS; lz++) {
                        if (!sameChunk(origin, ox + dx + lx, oz + dz + lz)) {
                            continue;
                        }
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

    /** Selects a real seed/food item already held by the farmer. */
    private SeedChoice findSeed(Villager villager) {
        List<SeedChoice> available = new ArrayList<>();
        SeedChoice[] choices = {
                new SeedChoice(Material.WHEAT_SEEDS, Material.WHEAT),
                new SeedChoice(Material.CARROT, Material.CARROTS),
                new SeedChoice(Material.POTATO, Material.POTATOES),
                new SeedChoice(Material.BEETROOT_SEEDS, Material.BEETROOTS)
        };
        for (SeedChoice choice : choices) {
            if (villager.getInventory().contains(choice.seed())) {
                available.add(choice);
            }
        }
        return available.isEmpty() ? null
                : available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }

    private record SeedChoice(Material seed, Material crop) {
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
        Block ore = scanFor(world, origin, b -> {
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
        Block station = scanFor(world, origin, b ->
                b.getType() == Material.CRAFTING_TABLE
                        || b.getType() == Material.SMOKER
                        || b.getType() == Material.FURNACE);
        if (station != null) {
            if (withinReach(origin, station.getLocation())) {
                if (blocksInteractAndCook(bv, self, station)) {
                    self.getWorld().spawnParticle(Particle.SMOKE, station.getLocation().add(0.5, 1, 0.5), 3);
                }
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
        int breedingFloor = Math.max(2, BV.config().raw().getInt(
                "gameplay.butcher.minimum-adults-per-species", 2));
        Map<EntityType, List<Animals>> adultsBySpecies = new java.util.EnumMap<>(EntityType.class);
        for (Entity nearby : self.getNearbyEntities(OP_RADIUS, OP_RADIUS, OP_RADIUS)) {
            if (nearby instanceof Animals animal && isLivestock(animal.getType())
                    && !animal.isDead() && animal.isAdult()) {
                adultsBySpecies.computeIfAbsent(animal.getType(), ignored -> new ArrayList<>()).add(animal);
            }
        }
        Animals target = adultsBySpecies.values().stream()
                .filter(group -> group.size() > breedingFloor)
                .flatMap(List::stream)
                .filter(ProfessionTaskEngine::isButcherCandidate)
                // Prefer animals currently on breeding cooldown while preserving a breeding pair.
                .min(Comparator.comparing(Animals::canBreed)
                        .thenComparingDouble(animal -> animal.getLocation()
                                .distanceSquared(self.getLocation())))
                .orElse(null);
        if (target != null) {
            if (withinReach(self.getLocation(), target.getLocation())) {
                self.swingMainHand();
                target.damage(Math.max(1.0, target.getHealth()), self);
            } else {
                MovementHelper.moveToward(self, target.getLocation(), WORK_SPEED);
            }
            return;
        }
        wanderInVillage(bv, self, WORK_SPEED);
    }

    private static boolean isLivestock(EntityType type) {
        return EnumSet.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN).contains(type);
    }

    private static boolean isButcherCandidate(Animals animal) {
        return animal.customName() == null
                && (!(animal instanceof org.bukkit.entity.Tameable tameable) || !tameable.isTamed())
                && animal.getLoveModeTicks() <= 0;
    }

    private boolean blocksInteractAndCook(BVillager bv, LivingEntity self, Block station) {
        if (!(self instanceof Villager villager)) {
            return false;
        }
        Material[][] recipes = {
                {Material.BEEF, Material.COOKED_BEEF},
                {Material.PORKCHOP, Material.COOKED_PORKCHOP},
                {Material.CHICKEN, Material.COOKED_CHICKEN},
                {Material.MUTTON, Material.COOKED_MUTTON},
                {Material.COD, Material.COOKED_COD},
                {Material.SALMON, Material.COOKED_SALMON},
                {Material.POTATO, Material.BAKED_POTATO}
        };
        for (Material[] recipe : recipes) {
            if (contains(villager, recipe[0])
                    && blocks.interactAt(bv, station.getLocation(), station.getType())
                    && removeOne(villager, recipe[0])) {
                giveItem(villager, new ItemStack(recipe[1]));
                return true;
            }
        }
        return false;
    }

    private boolean contains(Villager villager, Material material) {
        return villager.getInventory().contains(material);
    }

    private boolean removeOne(Villager villager, Material material) {
        for (ItemStack stack : villager.getInventory().getContents()) {
            if (stack != null && stack.getType() == material && stack.getAmount() > 0) {
                stack.setAmount(stack.getAmount() - 1);
                return true;
            }
        }
        return false;
    }

    private void giveItem(Villager villager, ItemStack item) {
        villager.getInventory().addItem(item).values().forEach(leftover ->
                villager.getWorld().dropItemNaturally(villager.getLocation(), leftover));
    }

    /** Retrieves missing work inputs or deposits role outputs into a nearby live container. */
    private boolean manageContainer(BVillager bv, Villager villager, Profession profession) {
        if (!ContainerPolicy.supports(profession)) {
            return false;
        }
        Inventory villagerInventory = villager.getInventory();
        boolean needsInput = (profession == Profession.FARMER || profession == Profession.CHEF)
                && !containsMatching(villagerInventory, material -> ContainerPolicy.input(profession, material));
        int minimumOccupied = Math.clamp(BV.config().raw().getInt(
                "gameplay.container.minimum-occupied-slots", 6), 1, villagerInventory.getSize());
        boolean needsDeposit = occupiedSlots(villagerInventory) >= minimumOccupied
                && containsMatching(villagerInventory,
                material -> ContainerPolicy.output(profession, material));
        if (!needsInput && !needsDeposit) {
            return false;
        }

        Location origin = villager.getLocation();
        Block target = scanFor(villager.getWorld(), origin, block ->
                ContainerPolicy.containerMaterial(block.getType())
                        && block.getState() instanceof Container
                        && (BV.regions() == null || !BV.regions().isProtected(block.getLocation())));
        if (target == null) {
            return false;
        }
        bv.state(VillagerState.WORKING);
        if (!withinReach(origin, target.getLocation())) {
            MovementHelper.moveToward(villager, target.getLocation(), WORK_SPEED);
            return true;
        }

        int maxStacks = Math.clamp(BV.config().raw().getInt(
                "gameplay.container.max-stacks-per-operation", 2), 1, 8);
        final boolean retrieve = needsInput;
        return blocks.accessContainer(bv, target.getLocation(), container -> retrieve
                ? transferMatching(container, villagerInventory,
                material -> ContainerPolicy.input(profession, material), maxStacks)
                : transferMatching(villagerInventory, container,
                material -> ContainerPolicy.output(profession, material), maxStacks));
    }

    private boolean joinVillageActivity(BVillager bv, LivingEntity self, Profession profession) {
        if (BV.activities() == null || BV.activities().activeFor(bv.villageId(), profession) == null) {
            return false;
        }
        bv.state(VillagerState.WORKING);
        moveToVillageCenter(bv, self);
        return true;
    }

    private static boolean transferMatching(Inventory source, Inventory destination,
                                            java.util.function.Predicate<Material> filter,
                                            int maxStacks) {
        boolean changed = false;
        int movedStacks = 0;
        for (int slot = 0; slot < source.getSize() && movedStacks < maxStacks; slot++) {
            ItemStack stack = source.getItem(slot);
            if (stack == null || stack.getAmount() <= 0 || !filter.test(stack.getType())) {
                continue;
            }
            int originalAmount = stack.getAmount();
            ItemStack offered = stack.clone();
            int left = destination.addItem(offered).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            int moved = originalAmount - left;
            if (moved <= 0) {
                continue;
            }
            int remaining = originalAmount - moved;
            if (remaining <= 0) {
                source.setItem(slot, null);
            } else {
                stack.setAmount(remaining);
                source.setItem(slot, stack);
            }
            changed = true;
            movedStacks++;
        }
        return changed;
    }

    private static boolean containsMatching(Inventory inventory,
                                            java.util.function.Predicate<Material> filter) {
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getAmount() > 0 && filter.test(stack.getType())) {
                return true;
            }
        }
        return false;
    }

    private static int occupiedSlots(Inventory inventory) {
        int occupied = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getAmount() > 0 && !stack.getType().isAir()) {
                occupied++;
            }
        }
        return occupied;
    }

    // ==================== 商人：前往市集交易 ====================

    private void merchantCycle(BVillager bv, LivingEntity self) {
        // 商人聚集到村庄中心区域（市集），实际交易由自动交易系统调度
        bv.state(VillagerState.WORKING);
        moveToVillageCenter(bv, self);
    }

    // ==================== 建筑师：协助建造 ====================

    private void builderCycle(BVillager bv, LivingEntity self) {
        bv.state(VillagerState.WORKING);
        // 建筑师前往村庄中心附近待命/采集建材，实际施工由战略层建造系统调度
        Location origin = self.getLocation();
        World world = origin.getWorld();
        if (world != null) {
            // 采集建材（白名单内的木头/石头类方块）
            Block mat = scanFor(world, origin, b -> {
                String name = b.getType().name();
                return name.endsWith("_LOG") || name.endsWith("_PLANKS")
                        || b.getType() == Material.COBBLESTONE;
            });
            if (mat != null && !withinReach(origin, mat.getLocation())) {
                MovementHelper.moveToward(self, mat.getLocation(), WORK_SPEED);
                return;
            }
        }
        moveToVillageCenter(bv, self);
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
    private Block scanFor(World world, Location origin, java.util.function.Predicate<Block> test) {
        int ox = origin.getBlockX();
        int oy = origin.getBlockY();
        int oz = origin.getBlockZ();
        for (int dx = -OP_RADIUS; dx <= OP_RADIUS; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -OP_RADIUS; dz <= OP_RADIUS; dz++) {
                    if (!sameChunk(origin, ox + dx, oz + dz)) {
                        continue;
                    }
                    Block b = world.getBlockAt(ox + dx, oy + dy, oz + dz);
                    if (test.test(b)) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    private static boolean sameChunk(Location origin, int blockX, int blockZ) {
        return (origin.getBlockX() >> 4) == (blockX >> 4)
                && (origin.getBlockZ() >> 4) == (blockZ >> 4);
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
    private void moveToVillageCenter(BVillager bv, LivingEntity self) {
        Village v = BV.villages() != null ? BV.villages().get(bv.villageId()).orElse(null) : null;
        if (v == null) {
            wanderInVillage(bv, self, WORK_SPEED);
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
        MovementHelper.moveToward(self, target, WORK_SPEED);
    }
}
