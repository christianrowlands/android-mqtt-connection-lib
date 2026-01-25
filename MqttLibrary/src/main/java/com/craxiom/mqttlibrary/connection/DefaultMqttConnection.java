package com.craxiom.mqttlibrary.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.IMqttService;
import com.craxiom.mqttlibrary.IQueueBackpressureListener;
import com.craxiom.mqttlibrary.R;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.hivemq.client.internal.mqtt.lifecycle.mqtt3.Mqtt3ClientDisconnectedContextView;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import timber.log.Timber;

/**
 * Class for creating a connection to an MQTT server. Typically this will be a field within
 * the {@link com.craxiom.mqttlibrary.IMqttService}, and is used to connect/disconnect to an MQTT
 * server via {@link com.craxiom.mqttlibrary.IMqttService#connectToMqttBroker(BrokerConnectionInfo)}
 * and {@link IMqttService#disconnectFromMqttBroker()} respectively.
 *
 * @since 0.1.0
 */
@SuppressWarnings("unused")
public class DefaultMqttConnection
{
    /**
     * The amount of time to wait for a proper disconnection to occur before we force kill it.
     */
    private static final long DISCONNECT_TIMEOUT = 250L;

    private final JsonFormat.Printer jsonFormatter;
    private final List<IConnectionStateListener> mqttConnectionListeners = new CopyOnWriteArrayList<>();
    private final List<IQueueBackpressureListener> queueBackpressureListeners = new CopyOnWriteArrayList<>();

    private final Handler uiThreadHandler;

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private Mqtt3AsyncClient mqtt3Client;

    // Queue management for backpressure
    private volatile int streamingQueueLimit = 0; // 0 = disabled (unbounded)
    private final AtomicInteger pendingMessageCount = new AtomicInteger(0);
    private final AtomicBoolean queueBackpressureActive = new AtomicBoolean(false);

    protected String mqttClientId;
    private CompletableFuture<Mqtt3ConnAck> connectFuture;
    private volatile boolean userCanceled = false;
    private volatile boolean disconnecting = false;

    /**
     * Tracks whether the MQTT client has successfully connected at least once. This prevents
     * attempting to publish messages to HiveMQ before the first successful connection, which
     * can cause blocking behavior due to HiveMQ Issue #612 where operations on a client that
     * has never connected can result in CompletableFutures that never complete.
     */
    private volatile boolean hasConnectedOnce = false;

    /**
     * Generation counter to track client instances. Incremented each time a new client is created.
     * Listeners capture the generation at creation time and ignore events if generation doesn't match.
     * This prevents stale client callbacks from affecting the current connection state.
     */
    private final AtomicLong clientGeneration = new AtomicLong(0);
    private String topicPrefix;
    private com.hivemq.client.mqtt.datatypes.MqttQos hiveMqttQos;

