package com.craxiom.mqttlibrary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/**
 * Tests the {@link MqttQos} enum.
 *
 * @since 0.8.0
 */
public class MqttQosTest
{
    @Test
    public void validateQosValues()
    {
        assertEquals(0, MqttQos.AT_MOST_ONCE.getValue());
        assertEquals(1, MqttQos.AT_LEAST_ONCE.getValue());
        assertEquals(2, MqttQos.EXACTLY_ONCE.getValue());
    }

    @Test
    public void validateFromValue()
    {
        assertEquals(MqttQos.AT_MOST_ONCE, MqttQos.fromValue(0));
        assertEquals(MqttQos.AT_LEAST_ONCE, MqttQos.fromValue(1));
        assertEquals(MqttQos.EXACTLY_ONCE, MqttQos.fromValue(2));
    }

    @Test
    public void validateFromValueInvalidNegative()
    {
        assertThrows(IllegalArgumentException.class, () -> MqttQos.fromValue(-1));
    }

    @Test
    public void validateFromValueInvalidThree()
    {
        assertThrows(IllegalArgumentException.class, () -> MqttQos.fromValue(3));
    }

    @Test
    public void validateFromValueInvalidLarge()
    {
        assertThrows(IllegalArgumentException.class, () -> MqttQos.fromValue(100));
    }
}
