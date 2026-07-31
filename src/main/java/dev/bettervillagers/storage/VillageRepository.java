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
            ps.executeUpdate();
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
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-king-update").replace("{error}", e.getMessage()), e);
        }
    }

    public void updatePopulation(int id, int population) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("UPDATE villages SET population=? WHERE id=?")) {
            ps.setInt(1, population);
            ps.setInt(2, id);
            ps.executeUpdate();
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
            ps.executeUpdate();
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
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.village-delete").replace("{error}", e.getMessage()), e);
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
