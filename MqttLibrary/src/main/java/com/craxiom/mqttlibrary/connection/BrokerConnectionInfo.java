package com.craxiom.mqttlibrary.connection;

import com.craxiom.mqttlibrary.MqttQos;

import java.util.Objects;

/**
 * Holds all the information for an MQTT Broker connection.
 *
 * @since 0.1.0
 */
public class BrokerConnectionInfo
{
    private static final String SSL_URI_PREFIX = "ssl://";
    private static final String TCP_URI_PREFIX = "tcp://";

    private final String mqttBrokerHost;
    private final int portNumber;
    private final boolean tlsEnabled;
    private final String mqttClientId;
    private final String mqttUsername;
    private final String mqttPassword;
    private final String topicPrefix;
    private final MqttQos mqttQos;

    private final int hashCode;

    /**
     * Constructs this info object with all the information needed to connect to an MQTT Broker.
     *
     * @param mqttBrokerHost The IP or hostname (hostname preferred) of the MQTT broker.
     * @param portNumber     The port number of the MQTT broker (typically 8883 for TLS, and 1883 for plaintext).
     * @param tlsEnabled     True if SSL/TLS should be used, false if the connection should be plaintext.
     * @param mqttClientId   The client ID that is used to represent this client to the server.
     * @param mqttUsername   The username used to authenticate to the MQTT Broker.
     * @param mqttPassword   The password used to authenticate to the MQTT Broker.
     * @param topicPrefix    The prefix to use for all MQTT topics.
     * @param mqttQos        The Quality of Service level to use for publishing messages. If null, defaults to AT_LEAST_ONCE (QoS 1).
     */
    public BrokerConnectionInfo(String mqttBrokerHost, int portNumber, boolean tlsEnabled,
                                String mqttClientId, String mqttUsername, String mqttPassword,
                                String topicPrefix, MqttQos mqttQos)
    {
        this.mqttBrokerHost = mqttBrokerHost;
        this.portNumber = portNumber;
        this.tlsEnabled = tlsEnabled;

        this.mqttClientId = mqttClientId;
        this.mqttUsername = mqttUsername;
        this.mqttPassword = mqttPassword;

        if (topicPrefix == null) topicPrefix = "";
        this.topicPrefix = topicPrefix;

        this.mqttQos = mqttQos != null ? mqttQos : MqttQos.AT_LEAST_ONCE;

        int result = mqttBrokerHost != null ? mqttBrokerHost.hashCode() : 0;
        result = 31 * result + portNumber;
        result = 31 * result + (tlsEnabled ? 1 : 0);
        result = 31 * result + (mqttClientId != null ? mqttClientId.hashCode() : 0);
        result = 31 * result + (mqttUsername != null ? mqttUsername.hashCode() : 0);
        result = 31 * result + (mqttPassword != null ? mqttPassword.hashCode() : 0);
        result = 31 * result + topicPrefix.hashCode();
        result = 31 * result + this.mqttQos.hashCode();
        hashCode = result;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BrokerConnectionInfo that = (BrokerConnectionInfo) o;

        if (portNumber != that.portNumber) return false;
        if (tlsEnabled != that.tlsEnabled) return false;
        if (!Objects.equals(mqttBrokerHost, that.mqttBrokerHost)) return false;
        if (!Objects.equals(mqttClientId, that.mqttClientId)) return false;
        if (!Objects.equals(mqttUsername, that.mqttUsername)) return false;
        if (!Objects.equals(topicPrefix, that.topicPrefix)) return false;
        if (!Objects.equals(mqttPassword, that.mqttPassword)) return false;
        return mqttQos == that.mqttQos;
    }

    @Override
    public int hashCode()
    {
        return hashCode;
    }

    public String getMqttBrokerHost()
    {
        return mqttBrokerHost;
    }

    public int getPortNumber()
    {
        return portNumber;
    }

    public boolean isTlsEnabled()
    {
        return tlsEnabled;
    }

    public String getMqttClientId()
    {
        return mqttClientId;
    }

    public String getMqttUsername()
    {
        return mqttUsername;
    }

    public String getMqttPassword()
    {
        return mqttPassword;
    }

    public String getTopicPrefix()
    {
        return topicPrefix;
    }

    public MqttQos getMqttQos()
    {
        return mqttQos;
    }

    /**
     * Given the host, port, and TLS setting, create and return the MQTT broker URI that can be used by the
     * {@link DefaultMqttConnection} client.
     *
     * @param mqttBrokerHost The IP or hostname (hostname preferred) of the MQTT broker.
     * @param portNumber     The port number of the MQTT broker (typically 8883 for TLS, and 1883 for plaintext).
     * @param tlsEnabled     True if SSL/TLS should be used, false if the connection should be plaintext.
     * @return The MQTT Broker URI String that can be use to connect to the MQTT broker.
     */
    public static String getMqttBrokerUriString(String mqttBrokerHost, int portNumber, boolean tlsEnabled)
    {
        final String uriPrefix = tlsEnabled ? SSL_URI_PREFIX : TCP_URI_PREFIX;
        return uriPrefix + mqttBrokerHost + ":" + portNumber;
    }
}
