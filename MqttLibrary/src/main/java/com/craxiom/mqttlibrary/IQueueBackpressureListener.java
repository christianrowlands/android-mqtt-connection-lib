package com.craxiom.mqttlibrary;

/**
 * Listener interface for receiving notifications about MQTT message queue backpressure.
 * <p>
 * When the message queue reaches its configured limit, scanning operations should be paused
 * to prevent out-of-memory crashes. When the queue drains sufficiently, scanning can resume.
 *
 * @since 1.1.0
 */
public interface IQueueBackpressureListener
{
    /**
     * Called when the message queue has reached its limit and scanning should be paused.
     *
     * @param queueSize  The current number of pending messages in the queue.
     * @param queueLimit The configured maximum queue size.
     */
    void onQueueFull(int queueSize, int queueLimit);

    /**
     * Called when the message queue has drained sufficiently and scanning can resume.
     *
     * @param queueSize  The current number of pending messages in the queue.
     * @param queueLimit The configured maximum queue size.
     */
    void onQueueDrained(int queueSize, int queueLimit);
}
