package dev.bettervillagers.storage;

import dev.bettervillagers.BV;
import dev.bettervillagers.village.Village;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;

/** 村庄仓储（规范 8.x）。阻塞 IO，须在异步线程调用。 */
public final class VillageRepository {

    private final DataSourceProvider provider;

    public VillageRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public int insert(Village v) {
        String sql = "INSERT INTO villages (world,center_x,center_y,center_z,radius,king_uuid,population,name,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, v.world());
            ps.setInt(2, v.centerX());
            ps.setInt(3, v.centerY());
            ps.setInt(4, v.centerZ());
            ps.setInt(5, v.radius());
            ps.setString(6, v.kingUuid());
            ps.setInt(7, v.population());
            ps.setString(8, v.name());
            ps.setLong(9, System.currentTimeMillis());
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village insert affected no row");
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-insert").replace("{error}", e.getMessage()), e);
        }
    }

    public void updateKing(int id, String kingUuid) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE villages SET king_uuid=? WHERE id=?")) {
            ps.setString(1, kingUuid);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village does not exist: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-king-update").replace("{error}", e.getMessage()), e);
        }
    }

    /** Clears the king slot only when it still belongs to the specified villager. */
    public void clearKingIfOwned(int id, String kingUuid) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE villages SET king_uuid=NULL WHERE id=? AND king_uuid=?")) {
            ps.setInt(1, id);
            ps.setString(2, kingUuid);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-king-update")
                    .replace("{error}", e.getMessage()), e);
        }
    }

    public void updatePopulation(int id, int population) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE villages SET population=? WHERE id=?")) {
            ps.setInt(1, population);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village does not exist: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-population-update").replace("{error}", e.getMessage()), e);
        }
    }

    public void incrementPopulation(int id) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE villages SET population=population+1 WHERE id=?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village does not exist: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-population-update").replace("{error}", e.getMessage()), e);
        }
    }

    /** 更新村庄名字（问题4：AI 命名后持久化）。 */
    public void updateName(int id, String name) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE villages SET name=? WHERE id=?")) {
            ps.setString(1, name);
            ps.setInt(2, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village does not exist: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-name-update").replace("{error}", e.getMessage()), e);
        }
    }

    public List<Village> findAll() {
        List<Village> out = new ArrayList<>();
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM villages");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-query").replace("{error}", e.getMessage()), e);
        }
        return out;
    }

    public void delete(int id) {
        try (Connection c = provider.connection();
            PreparedStatement ps = c.prepareStatement("DELETE FROM villages WHERE id=?")) {
            ps.setInt(1, id);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Village does not exist: " + id);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-delete").replace("{error}", e.getMessage()), e);
        }
    }

    /** 原子提交村庄合并，避免更新目标后删除源村庄失败形成半合并状态。 */
    public void merge(int fromId, int toId, int population, String kingUuid, int radius) {
        try (Connection c = provider.connection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement update = c.prepareStatement(
                     "UPDATE villages SET population=?,king_uuid=?,radius=? WHERE id=?");
                 PreparedStatement moveVillagers = c.prepareStatement(
                         "UPDATE villagers SET village_id=? WHERE village_id=?");
                 PreparedStatement moveLayouts = c.prepareStatement(
                         "UPDATE build_layouts SET village_id=? WHERE village_id=?");
                 PreparedStatement moveRoadPorts = c.prepareStatement(
                         "UPDATE road_ports SET village_id=? WHERE village_id=?");
                 PreparedStatement deleteDiplomacy = c.prepareStatement(
                         "DELETE FROM village_diplomacy WHERE village_a=? OR village_b=?");
                 PreparedStatement delete = c.prepareStatement("DELETE FROM villages WHERE id=?")) {
                update.setInt(1, population);
                update.setString(2, kingUuid);
                update.setInt(3, radius);
                update.setInt(4, toId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Merge target village does not exist: " + toId);
                }
                moveVillagers.setInt(1, toId);
                moveVillagers.setInt(2, fromId);
                moveVillagers.executeUpdate();
                moveLayouts.setInt(1, toId);
                moveLayouts.setInt(2, fromId);
                moveLayouts.executeUpdate();
                moveRoadPorts.setInt(1, toId);
                moveRoadPorts.setInt(2, fromId);
                moveRoadPorts.executeUpdate();
                deduplicateLayouts(c, toId);
                deduplicateRoadPorts(c, toId);
                deleteDiplomacy.setInt(1, fromId);
                deleteDiplomacy.setInt(2, fromId);
                deleteDiplomacy.executeUpdate();
                delete.setInt(1, fromId);
                if (delete.executeUpdate() != 1) {
                    throw new SQLException("Merge source village does not exist: " + fromId);
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-merge")
                    .replace("{from}", String.valueOf(fromId))
                    .replace("{to}", String.valueOf(toId))
                    .replace("{error}", e.getMessage()), e);
        }
    }

    private static void deduplicateLayouts(Connection connection, int villageId) throws SQLException {
        LinkedHashSet<BuildLayoutRecord> unique = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM build_layouts WHERE village_id=?")) {
            query.setInt(1, villageId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    unique.add(new BuildLayoutRecord(villageId, rows.getString("world"),
                            rows.getString("build_type"), rows.getString("template_id"),
                            rows.getInt("center_x"), rows.getInt("center_y"), rows.getInt("center_z"),
                            rows.getInt("min_x"), rows.getInt("max_x"), rows.getInt("min_z"),
                            rows.getInt("max_z"), rows.getString("rotation"), rows.getString("mirror"),
                            rows.getString("cluster_id")));
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM build_layouts WHERE village_id=?")) {
            delete.setInt(1, villageId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO build_layouts (village_id,world,build_type,template_id,center_x,center_y,center_z,min_x,max_x,min_z,max_z,rotation,mirror,cluster_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (BuildLayoutRecord record : unique) {
                insert.setInt(1, villageId);
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

    private static void deduplicateRoadPorts(Connection connection, int villageId) throws SQLException {
        LinkedHashSet<RoadPortRecord> unique = new LinkedHashSet<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT world,x,y,z,direction FROM road_ports WHERE village_id=?")) {
            query.setInt(1, villageId);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    unique.add(new RoadPortRecord(villageId, rows.getString("world"), rows.getInt("x"),
                            rows.getInt("y"), rows.getInt("z"), rows.getString("direction")));
                }
            }
        }
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM road_ports WHERE village_id=?")) {
            delete.setInt(1, villageId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO road_ports (village_id,world,x,y,z,direction) VALUES (?,?,?,?,?,?)")) {
            for (RoadPortRecord port : unique) {
                insert.setInt(1, villageId);
                insert.setString(2, port.world());
                insert.setInt(3, port.x());
                insert.setInt(4, port.y());
                insert.setInt(5, port.z());
                insert.setString(6, port.direction());
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private Village map(ResultSet rs) throws SQLException {
        String name = null;
        try {
            name = rs.getString("name");
        } catch (SQLException ignored) {
            // 兼容无 name 列的旧库
        }
        return new Village(
                rs.getInt("id"),
                rs.getString("world"),
                rs.getInt("center_x"),
                rs.getInt("center_y"),
                rs.getInt("center_z"),
                rs.getInt("radius"),
                rs.getString("king_uuid"),
                rs.getInt("population"),
                name
        );
    }
}
