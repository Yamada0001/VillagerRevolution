package dev.bettervillagers.storage;

import dev.bettervillagers.BV;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Blocking, idempotent repository for autonomous trade recovery. */
public final class TradeJournalRepository {

    private final DataSourceProvider provider;

    public TradeJournalRepository(DataSourceProvider provider) {
        this.provider = provider;
    }

    public void create(TradeJournalRecord record) {
        String sql = "INSERT INTO trade_journal "
                + "(trade_id,buyer_uuid,seller_uuid,ingredients,result_item,state,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,?,?,?)";
        try (Connection connection = provider.connection();
             PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, record.tradeId());
            insert.setString(2, record.buyerUuid());
            insert.setString(3, record.sellerUuid());
            insert.setString(4, record.ingredients());
            insert.setString(5, record.resultItem());
            insert.setString(6, record.state().name());
            insert.setLong(7, record.createdAt());
            insert.setLong(8, record.updatedAt());
            insert.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void markDebited(String tradeId) {
        transition(tradeId, TradeJournalRecord.State.DEBITED,
                TradeJournalRecord.State.PREPARED);
    }

    public void markCommitted(String tradeId) {
        transition(tradeId, TradeJournalRecord.State.COMMITTED,
                TradeJournalRecord.State.PREPARED, TradeJournalRecord.State.DEBITED);
    }

    public void markRefunded(String tradeId) {
        transition(tradeId, TradeJournalRecord.State.REFUNDED,
                TradeJournalRecord.State.PREPARED, TradeJournalRecord.State.DEBITED);
    }

    public void markSettled(String tradeId) {
        transition(tradeId, TradeJournalRecord.State.SETTLED,
                TradeJournalRecord.State.COMMITTED);
    }

    public List<TradeJournalRecord> findUnresolvedFor(String uuid) {
        List<TradeJournalRecord> result = new ArrayList<>();
        String sql = "SELECT * FROM trade_journal WHERE (buyer_uuid=? OR seller_uuid=?) "
                + "AND state NOT IN ('REFUNDED','SETTLED') ORDER BY created_at";
        try (Connection connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, uuid);
            query.setString(2, uuid);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(read(rows));
                }
            }
            return result;
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    public void deleteTerminalBefore(long cutoff) {
        try (Connection connection = provider.connection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM trade_journal WHERE state IN ('REFUNDED','SETTLED') AND updated_at<?")) {
            delete.setLong(1, cutoff);
            delete.executeUpdate();
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private void transition(String tradeId, TradeJournalRecord.State target,
                            TradeJournalRecord.State... allowed) {
        StringBuilder sql = new StringBuilder(
                "UPDATE trade_journal SET state=?,updated_at=? WHERE trade_id=? AND state IN (");
        for (int i = 0; i < allowed.length; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append('?');
        }
        sql.append(')');
        try (Connection connection = provider.connection();
             PreparedStatement update = connection.prepareStatement(sql.toString())) {
            update.setString(1, target.name());
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, tradeId);
            for (int i = 0; i < allowed.length; i++) {
                update.setString(4 + i, allowed[i].name());
            }
            if (update.executeUpdate() > 0 || stateIs(connection, tradeId, target)) {
                return;
            }
            throw new SQLException("Illegal trade transition for " + tradeId + " to " + target);
        } catch (SQLException e) {
            throw failure(e);
        }
    }

    private static boolean stateIs(Connection connection, String tradeId,
                                   TradeJournalRecord.State expected) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT state FROM trade_journal WHERE trade_id=?")) {
            query.setString(1, tradeId);
            try (ResultSet row = query.executeQuery()) {
                return row.next() && expected.name().equals(row.getString(1));
            }
        }
    }

    private static TradeJournalRecord read(ResultSet rows) throws SQLException {
        return new TradeJournalRecord(
                rows.getString("trade_id"), rows.getString("buyer_uuid"), rows.getString("seller_uuid"),
                rows.getString("ingredients"), rows.getString("result_item"),
                TradeJournalRecord.State.valueOf(rows.getString("state")),
                rows.getLong("created_at"), rows.getLong("updated_at"));
    }

    private static RuntimeException failure(SQLException e) {
        return new RuntimeException(BV.messages().raw("errors.schema-init")
                .replace("{error}", e.getMessage()), e);
    }
}
