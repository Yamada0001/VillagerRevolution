package dev.bettervillagers.villager;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyedSerialExecutorTest {

    @Test
    void serializesOneUuidWhileAllowingAnotherUuidToProgress() throws Exception {
        KeyedSerialExecutor executor = new KeyedSerialExecutor(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch otherKeyRan = new CountDownLatch(1);
        CountDownLatch allDone = new CountDownLatch(3);
        List<Integer> order = new CopyOnWriteArrayList<>();

        executor.execute("same", () -> {
            firstStarted.countDown();
            await(releaseFirst);
            order.add(1);
            allDone.countDown();
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
        executor.execute("same", () -> {
            order.add(2);
            allDone.countDown();
        });
        executor.execute("other", () -> {
            otherKeyRan.countDown();
            allDone.countDown();
        });

        assertTrue(otherKeyRan.await(2, TimeUnit.SECONDS));
        assertFalse(order.contains(2));
        releaseFirst.countDown();
        assertTrue(allDone.await(2, TimeUnit.SECONDS));
        executor.shutdownAndAwait(2, TimeUnit.SECONDS);

        assertEquals(List.of(1, 2), order);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
