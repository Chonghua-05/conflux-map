# NeoForge 26.1

Experimental platform adapter based on upstream `c00827418a34984db7633d665b0a8ec51837e401`.
This is the NeoForge 26.1 build. The shared map/client services and the
server companion are included. MaliLib is intentionally not part of this target;
NeoForge's built-in mod list is used for the configuration entry point.

## Target

- Minecraft **26.1** (exact version, not 1.21.6 or 26.1.2).
- NeoForge **26.1.0.19-beta**.
- Java **25** and ModDevGradle **2.0.146**.
- Release version `0.1.4`.

The existing Fabric targets and Paper adapter retain their own build scripts.
The port keeps a checked-out preprocessed 26.1 source snapshot under
`src/main/java` and uses small source-level compatibility shims for source areas
that have not yet been replaced by direct NeoForge events. Fabric API is not
bundled in the JAR.

## Build

From the repository root, with a Java 25 JDK selected through `JAVA_HOME`:

```powershell
.\gradlew.bat -p versions/neoforge-26.1 build
.\gradlew.bat -p versions/neoforge-26.1 runClient
.\gradlew.bat -p versions/neoforge-26.1 runServer
```

On Linux/macOS, use `./gradlew` with the same arguments. The nested settings file
loads only this adapter and `common`, avoiding downloads for every Fabric version.
The resulting JAR is under `versions/neoforge-26.1/build/libs/`.

`build` runs the adapter's JUnit tests and `common:check`. Native compilation is
not required: the committed `native/prebuilt` binaries are bundled. The shared
Java classes and web assets are merged into the JAR; NanoHTTPD and its WebSocket
extension are bundled with NeoForge Jar-in-Jar metadata.

## Implemented

- Separate common and physical-client `@Mod` entrypoints.
- NeoForge metadata, client/server development runs and shared-core packaging.
- Native predictor initialization through the common setup event.
- Existing JSON client configuration and the complete client service graph source.
- Manual seed configuration is available in singleplayer as well as multiplayer;
  a saved singleplayer entry explicitly overrides the integrated world's seed for
  client-side prediction.
- Native NeoForge HUD layer registration for the minimap and waypoint item HUD.
- Native NeoForge key mappings under the "Conflux Map" controls category.
- NeoForge mod-list configuration screen extension backed by `ConfigScreen`.
- Server companion lifecycle, region summaries, chunk load tracking, commands,
  web map backend and shared-waypoint services.
- Both `confluxmap:map_sync` and `confluxmap:waypoints_v1` raw-byte play channels,
  with main-thread receivers and stale-connection checks.
- Wire-format tests using the existing released HELLO fixture, including payload
  size boundaries. These tests do not prove cross-loader interoperability.
- Preserve the original map-export cancellation reason when Java 25 wraps a
  cancelled future in another `CancellationException` with the message `get`.

The NeoForge entrypoint declares both application channels before
`RegisterPayloadHandlersEvent`; the existing handshake and shared-waypoint
receivers bind during companion/client service initialization.

## Verification

Verified on Windows x64 with Temurin Java 25.0.4.1 and Gradle 9.1.0:

- Standalone `build`: successful.
- Shared core: 1,066 tests, zero failures/errors/skips.
- NeoForge transport: 5 tests, zero failures/errors/skips.
- Shared-platform isolation check: passed, including the new NeoForge import rule.
- JAR contents checked: expanded NeoForge metadata, client/server classes, shared
  web resources, native libraries and both NanoHTTPD Jar-in-Jar dependencies. No
  Fabric API classes or Fabric metadata are bundled.
- `runServer`: successful dedicated-server startup through world generation;
  logs reached `Conflux Map ... NeoForge adapter loaded`, `companion ready` and
  `web map listening`. A development server with the mod is currently available
  on `127.0.0.1:25565` from the NeoForge run directory.
- `runClient`: successful client startup, resource loading and integrated-world
  login; logs reached `Conflux Map client services started (6 workers)`, sent both
  Conflux Map HELLO packets and started a map session without client errors before
  the process was stopped. The client registers its NeoForge HUD layer, key
  mappings and mod-list configuration extension during startup.

The repository wrapper remains on Gradle 9.5.0. Its initial distribution download
was interrupted by the network; verification used an already cached Gradle 9.1.0
installation. A Java 25 JDK was downloaded into the ignored `tools/jdk25/` folder
for this workspace, without changing the system Java installation or PATH.

An automated client-to-server login and visual verification of the retained HUD/
world renderer have not been completed. CI or a maintainer workstation should
connect `runClient` to `runServer` and inspect the fullscreen map before calling
the port playable.

## Remaining Work

1. Replace the remaining no-op resource-reload compatibility shim with a direct
   NeoForge reload-listener event and validate resource-pack reload behavior.
2. Convert the three access-widener entries in `versions/26.1.2` to access
   transformers or mixin accessors and verify every mixin target in 26.1.
3. Add a CI smoke job that starts `runServer`, launches `runClient` under Xvfb,
   checks the HELLO handshake and opens the fullscreen map for several frames.

Reference APIs were checked against the published NeoForge 26.1.0.19-beta source
artifact and the official [26.1 MDK](https://github.com/NeoForgeMDKs/MDK-26.1-NeoGradle)
and [ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle).
