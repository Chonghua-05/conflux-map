# Conflux Map for NeoForge

This branch is the independent NeoForge line of Conflux Map. It is intentionally
separate from the Fabric/Paper mainline and may contain several NeoForge
Minecraft versions without requiring the mainline version matrix.

## Supported versions

| Module | Minecraft | NeoForge | Java |
|---|---|---|---|
| `26.1` | 26.1 | 26.1.0.19-beta | 25 |

Additional NeoForge versions are added as `versions/<minecraft-version>` modules
when they are supported. Those directories contain only version metadata and a
thin build wrapper; the canonical NeoForge source is in `src/`.

## Install

Download the JAR for the target Minecraft version from Releases and put it in
the `mods/` directory. The same JAR works on the client and on a dedicated
server. No additional loader or companion API is required.

The client provides the minimap, fullscreen map, prediction, waypoints,
structure search, drawing, PNG export, and entity radar. The server companion
adds shared map data, shared waypoints, corrections, chunk-load information,
and the browser web map according to `config/confluxmap/server.json`.

## Build

Use JDK 25 from the repository root:

```sh
./gradlew :26.1:build
./gradlew :26.1:runClient
./gradlew :26.1:runServer
```

Windows users can run the same tasks with `gradlew.bat`. Build artifacts are
written to `versions/26.1/build/libs/`.

## Layout

- `common/`: loader-independent protocol, storage, prediction, and web-map core.
- `src/`: NeoForge client/server implementation shared by supported versions.
- `versions/`: version properties and per-version build wrappers.
- `native/`: predictor sources and committed platform binaries.

## License

GPL-3.0. See [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
