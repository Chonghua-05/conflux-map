# NeoForge Branch Architecture

This branch is maintained independently from the Fabric/Paper mainline. Its
build graph is deliberately small and version-oriented:

```text
common/                    loader-independent core
src/main/java/             NeoForge implementation shared by supported versions
src/main/resources/        NeoForge metadata, mixins, and client assets
versions/26.1/             Minecraft/NeoForge 26.1 metadata and build wrapper
versions/26.2/             future version module, when supported
```

## Ownership rules

- `common` must not import Minecraft, NeoForge, Fabric, Bukkit, or rendering
  APIs. Protocols, persistence, prediction, and web-map logic belong here.
- `src` is the only NeoForge implementation source tree. Do not copy it into a
  version directory.
- A version directory contains `gradle.properties` and a thin `build.gradle`
  that applies the root `neoforge.gradle` convention. Version-specific source
  changes are allowed only when the Minecraft API genuinely requires them.
- NeoForge lifecycle, networking, HUD, and resource events are adapted at the
  platform boundary. The rest of the client and server services depend on
  those adapters rather than on a second loader API.
- Every supported version has an independent build and test task. Adding a
  version must not change the source layout or remove coverage from existing
  versions.

## Adding a version

1. Create `versions/<minecraft-version>/gradle.properties` with the Minecraft,
   NeoForge, Java, and mod version values.
2. Add a one-line `build.gradle` applying `../../neoforge.gradle`.
3. Add only the required version-specific resource or source override.
4. Run `./gradlew :<minecraft-version>:build` and update the support table in
   the root README files.

The branch intentionally does not contain Fabric version modules, Fabric
mapping links, Paper build logic, or a loader matrix. Those remain maintained in
the mainline branch.
