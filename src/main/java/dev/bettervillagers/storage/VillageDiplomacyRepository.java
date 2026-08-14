package dev.bettervillagers.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Blocking repository for durable village-to-village diplomacy state. */
public final class VillageDiplomacyRepository {

    private final DataSourceProvider provider;

    public VillageDiplomacyRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public List<VillageDiplomacyRecord> findAll() {
        List<VillageDiplomacyRecord> result = new ArrayList<>();
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT village_a,village_b,relation,updated_at FROM village_diplomacy");
             ResultSet row = query.executeQuery()) {
            while (row.next()) {
                result.add(new VillageDiplomacyRecord(
                        row.getInt("village_a"), row.getInt("village_b"),
                        row.getString("relation"), row.getLong("updated_at")));
            }
            return List.copyOf(result);
        } catch (SQLException error) {
            throw new RuntimeException("Unable to load village diplomacy: " + error.getMessage(), error);
        }
    }

    /** Update-first upsert works on both SQLite and MySQL without dialect-specific SQL. */
    public synchronized void upsert(int first, int second, String relation, long updatedAt) {
        Pair pair = Pair.of(first, second);
        try (Connection connection = provider.connection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement update = connection.prepareStatement("""
                        UPDATE village_diplomacy SET relation=?,updated_at=?
                        WHERE village_a=? AND village_b=?
                        """)) {
                    update.setString(1, relation);
                    update.setLong(2, updatedAt);
                    update.setInt(3, pair.first());
                    update.setInt(4, pair.second());
                    changed = update.executeUpdate();
                }
                if (changed == 0) {
                    try (PreparedStatement insert = connection.prepareStatement("""
                            INSERT INTO village_diplomacy
                            (village_a,village_b,relation,updated_at) VALUES (?,?,?,?)
                            """)) {
                        insert.setInt(1, pair.first());
                        insert.setInt(2, pair.second());
                        insert.setString(3, relation);
                        insert.setLong(4, updatedAt);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException error) {
            throw new RuntimeException("Unable to persist village diplomacy: " + error.getMessage(), error);
        }
    }

    private record Pair(int first, int second) {
        private static Pair of(int first, int second) {
            if (first < 0 || second < 0 || first == second) {
                throw new IllegalArgumentException("Diplomacy requires two distinct village ids");
            }
            return first < second ? new Pair(first, second) : new Pair(second, first);
        }
    }
}
