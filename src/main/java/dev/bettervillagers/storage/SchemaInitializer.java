package dev.bettervillagers.storage;

import dev.bettervillagers.BV;

import java.sql.Connection;
import java.sql.ResultSet;
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
                    )""".formatted(autoVillage));
            createIndexIfMissing(c, "villagers", "idx_villagers_village", "village_id");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS build_layouts (village_id INT, world VARCHAR(64), build_type VARCHAR(32), template_id VARCHAR(128), center_x INT, center_y INT, center_z INT, min_x INT, max_x INT, min_z INT, max_z INT, rotation VARCHAR(32), mirror VARCHAR(32), cluster_id VARCHAR(128) NOT NULL DEFAULT '')");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS road_ports (village_id INT, world VARCHAR(64), x INT, y INT, z INT, direction VARCHAR(16))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS construction_jobs (job_id VARCHAR(36) PRIMARY KEY, state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS construction_changes (job_id VARCHAR(36) NOT NULL, seq INT NOT NULL, world VARCHAR(64) NOT NULL, x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, old_block_data TEXT NOT NULL, PRIMARY KEY(job_id,seq))");
            createIndexIfMissing(c, "construction_changes", "idx_construction_changes_job", "job_id");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS trade_journal (trade_id VARCHAR(36) PRIMARY KEY, buyer_uuid VARCHAR(36) NOT NULL, seller_uuid VARCHAR(36) NOT NULL, ingredients TEXT NOT NULL, result_item TEXT NOT NULL, state VARCHAR(16) NOT NULL, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            createIndexIfMissing(c, "trade_journal", "idx_trade_buyer_state", "buyer_uuid,state");
            createIndexIfMissing(c, "trade_journal", "idx_trade_seller_state", "seller_uuid,state");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS villager_relations (subject_a VARCHAR(36) NOT NULL, subject_b VARCHAR(36) NOT NULL, affinity INT NOT NULL, last_breed_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY(subject_a,subject_b))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS relation_events (event_id VARCHAR(64) PRIMARY KEY, subject_a VARCHAR(36) NOT NULL, subject_b VARCHAR(36) NOT NULL, created_at BIGINT NOT NULL)");
            createIndexIfMissing(c, "relation_events", "idx_relation_events_created", "created_at");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS village_diplomacy (village_a INT NOT NULL, village_b INT NOT NULL, relation VARCHAR(16) NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY(village_a,village_b))");
            // 兼容旧库：villages 表增加 name 列（已存在则忽略，规范 8.2）
            addColumnIfMissing(c, "villages", "name", "VARCHAR(64)");
            addColumnIfMissing(c, "build_layouts", "cluster_id", "VARCHAR(128) NOT NULL DEFAULT ''");
            st.executeUpdate("UPDATE build_layouts SET cluster_id='' WHERE cluster_id IS NULL");
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.schema-init").replace("{error}", e.getMessage()), e);
        }
    }

    /** 幂等加列：若列已存在则忽略（兼容 SQLite/MySQL 老库升级，规范 8.2）。 */
    private static void addColumnIfMissing(Connection connection, String table, String column, String type)
            throws SQLException {
        if (columnExists(connection, table, column)) {
            return;
        }
        try (Statement alter = connection.createStatement()) {
            alter.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        String[] tablePatterns = {table, table.toUpperCase(java.util.Locale.ROOT),
                table.toLowerCase(java.util.Locale.ROOT)};
        for (String tablePattern : tablePatterns) {
            try (ResultSet columns = connection.getMetaData().getColumns(
                    connection.getCatalog(), null, tablePattern, "%")) {
                while (columns.next()) {
                    if (column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void createIndexIfMissing(Connection connection, String table,
                                             String indexName, String columns) throws SQLException {
        String[] tablePatterns = {table, table.toUpperCase(java.util.Locale.ROOT),
                table.toLowerCase(java.util.Locale.ROOT)};
        for (String tablePattern : tablePatterns) {
            try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                    connection.getCatalog(), null, tablePattern, false, false)) {
                while (indexes.next()) {
                    if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) {
                        return;
                    }
                }
            }
        }
        try (Statement create = connection.createStatement()) {
            create.executeUpdate("CREATE INDEX " + indexName + " ON " + table + "(" + columns + ")");
        }
    }

    @FunctionalInterface
    public interface ConnectionFactory {
        Connection get() throws SQLException;
    }
}
