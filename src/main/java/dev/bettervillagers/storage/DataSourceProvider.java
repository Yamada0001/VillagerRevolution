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
 * SQLite 启用 WAL 模式（规范 4.5 数据安全）；MySQL Connector/J 随插件产物打包。
 */
public final class DataSourceProvider {

    private final Plugin plugin;
    private final HikariDataSource dataSource;

    public DataSourceProvider(Plugin plugin, ConfigurationSection storageCfg) {
        this.plugin = plugin;
        String type = storageCfg.getString("type", "sqlite").toLowerCase();
        this.dataSource = type.equals("mysql") ? buildMysql(storageCfg) : buildSqlite(storageCfg);
    }

    /** Package-private test fixture using an explicit JDBC URL. */
    DataSourceProvider(String jdbcUrl) {
        this.plugin = null;
        HikariConfig config = new HikariConfig();
        config.setPoolName("BetterVillagers-Test-" + Integer.toHexString(jdbcUrl.hashCode()));
        config.setJdbcUrl(jdbcUrl);
        config.setMaximumPoolSize(2);
        config.setConnectionInitSql("PRAGMA foreign_keys=ON");
        this.dataSource = new HikariDataSource(config);
    }

    private HikariDataSource buildSqlite(ConfigurationSection cfg) {
        String file = cfg.getString("sqlite-file", "bettervillagers.db");
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.isDirectory() && !dataFolder.mkdirs() && !dataFolder.isDirectory()) {
            throw new IllegalStateException("无法创建插件数据目录: " + dataFolder);
        }
        File dbFile = new File(dataFolder, file);
        return new HikariDataSource(sqliteConfig(dbFile));
    }

    private HikariConfig sqliteConfig(File dbFile) {
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("BetterVillagers-SQLite");
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hc.setDriverClassName(org.sqlite.JDBC.class.getName());
        hc.setMaximumPoolSize(2); // SQLite 写串行，池不宜过大
        hc.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");
        hc.addDataSourceProperty("foreign_keys", "true");
        hc.setLeakDetectionThreshold(30_000);
        return hc;
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
        // Connector/J 随插件打包；仍保留 MariaDB 驱动探测以兼容自定义构建。
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
