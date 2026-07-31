package dev.bettervillagers.building;

import dev.bettervillagers.BV;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 自动发现并加载 resources/structures 与插件数据目录中的结构模板。 */
final class StructureTemplateLibrary {

    enum RoadPiece { STRAIGHT, CORNER, JUNCTION, BRIDGE, END }
    private enum Landmark { FORT, TREEHOUSE }

    private static final List<StructureCluster> CLUSTERS = List.of(
            new StructureCluster("frontier_fort", "landmarks/fort/fort.nbt", List.of(
                    new StructureCluster.Member("landmarks/fort/mini_fort.nbt", 18, 0, true),
                    new StructureCluster.Member("markets/plains/plains_market_1.nbt", 0, 18, false)), 12),
            new StructureCluster("frozen_fort", "landmarks/fort/ice_fort.nbt", List.of(
                    new StructureCluster.Member("housing/snowy/snowy_medium_house_1.nbt", 18, 0, true),
                    new StructureCluster.Member("housing/snowy/snowy_small_house_1.nbt", 0, 18, false)), 10),
            new StructureCluster("jungle_canopy", "landmarks/treehouse/jungle_treehouse.nbt", List.of(
                    new StructureCluster.Member("farms/plains/plains_farm_1.nbt", 16, 0, false)), 8));

    private final Path externalRoot;
    private final StructureNbtReader reader = new StructureNbtReader();
    private final Map<String, StructureTemplate> loaded = new ConcurrentHashMap<>();
    private final Map<BuildType, List<String>> defaults = new EnumMap<>(BuildType.class);
    private final Map<String, Map<BuildType, List<String>>> biomePools = new LinkedHashMap<>();
    private final Map<RoadPiece, List<String>> roads = new EnumMap<>(RoadPiece.class);
    private final Map<Landmark, List<String>> landmarks = new EnumMap<>(Landmark.class);
    private final List<String> bundledPaths;

    StructureTemplateLibrary() {
        externalRoot = BV.plugin().getDataFolder().toPath().resolve("structures");
        bundledPaths = readBundledIndex();
        rebuildPools(discoverPaths());
    }

    void installDefaults() {
        for (String path : bundledPaths) {
            Path target = externalRoot.resolve(path);
            if (Files.exists(target)) {
                continue;
            }
            try (InputStream source = BV.plugin().getResource("structures/" + path)) {
                if (source == null) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            } catch (IOException e) {
                BV.plugin().getLogger().warning("无法释放结构模板 " + path + ": " + e.getMessage());
            }
        }
        rebuildPools(discoverPaths());
    }

    Optional<StructureTemplate> choose(BuildType type, String biome, long seed, int x, int z) {
        if (type == BuildType.ROAD) {
            return chooseRoad(seed, x, z, roadPiece(seed, x, z));
        }
        String group = normalizeBiome(biome);
        Map<BuildType, List<String>> biomePool = biomePools.get(group);
        List<String> candidates = biomePool == null ? defaults.getOrDefault(type, List.of())
                : biomePool.getOrDefault(type, defaults.getOrDefault(type, List.of()));
        return choose(candidates, seed, x, z);
    }

    Optional<StructureTemplate> chooseRoad(long seed, int x, int z, RoadPiece piece) {
        return choose(roads.getOrDefault(piece, List.of()), seed, x, z);
    }

    private RoadPiece roadPiece(long seed, int x, int z) {
        int roll = Math.floorMod((int) (seed ^ (x * 73428767L) ^ (z * 912931L)), 12);
        return switch (roll) {
            case 0 -> RoadPiece.JUNCTION;
            case 1, 2 -> RoadPiece.CORNER;
            case 3 -> RoadPiece.BRIDGE;
            case 4 -> RoadPiece.END;
            default -> RoadPiece.STRAIGHT;
        };
    }

