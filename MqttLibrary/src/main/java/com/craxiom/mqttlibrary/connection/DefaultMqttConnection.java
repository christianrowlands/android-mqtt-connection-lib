package com.craxiom.mqttlibrary.connection;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.IMqttService;
import com.craxiom.mqttlibrary.R;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import com.hivemq.client.internal.mqtt.lifecycle.mqtt3.Mqtt3ClientDisconnectedContextView;
import com.hivemq.client.mqtt.datatypes.MqttQos;
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

    private final Handler uiThreadHandler;

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private Mqtt3AsyncClient mqtt3Client;

    protected String mqttClientId;
    private CompletableFuture<Mqtt3ConnAck> connectFuture;
    private volatile boolean userCanceled = false;
    private volatile boolean disconnecting = false;

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
                    .automaticReconnect().maxDelay(20, TimeUnit.SECONDS).applyAutomaticReconnect()

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
     * @param mqttMessageTopic The MQTT Topic to publish the message to.
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
     *
     * @param mqttMessageTopic The MQTT topic to publish the message to.
     * @param jsonMessage      The JSON string to send to the MQTT broker.
     * @since 0.6.0
     */
    protected void publishMessage(String mqttMessageTopic, String jsonMessage)
    {
        if (mqtt3Client.getState().isConnectedOrReconnect())
        {
            mqtt3Client.publishWith().topic(mqttMessageTopic).qos(MqttQos.AT_LEAST_ONCE).payload(jsonMessage.getBytes()).send();
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
