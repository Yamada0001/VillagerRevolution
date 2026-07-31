package dev.bettervillagers.storage;

import dev.bettervillagers.BV;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 建筑布局仓储。所有方法为阻塞 IO，调用方必须在异步线程执行。 */
public final class BuildLayoutRepository {

    private final DataSourceProvider provider;
    public BuildLayoutRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public void replaceVillage(int villageId, List<BuildLayoutRecord> records) {
        try (Connection connection = provider.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM build_layouts WHERE village_id=?")) {
                    delete.setInt(1, villageId);
                    delete.executeUpdate();
                }
                if (!records.isEmpty()) {
                    String sql = "INSERT INTO build_layouts (village_id,world,build_type,template_id,center_x,center_y,center_z,"
                            + "min_x,max_x,min_z,max_z,rotation,mirror,cluster_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
                    try (PreparedStatement insert = connection.prepareStatement(sql)) {
                        for (BuildLayoutRecord record : records) {
                            insert.setInt(1, record.villageId());
                            insert.setString(2, record.world());
                            insert.setString(3, record.buildType());
                            insert.setString(4, record.templateId());
                            insert.setInt(5, record.centerX());
                            insert.setInt(6, record.centerY());
                            insert.setInt(7, record.centerZ());
                            insert.setInt(8, record.minX());
                            insert.setInt(9, record.maxX());
                            insert.setInt(10, record.minZ());
                            insert.setInt(11, record.maxZ());
                            insert.setString(12, record.rotation());
                            insert.setString(13, record.mirror());
                            insert.setString(14, record.clusterId() == null ? "" : record.clusterId());
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.schema-init").replace("{error}", e.getMessage()), e);
        }
    }

    public List<BuildLayoutRecord> findAll() {
        List<BuildLayoutRecord> result = new ArrayList<>();
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement("SELECT * FROM build_layouts");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                result.add(new BuildLayoutRecord(
                        rows.getInt("village_id"), rows.getString("world"), rows.getString("build_type"),
                        rows.getString("template_id"), rows.getInt("center_x"), rows.getInt("center_y"),
                        rows.getInt("center_z"), rows.getInt("min_x"), rows.getInt("max_x"),
                        rows.getInt("min_z"), rows.getInt("max_z"), rows.getString("rotation"), rows.getString("mirror"),
                        rows.getString("cluster_id")));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.schema-init").replace("{error}", e.getMessage()), e);
        }
    }

    public void deleteVillage(int villageId) {
        try (Connection connection = provider.connection();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM build_layouts WHERE village_id=?")) {
            delete.setInt(1, villageId);
            delete.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.schema-init").replace("{error}", e.getMessage()), e);
        }
    }
}