    protected DefaultMqttConnection()
    {
        jsonFormatter = JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();

        uiThreadHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Connect to the MQTT Broker.
     * <p>
     * Synchronize so that we don't mess with the connection client while creating a new connection.
     *
     * @param applicationContext The context to use for the MQTT Android Client.
     */
    @SuppressWarnings("NonPrivateFieldAccessedInSynchronizedContext")
    public synchronized void connect(Context applicationContext, BrokerConnectionInfo connectionInfo)
    {
        try
        {
            // Increment generation FIRST to invalidate any callbacks from old client
            final long thisGeneration = clientGeneration.incrementAndGet();
            hasConnectedOnce = false;
            Timber.d("Creating new MQTT client with generation %d", thisGeneration);

            if (mqtt3Client != null && mqtt3Client.getState().isConnectedOrReconnect())
            {
                Timber.d("Attempting to disconnect old client before creating new one");
                try
                {
                    final CompletableFuture<Void> disconnectFuture = mqtt3Client.disconnect();
                    disconnectFuture.get(3, TimeUnit.SECONDS);
                    Timber.d("Old client disconnect completed");
                } catch (Throwable t)
                {
                    // This is expected when the old client was in reconnecting state but not actually connected.
                    // The generation counter will ensure the old client's callbacks are ignored anyway.
                    Timber.d(t, "Old client disconnect did not complete cleanly (expected if client was reconnecting)");
                }
            }

            userCanceled = false;
            mqttClientId = connectionInfo.getMqttClientId();
            topicPrefix = connectionInfo.getTopicPrefix();
            hiveMqttQos = com.hivemq.client.mqtt.datatypes.MqttQos.fromCode(
                    connectionInfo.getMqttQos().getValue());

            final String username = connectionInfo.getMqttUsername();
            final String password = connectionInfo.getMqttPassword();

            final Mqtt3ClientBuilder mqtt3ClientBuilder = Mqtt3Client.builder().identifier(mqttClientId);

            if (username != null || password != null)
            {
                Mqtt3SimpleAuthBuilder authBuilder = Mqtt3SimpleAuth.builder();
                if (username != null)
                {
                    final Mqtt3SimpleAuthBuilder.Complete authBuilderComplete = authBuilder.username(username);
                    if (password != null) authBuilderComplete.password(password.getBytes());

                    mqtt3ClientBuilder.simpleAuth(authBuilderComplete.build());
                }
            }

            if (connectionInfo.isTlsEnabled()) mqtt3ClientBuilder.sslWithDefaultConfig();

            mqtt3ClientBuilder.serverHost(connectionInfo.getMqttBrokerHost())
                    .serverPort(connectionInfo.getPortNumber())
                    .automaticReconnect().maxDelay(20, TimeUnit.SECONDS).applyAutomaticReconnect()

                    .addConnectedListener(context -> {
                        // Check if this listener's generation is still current
                        if (thisGeneration != clientGeneration.get())
                        {
                            Timber.d("Ignoring connected event from stale client (generation %d, current %d)",
                                    thisGeneration, clientGeneration.get());
                            return;
                        }

                        if (userCanceled)
                        {
                            Timber.i("The user canceled the MQTT connection prior to the connection attempt completing, closing the new connection");
                            synchronized (this)
                            {
                                mqtt3Client.disconnect();
                            }
                        } else
                        {
                            Timber.i("MQTT Broker Connected!!!!");
                            hasConnectedOnce = true;
                            notifyConnectionStateChange(ConnectionState.CONNECTED);
                        }
                    })

                    .addDisconnectedListener(context -> {
                        // Check if this listener's generation is still current
                        if (thisGeneration != clientGeneration.get())
                        {
                            Timber.d("Ignoring disconnected event from stale client (generation %d, current %d), stopping reconnect",
                                    thisGeneration, clientGeneration.get());
                            // Stop reconnect attempts for stale client
                            context.getReconnector().reconnect(false);
                            return;
                        }

                        final MqttDisconnectSource source = context.getSource();
                        Timber.d(context.getCause(), "MQTT Broker disconnected. source=%s", source);

                        Mqtt3ConnAckReturnCode returnCode = null;
                        if (context instanceof Mqtt3ClientDisconnectedContextView)
                        {
                            final Throwable cause = context.getCause();
                            if (cause instanceof Mqtt3ConnAckException)
                            {
                                returnCode = ((Mqtt3ConnAckException) cause).getMqttMessage().getReturnCode();
                            }
                        }

                        if (returnCode == Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD
                                || returnCode == Mqtt3ConnAckReturnCode.NOT_AUTHORIZED)
                        {
                            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                            Timber.d("Force stopping the reconnect attempts because the username and password were not correct");
                            context.getReconnector().reconnect(false);
                            uiThreadHandler.post(() -> Toast.makeText(applicationContext,
                                    applicationContext.getText(R.string.connection_error_invalid_credentials), Toast.LENGTH_LONG).show());
                        } else if (userCanceled)
                        {
                            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                            Timber.d("Force stopping the reconnect attempts because the user toggled the connection off");
                            context.getReconnector().reconnect(false);
                        } else if (source == MqttDisconnectSource.USER)
                        {
                            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                        } /* TODO Removing this block because it was causing the reconnect to stop even when the internet connectivity breaks for a few seconds
                        else if (source == MqttDisconnectSource.CLIENT)
                        {
                            final Throwable cause = context.getCause();
                            if (cause instanceof ConnectionFailedException
                                    && cause.getCause() instanceof UnknownHostException)
                            {
                                notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                                Timber.d("Force stopping the reconnect attempts because the server is unavailable");
                                context.getReconnector().reconnect(false);
                                uiThreadHandler.post(() -> Toast.makeText(applicationContext,
                                        applicationContext.getText(R.string.connection_error_server_unavailable), Toast.LENGTH_LONG).show());
                            }
                        }*/ else
                        {
                            notifyConnectionStateChange(ConnectionState.CONNECTING);
                        }
                    });

            mqtt3Client = mqtt3ClientBuilder.buildAsync();

            // Clean session must be set to false if we want the HiveMQ client library to queue messages while this
            // device is offline, and then to send those messages when the device comes back online.
            connectFuture = mqtt3Client.connectWith().cleanSession(false).send();
        } catch (Exception e)
        {
            Timber.e(e, "Unable to create the connection to the MQTT broker");
        }
    }

    /**
     * Disconnect from the MQTT Broker.
     * <p>
     * This method is synchronized so that we don't try connecting while a disconnect is in progress.
     */
    public synchronized void disconnect()
    {
        userCanceled = true;

        if (mqtt3Client != null)
        {
            try
            {
                if (connectFuture != null && !connectFuture.isDone())
                {
                    Timber.i("Canceling the currently connecting connection using the future");
                    connectFuture.cancel(true);
                }

                // Just in case the connection completed between calling isDone() and cancel(), we go through the disconnect to be sure
                disconnecting = true;
                final CompletableFuture<Void> disconnect = mqtt3Client.disconnect();
                disconnect.whenComplete((aVoid, throwable) -> {
                    Timber.d(throwable, "The MQTT disconnect request completed");
                    notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                    disconnecting = false;
                });
            } catch (Exception e)
            {
                Timber.e(e, "An exception occurred when disconnecting from the MQTT broker");
                disconnecting = false;
            }
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
     * @param mqttMessageTopic The MQTT Topic to publish the message to. The {@link #topicPrefix} will be prepended to this.
     * @param message          The Protobuf message to format as JSON and send to the MQTT Broker.
     */
    protected void publishMessage(String mqttMessageTopic, MessageOrBuilder message)
    {
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
     * If a streaming queue limit is configured and the queue is full, the message will be dropped
     * and a backpressure notification will be sent to listeners.
     *
     * @param mqttMessageTopic The MQTT topic to publish the message to. The {@link #topicPrefix} will be prepended to this.
     * @param jsonMessage      The JSON string to send to the MQTT broker.
     * @since 0.6.0
     */
    protected void publishMessage(String mqttMessageTopic, String jsonMessage)
    {
        // Don't attempt to publish until we've connected at least once.
        // This prevents the HiveMQ blocking bug (Issue #612) where publishing to a client
        // that has never successfully connected can result in CompletableFutures that never
        // complete, causing blocking behavior and UI freezes.
        if (!hasConnectedOnce || !mqtt3Client.getState().isConnectedOrReconnect())
        {
            return;
        }

        // If queue limit is disabled (0), use the original fire-and-forget behavior
        if (streamingQueueLimit <= 0)
        {
            mqtt3Client.publishWith()
                    .topic(topicPrefix + mqttMessageTopic)
                    .qos(hiveMqttQos)
                    .payload(jsonMessage.getBytes())
                    .send();
            return;
        }

        // Queue limit is enabled - use increment-first pattern to avoid race conditions
        // where multiple threads pass the check before any increment
        final int newPending = pendingMessageCount.incrementAndGet();
        if (newPending > streamingQueueLimit)
        {
            // Queue is over limit - decrement and apply backpressure
            pendingMessageCount.decrementAndGet();
            // Notify listeners if this is a new backpressure event
            if (!queueBackpressureActive.getAndSet(true))
            {
                Timber.w("MQTT streaming queue full (%d > %d), signaling to pause scanning",
                        newPending - 1, streamingQueueLimit);
                notifyQueueFull(newPending - 1, streamingQueueLimit);
            }
            // Drop the message - scanning should be paused by now
            return;
        }

        // Proceed with publishing - count already incremented
        mqtt3Client.publishWith()
                .topic(topicPrefix + mqttMessageTopic)
                .qos(hiveMqttQos)
                .payload(jsonMessage.getBytes())
                .send()
                .whenComplete((result, error) -> {
                    final int remaining = pendingMessageCount.decrementAndGet();

                    // Resume scanning when queue drains to half the limit
                    if (queueBackpressureActive.get() && remaining < streamingQueueLimit / 2)
                    {
                        if (queueBackpressureActive.getAndSet(false))
                        {
                            Timber.i("MQTT streaming queue drained (%d < %d/2), resuming scanning",
                                    remaining, streamingQueueLimit);
                            notifyQueueDrained(remaining, streamingQueueLimit);
                        }
                    }

                    if (error != null)
                    {
                        Timber.w(error, "Error publishing MQTT message");
                    }
                });
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
            final int currentPending = pendingMessageCount.get();
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
     * Gets the current number of pending messages in the queue.
     *
     * @return The number of messages waiting to be sent.
     * @since 1.1.0
     */
    public int getPendingMessageCount()
    {
        return pendingMessageCount.get();
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