    int assessmentRadius(BuildType type) {
        List<String> candidates = new ArrayList<>(defaults.getOrDefault(type, List.of()));
        biomePools.values().forEach(pool -> candidates.addAll(pool.getOrDefault(type, List.of())));
        int maxSpan = candidates.stream().distinct().map(this::load).filter(java.util.Objects::nonNull)
                .mapToInt(template -> Math.max(template.width(), template.depth())).max()
                .orElse(Math.max(7, type.minSpacing()));
        if (type == BuildType.LANDSCAPE) {
            for (StructureCluster cluster : CLUSTERS) {
                maxSpan = Math.max(maxSpan, clusterSpan(cluster));
            }
        }
        return maxSpan / 2 + 2;
    }

    Optional<StructureCluster> chooseCluster(String biome, long seed, int villageId, int population,
                                             Set<String> completed) {
        String group = normalizeBiome(biome);
        List<StructureCluster> eligible = CLUSTERS.stream()
                .filter(cluster -> completed == null || !completed.contains(cluster.id()))
                .filter(cluster -> population >= cluster.minimumPopulation())
                .filter(cluster -> !"frozen_fort".equals(cluster.id()) || "snowy".equals(group))
                .filter(cluster -> !"jungle_canopy".equals(cluster.id()) || "jungle".equals(group))
                .filter(cluster -> byId(cluster.rootTemplate()).isPresent())
                .toList();
        return eligible.isEmpty() ? Optional.empty()
                : Optional.of(eligible.get(Math.floorMod((int) (seed ^ villageId * 31L), eligible.size())));
    }

    Optional<StructureTemplate> byId(String id) {
        return id == null || id.isBlank() ? Optional.empty() : Optional.ofNullable(loaded.computeIfAbsent(id, this::load));
    }

    private synchronized void rebuildPools(List<String> paths) {
        defaults.clear();
        biomePools.clear();
        roads.clear();
        landmarks.clear();
        loaded.clear();
        for (String path : paths) {
            classify(path.replace('\\', '/'));
        }
        defaults.put(BuildType.HOUSE, pool("housing/plains/"));
        defaults.put(BuildType.UPGRADE_HOUSE, pool("housing/plains/", "medium", "big", "large"));
        defaults.put(BuildType.FARM, pool("farms/plains/"));
        defaults.put(BuildType.TRADE_FAIR, pool("markets/plains/"));
        defaults.put(BuildType.LANDSCAPE, concat(landmarks.get(Landmark.FORT), landmarks.get(Landmark.TREEHOUSE)));
        defaults.put(BuildType.WALL, landmarks.getOrDefault(Landmark.FORT, List.of()));
    }

