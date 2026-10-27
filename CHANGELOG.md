# Changelog

## [2.0.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v2.0.1) - 2026-10-27
* Fixed: messages waiting to be sent while the connection was down were thrown away when the broker came back without the client's session (a broker restart without persistence, an evicted session, or another node of a load-balanced cluster). They are now sent again once the client reconnects.

## [2.0.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v2.0.0) - 2026-10-15
* **Breaking:** the library is split into two artifacts. `mqtt-library` is now only the connection (`DefaultMqttConnection`, `BrokerConnectionInfo`, `ConnectionState`, `IMqttService`, `MqttConstants`, `MqttQos` and the listener interfaces), with no UI and no AppCompat or Material dependency. The connection fragment (`AConnectionFragment`, `DefaultConnectionFragment`), `ICredentialStorage`, and the layouts and UI resources move to the new `mqtt-library-ui` artifact, which depends on `mqtt-library`. Package names are unchanged, so an app that uses the fragment only adds `implementation 'com.craxiom:mqtt-library-ui:2.0.0'`. The UI resources now live in the `com.craxiom.mqttlibrary.ui` namespace.
* **Breaking:** the `-debug` artifacts are no longer published.
* **Breaking:** `mqtt-library` no longer shows a Toast when the broker refuses the user name or password. Read `getLastFailure()` instead; the fragment in `mqtt-library-ui` still shows the message. The `Context` passed to `connect()` is no longer used.
* The connection now reports CONNECTING as soon as a connection attempt starts, and DISCONNECTING when a disconnect is requested.
* Messages published before the first successful connection are now held and sent, in order, once the broker connects, instead of being thrown away. They are bounded by the streaming queue limit, or by `DEFAULT_HOLD_LIMIT` (20,000) when no limit is set; when full, new messages are dropped and the backpressure listeners are told, as for the streaming queue. Turning the connection off discards them.
* `getPendingMessageCount()` now counts every message waiting to be sent, including held ones, whether or not a queue limit is set.
* New `hasConnectedOnce()`, `getDroppedMessageCount()` and `getLastFailure()`. The new `ConnectionFailure` enum says why the latest attempt failed: `SIGN_IN_REJECTED` (the broker refused the user name or password; no more retries), `UNREACHABLE` (retrying on its own) or `INVALID_SETTINGS` (the connection could not be created, for example an empty host or a `#` or `+` in the topic prefix; no retries).
* `IMqttService` has a new default method, `getMqttConnectionFailure()`, so the fragment can tell why a connection failed.
* Fixed: moving to another broker (disconnect, then connect) could end in DISCONNECTED once the old connection finished closing.
* Fixed: messages stuck in a client that was replaced no longer count as pending for the next connection.
* `protobuf-java-util` is now an `api` dependency of `mqtt-library`, and AppCompat and Material are `api` dependencies of `mqtt-library-ui`.

## [1.3.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v1.3.0) - 2026-03-24
* Add a credential storage interface to allow the implementer of the MQTT connection to decide how they want to store the MQTT credentials.

## [1.2.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v1.2.0) - 2026-02-20
* Replace SwitchCompat with the newer MaterialSwitch to enable supporting more devices (prevents a crash on certain older devices).

## [1.1.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v1.1.0) - 2026-01-26
* Adds back pressure support to the MQTT queue with the ability to set the max queue size and a listener interface for notifications of back pressure events.
* Prevent an edge case bug where the hive client blocks the message processing thread because the hive client has not successfully connected to the MQTT broker at least once. See https://github.com/hivemq/hivemq-mqtt-client/issues/612 for more details.

## [1.0.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v1.0.0) - 2025-12-17
* Fix an edge case bug where an old unsuccessful connection attempt was being retried and causing the current connection to be disconnected.
* Adds an MQTT QoS setting to the connection info and connection UI.
* Remove the help card from the MQTT connection UI.
* Update the protobuf, hive mqtt, and other libraries.
* Update to AGP 8.13.2.

## [0.7.5](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.5) - 2025-02-12
* Update the protobuf and hive mqtt libraries.

## [0.7.4](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.4) - 2024-11-04
* Center the connection status text vertically.
* Update the gradle version and the Android SDK version to 35.

## [0.7.3](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.3) - 2024-08-23
* Change the access modifier of the storeConnectionParameters method to protected so it can be called.

## [0.7.2](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.2) - 2024-02-22
* Fixed a memory leak where the service connection was not unbound when the fragment was paused.

## [0.7.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.1) - 2024-02-19
* Handle the edge case where the app has been minimized while the service is starting.
* Update the hive MQTT client.

## [0.7.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.7.0) - 2023-12-20
* Add support for setting a topic prefix for the MQTT topics that this library publishes to.

## [0.6.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.6.0) - 2023-07-17
* Overload the publishMessage method to enable sending plain JSON strings.

## [0.5.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.5.0) - 2022-10-25
* Change the onMdmOverride method to protected so it can be overridden

## [0.4.5](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.5) - 2022-06-16
* Made MQTT connection fields protected so they can be manipulated from deriving classes.
* Updated some dependencies.

## [0.4.4](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.4) - 2022-06-11
* Added some defensive programming around the service being null when the mdm override switch is toggled.
* Updated several of the libraries.

## [0.4.3](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.3) - 2021-07-10
* Added some defensive programming when updating the UI state if the fragment has been detached.

## [0.4.2](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.2) - 2021-06-28
* Fixed a bug where the MQTT connection would not reestablish when the Internet connection dropped.

## [0.4.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.1) - 2021-06-11
* Fixed a bug where a UI deadlock could occur when the connection is unsuccessful and messages are published.
* Improved UX for showing the user connection error reasons such as invalid username and password.

## [0.4.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.0) - 2021-06-10
* Added support for queueing messages while the MQTT broker connection is offline and resending them when a connection comes back online.

## [0.3.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.3.0) - 2021-06-03
* Changed the QoS from 0 to 1 for the MQTT connection.

## [0.2.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.2.1) - 2021-04-30
* Added some additional protections against concurrent MQTT connections.

## [0.2.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.2.0) - 2021-04-28
* Switched out the Eclipse Paho MQTT Client for HiveMQ to improve stability and error scenario handling.

## [0.1.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.1.0) - 2020-12-22
* Initial release of this Android MQTT Connection Library.
