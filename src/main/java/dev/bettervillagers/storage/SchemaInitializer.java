package dev.bettervillagers.storage;

import dev.bettervillagers.BV;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 数据库 schema 初始化（规范 8.2 三张表）。
 * 幂等执行：所有建表语句均使用 {@code IF NOT EXISTS}；自增主键按方言适配 SQLite/MySQL。
 */
public final class SchemaInitializer {

    private SchemaInitializer() {
    }

    public static void init(ConnectionFactory factory, boolean mysql) {
        String autoVillage = mysql ? "INT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String autoRegion = autoVillage;
        try (Connection c = factory.get();
             Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS villagers (
                        uuid VARCHAR(36) PRIMARY KEY,
                        name VARCHAR(64),
                        profession VARCHAR(32),
                        health DOUBLE,
                        attack DOUBLE,
                        defense VARCHAR(16),
                        location_world VARCHAR(64),
                        location_x DOUBLE,
                        location_y DOUBLE,
                        location_z DOUBLE,
                        village_id INT,
                        ai_enabled BOOLEAN DEFAULT TRUE,
                        ai_memory TEXT,
                        created_at BIGINT,
                        updated_at BIGINT
                    )""");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS villages (
                        id %s,
                        world VARCHAR(64),
                        center_x INT,
                        center_y INT,
                        center_z INT,
                        radius INT,
                        king_uuid VARCHAR(36),
                        population INT,
                        name VARCHAR(64),
                        created_at BIGINT
                    )""".formatted(autoVillage));
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS protected_regions (
                        id %s,
                        name VARCHAR(64) UNIQUE,
                        world VARCHAR(64),
                        min_x INT, min_y INT, min_z INT,
                        max_x INT, max_y INT, max_z INT,
                        owner VARCHAR(36),
                        created_at BIGINT
                    )""".formatted(autoRegion));
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_villagers_village ON villagers(village_id)");
            // 兼容旧库：villages 表增加 name 列（已存在则忽略，规范 8.2）
            addColumnIfMissing(st, "villages", "name", "VARCHAR(64)");
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.schema-init").replace("{error}", e.getMessage()), e);
        }
    }

    /** 幂等加列：若列已存在则忽略（兼容 SQLite/MySQL 老库升级，规范 8.2）。 */
    private static void addColumnIfMissing(Statement st, String table, String column, String type) {
        try {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
            // 列已存在或其它可忽略错误（duplicate column 等）
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection get() throws SQLException;
    }
}
