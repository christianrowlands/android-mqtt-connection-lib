package com.craxiom.mqttlibrary.connection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.craxiom.mqttlibrary.IQueueBackpressureListener;
import com.craxiom.mqttlibrary.MqttQos;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Tests {@link DefaultMqttConnection} against a broker that can't be reached, so every message
 * published after {@code connect} is held.
 *
 * @since 2.0.0
 */
public class DefaultMqttConnectionTest
{
    private static final String LOCALHOST = "127.0.0.1";
    /** Nothing listens here, so the connection is refused and retried. */
    private static final int CLOSED_PORT = 1;
    private static final String CLIENT_ID = "test-client";
    private static final long WAIT_MS = 5_000;

    /**
     * Exposes the protected publish method.
     */
    private static final class TestConnection extends DefaultMqttConnection
    {
        void publish(String payload)
        {
            publishMessage("topic", payload);
        }
    }

    /**
     * Counts the backpressure notifications.
     */
    private static final class CountingBackpressureListener implements IQueueBackpressureListener
    {
        final AtomicInteger full = new AtomicInteger();
        final AtomicInteger drained = new AtomicInteger();

        @Override
        public void onQueueFull(int queueSize, int queueLimit)
        {
            full.incrementAndGet();
        }

        @Override
        public void onQueueDrained(int queueSize, int queueLimit)
        {
            drained.incrementAndGet();
        }
    }

    private final TestConnection connection = new TestConnection();
    private final List<ConnectionState> states = new CopyOnWriteArrayList<>();

    {
        connection.registerMqttConnectionStateListener(states::add);
    }

    @After
    public void tearDown()
    {
        connection.disconnect();
    }

    private static BrokerConnectionInfo broker(String host, int port, String clientId, String topicPrefix)
    {
        return new BrokerConnectionInfo(host, port, false, clientId, null, null, topicPrefix, MqttQos.AT_LEAST_ONCE);
    }

    private static BrokerConnectionInfo unreachableBroker()
    {
        return broker(LOCALHOST, CLOSED_PORT, CLIENT_ID, "");
    }

    private void publish(int count)
    {
        for (int i = 0; i < count; i++) connection.publish("{\"n\":" + i + "}");
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException
    {
        final long deadline = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean())
        {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("Timed out waiting for " + what);
            Thread.sleep(20);
        }
    }

    @Test
    public void publishWithoutConnecting_isIgnored()
    {
        publish(3);

        assertEquals(0, connection.getPendingMessageCount());
        assertEquals(ConnectionState.DISCONNECTED, connection.getConnectionState());
    }

    @Test
    public void publishWhileConnecting_isHeld()
    {
        connection.connect(null, unreachableBroker());
        publish(5);

        assertEquals(ConnectionState.CONNECTING, connection.getConnectionState());
        assertEquals(5, connection.getPendingMessageCount());
        assertEquals(0, connection.getDroppedMessageCount());
        assertFalse(connection.hasConnectedOnce());
    }

    @Test
    public void invalidSettings_endDisconnectedWithInvalidSettings()
    {
        final BrokerConnectionInfo[] invalid = {
                broker("", CLOSED_PORT, CLIENT_ID, ""),
                broker(LOCALHOST, 0, CLIENT_ID, ""),
                broker(LOCALHOST, CLOSED_PORT, "", ""),
                broker(LOCALHOST, CLOSED_PORT, CLIENT_ID, "survey/#/"),
                broker(LOCALHOST, CLOSED_PORT, CLIENT_ID, "survey/+/"),
        };

        for (BrokerConnectionInfo info : invalid)
        {
            connection.connect(null, unreachableBroker());
            publish(3);

            connection.connect(null, info);

            assertEquals(ConnectionState.DISCONNECTED, connection.getConnectionState());
            assertEquals(ConnectionFailure.INVALID_SETTINGS, connection.getLastFailure());
            assertEquals("Held messages are discarded", 0, connection.getPendingMessageCount());

            publish(1);
            assertEquals("Nothing is held for a failed connection", 0, connection.getPendingMessageCount());
        }
    }

    @Test
    public void holdLimit_dropsAndCounts_andSignalsBackpressureOnce()
    {
        final CountingBackpressureListener listener = new CountingBackpressureListener();
        connection.registerQueueBackpressureListener(listener);
        connection.setStreamingQueueLimit(3);
        connection.connect(null, unreachableBroker());

        publish(5);

        assertEquals(3, connection.getPendingMessageCount());
        assertEquals(2, connection.getDroppedMessageCount());
        assertTrue(connection.isQueueBackpressureActive());
        assertEquals(1, listener.full.get());
    }

    @Test
    public void disconnect_discardsHeldMessages_andEndsBackpressure() throws InterruptedException
    {
        final CountingBackpressureListener listener = new CountingBackpressureListener();
        connection.registerQueueBackpressureListener(listener);
        connection.setStreamingQueueLimit(3);
        connection.connect(null, unreachableBroker());
        publish(5);

        connection.disconnect();

        assertEquals(0, connection.getPendingMessageCount());
        assertFalse(connection.isQueueBackpressureActive());
        assertEquals(1, listener.drained.get());
        assertNull(connection.getLastFailure());
        waitFor("DISCONNECTED", () -> connection.getConnectionState() == ConnectionState.DISCONNECTED);

        publish(1);
        assertEquals("Nothing is held once the connection is off", 0, connection.getPendingMessageCount());
    }

    @Test
    public void disconnectThenConnect_staysConnecting() throws InterruptedException
    {
        connection.connect(null, unreachableBroker());
        connection.disconnect();
        connection.connect(null, unreachableBroker());

        // Give the old client's disconnect time to complete; it must not report DISCONNECTED now
        Thread.sleep(500);

        assertEquals(ConnectionState.CONNECTING, connection.getConnectionState());
        assertEquals(ConnectionState.CONNECTING, states.get(states.size() - 1));
    }

    @Test
    public void connectAgain_keepsHeldMessages_andResetsDropCount()
    {
        connection.setStreamingQueueLimit(3);
        connection.connect(null, unreachableBroker());
        publish(4);
        assertEquals(1, connection.getDroppedMessageCount());

        connection.connect(null, unreachableBroker());

        assertEquals(3, connection.getPendingMessageCount());
        assertEquals(0, connection.getDroppedMessageCount());
    }

    @Test
    public void unreachableBroker_reportsUnreachable_andKeepsConnecting() throws InterruptedException
    {
        connection.connect(null, unreachableBroker());

        waitFor("UNREACHABLE", () -> connection.getLastFailure() == ConnectionFailure.UNREACHABLE);
        assertEquals(ConnectionState.CONNECTING, connection.getConnectionState());
    }
}
