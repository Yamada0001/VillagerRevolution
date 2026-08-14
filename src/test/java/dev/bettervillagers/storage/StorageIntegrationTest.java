package dev.bettervillagers.storage;

import dev.bettervillagers.village.Village;
import dev.bettervillagers.villager.VillagerData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIntegrationTest {

    @TempDir
    Path temporaryDirectory;

    private DataSourceProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DataSourceProvider("jdbc:sqlite:" + temporaryDirectory.resolve("test.db"));
        SchemaInitializer.init(provider::connection, false);
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    void schemaMigrationIsIdempotentAndCreatesRecoveryTables() throws Exception {
        SchemaInitializer.init(provider::connection, false);

        assertTrue(columnExists("build_layouts", "cluster_id"));
        assertTrue(tableExists("construction_changes"));
        assertTrue(tableExists("trade_journal"));
        assertTrue(tableExists("villager_relations"));
        assertTrue(tableExists("relation_events"));
        assertTrue(tableExists("village_diplomacy"));
    }

    @Test
    void villageMergeMovesEveryDependentTableInOneCommit() throws Exception {
        VillageRepository villages = new VillageRepository(provider);
        int source = villages.insert(new Village(0, "world", 0, 64, 0, 32, null, 2, "Source"));
        int target = villages.insert(new Village(0, "world", 100, 64, 100, 48, null, 3, "Target"));
        VillagerRepository villagers = new VillagerRepository(provider, false);
        villagers.upsert(villager("00000000-0000-0000-0000-000000000101", source));
        BuildLayoutRecord sourceLayout = new BuildLayoutRecord(
                source, "world", "HOUSE", "house", 1, 64, 1,
                0, 2, 0, 2, "NONE", "NONE", "");
        new BuildLayoutRepository(provider).replaceVillage(source, List.of(sourceLayout));
        new BuildLayoutRepository(provider).replaceVillage(target, List.of(new BuildLayoutRecord(
                target, sourceLayout.world(), sourceLayout.buildType(), sourceLayout.templateId(),
                sourceLayout.centerX(), sourceLayout.centerY(), sourceLayout.centerZ(),
                sourceLayout.minX(), sourceLayout.maxX(), sourceLayout.minZ(), sourceLayout.maxZ(),
                sourceLayout.rotation(), sourceLayout.mirror(), sourceLayout.clusterId())));
        new RoadPortRepository(provider).replaceVillage(source,
                List.of(new RoadPortRecord(source, "world", 2, 64, 2, "NORTH")));
        new RoadPortRepository(provider).replaceVillage(target,
                List.of(new RoadPortRecord(target, "world", 2, 64, 2, "NORTH")));
        VillageDiplomacyRepository diplomacy = new VillageDiplomacyRepository(provider);
        diplomacy.upsert(source, target, "ALLY", 100L);

        villages.merge(source, target, 5, null, 48);

        assertEquals(0, count("villages", "id", source));
        assertEquals(1, count("villagers", "village_id", target));
        assertEquals(1, count("build_layouts", "village_id", target));
        assertEquals(1, count("road_ports", "village_id", target));
        assertEquals(0, count("villagers", "village_id", source));
        assertTrue(diplomacy.findAll().isEmpty());
    }

    @Test
    void permanentDeathDeletesVillagerAndClearsKingAtomically() throws Exception {
        String uuid = "00000000-0000-0000-0000-000000000201";
        VillageRepository villages = new VillageRepository(provider);
        int villageId = villages.insert(new Village(0, "world", 0, 64, 0, 32, uuid, 1, "Village"));
        VillagerRepository villagers = new VillagerRepository(provider, false);
        villagers.upsert(villager(uuid, villageId));

        villagers.deletePermanently(uuid, villageId);
        villagers.deletePermanently(uuid, villageId);

        assertFalse(villagers.find(uuid).isPresent());
        try (var connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT population,king_uuid FROM villages WHERE id=?")) {
            query.setInt(1, villageId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0, row.getInt("population"));
                assertEquals(null, row.getString("king_uuid"));
            }
        }
    }

    @Test
    void retryingInitialRegistrationDoesNotDoubleAttachPopulation() throws Exception {
        String uuid = "00000000-0000-0000-0000-000000000202";
        VillageRepository villages = new VillageRepository(provider);
        int villageId = villages.insert(new Village(0, "world", 0, 64, 0, 32, null, 0, "Village"));
        VillagerRepository villagers = new VillagerRepository(provider, false);
        VillagerData data = villager(uuid, villageId);

        villagers.insertNewAndAttach(data, false);
        villagers.insertNewAndAttach(data, false);

        try (var connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT population FROM villages WHERE id=?")) {
            query.setInt(1, villageId);
            try (ResultSet row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(1, row.getInt(1));
            }
        }
    }

    @Test
    void tradeLedgerTransitionsAreIdempotent() {
        TradeJournalRepository trades = new TradeJournalRepository(provider);
        long now = System.currentTimeMillis();
        TradeJournalRecord record = new TradeJournalRecord(
                "00000000-0000-0000-0000-000000000301", "buyer", "seller",
                "ingredients", "result", TradeJournalRecord.State.PREPARED, now, now);
        trades.create(record);

        trades.markDebited(record.tradeId());
        trades.markDebited(record.tradeId());
        trades.markCommitted(record.tradeId());
        trades.markCommitted(record.tradeId());
        assertEquals(1, trades.findUnresolvedFor("seller").size());
        trades.markSettled(record.tradeId());
        trades.markSettled(record.tradeId());

        assertTrue(trades.findUnresolvedFor("buyer").isEmpty());
        assertTrue(trades.findUnresolvedFor("seller").isEmpty());
    }

    @Test
    void relationEventsIncreaseAffinityOnceAndGateBreedingByCooldown() {
        RelationRepository relations = new RelationRepository(provider, false);
        long now = 10_000L;

        RelationUpdate first = relations.recordInteraction(
                "trade-1", "a", "b", 50, 50, 5_000L, now, true);
        RelationUpdate duplicate = relations.recordInteraction(
                "trade-1", "b", "a", 50, 50, 5_000L, now, true);
        RelationUpdate duringCooldown = relations.recordInteraction(
                "trade-2", "a", "b", 10, 50, 5_000L, now + 1_000L, true);

        assertEquals(50, first.affinity());
        assertTrue(first.breedingReady());
        assertEquals(50, duplicate.affinity());
        assertFalse(duplicate.breedingReady());
        assertEquals(60, duringCooldown.affinity());
        assertFalse(duringCooldown.breedingReady());
    }

    @Test
    void villageDiplomacyUpsertIsCanonicalAndDurable() {
        VillageDiplomacyRepository diplomacy = new VillageDiplomacyRepository(provider);

        diplomacy.upsert(7, 3, "ALLY", 100L);
        diplomacy.upsert(3, 7, "ENEMY", 200L);

        List<VillageDiplomacyRecord> records = diplomacy.findAll();
        assertEquals(1, records.size());
        assertEquals(3, records.getFirst().villageA());
        assertEquals(7, records.getFirst().villageB());
        assertEquals("ENEMY", records.getFirst().relation());
        assertEquals(200L, records.getFirst().updatedAt());
    }

    @Test
    void constructionJournalSurvivesUntilExplicitRollbackCompletion() {
        ConstructionJournalRepository journal = new ConstructionJournalRepository(provider);
        String jobId = "00000000-0000-0000-0000-000000000401";
        journal.create(jobId);
        journal.append(new ConstructionChangeRecord(
                jobId, 0, "world", 1, 64, 2, "minecraft:stone"));

        assertEquals(List.of(jobId), journal.findPreparedJobs());
        assertEquals("minecraft:stone", journal.findChanges(jobId).getFirst().oldBlockData());
        journal.rolledBack(jobId);

        assertTrue(journal.findPreparedJobs().isEmpty());
        assertTrue(journal.findChanges(jobId).isEmpty());
    }

    @Test
    void constructionCompletionPublishesLayoutAndClearsJournalAtomically() throws Exception {
        ConstructionJournalRepository journal = new ConstructionJournalRepository(provider);
        String jobId = "00000000-0000-0000-0000-000000000402";
        journal.create(jobId);
        journal.append(new ConstructionChangeRecord(
                jobId, 0, "world", 3, 64, 4, "minecraft:dirt"));
        BuildLayoutRecord layout = new BuildLayoutRecord(
                9, "world", "HOUSE", "house", 3, 64, 4,
                2, 4, 3, 5, "NONE", "NONE", "");

        journal.completeWithLayout(jobId, 9, List.of(layout),
                List.of(new RoadPortRecord(9, "world", 4, 64, 4, "EAST")));

        assertTrue(journal.findPreparedJobs().isEmpty());
        assertEquals(1, count("build_layouts", "village_id", 9));
        assertEquals(1, count("road_ports", "village_id", 9));
    }

    private VillagerData villager(String uuid, int villageId) {
        long now = System.currentTimeMillis();
        return new VillagerData(uuid, "Test", "farmer", 20, 2, "LOW",
                "world", 0, 64, 0, villageId, true, "[]", now, now);
    }

    private int count(String table, String column, int value) throws Exception {
        try (var connection = provider.connection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + "=?")) {
            query.setInt(1, value);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (var connection = provider.connection();
             ResultSet tables = connection.getMetaData().getTables(null, null, table, null)) {
            return tables.next();
        }
    }

    private boolean columnExists(String table, String column) throws Exception {
        try (var connection = provider.connection();
             ResultSet columns = connection.getMetaData().getColumns(null, null, table, column)) {
            return columns.next();
        }
    }
}
