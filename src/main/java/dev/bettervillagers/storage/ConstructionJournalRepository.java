package dev.bettervillagers.storage;

import dev.bettervillagers.BV;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Blocking repository for crash-recoverable construction journals. */
public final class ConstructionJournalRepository {

    private final DataSourceProvider provider;

    public ConstructionJournalRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public void create(String jobId) {
        String sql = "INSERT INTO construction_jobs (job_id,state,created_at) VALUES (?,?,?)";
        try (Connection connection = provider.connection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, jobId);
            insert.setString(2, "PREPARED");
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void append(ConstructionChangeRecord change) {
        String sql = "INSERT INTO construction_changes "
                + "(job_id,seq,world,x,y,z,old_block_data) VALUES (?,?,?,?,?,?,?)";
        try (Connection connection = provider.connection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, change.jobId());
            insert.setInt(2, change.sequence());
            insert.setString(3, change.world());
            insert.setInt(4, change.x());
            insert.setInt(5, change.y());
            insert.setInt(6, change.z());
            insert.setString(7, change.oldBlockData());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public List<String> findPreparedJobs() {
        List<String> jobs = new ArrayList<>();
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT job_id FROM construction_jobs WHERE state='PREPARED' ORDER BY created_at");
             ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                jobs.add(rows.getString(1));
            }
            return jobs;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public List<ConstructionChangeRecord> findChanges(String jobId) {
        List<ConstructionChangeRecord> changes = new ArrayList<>();
        String sql = "SELECT job_id,seq,world,x,y,z,old_block_data FROM construction_changes "
                + "WHERE job_id=? ORDER BY seq";
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, jobId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    changes.add(new ConstructionChangeRecord(
                            rows.getString("job_id"), rows.getInt("seq"), rows.getString("world"),
                            rows.getInt("x"), rows.getInt("y"), rows.getInt("z"),
                            rows.getString("old_block_data")));
                }
            }
            return changes;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void complete(String jobId) {
        delete(jobId);
    }

    /** Atomically publishes the completed layout/road snapshot and removes its rollback journal. */
    public void completeWithLayout(String jobId, int villageId,
                                   List<BuildLayoutRecord> layouts, List<RoadPortRecord> ports) {
        try (Connection connection = provider.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement deleteLayouts = connection.prepareStatement(
                        "DELETE FROM build_layouts WHERE village_id=?")) {
                    deleteLayouts.setInt(1, villageId);
                    deleteLayouts.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO build_layouts (village_id,world,build_type,template_id,center_x,center_y,center_z,min_x,max_x,min_z,max_z,rotation,mirror,cluster_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (BuildLayoutRecord record : layouts) {
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
                try (PreparedStatement deletePorts = connection.prepareStatement(
                        "DELETE FROM road_ports WHERE village_id=?")) {
                    deletePorts.setInt(1, villageId);
                    deletePorts.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO road_ports (village_id,world,x,y,z,direction) VALUES (?,?,?,?,?,?)")) {
                    for (RoadPortRecord port : ports) {
                        insert.setInt(1, port.villageId());
                        insert.setString(2, port.world());
                        insert.setInt(3, port.x());
                        insert.setInt(4, port.y());
                        insert.setInt(5, port.z());
                        insert.setString(6, port.direction());
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                deleteWithin(connection, "construction_changes", "job_id", jobId);
                deleteWithin(connection, "construction_jobs", "job_id", jobId);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static void deleteWithin(Connection connection, String table, String column, String value)
            throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + "=?")) {
            delete.setString(1, value);
            delete.executeUpdate();
        }
    }

    public void rolledBack(String jobId) {
        delete(jobId);
    }

    private void delete(String jobId) {
        try (Connection connection = provider.connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement changes = connection.prepareStatement(
                        "DELETE FROM construction_changes WHERE job_id=?")) {
                    changes.setString(1, jobId);
                    changes.executeUpdate();
                }
                try (PreparedStatement job = connection.prepareStatement(
                        "DELETE FROM construction_jobs WHERE job_id=?")) {
                    job.setString(1, jobId);
                    job.executeUpdate();
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
            throw failure(e);
        }
    }

    private static RuntimeException failure(SQLException e) {
        return new RuntimeException(BV.messages().raw("errors.schema-init")
                .replace("{error}", e.getMessage()), e);
    }
}