    private void classify(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nbt")) {
            return;
        }
        if (lower.startsWith("roads/straight/")) {
            add(roads, RoadPiece.STRAIGHT, path);
        } else if (lower.startsWith("roads/corner/")) {
            add(roads, RoadPiece.CORNER, path);
        } else if (lower.startsWith("roads/junction/")) {
            add(roads, RoadPiece.JUNCTION, path);
        } else if (lower.startsWith("roads/bridge/")) {
            add(roads, RoadPiece.BRIDGE, path);
        } else if (lower.startsWith("roads/end/")) {
            add(roads, RoadPiece.END, path);
        } else if (lower.startsWith("landmarks/fort/")) {
            add(landmarks, Landmark.FORT, path);
        } else if (lower.startsWith("landmarks/treehouse/")) {
            add(landmarks, Landmark.TREEHOUSE, path);
        } else if (lower.startsWith("housing/") || lower.startsWith("farms/") || lower.startsWith("markets/")) {
            String[] parts = lower.split("/");
            if (parts.length < 3) {
                return;
            }
            BuildType type = parts[0].equals("housing") ? BuildType.HOUSE
                    : parts[0].equals("farms") ? BuildType.FARM : BuildType.TRADE_FAIR;
            Map<BuildType, List<String>> pool = biomePools.computeIfAbsent(parts[1], ignored -> new EnumMap<>(BuildType.class));
            add(pool, type, path);
            if (type == BuildType.HOUSE && !lower.contains("small")) {
                add(pool, BuildType.UPGRADE_HOUSE, path);
            }
        }
    }

    private List<String> discoverPaths() {
        List<String> paths = new ArrayList<>(bundledPaths);
        if (Files.isDirectory(externalRoot)) {
            try (var walk = Files.walk(externalRoot)) {
                walk.filter(Files::isRegularFile).filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".nbt"))
                        .map(path -> externalRoot.relativize(path).toString().replace('\\', '/')).forEach(paths::add);
            } catch (IOException e) {
                BV.plugin().getLogger().warning("无法扫描外部结构模板: " + e.getMessage());
            }
        }
        return paths.stream().distinct().sorted().toList();
    }

    private List<String> readBundledIndex() {
        try (InputStream input = BV.plugin().getResource("structures/index.txt")) {
            if (input == null) {
                return List.of();
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                return reader.lines().map(line -> line.replace("\uFEFF", "").trim())
                        .filter(line -> !line.isBlank() && line.toLowerCase(Locale.ROOT).endsWith(".nbt"))
                        .distinct().sorted().toList();
            }
        } catch (IOException e) {
            BV.plugin().getLogger().warning("无法读取结构模板索引: " + e.getMessage());
            return List.of();
        }
    }

    private Optional<StructureTemplate> choose(List<String> candidates, long seed, int x, int z) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = new Random(seed ^ (x * 341873128712L) ^ (z * 132897987541L)).nextInt(candidates.size());
        return byId(candidates.get(index));
    }

    private StructureTemplate load(String path) {
        Path external = externalRoot.resolve(path);
        try (InputStream input = Files.exists(external) ? Files.newInputStream(external)
                : BV.plugin().getResource("structures/" + path)) {
            return input == null ? null : reader.read(input, path);
        } catch (IOException | RuntimeException e) {
            BV.plugin().getLogger().warning("无法加载结构模板 " + path + ": " + e.getMessage());
            return null;
        }
    }

    private int clusterSpan(StructureCluster cluster) {
        StructureTemplate root = load(cluster.rootTemplate());
        if (root == null) {
            return 0;
        }
        int minX = -root.width() / 2;
        int maxX = minX + root.width() - 1;
        int minZ = -root.depth() / 2;
        int maxZ = minZ + root.depth() - 1;
        for (StructureCluster.Member member : cluster.members()) {
            StructureTemplate child = load(member.template());
            if (child == null) {
                continue;
            }
            int x = member.offsetX() - child.width() / 2;
            int z = member.offsetZ() - child.depth() / 2;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x + child.width() - 1);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z + child.depth() - 1);
        }
        return Math.max(maxX - minX + 1, maxZ - minZ + 1);
    }

    private List<String> pool(String prefix, String... names) {
        return bundledPaths.stream()
                .filter(path -> path.startsWith(prefix))
                .filter(path -> names.length == 0 || java.util.Arrays.stream(names).anyMatch(path::contains))
                .toList();
    }

    private static <K> void add(Map<K, List<String>> map, K key, String path) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(path);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        if (first != null) {
            result.addAll(first);
        }
        if (second != null) {
            result.addAll(second);
        }
        return List.copyOf(result);
    }

    private static String normalizeBiome(String biome) {
        if (biome == null || biome.isBlank()) {
            return "plains";
        }
        String value = biome.toLowerCase(Locale.ROOT);
        if (value.contains("desert")) {
            return "desert";
        }
        if (value.contains("savanna")) {
            return "savanna";
        }
        if (value.contains("snow") || value.contains("frozen") || value.contains("ice")) {
            return "snowy";
        }
        if (value.contains("jungle")) {
            return "jungle";
        }
        if (value.contains("swamp")) {
            return "swamp";
        }
        if (value.contains("taiga")) {
            return "taiga";
        }
        if (value.contains("cherry")) {
            return "cherry";
        }
        return "plains";
    }
}
