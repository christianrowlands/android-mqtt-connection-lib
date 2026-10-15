package com.craxiom.mqttlibrary.connection;

import java.util.ArrayDeque;

/**
 * Holds messages published before the connection to the broker first succeeds, and hands them out
 * in order once it does.
 * <p>
 * The HiveMQ client must not be used before its first successful connection (HiveMQ issue #612),
 * so messages wait here instead of being thrown away. Once the connection succeeds the queue is
 * drained with {@link #pollForRelease(long)}; anything offered while draining is appended, so order
 * is kept, and the queue is released only when it is empty. After release, {@link #offer} returns
 * {@link Offer#RELEASED} and the caller publishes directly.
 * <p>
 * Every call carries the generation of the connection attempt it belongs to. Only the latest
 * {@link #rearm(long)} generation may release the queue, and messages offered for a generation older
 * than the latest {@link #reset(long)} are refused, so a connection that was turned off can't leave
 * messages behind for the next one.
 *
 * @since 2.0.0
 */
final class HoldQueue
{
    /**
     * A message waiting for the first connection.
     */
    static final class HeldMessage
    {
        final String topic;
        final String payload;

        HeldMessage(String topic, String payload)
        {
            this.topic = topic;
            this.payload = payload;
        }
    }

    /**
     * The result of {@link #offer}.
     */
    enum Offer
    {
        /** The message is held until the connection succeeds. */
        HELD,
        /** The queue is full, so the message was not held. */
        FULL,
        /** The queue was released, so the caller should publish the message itself. */
        RELEASED,
        /** The message belongs to a connection that was turned off, so it was not held. */
        STALE
    }

    private final ArrayDeque<HeldMessage> messages = new ArrayDeque<>();
    private boolean released = false;
    private long releaseGeneration = 0;
    private long oldestAcceptedGeneration = 0;

    /**
     * Adds a message to the end of the queue unless it is stale, the queue was released, or the
     * queue is full.
     *
     * @param generation The connection attempt the message was published for.
     * @param message    The message to hold.
     * @param limit      The most messages allowed, counting {@code inFlight}.
     * @param inFlight   The messages already handed to the client and not yet completed.
     */
    synchronized Offer offer(long generation, HeldMessage message, int limit, int inFlight)
    {
        if (generation < oldestAcceptedGeneration) return Offer.STALE;
        if (released) return Offer.RELEASED;
        if (messages.size() + inFlight >= limit) return Offer.FULL;
        messages.addLast(message);
        return Offer.HELD;
    }

    /**
     * @param generation The connection attempt that succeeded.
     * @return The next held message to publish, or null once the queue is empty, in which case the
     * queue is released in the same step so no message offered meanwhile is left behind. Always
     * null, without releasing, if {@code generation} is not the latest {@link #rearm(long)}.
     */
    synchronized HeldMessage pollForRelease(long generation)
    {
        if (generation != releaseGeneration) return null;
        final HeldMessage next = messages.pollFirst();
        if (next == null) released = true;
        return next;
    }

    /**
     * Starts holding again for a new connection attempt, keeping any messages still held.
     */
    synchronized void rearm(long generation)
    {
        releaseGeneration = generation;
        released = false;
    }

    /**
     * Throws away every held message, starts holding again, and refuses messages offered for any
     * generation older than {@code generation}.
     *
     * @return The number of messages thrown away.
     */
    synchronized int reset(long generation)
    {
        final int count = messages.size();
        messages.clear();
        released = false;
        releaseGeneration = generation;
        oldestAcceptedGeneration = generation;
        return count;
    }

    /**
     * @return The number of held messages.
     */
    synchronized int size()
    {
        return messages.size();
    }
}
