package com.craxiom.mqttlibrary;

/**
 * Interface for secure credential storage. Implementations can provide encrypted or
 * otherwise secure storage for MQTT credentials instead of using plain SharedPreferences.
 * <p>
 * When provided via {@link com.craxiom.mqttlibrary.ui.AConnectionFragment#getCredentialStorage()},
 * the fragment will delegate credential read/write operations to this interface instead of
 * storing credentials in SharedPreferences.
 *
 * @since 1.3.0
 */
public interface ICredentialStorage
{
    /**
     * Store the MQTT username and password securely.
     *
     * @param username The MQTT username to store.
     * @param password The MQTT password to store.
     */
    void storeCredentials(String username, String password);

    /**
     * Retrieve the stored MQTT username.
     *
     * @return The stored username, or an empty string if no username is stored.
     */
    String getUsername();

    /**
     * Retrieve the stored MQTT password.
     *
     * @return The stored password, or an empty string if no password is stored.
     */
    String getPassword();
}
