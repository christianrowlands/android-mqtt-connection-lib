package com.craxiom.mqttlibrary.connection;

/**
 * Why the latest connection attempt to the MQTT broker failed, so an app can tell its user what to
 * fix instead of showing "Connecting" forever.
 *
 * @since 2.0.0
 */
public enum ConnectionFailure
{
    /**
     * The broker refused the user name or password (CONNACK "bad user name or password" or "not
     * authorized"). The connection stops retrying.
     */
    SIGN_IN_REJECTED,

    /**
     * The broker could not be reached or the connection to it dropped. The connection keeps
     * retrying on its own.
     */
    UNREACHABLE,

    /**
     * The connection could not be created from the settings, for example an empty host or a topic
     * prefix with a wildcard. The connection does not retry.
     */
    INVALID_SETTINGS
}
