package dev.bettervillagers.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Persistent pair affinity with idempotent source-event application. */
public final class RelationRepository {

    private final DataSourceProvider provider;
    private final boolean mysql;

    public RelationRepository(DataSourceProvider provider, boolean mysql) {
        this.provider = provider;
        this.mysql = mysql;
    }

    public synchronized RelationUpdate recordInteraction(String eventId, String first, String second,
                                                          int gain, int breedingThreshold,
                                                          long breedingCooldownMillis, long now,
                                                          boolean allowBreeding) {
        Pair pair = Pair.of(first, second);
        try (Connection connection = provider.connection()) {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                RelationUpdate existingEvent = findAppliedEvent(connection, eventId, pair);
                if (existingEvent != null) {
                    connection.rollback();
                    return existingEvent;
                }
                int affinity = 0;
                long lastBreedAt = 0L;
                String selectSql = "SELECT affinity,last_breed_at FROM villager_relations "
                        + "WHERE subject_a=? AND subject_b=?" + (mysql ? " FOR UPDATE" : "");
                boolean exists;
                try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                    select.setString(1, pair.first());
                    select.setString(2, pair.second());
                    try (ResultSet row = select.executeQuery()) {
                        exists = row.next();
                        if (exists) {
                            affinity = row.getInt(1);
                            lastBreedAt = row.getLong(2);
                        }
                    }
                }
                int updatedAffinity = Math.clamp(affinity + Math.max(0, gain), 0, 100);
                boolean breedingReady = allowBreeding && updatedAffinity >= breedingThreshold
                        && now - lastBreedAt >= Math.max(0L, breedingCooldownMillis);
                long updatedBreedAt = breedingReady ? now : lastBreedAt;
                if (exists) {
                    update(connection, pair, updatedAffinity, updatedBreedAt, now);
                } else {
                    insert(connection, pair, updatedAffinity, updatedBreedAt, now);
                }
                try (PreparedStatement event = connection.prepareStatement("""
                        INSERT INTO relation_events (event_id,subject_a,subject_b,created_at)
                        VALUES (?,?,?,?)
                        """)) {
                    event.setString(1, eventId);
                    event.setString(2, pair.first());
                    event.setString(3, pair.second());
                    event.setLong(4, now);
                    event.executeUpdate();
                }
                connection.commit();
                return new RelationUpdate(updatedAffinity, breedingReady);
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException error) {
            throw new RuntimeException("Unable to persist villager relation: " + error.getMessage(), error);
        }
    }

    public int affinity(String first, String second) {
        Pair pair = Pair.of(first, second);
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT affinity FROM villager_relations WHERE subject_a=? AND subject_b=?")) {
            query.setString(1, pair.first());
            query.setString(2, pair.second());
            try (ResultSet row = query.executeQuery()) {
                return row.next() ? row.getInt(1) : 0;
            }
        } catch (SQLException error) {
            throw new RuntimeException("Unable to query villager relation: " + error.getMessage(), error);
        }
    }

    public void deleteEventsBefore(long cutoff) {
        try (Connection connection = provider.connection();
             PreparedStatement delete = connection.prepareStatement(
                     """
                     DELETE FROM relation_events
                     WHERE created_at<?
                       AND NOT EXISTS (
                         SELECT 1 FROM trade_journal
                         WHERE trade_id=relation_events.event_id
                           AND state NOT IN ('SETTLED','REFUNDED')
                       )
                     """)) {
            delete.setLong(1, cutoff);
            delete.executeUpdate();
        } catch (SQLException error) {
            throw new RuntimeException("Unable to prune relation events: " + error.getMessage(), error);
        }
    }

    private void update(Connection connection, Pair pair, int affinity, long lastBreedAt, long now)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("""
                UPDATE villager_relations SET affinity=?,last_breed_at=?,updated_at=?
                WHERE subject_a=? AND subject_b=?
                """)) {
            update.setInt(1, affinity);
            update.setLong(2, lastBreedAt);
            update.setLong(3, now);
            update.setString(4, pair.first());
            update.setString(5, pair.second());
            update.executeUpdate();
        }
    }

    private void insert(Connection connection, Pair pair, int affinity, long lastBreedAt, long now)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO villager_relations
                (subject_a,subject_b,affinity,last_breed_at,updated_at) VALUES (?,?,?,?,?)
                """)) {
            insert.setString(1, pair.first());
            insert.setString(2, pair.second());
            insert.setInt(3, affinity);
            insert.setLong(4, lastBreedAt);
            insert.setLong(5, now);
            insert.executeUpdate();
        }
    }

    private RelationUpdate findAppliedEvent(Connection connection, String eventId, Pair pair) throws SQLException {
        try (PreparedStatement event = connection.prepareStatement(
                "SELECT 1 FROM relation_events WHERE event_id=?")) {
            event.setString(1, eventId);
            try (ResultSet row = event.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
            }
        }
        try (PreparedStatement relation = connection.prepareStatement(
                "SELECT affinity FROM villager_relations WHERE subject_a=? AND subject_b=?")) {
            relation.setString(1, pair.first());
            relation.setString(2, pair.second());
            try (ResultSet row = relation.executeQuery()) {
                return new RelationUpdate(row.next() ? row.getInt(1) : 0, false);
            }
        }
    }

    private record Pair(String first, String second) {
        private static Pair of(String first, String second) {
            if (first == null || second == null || first.equals(second)) {
                throw new IllegalArgumentException("Relation subjects must be distinct");
            }
            return first.compareTo(second) <= 0 ? new Pair(first, second) : new Pair(second, first);
        }
    }
}
