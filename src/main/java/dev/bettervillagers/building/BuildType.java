package dev.bettervillagers.building;

/**
 * 建造类型与村庄发展四阶段映射（要想富先修路）。
 * <ol>
 *   <li>ROADS — 道路网络</li>
 *   <li>STREETSCAPE — 路灯/行道树/街边设施</li>
 *   <li>HOUSING — 存量房屋优化升级</li>
 *   <li>DEFENSE_LANDSCAPE — 环境景观 + 防御城墙闭环</li>
 * </ol>
 */
public enum BuildType {
    ROAD(DevPhase.ROADS, 8, 12, 16),
    STREETSCAPE(DevPhase.STREETSCAPE, 6, 10, 12),
    HOUSE(DevPhase.HOUSING, 4, 14, 32),
    UPGRADE_HOUSE(DevPhase.HOUSING, 3, 16, 8),
    FARM(DevPhase.HOUSING, 3, 12, 6),
    TRADE_FAIR(DevPhase.HOUSING, 2, 16, 4),
    WALL(DevPhase.DEFENSE_LANDSCAPE, 4, 18, 8),
    LANDSCAPE(DevPhase.DEFENSE_LANDSCAPE, 3, 14, 6),
    DESTROY(DevPhase.DEFENSE_LANDSCAPE, 1, 8, 2),
    DEFENSE_DRILL(DevPhase.DEFENSE_LANDSCAPE, 0, 0, 99),
    HARVEST_FESTIVAL(DevPhase.HOUSING, 0, 0, 99),
    BUILDING_CONTEST(DevPhase.HOUSING, 0, 0, 99);

    public enum DevPhase {
        ROADS(1),
        STREETSCAPE(2),
        HOUSING(3),
        DEFENSE_LANDSCAPE(4);

        private final int order;

        DevPhase(int order) {
            this.order = order;
        }

        int order() {
            return order;
        }
    }

    private final DevPhase phase;
    /** 同类型最小间距（格）。 */
    private final int minSpacing;
    /** 网格缓存单元大小（格），同格同类型不重复。 */
    private final int gridSize;
    /** 单村同类型上限。 */
    private final int maxPerVillage;

    BuildType(DevPhase phase, int minSpacing, int gridSize, int maxPerVillage) {
        this.phase = phase;
        this.minSpacing = minSpacing;
        this.gridSize = gridSize;
        this.maxPerVillage = maxPerVillage;
    }

    public DevPhase phase() {
        return phase;
    }

    int minSpacing() {
        return minSpacing;
    }

    int gridSize() {
        return gridSize;
    }

    int maxPerVillage() {
        return maxPerVillage;
    }

    public boolean physical() {
        return minSpacing > 0;
    }

    public static BuildType fromCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String u = raw.toUpperCase().replace("BUILD_", "").trim();
        return switch (u) {
            case "ROAD", "ROADS" -> ROAD;
            case "STREET", "STREETSCAPE", "STREET_SCENE" -> STREETSCAPE;
            case "HOUSE", "HOUSING" -> HOUSE;
            case "UPGRADE", "UPGRADE_HOUSE", "RENOVATE" -> UPGRADE_HOUSE;
            case "FARM" -> FARM;
            case "FAIR", "TRADE_FAIR" -> TRADE_FAIR;
            case "WALL" -> WALL;
            case "LANDSCAPE", "PARK", "GARDEN" -> LANDSCAPE;
            case "DESTROY" -> DESTROY;
            case "DEFENSE_DRILL", "DEFENSE" -> DEFENSE_DRILL;
            case "HARVEST_FESTIVAL" -> HARVEST_FESTIVAL;
            case "BUILDING_CONTEST" -> BUILDING_CONTEST;
            default -> null;
        };
    }

    public String structureKey() {
        return name().toLowerCase();
    }
}
