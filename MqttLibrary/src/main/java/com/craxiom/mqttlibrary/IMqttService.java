package com.craxiom.mqttlibrary;

import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;
import com.craxiom.mqttlibrary.connection.ConnectionState;

/**
 * A contract for services that utilize an MQTT connection.
 *
 * @since 0.1.0
 */
public interface IMqttService
{
    /**
     * Registers the given {@link IConnectionStateListener} to the service.
     *
     * @param connectionStateListener The connection state listener.
     */
    void registerMqttConnectionStateListener(IConnectionStateListener connectionStateListener);

    /**
     * Unregisters the given {@link IConnectionStateListener} from the service.
     *
     * @param connectionStateListener The connection state listener.
     */
    void unregisterMqttConnectionStateListener(IConnectionStateListener connectionStateListener);

    /**
     * If connection information is specified for an MQTT Broker via the MDM Managed Configuration, then kick off an
     * MQTT connection.
     *
     * @param forceDisconnect Set to true so that the MQTT broker connection will be shutdown even if the MDM configured
     *                        connection info is not present.  This flag is needed to stop an MQTT connection if it was
     *                        previously configured via MDM, but the config has since been removed from the MDM.  In
     *                        that case, the connection info will be null but we still want to disconnect from the MQTT
     *                        broker.
     */
    void attemptMqttConnectWithMdmConfig(boolean forceDisconnect);

    /**
     * Connect to an MQTT broker.
     *
     * @param brokerConnectionInfo The information needed to connect to the MQTT broker.
     */
    void connectToMqttBroker(BrokerConnectionInfo brokerConnectionInfo);

    /**
     * Disconnect from the MQTT broker.
     */
    void disconnectFromMqttBroker();

    /**
     * @return The current connection state to the MQTT Broker.
     */
    ConnectionState getMqttConnectionState();
}
