package dev.bettervillagers.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 道路开放端口仓储。 */
public final class RoadPortRepository {
    private final DataSourceProvider provider;
    public RoadPortRepository(DataSourceProvider provider) { this.provider = provider; }
    public void replaceVillage(int villageId, List<RoadPortRecord> ports) {
        try (Connection c = provider.connection()) { c.setAutoCommit(false); try (PreparedStatement d=c.prepareStatement("DELETE FROM road_ports WHERE village_id=?")) { d.setInt(1,villageId); d.executeUpdate(); }
            try (PreparedStatement i=c.prepareStatement("INSERT INTO road_ports (village_id,world,x,y,z,direction) VALUES (?,?,?,?,?,?)")) { for (RoadPortRecord p:ports) { i.setInt(1,p.villageId());i.setString(2,p.world());i.setInt(3,p.x());i.setInt(4,p.y());i.setInt(5,p.z());i.setString(6,p.direction());i.addBatch(); } i.executeBatch(); } c.commit();
        } catch(SQLException e) { throw new RuntimeException(e); }
    }
    public List<RoadPortRecord> findAll() { List<RoadPortRecord> ports=new ArrayList<>(); try(Connection c=provider.connection();PreparedStatement s=c.prepareStatement("SELECT village_id,world,x,y,z,direction FROM road_ports");ResultSet r=s.executeQuery()){while(r.next())ports.add(new RoadPortRecord(r.getInt(1),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),r.getString(6)));return ports;}catch(SQLException e){throw new RuntimeException(e);} }
    public void deleteVillage(int id) { replaceVillage(id,List.of()); }
}
