# MQTT Connection Library

## What is it?
The MQTT Connection Library provides a simple connection UI as well as logic to perform an MQTT connection in an Android app. If additional options are needed for the connection (beyond the provided), there is a capability that can be utilized. Please read Development Instructions for more detail.

## How to Use this Library
It is important to note that the pieces provided in the library cannot stand on their own. Most classes are to be extended or implemented, as an "MQTT Connection" relies on a service that drives the logic using the connection. We will go over the correct way to wire everything together below.
1. Ensure that the correct version of the library is included in the consuming project's `build.gradle` file, under its dependencies:<br><br>
   ```
   implementation 'com.craxiom:mqttlibrary:0.4.4'
   ```
2. Next, for the connection UI, the `fragment_mqtt_connection` is readily available under `res/layout`; however, `AConnectionFragment` must still be extended, or `DefaultConnectionFragment`.<br>Note: Users can extend the latter if they do not wish to add extra UI components. Otherwise, extend `AConnectionFragment`, which contains methods marked with "additional" in their names and <i>must</i> be overridden in the child class.<br>
  &ensp; a. Whichever fragment ends up being extended, it will require a binder parameter. This binder should extend the provided `AConnectionFragment#ServiceBinder` in order to be recognized. Likely, this binder will be located in the `IMqttService` implementation of the consuming project.
3. Moving on to the service interface, `IMqttService`: The fragment relies on the service to perform the connection backend logic. For example, if the user toggles the connection switch, the fragment will call `IMqttService#connectToMqttBroker`.
4. The `IMqttService` implementation should ideally include a `DefaultMqttConnection`, which will perform connection/disconnection.
5. Additionally, the `BrokerConnectionInfo` holds relevant information in order to successfully make an MQTT connection. It is important to note that one should extend this class if additional fields are needed and these fields should correspond to the additional fragment UI components, if any.

![Example UI](screenshots/additional_fields.png "Example Connection UI With Additional Fields")


## Build Instructions
 - Execute `gradlew build` in the root directory to produce the Android AAR library.
 - Execute `gradlew publishDebugPublicationToMavenLocal` in the root directory to publish the debug aar artifact to the local Maven cache.
 - Execute `gradlew publishReleasePublicationToMavenLocal` in the root directory to publish the release aar artifact to the local Maven cache.


## Change log
##### [0.4.5](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.5) - 2022-06-16
 * Made MQTT connection fields protected so they can be manipulated from deriving classes
 * Updated some dependencies

##### [0.4.4](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.4) - 2022-06-11
 * Added some defensive programming around the service being null when the mdm override switch is toggled.
 * Updated several of the libraries.

##### [0.4.3](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.3) - 2021-07-10
 * Added some defensive programming when updating the UI state if the fragment has been detached.

##### [0.4.2](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.2) - 2021-06-28
 * Fixed a bug where the MQTT connection would not reestablish when the Internet connection dropped.

##### [0.4.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.1) - 2021-06-11
 * Fixed a bug where a UI deadlock could occur when the connection is unsuccessful and messages are published.
 * Improved UX for showing the user connection error reasons such as invalid username and password.

##### [0.4.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.4.0) - 2021-06-10
 * Added support for queueing messages while the MQTT broker connection is offline and resending them when a connection comes back online.

##### [0.3.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.3.0) - 2021-06-03
 * Changed the QoS from 0 to 1 for the MQTT connection.

##### [0.2.1](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.2.1) - 2021-04-30
 * Added some additional protections against concurrent MQTT connections.

##### [0.2.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.2.0) - 2021-04-28
 * Switched out the Eclipse Paho MQTT Client for HiveMQ to improve stability and error scenario handling.

##### [0.1.0](https://github.com/christianrowlands/android-mqtt-connection-lib/releases/tag/v0.1.0) - 2020-12-22
 * Initial release of this Android MQTT Connection Library.

## Contact
* **Eliza Alcaraz** - [eliza-mae-alcaraz](https://github.com/eliza-mae-alcaraz)
* **Christian Rowlands** - [Craxiom](https://github.com/christianrowlands)
