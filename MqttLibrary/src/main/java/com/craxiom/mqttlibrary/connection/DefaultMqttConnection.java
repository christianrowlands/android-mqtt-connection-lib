package com.craxiom.mqttlibrary.connection;

import android.content.Context;

import androidx.annotation.Nullable;

import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.IMqttService;
import com.craxiom.mqttlibrary.IQueueBackpressureListener;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import timber.log.Timber;

/**
 * Class for creating a connection to an MQTT server. Typically this will be a field within
 * the {@link com.craxiom.mqttlibrary.IMqttService}, and is used to connect/disconnect to an MQTT
 * server via {@link com.craxiom.mqttlibrary.IMqttService#connectToMqttBroker(BrokerConnectionInfo)}
 * and {@link IMqttService#disconnectFromMqttBroker()} respectively.
 *
 * @since 0.1.0
 */
public class DefaultMqttConnection
{
    /**
     * The most messages held before the first connection when no streaming queue limit is set, so
     * an unreachable broker can't fill the phone's memory.
     *
     * @since 2.0.0
     */
    public static final int DEFAULT_HOLD_LIMIT = 20_000;

    private static final int MAX_PORT = 65_535;

    /**
     * One connection attempt: everything from a {@link #connect} up to the next {@link #connect} or
     * {@link #disconnect}. Callbacks from the client check that their session is still the current
     * one, and messages count against the session they were sent in, so a client that was replaced
     * can't change the state or the pending count of the one that replaced it.
     */
    private static final class Session
    {
        final long generation;
        /** Null for a session that is not trying to connect (turned off, or failed). */
        final Mqtt3AsyncClient client;
        final String topicPrefix;
        final MqttQos qos;
        /** Messages handed to {@link #client} that have not completed. */
        final AtomicInteger inFlight = new AtomicInteger(0);

        Session(long generation, Mqtt3AsyncClient client, String topicPrefix, MqttQos qos)
        {
            this.generation = generation;
            this.client = client;
            this.topicPrefix = topicPrefix;
            this.qos = qos;
        }

        boolean isActive()
        {
            return client != null;
        }
    }

    private final JsonFormat.Printer jsonFormatter;
    private final List<IConnectionStateListener> mqttConnectionListeners = new CopyOnWriteArrayList<>();
    private final List<IQueueBackpressureListener> queueBackpressureListeners = new CopyOnWriteArrayList<>();

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;

    private final AtomicLong clientGeneration = new AtomicLong(0);
    private volatile Session session = new Session(0, null, "", MqttQos.AT_LEAST_ONCE);

    // Queue management for backpressure
    private volatile int streamingQueueLimit = 0; // 0 = disabled (unbounded)
    private final AtomicBoolean queueBackpressureActive = new AtomicBoolean(false);

    protected String mqttClientId;
    private CompletableFuture<Mqtt3ConnAck> connectFuture;

    /**
     * True once the current session's client has connected, which is what {@link #hasConnectedOnce()}
     * reports.
     */
    private volatile boolean hasConnectedOnce = false;

    /**
     * Messages published before the first successful connection, sent once it succeeds.
     */
    private final HoldQueue holdQueue = new HoldQueue();

    /**
     * Messages thrown away because the queue was full, since the latest {@link #connect}.
     */
    private final AtomicLong droppedMessageCount = new AtomicLong(0);

    /**
     * The limit that turned backpressure on, so the drain check uses the same limit (the hold limit
     * applies even when the streaming queue limit is disabled).
     */
    private volatile int backpressureLimit = 0;

    private volatile ConnectionFailure lastFailure = null;

    protected DefaultMqttConnection()
    {
        jsonFormatter = JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();
    }

