package dev.bettervillagers.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.bettervillagers.BV;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 数据源提供者（规范 8.x：SQLite 默认 / MySQL 可选，统一 HikariCP 连接池）。
 * <p>
 * SQLite 启用 WAL 模式（规范 4.5 数据安全）；MySQL 优先复用服务端已加载的驱动，
 * 缺失时给出明确错误。
 */
public final class DataSourceProvider {

    private final Plugin plugin;
    private final HikariDataSource dataSource;

    public DataSourceProvider(Plugin plugin, ConfigurationSection storageCfg) {
        this.plugin = plugin;
        String type = storageCfg.getString("type", "sqlite").toLowerCase();
        this.dataSource = type.equals("mysql") ? buildMysql(storageCfg) : buildSqlite(storageCfg);
    }

    private HikariDataSource buildSqlite(ConfigurationSection cfg) {
        String file = cfg.getString("sqlite-file", "bettervillagers.db");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("无法创建插件数据目录: " + dataFolder);
        }
        File dbFile = new File(dataFolder, file);

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("BetterVillagers-SQLite");
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hc.setMaximumPoolSize(2); // SQLite 写串行，池不宜过大
        hc.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");
        hc.addDataSourceProperty("foreign_keys", "true");
        hc.setLeakDetectionThreshold(30_000);
        return new HikariDataSource(hc);
    }

    private HikariDataSource buildMysql(ConfigurationSection cfg) {
        ConfigurationSection m = cfg.getConfigurationSection("mysql");
        if (m == null) {
            throw new IllegalArgumentException("缺少 storage.mysql 配置段");
        }
        String host = m.getString("host", "localhost");
        int port = m.getInt("port", 3306);
        String database = m.getString("database", "bettervillagers");
        String user = m.getString("username", "root");
        String pass = m.getString("password", "");
        String params = m.getString("params", "useSSL=false&useUnicode=true&characterEncoding=utf8");

        HikariConfig hc = new HikariConfig();
        hc.setPoolName("BetterVillagers-MySQL");
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + params);
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(10);
        hc.setLeakDetectionThreshold(30_000);
        hc.setConnectionInitSql("SET NAMES utf8mb4");
        // 服务端若已捆绑 MySQL/MariaDB 驱动则复用，否则抛出可读错误
        hc.setDriverClassName(resolveMysqlDriver());
        return new HikariDataSource(hc);
    }

    private String resolveMysqlDriver() {
        for (String name : new String[]{"com.mysql.cj.jdbc.Driver", "org.mariadb.jdbc.Driver"}) {
            try {
                Class.forName(name);
                return name;
            } catch (ClassNotFoundException ignored) {
                // 继续尝试下一个驱动
            }
        }
        plugin.getLogger().severe(BV.messages().raw("errors.driver-missing"));
        throw new IllegalStateException(BV.messages().raw("errors.missing-mysql-driver"));
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
