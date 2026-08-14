package dev.bettervillagers.storage;

/** Durable autonomous-trade state used to reconcile entity inventories after crashes. */
public record TradeJournalRecord(
        String tradeId,
        String buyerUuid,
        String sellerUuid,
        String ingredients,
        String resultItem,
        State state,
        long createdAt,
        long updatedAt
) {
    public enum State {
        PREPARED,
        DEBITED,
        COMMITTED,
        REFUNDED,
        SETTLED
    }
}
