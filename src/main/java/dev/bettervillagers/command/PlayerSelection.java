package dev.bettervillagers.command;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家右键选定的村民目标（/bv 相关指令作用对象）。
 */
public final class PlayerSelection {

    private static final Map<UUID, UUID> PLAYER_TO_VILLAGER = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> SELECTING = new ConcurrentHashMap<>();

    private PlayerSelection() {
    }

    public static void begin(UUID playerId) {
        if (playerId != null) {
            SELECTING.put(playerId, true);
            PLAYER_TO_VILLAGER.remove(playerId);
        }
    }

    public static boolean isSelecting(UUID playerId) {
        return playerId != null && SELECTING.getOrDefault(playerId, false);
    }

    public static void select(UUID playerId, UUID villagerId) {
        if (playerId == null || villagerId == null) {
            return;
        }
        PLAYER_TO_VILLAGER.put(playerId, villagerId);
        SELECTING.remove(playerId);
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            PLAYER_TO_VILLAGER.remove(playerId);
            SELECTING.remove(playerId);
        }
    }

    public static Optional<UUID> get(UUID playerId) {
        if (playerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLAYER_TO_VILLAGER.get(playerId));
    }
}
