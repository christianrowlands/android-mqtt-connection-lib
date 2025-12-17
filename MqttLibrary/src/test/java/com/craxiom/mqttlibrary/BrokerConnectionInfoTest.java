package com.craxiom.mqttlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;

import org.junit.Test;

/**
 * Tests the {@link BrokerConnectionInfo} class.
 *
 * @since 0.2.0
 */
public class BrokerConnectionInfoTest
{
    @Test
    public void validateTlsEnableMqttConnectionUri()
    {
        final String host = "mqtt.example.com";
        final int port = 8883;
        final boolean tlsEnabled = true;
        final String clientId = "Pixel3a";
        final String username = "bob";
        final String password = "bob's password";
        final MqttQos qos = MqttQos.AT_LEAST_ONCE;

        final BrokerConnectionInfo mqttBrokerConnectionInfo = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", qos);

        assertEquals(host, mqttBrokerConnectionInfo.getMqttBrokerHost());
        assertEquals(port, mqttBrokerConnectionInfo.getPortNumber());
        assertEquals(tlsEnabled, mqttBrokerConnectionInfo.isTlsEnabled());
        assertEquals(clientId, mqttBrokerConnectionInfo.getMqttClientId());
        assertEquals(username, mqttBrokerConnectionInfo.getMqttUsername());
        assertEquals(password, mqttBrokerConnectionInfo.getMqttPassword());
        assertEquals(qos, mqttBrokerConnectionInfo.getMqttQos());
    }

    @Test
    public void validatePlaintextMqttConnectionUri()
    {
        final String host = "mqtt.example.com";
        final int port = 1883;
        final boolean tlsEnabled = false;
        final String clientId = "Pixel3a";
        final String username = "bob";
        final String password = "bob's password";
        final MqttQos qos = MqttQos.EXACTLY_ONCE;

        final BrokerConnectionInfo mqttBrokerConnectionInfo = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", qos);

        assertEquals(host, mqttBrokerConnectionInfo.getMqttBrokerHost());
        assertEquals(port, mqttBrokerConnectionInfo.getPortNumber());
        assertEquals(tlsEnabled, mqttBrokerConnectionInfo.isTlsEnabled());
        assertEquals(clientId, mqttBrokerConnectionInfo.getMqttClientId());
        assertEquals(username, mqttBrokerConnectionInfo.getMqttUsername());
        assertEquals(password, mqttBrokerConnectionInfo.getMqttPassword());
        assertEquals(qos, mqttBrokerConnectionInfo.getMqttQos());
    }

    @Test
    public void validateMqttConnectionInfoEquals_correct()
    {
        final String host = "mqtt.example.com";
        final int port = 1883;
        final boolean tlsEnabled = false;
        final String clientId = "iPhone";
        final String username = "bob";
        final String password = "bob's password";
        final MqttQos qos = MqttQos.AT_LEAST_ONCE;

        final BrokerConnectionInfo mqttBrokerConnectionInfo1 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", qos);
        final BrokerConnectionInfo mqttBrokerConnectionInfo2 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", qos);

        assertEquals(mqttBrokerConnectionInfo1, mqttBrokerConnectionInfo2);
    }

    @Test
    public void validateMqttConnectionInfoEquals_invalid()
    {
        final String host = "mqtt.example.com";
        final int port = 1883;
        final boolean tlsEnabled = false;
        final String clientId = "iPhone";
        final String username = "bob";
        final String password = "bob's password";
        final MqttQos qos = MqttQos.AT_LEAST_ONCE;

        BrokerConnectionInfo connectionInfo1 = new BrokerConnectionInfo(
                "mqtt.example.com", port, tlsEnabled, clientId, username, password, "", qos);
        BrokerConnectionInfo connectionInfo2 = new BrokerConnectionInfo(
                "craxiom.com", port, tlsEnabled, clientId, username, password, "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(
                host, 123, tlsEnabled, clientId, username, password, "", qos);
        connectionInfo2 = new BrokerConnectionInfo(
                host, 1234, tlsEnabled, clientId, username, password, "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(
                host, port, true, clientId, username, password, "", qos);
        connectionInfo2 = new BrokerConnectionInfo(
                host, port, false, clientId, username, password, "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(
                host, port, tlsEnabled, "Pixel4", username, password, "", qos);
        connectionInfo2 = new BrokerConnectionInfo(
                host, port, tlsEnabled, "S20", username, password, "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, "john", password, "", qos);
        connectionInfo2 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, "steve", password, "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, "bob's password", "", qos);
        connectionInfo2 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, "bob's burgers", "", qos);
        assertNotEquals(connectionInfo1, connectionInfo2);

        // Test QoS inequality
        connectionInfo1 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", MqttQos.AT_MOST_ONCE);
        connectionInfo2 = new BrokerConnectionInfo(
                host, port, tlsEnabled, clientId, username, password, "", MqttQos.EXACTLY_ONCE);
        assertNotEquals(connectionInfo1, connectionInfo2);
    }

    @Test
    public void validateNullQosDefaultsToAtLeastOnce()
    {
        final BrokerConnectionInfo connectionInfo = new BrokerConnectionInfo(
                "mqtt.example.com", 8883, true, "client", "user", "pass", "", null);

        assertEquals(MqttQos.AT_LEAST_ONCE, connectionInfo.getMqttQos());
    }

    @Test
    public void validateAllQosLevels()
    {
        for (MqttQos qos : MqttQos.values())
        {
            final BrokerConnectionInfo connectionInfo = new BrokerConnectionInfo(
                    "mqtt.example.com", 8883, true, "client", "user", "pass", "", qos);

            assertEquals(qos, connectionInfo.getMqttQos());
        }
    }
}