    /**
     * Connect to the MQTT Broker.
     * <p>
     * If the connection can't be created from {@code connectionInfo} (for example the host is
     * empty, or the topic prefix contains a wildcard), the state goes to
     * {@link ConnectionState#DISCONNECTED} and {@link #getLastFailure()} returns
     * {@link ConnectionFailure#INVALID_SETTINGS}.
     *
     * @param applicationContext Not used since 2.0.0; kept so existing callers still compile.
     * @param connectionInfo     The broker to connect to.
     */
    @SuppressWarnings("NonPrivateFieldAccessedInSynchronizedContext")
    public synchronized void connect(Context applicationContext, BrokerConnectionInfo connectionInfo)
    {
        final Session previous = session;
        hasConnectedOnce = false;
        lastFailure = null;
        droppedMessageCount.set(0);
        mqttClientId = connectionInfo.getMqttClientId();

        notifyConnectionStateChange(ConnectionState.CONNECTING);

        if (previous.client != null && previous.client.getState().isConnectedOrReconnect())
        {
            // Not waited on: the old client's callbacks are ignored once the new session starts
            Timber.d("Disconnecting the old MQTT client before creating a new one");
            previous.client.disconnect();
        }

        try
        {
            validate(connectionInfo);

            final long thisGeneration = clientGeneration.incrementAndGet();
            Timber.d("Creating new MQTT client with generation %d", thisGeneration);

            final Mqtt3ClientBuilder mqtt3ClientBuilder = Mqtt3Client.builder().identifier(mqttClientId);

            final String username = connectionInfo.getMqttUsername();
            final String password = connectionInfo.getMqttPassword();
            if (username != null)
            {
                final Mqtt3SimpleAuthBuilder.Complete authBuilder = Mqtt3SimpleAuth.builder().username(username);
                if (password != null) authBuilder.password(password.getBytes(StandardCharsets.UTF_8));

                mqtt3ClientBuilder.simpleAuth(authBuilder.build());
            }

            if (connectionInfo.isTlsEnabled()) mqtt3ClientBuilder.sslWithDefaultConfig();

            final AtomicReference<Mqtt3AsyncClient> clientRef = new AtomicReference<>();
            final Mqtt3AsyncClient client = mqtt3ClientBuilder.serverHost(connectionInfo.getMqttBrokerHost())
                    .serverPort(connectionInfo.getPortNumber())
                    .automaticReconnect().maxDelay(20, TimeUnit.SECONDS).applyAutomaticReconnect()
                    .addConnectedListener(context -> onConnected(thisGeneration, clientRef.get()))
                    .addDisconnectedListener(context -> onDisconnected(thisGeneration, context))
                    .buildAsync();
            clientRef.set(client);

            final Session newSession = new Session(thisGeneration, client, connectionInfo.getTopicPrefix(),
                    MqttQos.fromCode(connectionInfo.getMqttQos().getValue()));
            // Messages held for an earlier attempt are kept and go to this broker once it connects
            holdQueue.rearm(thisGeneration);
            session = newSession;
            endBackpressureIfDrained();

            // Clean session must be set to false if we want the HiveMQ client library to queue messages while this
            // device is offline, and then to send those messages when the device comes back online.
            connectFuture = client.connectWith().cleanSession(false).send();
        } catch (Exception e)
        {
            Timber.e(e, "Unable to create the connection to the MQTT broker");
            endSession();
            lastFailure = ConnectionFailure.INVALID_SETTINGS;
            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Disconnect from the MQTT Broker, and throw away any messages held for the first connection.
     * <p>
     * This method is synchronized so that we don't try connecting while a disconnect is in progress.
     */
    public synchronized void disconnect()
    {
        final Session previous = session;
        lastFailure = null;

        if (previous.client == null)
        {
            endSession();
            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
            return;
        }

        // DISCONNECTING first, then end the session: a publish that already read the old session
        // is refused by the hold queue, so nothing is held for a connection that is off.
        notifyConnectionStateChange(ConnectionState.DISCONNECTING);
        final long thisGeneration = endSession();

        try
        {
            if (connectFuture != null && !connectFuture.isDone())
            {
                Timber.i("Canceling the currently connecting connection using the future");
                connectFuture.cancel(true);
            }

            // Just in case the connection completed between calling isDone() and cancel(), we go through the disconnect to be sure
            previous.client.disconnect().whenComplete((aVoid, throwable) -> {
                Timber.d(throwable, "The MQTT disconnect request completed");
                synchronized (this)
                {
                    // A connect() since this disconnect owns the state now
                    if (thisGeneration == clientGeneration.get())
                    {
                        notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                    }
                }
            });
        } catch (Exception e)
        {
            Timber.e(e, "An exception occurred when disconnecting from the MQTT broker");
            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * @return The current {@link ConnectionState} of the connection to the MQTT Broker.
     */
    public ConnectionState getConnectionState()
    {
        return connectionState;
    }

    /**
     * Send the provided Protobuf message to the MQTT Broker.
     * <p>
     * The Protobuf message is formatted as JSON and then published to the specified topic.
     *
     * @param mqttMessageTopic The MQTT Topic to publish the message to. The topic prefix will be prepended to this.
     * @param message          The Protobuf message to format as JSON and send to the MQTT Broker.
     */
    protected void publishMessage(String mqttMessageTopic, MessageOrBuilder message)
    {
        // Skip the JSON formatting when the message would be ignored anyway
        if (!session.isActive()) return;

        try
        {
            final String jsonMessage = jsonFormatter.print(message);
            publishMessage(mqttMessageTopic, jsonMessage);
        } catch (InvalidProtocolBufferException e)
        {
            Timber.e(e, "Caught an exception when trying to send an MQTT message");
        }
    }

    /**
     * Publishes the JSON string to the specified topic.
     * <p>
     * While a connection attempt has not yet succeeded, the message is held and sent, in order,
     * once it does (since 2.0.0; earlier versions threw it away). With no connection attempt in
     * progress, the message is ignored.
     * <p>
     * If the queue is full, the message is dropped, counted in {@link #getDroppedMessageCount()},
     * and a backpressure notification is sent to listeners. The limit is the streaming queue limit,
     * or {@link #DEFAULT_HOLD_LIMIT} for held messages when no limit is set.
     *
     * @param mqttMessageTopic The MQTT topic to publish the message to. The topic prefix will be prepended to this.
     * @param jsonMessage      The JSON string to send to the MQTT broker.
     * @since 0.6.0
     */
    protected void publishMessage(String mqttMessageTopic, String jsonMessage)
    {
        final Session current = session;
        if (!current.isActive()) return;

        // Until the first successful connection, hold the message instead of touching the HiveMQ
        // client: publishing to a client that has never connected can block (HiveMQ Issue #612).
        final HoldQueue.HeldMessage message = new HoldQueue.HeldMessage(mqttMessageTopic, jsonMessage);
        final int holdLimit = holdLimit();
        switch (holdQueue.offer(current.generation, message, holdLimit, current.inFlight.get()))
        {
            case HELD:
            case STALE:
                return;
            case FULL:
                recordDroppedMessage(getPendingMessageCount(), holdLimit);
                return;
            case RELEASED:
            default:
                break;
        }

        if (!current.client.getState().isConnectedOrReconnect()) return;

        if (reserveQueueSlot(current, streamingQueueLimit))
        {
            send(current, message);
        }
    }

    /**
     * Counts a message as in flight, unless the queue is over {@code limit}, in which case the
     * message is counted as dropped. Counting first avoids the race where several threads pass the
     * check before any of them increments.
     *
     * @param limit The most pending messages allowed, or 0 or less for no limit.
     * @return True if the message may be sent.
     */
    private boolean reserveQueueSlot(Session current, int limit)
    {
        final int newPending = current.inFlight.incrementAndGet();
        if (limit > 0 && newPending > limit)
        {
            current.inFlight.decrementAndGet();
            recordDroppedMessage(newPending - 1, limit);
            return false;
        }
        return true;
    }

    /**
     * Counts a message dropped because the queue was full, and tells the backpressure listeners
     * the first time the queue fills.
     */
    private void recordDroppedMessage(int queueSize, int limit)
    {
        droppedMessageCount.incrementAndGet();
        if (!queueBackpressureActive.getAndSet(true))
        {
            backpressureLimit = limit;
            Timber.w("MQTT streaming queue full (%d >= %d), signaling to pause scanning", queueSize, limit);
            notifyQueueFull(queueSize, limit);
        }
    }

    /**
     * @return The most messages held before the first connection.
     */
    private int holdLimit()
    {
        return streamingQueueLimit > 0 ? streamingQueueLimit : DEFAULT_HOLD_LIMIT;
    }

    /**
     * Publishes a message already counted in {@code current}'s in-flight count, and frees that count
     * when the publish completes or fails.
     */
    private void send(Session current, HoldQueue.HeldMessage message)
    {
        try
        {
            current.client.publishWith()
                    .topic(current.topicPrefix + message.topic)
                    .qos(current.qos)
                    .payload(message.payload.getBytes(StandardCharsets.UTF_8))
                    .send()
                    .whenComplete((result, error) -> {
                        current.inFlight.decrementAndGet();
                        if (current == session) endBackpressureIfDrained();

                        if (error != null) Timber.w(error, "Error publishing MQTT message");
                    });
        } catch (Exception e)
        {
            // For example an invalid topic; one bad message must not hold a queue slot forever
            current.inFlight.decrementAndGet();
            Timber.w(e, "Could not publish an MQTT message");
        }
    }

    /**
     * Ends backpressure if the queue has drained to half the limit that turned it on.
     */
    private void endBackpressureIfDrained()
    {
        if (!queueBackpressureActive.get()) return;

        final int remaining = getPendingMessageCount();
        if (remaining < backpressureLimit / 2 && queueBackpressureActive.getAndSet(false))
        {
            Timber.i("MQTT streaming queue drained (%d < %d/2), resuming scanning", remaining, backpressureLimit);
            notifyQueueDrained(remaining, backpressureLimit);
        }
    }

    /**
     * Handles a connection by the client of the given generation: marks the connection as up and
     * sends the held messages, or, for a client that was replaced, disconnects it.
     */
    private synchronized void onConnected(long generation, Mqtt3AsyncClient client)
    {
        final Session current = session;
        if (generation != current.generation)
        {
            Timber.d("Disconnecting a replaced MQTT client that connected (generation %d, current %d)",
                    generation, current.generation);
            client.disconnect();
            return;
        }

        Timber.i("Connected to the MQTT broker");
        hasConnectedOnce = true;
        lastFailure = null;
        try
        {
            releaseHeldMessages(current);
        } finally
        {
            notifyConnectionStateChange(ConnectionState.CONNECTED);
        }
    }

    /**
     * Handles a disconnect of the client of the given generation. A rejected sign-in ends the
     * session; anything else is retried by the client and reported as connecting.
     */
    private synchronized void onDisconnected(long generation, MqttClientDisconnectedContext context)
    {
        if (generation != session.generation)
        {
            Timber.d("Stopping reconnects of a replaced MQTT client (generation %d, current %d)",
                    generation, session.generation);
            context.getReconnector().reconnect(false);
            return;
        }

        final MqttDisconnectSource source = context.getSource();
        Timber.d(context.getCause(), "MQTT Broker disconnected. source=%s", source);

        Mqtt3ConnAckReturnCode returnCode = null;
        final Throwable cause = context.getCause();
        if (cause instanceof Mqtt3ConnAckException)
        {
            returnCode = ((Mqtt3ConnAckException) cause).getMqttMessage().getReturnCode();
        }

        if (returnCode == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD
                || returnCode == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED)
        {
            Timber.d("Force stopping the reconnect attempts because the username and password were not correct");
            context.getReconnector().reconnect(false);
            endSession();
            lastFailure = ConnectionFailure.SIGN_IN_REJECTED;
            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
        } else if (source == MqttDisconnectSource.USER)
        {
            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
        } else
        {
            // If the broker comes back without our session (a restart without persistence, an
            // evicted session, another node of a cluster), HiveMQ would fail every queued and
            // unacknowledged publish with MqttSessionExpiredException. Send them again instead.
            context.getReconnector().republishIfSessionExpired(true);
            lastFailure = ConnectionFailure.UNREACHABLE;
            notifyConnectionStateChange(ConnectionState.CONNECTING);
        }
    }

    /**
     * Sends every message held before the first connection, in order. Messages published while
     * this runs are appended to the hold queue, so they go out after the older ones.
     */
    private void releaseHeldMessages(Session current)
    {
        final int heldCount = holdQueue.size();
        if (heldCount > 0) Timber.i("Sending %d MQTT messages held until the broker connected", heldCount);

        HoldQueue.HeldMessage next;
        while ((next = holdQueue.pollForRelease(current.generation)) != null)
        {
            current.inFlight.incrementAndGet();
            send(current, next);
        }
    }

    /**
     * Ends the current session: callbacks from its client are ignored from now on, the held
     * messages are thrown away, and any backpressure ends.
     *
     * @return The generation that replaced the ended session.
     */
    private synchronized long endSession()
    {
        final long generation = clientGeneration.incrementAndGet();
        session = new Session(generation, null, "", MqttQos.AT_LEAST_ONCE);

        final int discarded = holdQueue.reset(generation);
        if (discarded > 0)
        {
            Timber.i("Discarding %d MQTT messages held for a connection that ended", discarded);
        }

        // Whatever filled the queue no longer applies to the next connection
        if (queueBackpressureActive.getAndSet(false))
        {
            notifyQueueDrained(getPendingMessageCount(), backpressureLimit);
        }
        return generation;
    }

    /**
     * Checks the settings HiveMQ would otherwise reject later, on the first publish.
     *
     * @throws IllegalArgumentException If a setting is not valid.
     */
    private static void validate(BrokerConnectionInfo connectionInfo)
    {
        final String host = connectionInfo.getMqttBrokerHost();
        if (host == null || host.trim().isEmpty())
        {
            throw new IllegalArgumentException("The MQTT broker host is empty");
        }

        final int port = connectionInfo.getPortNumber();
        if (port < 1 || port > MAX_PORT)
        {
            throw new IllegalArgumentException("The MQTT broker port is not valid: " + port);
        }

        final String clientId = connectionInfo.getMqttClientId();
        if (clientId == null || clientId.isEmpty())
        {
            throw new IllegalArgumentException("The MQTT client ID is empty");
        }

        final String topicPrefix = connectionInfo.getTopicPrefix();
        if (topicPrefix.contains("#") || topicPrefix.contains("+"))
        {
            throw new IllegalArgumentException("The MQTT topic prefix can't contain the wildcards # or +");
        }
    }

    /**
     * @return True if the connection has succeeded at least once since the latest {@link #connect}.
     * @since 2.0.0
     */
    public boolean hasConnectedOnce()
    {
        return hasConnectedOnce;
    }

    /**
     * @return Why the latest connection attempt failed, or null if it hasn't failed (or succeeded
     * since). {@link ConnectionFailure#UNREACHABLE} also covers a dropped connection that is being
     * retried.
     * @since 2.0.0
     */
    @Nullable
    public ConnectionFailure getLastFailure()
    {
        return lastFailure;
    }

    /**
     * @return The number of messages thrown away because the queue was full, since the latest
     * {@link #connect}.
     * @since 2.0.0
     */
    public long getDroppedMessageCount()
    {
        return droppedMessageCount.get();
    }

    /**
     * Adds an {@link IConnectionStateListener} so that it will be notified of all future MQTT connection state changes.
     *
     * @param connectionStateListener The listener to add.
     */
    public void registerMqttConnectionStateListener(IConnectionStateListener connectionStateListener)
    {
        mqttConnectionListeners.add(connectionStateListener);
    }

    /**
     * Removes an {@link IConnectionStateListener} so that it will no longer be notified of MQTT connection state changes.
     *
     * @param connectionStateListener The listener to remove.
     */
    public void unregisterMqttConnectionStateListener(IConnectionStateListener connectionStateListener)
    {
        mqttConnectionListeners.remove(connectionStateListener);
    }

    /**
     * Sets the maximum number of pending messages allowed in the queue before backpressure is applied.
     * <p>
     * When the queue reaches this limit, new messages will be dropped and listeners will be notified
     * to pause scanning. When the queue drains to half the limit, listeners will be notified to resume.
     *
     * @param limit The maximum queue size. Set to 0 to disable queue limiting (unbounded queue).
     * @since 1.1.0
     */
    public void setStreamingQueueLimit(int limit)
    {
        streamingQueueLimit = Math.max(0, limit);
        Timber.d("MQTT streaming queue limit set to %d", streamingQueueLimit);

        // If we're reducing the limit and currently in backpressure, check if we should still be
        if (limit > 0 && queueBackpressureActive.get())
        {
            final int currentPending = getPendingMessageCount();
            if (currentPending < limit / 2)
            {
                // Queue has already drained below the new threshold
                if (queueBackpressureActive.getAndSet(false))
                {
                    notifyQueueDrained(currentPending, limit);
                }
            }
        }
    }

    /**
     * Gets the current streaming queue limit.
     *
     * @return The queue limit, or 0 if unlimited.
     * @since 1.1.0
     */
    public int getStreamingQueueLimit()
    {
        return streamingQueueLimit;
    }

    /**
     * Gets the current number of pending messages: messages held until the first connection plus
     * messages handed to the current client that haven't completed. Counted whether or not a queue
     * limit is set (since 2.0.0).
     *
     * @return The number of messages waiting to be sent.
     * @since 1.1.0
     */
    public int getPendingMessageCount()
    {
        return holdQueue.size() + session.inFlight.get();
    }

    /**
     * Checks if queue backpressure is currently active.
     *
     * @return true if scanning should be paused due to queue backpressure.
     * @since 1.1.0
     */
    public boolean isQueueBackpressureActive()
    {
        return queueBackpressureActive.get();
    }

    /**
     * Registers a listener to receive queue backpressure notifications.
     *
     * @param listener The listener to add.
     * @since 1.1.0
     */
    public void registerQueueBackpressureListener(IQueueBackpressureListener listener)
    {
        queueBackpressureListeners.add(listener);
    }

    /**
     * Removes a queue backpressure listener.
     *
     * @param listener The listener to remove.
     * @since 1.1.0
     */
    public void unregisterQueueBackpressureListener(IQueueBackpressureListener listener)
    {
        queueBackpressureListeners.remove(listener);
    }

    /**
     * Notifies all registered listeners that the queue is full and scanning should pause.
     */
    private void notifyQueueFull(int queueSize, int queueLimit)
    {
        for (IQueueBackpressureListener listener : queueBackpressureListeners)
        {
            try
            {
                listener.onQueueFull(queueSize, queueLimit);
            } catch (Exception e)
            {
                Timber.e(e, "Error notifying queue backpressure listener of queue full");
            }
        }
    }

    /**
     * Notifies all registered listeners that the queue has drained and scanning can resume.
     */
    private void notifyQueueDrained(int queueSize, int queueLimit)
    {
        for (IQueueBackpressureListener listener : queueBackpressureListeners)
        {
            try
            {
                listener.onQueueDrained(queueSize, queueLimit);
            } catch (Exception e)
            {
                Timber.e(e, "Error notifying queue backpressure listener of queue drained");
            }
        }
    }

    /**
     * Notify all the registered listeners of the new connection state.
     *
     * @param newConnectionState The new MQTT connection state.
     */
    private synchronized void notifyConnectionStateChange(ConnectionState newConnectionState)
    {
        Timber.i("MQTT Connection State Changed.  oldConnectionState=%s, newConnectionState=%s", connectionState, newConnectionState);

        connectionState = newConnectionState;

        for (IConnectionStateListener listener : mqttConnectionListeners)
        {
            try
            {
                listener.onConnectionStateChange(newConnectionState);
            } catch (Exception e)
            {
                Timber.e(e, "Unable to notify a MQTT Connection State Listener because of an exception");
            }
        }
    }
}
