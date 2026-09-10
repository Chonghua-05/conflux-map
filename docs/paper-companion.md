# Server companion (`paper/` module)

> **This branch targets Folia only.** Everything below describes the regionised runtime.
> The module directory is still `paper/` and the classes still use the `Paper` prefix because
> Folia *is* a Paper fork and the adapter speaks the Paper API; keeping the layout identical to
> `master` is deliberate — it is the same shape the NeoForge branch keeps when it replaces the
> contents of `src/` — so that fixes travel between the branches by path instead of by rename.

The standalone `confluxmap-paper` plugin provides the same public companion protocols as the
Fabric server entrypoint. One artifact targets Paper 1.21.1 through 26.2. It is deliberately
separate from the version-specific Fabric client artifacts: bundling Bukkit classes into every
remapped Fabric jar would enlarge every client download and create avoidable class-loading and
compatibility surfaces.

## Folia thread model

Folia splits each world into independently ticked regions, so the adapter follows three rules.
`PaperPlatform` is the only place that talks to a scheduler, and every call goes through
`io.papermc.paper.threadedregions` — Paper implements those schedulers too, which is why there is
a single code path rather than one per platform.

| Scope | Owns | May touch |
|---|---|---|
| Global region | correction service, invalidation publishers, subscription tables | nothing region-owned |
| Region | chunks | its own chunk snapshots and load levels |
| Entity | players | that player's position, profile and permissions |

Two consequences worth knowing before changing anything here:

- **Shared state stays on one logical thread.** `MapInvalidationPublisher`,
  `MapRegionInvalidationPublisher`, `LiveChunkSummaryCache` and `PlayerBudget` live in `common/`
  and are not thread safe, so plugin-message and web requests queue onto the global region exactly
  as they used to queue onto the Bukkit main thread.
- **Player state is never read from the global tick.** `PaperPlayerProbe` asks each player, on its
  own scheduler, to publish an immutable snapshot; the position broadcast, the web map and the
  shared-waypoint tick all consume those snapshots.

Two features behave differently on a regionised server:

- **Chunk load state is off.** Publishing it would mean polling `Chunk.LoadLevel` — region-owned
  state — for every loaded chunk, thousands of region tasks per tick. The service is never
  instantiated and clients are told during the handshake that the feature is unavailable.
- **The initial loaded-chunk scan is skipped.** `World#getLoadedChunks()` does not exist on a
  regionised server, so live summaries start from the chunk loads that follow startup. A world
  that was already loaded when the plugin enabled contributes its summaries on the next load.


## Installation

1. Build with `./gradlew :paper:build`, or download the Paper artifact from a release.
2. Put `confluxmap-paper-<version>.jar` in the Paper server's `plugins/` directory.
3. Keep the normal version-specific Conflux Map Fabric jar on each client.
4. Start the server once to create `config/confluxmap/server.json`, then adjust the opt-in policy.

For local development, run `./gradlew :paper:runServer`. The task builds the plugin, downloads a
Paper 1.21.1 development server, installs the new jar, and keeps its disposable state under the
ignored `paper/run/` directory. The first invocation writes `paper/run/eula.txt`; review the
Minecraft EULA, change that file to `eula=true`, and invoke the task again. That task exercises
the Paper flavour of the scheduler only.

To smoke-test the regionised runtime the branch actually targets, run a Folia server by hand.
Folia 26.x needs Java 25:

```sh
curl -sL -o folia.jar \
  "$(curl -s https://fill.papermc.io/v3/projects/folia/versions/26.1.2/builds \
     | python -c 'import json,sys; print(json.load(sys.stdin)[-1]["downloads"]["server:default"]["url"])')"
./gradlew :paper:jar
mkdir -p run-folia/plugins
cp paper/build/libs/confluxmap-paper-*.jar run-folia/plugins/
printf 'eula=true\n' > run-folia/eula.txt
cd run-folia && java -Xmx2G -jar ../folia.jar nogui
```

A healthy start logs `Skipping the initial loaded-chunk scan`, one line per dimension,
`Web map listening on 127.0.0.1:8123`, and `Paper companion ready`. `Chunk load state stays
disabled` appears when `shareChunkLoadState` is on. Any `ConfluxMap` exception, or a Folia
complaint about accessing state from the wrong thread, means the rules above were broken.

Set `enable-rcon=true` and a password in `server.properties` before shutting down: neither
`SIGTERM` nor a piped stdin stops a Folia server on Windows, and a hard kill skips `onDisable`,
which is where the tick task, the web map and every subscription are released.

