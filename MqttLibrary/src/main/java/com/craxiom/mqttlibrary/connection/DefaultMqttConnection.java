package com.craxiom.mqttlibrary.connection;

import android.content.Context;

import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.IMqttService;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth;
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuthBuilder;
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

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

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private Mqtt3AsyncClient mqtt3Client;

    protected String mqttClientId;
    private CompletableFuture<Mqtt3ConnAck> connectFuture;
    private volatile boolean userCanceled = false;
    private volatile boolean disconnecting = false;

    protected DefaultMqttConnection()
    {
        jsonFormatter = JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();
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
            if (mqtt3Client != null && mqtt3Client.getState().isConnectedOrReconnect())
            {
                Timber.i("Disconnect in progress, delaying the new connection");
                try
                {
                    final CompletableFuture<Void> disconnectFuture = mqtt3Client.disconnect();
                    disconnectFuture.get(3, TimeUnit.SECONDS);
                } catch (Throwable t)
                {
                    Timber.e(t, "Could not properly close the old connection before starting a new one.");
                }
                Timber.i("Disconnect complete, resuming the new connection");
            }

            userCanceled = false;
            mqttClientId = connectionInfo.getMqttClientId();

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
                    .automaticReconnect().maxDelay(60, TimeUnit.SECONDS).applyAutomaticReconnect()

                    .addConnectedListener(context -> {
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
                            notifyConnectionStateChange(ConnectionState.CONNECTED);
                        }
                    })

                    .addDisconnectedListener(context -> {
                        final MqttDisconnectSource source = context.getSource();
                        Timber.d(context.getCause(), "MQTT Broker disconnected. source=%s", source);
                        if (userCanceled)
                        {
                            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                            Timber.d("Force stopping the reconnect attempts because the user toggled the connection off");
                            context.getReconnector().reconnect(false);
                        } else if (source == MqttDisconnectSource.USER)
                        {
                            notifyConnectionStateChange(ConnectionState.DISCONNECTED);
                        } else
                        {
                            notifyConnectionStateChange(ConnectionState.CONNECTING);
                        }
                    });

            mqtt3Client = mqtt3ClientBuilder.buildAsync();

            connectFuture = mqtt3Client.connect();
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
     * @param mqttMessageTopic The MQTT Topic to publish the message to.
     * @param message          The Protobuf message to format as JSON and send to the MQTT Broker.
     */
    protected synchronized void publishMessage(String mqttMessageTopic, MessageOrBuilder message)
    {
        try
        {
            final String messageJson = jsonFormatter.print(message);

            if (mqtt3Client.getState().isConnected())
            {
                mqtt3Client.publishWith().topic(mqttMessageTopic).payload(messageJson.getBytes()).send();
            }
        } catch (Exception e)
        {
            Timber.e(e, "Caught an exception when trying to send an MQTT message");
        }
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
