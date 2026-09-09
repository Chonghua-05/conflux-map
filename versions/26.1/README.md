# NeoForge 26.1

This directory contains the NeoForge 26.1 version module. The canonical
NeoForge source tree is at the repository root; version directories contain
only Minecraft/NeoForge metadata and a thin build-script wrapper.

## Build

From the repository root, use JDK 25:

```powershell
.\gradlew.bat :26.1:build
.\gradlew.bat :26.1:runClient
.\gradlew.bat :26.1:runServer
```

On Linux or macOS, use `./gradlew` with the same task names. The JAR is written
to `versions/26.1/build/libs/`.

The module packages the shared core, native predictor binaries, web-map assets,
and NanoHTTPD dependencies. No other loader API is required.

## Runtime

The produced JAR can be installed on a Minecraft 26.1 client or dedicated
server in `mods/`. The client provides the minimap, fullscreen map, prediction,
waypoints, structure search, drawing, export, and entity radar. Installing it
on a server additionally enables the shared map, shared waypoints, corrections,
chunk-load information, and web map according to the server configuration.

NeoForge's controls screen and mod list provide the configuration entry point.
