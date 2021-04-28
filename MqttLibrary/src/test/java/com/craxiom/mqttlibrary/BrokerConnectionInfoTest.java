package com.craxiom.mqttlibrary;

import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

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

        final BrokerConnectionInfo mqttBrokerConnectionInfo = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, password);

        assertEquals(host, mqttBrokerConnectionInfo.getMqttBrokerHost());
        assertEquals(port, mqttBrokerConnectionInfo.getPortNumber());
        assertEquals(tlsEnabled, mqttBrokerConnectionInfo.isTlsEnabled());
        assertEquals(clientId, mqttBrokerConnectionInfo.getMqttClientId());
        assertEquals(username, mqttBrokerConnectionInfo.getMqttUsername());
        assertEquals(password, mqttBrokerConnectionInfo.getMqttPassword());
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

        final BrokerConnectionInfo mqttBrokerConnectionInfo = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, password);

        assertEquals(host, mqttBrokerConnectionInfo.getMqttBrokerHost());
        assertEquals(port, mqttBrokerConnectionInfo.getPortNumber());
        assertEquals(tlsEnabled, mqttBrokerConnectionInfo.isTlsEnabled());
        assertEquals(clientId, mqttBrokerConnectionInfo.getMqttClientId());
        assertEquals(username, mqttBrokerConnectionInfo.getMqttUsername());
        assertEquals(password, mqttBrokerConnectionInfo.getMqttPassword());
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

        final BrokerConnectionInfo mqttBrokerConnectionInfo1 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, password);
        final BrokerConnectionInfo mqttBrokerConnectionInfo2 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, password);

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

        BrokerConnectionInfo connectionInfo1 = new BrokerConnectionInfo("mqtt.example.com", port, tlsEnabled, clientId, username, password);
        BrokerConnectionInfo connectionInfo2 = new BrokerConnectionInfo("craxiom.com", port, tlsEnabled, clientId, username, password);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(host, 123, tlsEnabled, clientId, username, password);
        connectionInfo2 = new BrokerConnectionInfo(host, 1234, tlsEnabled, clientId, username, password);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(host, port, true, clientId, username, password);
        connectionInfo2 = new BrokerConnectionInfo(host, port, false, clientId, username, password);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(host, port, tlsEnabled, "Pixel4", username, password);
        connectionInfo2 = new BrokerConnectionInfo(host, port, tlsEnabled, "S20", username, password);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, "john", password);
        connectionInfo2 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, "steve", password);
        assertNotEquals(connectionInfo1, connectionInfo2);

        connectionInfo1 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, "bob's password");
        connectionInfo2 = new BrokerConnectionInfo(host, port, tlsEnabled, clientId, username, "bob's burgers");
        assertNotEquals(connectionInfo1, connectionInfo2);
    }
}
