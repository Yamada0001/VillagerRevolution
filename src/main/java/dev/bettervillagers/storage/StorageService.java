package dev.bettervillagers.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

/**
 * 存储服务门面（规范 8.x）：统一数据源、schema 初始化与各仓储。
 * <p>
 * 所有仓储方法为阻塞 IO，调用方（如村民管理器、命令）须通过 {@code SchedulerAdapter.runAsync} 调度。
 */
public final class StorageService {

    private final DataSourceProvider dataSource;
    private final VillagerRepository villagers;
    private final VillageRepository villages;
    private final RegionRepository regions;
    private final BuildLayoutRepository buildLayouts;
    private final RoadPortRepository roadPorts;

    public StorageService(Plugin plugin, ConfigurationSection storageCfg) {
        boolean mysql = "mysql".equalsIgnoreCase(storageCfg.getString("type", "sqlite"));
        this.dataSource = new DataSourceProvider(plugin, storageCfg);
        SchemaInitializer.init(dataSource::connection, mysql);
        this.villagers = new VillagerRepository(dataSource, mysql);
        this.villages = new VillageRepository(dataSource);
        this.regions = new RegionRepository(dataSource);
        this.buildLayouts = new BuildLayoutRepository(dataSource);
        this.roadPorts = new RoadPortRepository(dataSource);
    }

    public VillagerRepository villagers() {
        return villagers;
    }

    public VillageRepository villages() {
        return villages;
    }

    public RegionRepository regions() {
        return regions;
    }

    public BuildLayoutRepository buildLayouts() { return buildLayouts; }

    public RoadPortRepository roadPorts() { return roadPorts; }

    public void close() {
        dataSource.close();
    }
}
