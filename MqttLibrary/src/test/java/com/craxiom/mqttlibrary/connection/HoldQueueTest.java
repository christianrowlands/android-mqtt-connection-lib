package com.craxiom.mqttlibrary.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Tests the {@link HoldQueue} that keeps messages published before the first connection.
 *
 * @since 2.0.0
 */
public class HoldQueueTest
{
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private static HoldQueue.HeldMessage message(String payload)
    {
        return new HoldQueue.HeldMessage("topic", payload);
    }

    private static HoldQueue.Offer offer(HoldQueue queue, long generation, String payload)
    {
        return queue.offer(generation, message(payload), NO_LIMIT, 0);
    }

    @Test
    public void holdsUntilReleased_andReleasesInOrder()
    {
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);
        assertEquals(HoldQueue.Offer.HELD, offer(queue, 1, "1"));
        assertEquals(HoldQueue.Offer.HELD, offer(queue, 1, "2"));

        assertEquals("1", queue.pollForRelease(1).payload);
        assertEquals("Still holding while draining", HoldQueue.Offer.HELD, offer(queue, 1, "3"));
        assertEquals("2", queue.pollForRelease(1).payload);
        assertEquals("3", queue.pollForRelease(1).payload);
        assertNull(queue.pollForRelease(1));
        assertEquals(HoldQueue.Offer.RELEASED, offer(queue, 1, "late"));
        assertEquals(0, queue.size());
    }

    @Test
    public void rearm_holdsAgainAndKeepsWhatIsHeld()
    {
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);
        offer(queue, 1, "kept");
        queue.rearm(2);

        assertEquals("A message for the earlier attempt is still held", HoldQueue.Offer.HELD, offer(queue, 1, "also kept"));
        assertEquals(2, queue.size());
        assertEquals("kept", queue.pollForRelease(2).payload);
    }

    @Test
    public void replacedGeneration_cannotRelease()
    {
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);
        offer(queue, 1, "1");
        queue.rearm(2);

        assertNull(queue.pollForRelease(1));
        assertEquals("The old drain must not release the queue", HoldQueue.Offer.HELD, offer(queue, 2, "2"));
        assertEquals(2, queue.size());
    }

    @Test
    public void reset_throwsAwayHeldMessages_andRefusesOlderGenerations()
    {
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);
        offer(queue, 1, "1");
        offer(queue, 1, "2");

        assertEquals(2, queue.reset(2));
        assertEquals(0, queue.size());
        assertEquals(HoldQueue.Offer.STALE, offer(queue, 1, "after reset"));
        assertNull(queue.pollForRelease(1));

        queue.rearm(3);
        assertEquals(HoldQueue.Offer.HELD, offer(queue, 3, "next connection"));
    }

    @Test
    public void full_countsInFlightMessages()
    {
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);
        assertEquals(HoldQueue.Offer.HELD, queue.offer(1, message("1"), 3, 1));
        assertEquals(HoldQueue.Offer.HELD, queue.offer(1, message("2"), 3, 1));
        assertEquals(HoldQueue.Offer.FULL, queue.offer(1, message("3"), 3, 1));
        assertEquals(2, queue.size());
    }

    @Test
    public void concurrentOffersWhileDraining_keepOrderAndLoseNothing() throws Exception
    {
        final int count = 50_000;
        final HoldQueue queue = new HoldQueue();
        queue.rearm(1);

        final List<String> published = new ArrayList<>(count);
        final CountDownLatch start = new CountDownLatch(1);
        final Thread publisher = new Thread(() -> {
            try
            {
                start.await();
            } catch (InterruptedException e)
            {
                return;
            }
            for (int i = 0; i < count; i++)
            {
                final HoldQueue.HeldMessage next = message(String.valueOf(i));
                if (queue.offer(1, next, NO_LIMIT, 0) == HoldQueue.Offer.RELEASED)
                {
                    synchronized (published)
                    {
                        published.add(next.payload);
                    }
                }
            }
        });
        publisher.start();
        start.countDown();

        // Start draining while the publisher is still offering, so the two interleave
        while (queue.size() < 1_000 && publisher.isAlive()) Thread.yield();

        // Drain the way the connection does; once released, the publisher publishes directly
        HoldQueue.HeldMessage next;
        while ((next = queue.pollForRelease(1)) != null)
        {
            synchronized (published)
            {
                published.add(next.payload);
            }
        }
        publisher.join();

        assertEquals(count, published.size());
        for (int i = 0; i < count; i++)
        {
            assertEquals(String.valueOf(i), published.get(i));
        }
    }
}
