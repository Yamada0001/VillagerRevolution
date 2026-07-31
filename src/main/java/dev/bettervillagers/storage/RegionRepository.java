package dev.bettervillagers.storage;

import dev.bettervillagers.BV;
import dev.bettervillagers.redstone.ProtectedRegion;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 生电保护区仓储（规范 8.x）。阻塞 IO，须在异步线程调用。 */
public final class RegionRepository {

    private final DataSourceProvider provider;

    public RegionRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public int insert(ProtectedRegion r) {
        String sql = "INSERT INTO protected_regions (name,world,min_x,min_y,min_z,max_x,max_y,max_z,owner,created_at) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, r);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
            return -1;
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.region-insert").replace("{error}", e.getMessage()), e);
        }
    }

    public void update(ProtectedRegion r) {
        String sql = "UPDATE protected_regions SET name=?,world=?,min_x=?,min_y=?,min_z=?,max_x=?,max_y=?,max_z=?,owner=? WHERE id=?";
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, r.name());
            ps.setString(2, r.world());
            ps.setInt(3, r.minX());
            ps.setInt(4, r.minY());
            ps.setInt(5, r.minZ());
            ps.setInt(6, r.maxX());
            ps.setInt(7, r.maxY());
            ps.setInt(8, r.maxZ());
            ps.setString(9, r.owner());
            ps.setInt(10, r.id());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.region-update").replace("{error}", e.getMessage()), e);
        }
    }

    public void delete(int id) {
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM protected_regions WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.region-delete").replace("{error}", e.getMessage()), e);
        }
    }

    public List<ProtectedRegion> findAll() {
        List<ProtectedRegion> out = new ArrayList<>();
        try (Connection c = provider.connection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM protected_regions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException(BV.messages().raw("errors.region-query").replace("{error}", e.getMessage()), e);
        }
        return out;
    }

    private void bind(PreparedStatement ps, ProtectedRegion r) throws SQLException {
        ps.setString(1, r.name());
        ps.setString(2, r.world());
        ps.setInt(3, r.minX());
        ps.setInt(4, r.minY());
        ps.setInt(5, r.minZ());
        ps.setInt(6, r.maxX());
        ps.setInt(7, r.maxY());
        ps.setInt(8, r.maxZ());
        ps.setString(9, r.owner());
        ps.setLong(10, System.currentTimeMillis());
    }

    private ProtectedRegion map(ResultSet rs) throws SQLException {
        return new ProtectedRegion(
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("world"),
                rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
                rs.getString("owner")
        );
    }
}
