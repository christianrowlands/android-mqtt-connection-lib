package com.craxiom.mqttlibrary;

/**
 * Represents MQTT Quality of Service levels.
 * <p>
 * This enum abstracts the underlying HiveMQ client's QoS implementation,
 * allowing library consumers to specify QoS without depending on the HiveMQ library directly.
 */
public enum MqttQos
{
    /**
     * QoS 0: At most once delivery. Messages are delivered at most once, or may not be delivered at all.
     * No acknowledgment is sent, and no retry is performed.
     */
    AT_MOST_ONCE(0),

    /**
     * QoS 1: At least once delivery. Messages are guaranteed to arrive, but duplicates may occur.
     * The sender stores the message until it receives an acknowledgment.
     */
    AT_LEAST_ONCE(1),

    /**
     * QoS 2: Exactly once delivery. Messages are guaranteed to arrive exactly once.
     * This is the safest but slowest QoS level.
     */
    EXACTLY_ONCE(2);

    private final int value;

    MqttQos(int value)
    {
        this.value = value;
    }

    /**
     * @return The numeric QoS value (0, 1, or 2).
     */
    public int getValue()
    {
        return value;
    }

    /**
     * Creates an MqttQos from an integer value.
     *
     * @param value The QoS integer value (0, 1, or 2).
     * @return The corresponding MqttQos enum value.
     * @throws IllegalArgumentException if the value is not 0, 1, or 2.
     */
    public static MqttQos fromValue(int value)
    {
        switch (value)
        {
            case 0:
                return AT_MOST_ONCE;
            case 1:
                return AT_LEAST_ONCE;
            case 2:
                return EXACTLY_ONCE;
            default:
                throw new IllegalArgumentException("Invalid QoS value: " + value + ". Must be 0, 1, or 2.");
        }
    }
}
