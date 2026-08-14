package dev.bettervillagers.storage;

import dev.bettervillagers.BV;
import dev.bettervillagers.villager.VillagerData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** 村民数据仓储（规范 8.x）。所有方法为阻塞 IO，调用方须在异步线程执行。 */
public final class VillagerRepository {

    private final DataSourceProvider provider;
    private final String upsertSql;

    public VillagerRepository(DataSourceProvider provider, boolean mysql) {
        this.provider = provider;
        this.upsertSql = mysql ? MYSQL_UPSERT : SQLITE_UPSERT;
    }

    // SQLite：ON CONFLICT；MySQL：ON DUPLICATE KEY（alias 形式，兼容 8.0.19+）
    private static final String SQLITE_UPSERT = """
            INSERT INTO villagers (uuid,name,profession,health,attack,defense,
                location_world,location_x,location_y,location_z,village_id,ai_enabled,ai_memory,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(uuid) DO UPDATE SET
                name=excluded.name, profession=excluded.profession, health=excluded.health,
                attack=excluded.attack, defense=excluded.defense,
                location_world=excluded.location_world, location_x=excluded.location_x,
                location_y=excluded.location_y, location_z=excluded.location_z,
                village_id=excluded.village_id, ai_enabled=excluded.ai_enabled,
                ai_memory=excluded.ai_memory, updated_at=excluded.updated_at
            """;

    private static final String MYSQL_UPSERT = """
            INSERT INTO villagers (uuid,name,profession,health,attack,defense,
                location_world,location_x,location_y,location_z,village_id,ai_enabled,ai_memory,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) AS v
            ON DUPLICATE KEY UPDATE
                name=v.name, profession=v.profession, health=v.health,
                attack=v.attack, defense=v.defense,
                location_world=v.location_world, location_x=v.location_x,
                location_y=v.location_y, location_z=v.location_z,
                village_id=v.village_id, ai_enabled=v.ai_enabled,
                ai_memory=v.ai_memory, updated_at=v.updated_at
            """;

    public void upsert(VillagerData v) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement(upsertSql)) {
            bind(ps, v);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-save")
                    .replace("{uuid}", v.uuid()).replace("{error}", e.getMessage()), e);
        }
    }

    /**
     * Persists a first registration and its village population/king bookkeeping in one transaction.
     *
     * @return whether this villager owns the persisted king slot after the transaction
     */
    public boolean insertNewAndAttach(VillagerData v, boolean claimKing) {
        try (Connection c = provider.connection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement exists = c.prepareStatement("SELECT 1 FROM villagers WHERE uuid=?");
                 PreparedStatement upsert = c.prepareStatement(upsertSql);
                 PreparedStatement updateVillage = c.prepareStatement("""
                         UPDATE villages
                         SET population=population+1,
                             king_uuid=CASE
                                 WHEN ? AND (king_uuid IS NULL OR king_uuid='') THEN ?
                                 ELSE king_uuid
                             END
                         WHERE id=?
                         """);
                 PreparedStatement queryKing = c.prepareStatement("SELECT king_uuid FROM villages WHERE id=?")) {
                exists.setString(1, v.uuid());
                boolean alreadyAttached;
                try (ResultSet row = exists.executeQuery()) {
                    alreadyAttached = row.next();
                }
                bind(upsert, v);
                upsert.executeUpdate();
                if (!alreadyAttached) {
                    updateVillage.setBoolean(1, claimKing);
                    updateVillage.setString(2, v.uuid());
                    updateVillage.setInt(3, v.villageId());
                    if (updateVillage.executeUpdate() != 1) {
                        throw new SQLException("Village does not exist: " + v.villageId());
                    }
                }
                queryKing.setInt(1, v.villageId());
                boolean ownsKingSlot;
                try (ResultSet rs = queryKing.executeQuery()) {
                    ownsKingSlot = rs.next() && v.uuid().equals(rs.getString(1));
                }
                c.commit();
                return ownsKingSlot;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-save")
                    .replace("{uuid}", v.uuid()).replace("{error}", e.getMessage()), e);
        }
    }

    /** 批量保存（单连接单事务，规范 4.5 WAL 风格批量提交）。分批提交避免单事务过大。 */
    public void upsertAll(List<VillagerData> list) {
        if (list.isEmpty()) {
            return;
        }
        int batchSize = 100;
        try (Connection c = provider.connection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(upsertSql)) {
                int count = 0;
                for (VillagerData v : list) {
                    bind(ps, v);
                    ps.addBatch();
                    if (++count % batchSize == 0) {
                        ps.executeBatch();
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-batch-save").replace("{error}", e.getMessage()), e);
        }
    }

    private void bind(PreparedStatement ps, VillagerData v) throws SQLException {
        ps.setString(1, v.uuid());
        ps.setString(2, v.name());
        ps.setString(3, v.profession());
        ps.setDouble(4, v.health());
        ps.setDouble(5, v.attack());
        ps.setString(6, v.defense());
        ps.setString(7, v.locationWorld());
        ps.setDouble(8, v.locationX());
        ps.setDouble(9, v.locationY());
        ps.setDouble(10, v.locationZ());
        ps.setInt(11, v.villageId());
        ps.setBoolean(12, v.aiEnabled());
        ps.setString(13, v.aiMemoryJson());
        ps.setLong(14, v.createdAt());
        ps.setLong(15, v.updatedAt());
    }

    public Optional<VillagerData> find(String uuid) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM villagers WHERE uuid=?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-query")
                    .replace("{uuid}", uuid).replace("{error}", e.getMessage()), e);
        }
    }

    public void delete(String uuid) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM villagers WHERE uuid=?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-delete")
                    .replace("{uuid}", uuid).replace("{error}", e.getMessage()), e);
        }
    }

    /**
     * Permanently removes a dead villager and updates its village bookkeeping atomically.
     */
    public void deletePermanently(String uuid, int villageId) {
        try (Connection c = provider.connection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement delete = c.prepareStatement("DELETE FROM villagers WHERE uuid=?");
                 PreparedStatement updateVillage = c.prepareStatement("""
                         UPDATE villages
                         SET population=CASE WHEN population>0 THEN population-1 ELSE 0 END,
                             king_uuid=CASE WHEN king_uuid=? THEN NULL ELSE king_uuid END
                         WHERE id=?
                         """)) {
                delete.setString(1, uuid);
                boolean removed = delete.executeUpdate() == 1;
                if (removed && villageId > 0) {
                    updateVillage.setString(1, uuid);
                    updateVillage.setInt(2, villageId);
                    updateVillage.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.villager-delete")
                    .replace("{uuid}", uuid).replace("{error}", e.getMessage()), e);
        }
    }

    private VillagerData map(ResultSet rs) throws SQLException {
        return new VillagerData(
                rs.getString("uuid"),
                rs.getString("name"),
                rs.getString("profession"),
                rs.getDouble("health"),
                rs.getDouble("attack"),
                rs.getString("defense"),
                rs.getString("location_world"),
                rs.getDouble("location_x"),
                rs.getDouble("location_y"),
                rs.getDouble("location_z"),
                rs.getInt("village_id"),
                rs.getBoolean("ai_enabled"),
                rs.getString("ai_memory"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }
}