The plugin bytecode targets Java 21 and supports the Paper API only. Run the
[Java version required by Paper](https://docs.papermc.io/paper/getting-started/): Java 21 for Paper
through 1.21.11 and Java 25 for Paper 26.x — and therefore Java 25 for current Folia builds.
Spigot and CraftBukkit are outside the supported runtime contract, as is any server that does not
implement the threaded-regions schedulers. A proxy needs no companion plugin as long as it
transparently forwards Minecraft plugin messages.

## Compatibility and protocol

The plugin registers the existing `confluxmap:map_sync` and `confluxmap:waypoints_v1` plugin
message channels. It uses the same message codecs, negotiation rules, payload caps, policy flags,
per-player request spacing, pending-work bounds, and bandwidth token buckets as the Fabric
companion. Matching and mismatched client predictor versions therefore follow the existing
feature-level compatibility rules; the server never rejects a client solely because its mod
version string differs.

Matching predictor profiles use the bundled native baseline to send compact residual records.
Predictor mismatches, unavailable native support, custom generators, and dimensions without a
supported baseline fall back per response to authoritative absolute records. This preserves the
wire contract and visual result without relying on Paper internals or version-specific NMS
mappings.

## Terrain access

Loaded chunks are captured on the Paper main thread as immutable `ChunkSnapshot` values. Summary
work then uses the platform-neutral column seam. Unloaded chunks are read directly from the
world's `.mca` and external `.mcc` files by a bounded read-only scanner supporting GZIP, ZLIB,
uncompressed, and LZ4 Anvil payloads. Correction requests never call `getChunkAt` and therefore do
not generate terrain as a side effect.

The optional web map (`webMap.enabled`) is a browser client on the same wire protocol: its tile
and correction requests run through this same terrain-access and correction-sync path rather than
a separate reader.

Disk scans and patch construction run on two daemon workers. Plugin messages and all Bukkit world
access stay on the main thread. Live summaries use Fabric's demand window and two-chunk-per-tick
main-thread ceiling, further bounded by `maxChunkSummariesPerSecond`; chunk changes publish both
tile and exact-region invalidations. LOD 3/4 legacy tile requests scan progressively and return
`PARTIAL` while incomplete, so a coarse request cannot monopolize a worker or the server thread.

## Data ownership

- Policy: `<server-root>/config/confluxmap/server.json`, using the same schema as Fabric.
- Stable world ID: `<primary-world>/confluxmap/world_uuid.json`.
- Shared waypoints: `<primary-world>/confluxmap/shared_waypoints.json`.
- Web-map player opt-outs: `<primary-world>/confluxmap/webmap-hidden.txt`.
- Terrain source: each Bukkit world's resolved `region/` directory. Standard Nether and End
  `DIM-1/region` and `DIM1/region` layouts and Paper 26.x namespaced
  `dimensions/<namespace>/<dimension>/region` layouts are recognized when present.

The primary world is the first loaded normal-environment world. Dimension indexes are append-only
for the plugin lifetime so a late world load cannot change an index already sent to clients.

## Commands

- `/confluxmap performance` shows the current player's completed correction-sync averages.
- `/confluxmap webmap hide|show` controls whether that player appears on the optional public radar.
- `/confluxmap waypoints list [page]` lists public waypoints. Each entry includes a standard Xaero
  share message, so Xaero's Minimap users can add it from chat without installing Conflux Map.
- `/confluxmap waypoints add <name>` publishes the player's current position.
- `/confluxmap waypoints edit <id> <name>` renames a public waypoint.
- `/confluxmap waypoints move <id>` moves a public waypoint to the player's current position.
- `/confluxmap waypoints delete <id>` deletes a public waypoint.
- `/confluxmap waypoints status` shows the effective shared-waypoint state and quotas.
- `/confluxmap waypoints enable` loads storage, atomically persists the setting, then advertises it.
- `/confluxmap waypoints disable` blocks mutations immediately and persists the setting.

Listing is available to every player. By default, ordinary players may upload, edit, move, and
delete only waypoints they published; operators may manage every waypoint. Setting
`allowNonOperatorSharedWaypointManagement` to `false` restricts all mutations to
`confluxmap.admin`, granted to operators by default. Feature toggles and status inspection always
require `confluxmap.admin`. The accepted short ID is shown by `waypoints list`.
