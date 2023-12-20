# Changelog

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
