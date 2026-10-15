package com.craxiom.mqttlibrary;

import com.craxiom.mqttlibrary.connection.ConnectionState;

/**
 * Listener interface for those interested in keeping track of a connection state so they can update themselves
 * appropriately.
 *
 * @since 0.1.0
 */
public interface IConnectionStateListener
{
    /**
     * Called when the Connection State changes, on the thread that changed it: the caller of
     * connect or disconnect, or one of the MQTT client's threads. Don't block in it.
     *
     * @param newConnectionState the new Connection State.
     */
    void onConnectionStateChange(ConnectionState newConnectionState);
}
