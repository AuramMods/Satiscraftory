# Satiscraftory

Satiscraftory is a Minecraft Forge mod that currently adds a functional conveyor belt system inspired by factory automation gameplay.

## Version And Compatibility

| Component | Value |
| --- | --- |
| Mod ID | `satiscraftory` |
| Mod Name | `Satiscraftory` |
| Mod Version | `1.0.0` |
| Minecraft Version | `1.20.1` |
| Forge Version | `47.4.10` |
| Java Target | `17` (toolchain) |
| Gradle Wrapper | `8.8` |

The authoritative values live in `gradle.properties` and are injected into `mods.toml` during `processResources`.

## What The Mod Does

Current content is centered around one transport block:

- `satiscraftory:conveyor` (plus matching block item and creative tab entry).
- Automatically resolves shape and direction when placed near other conveyors.
- Supports these conveyor shapes:
  - `straight`
  - `left`
  - `right`
  - `up`
  - `down`
- Moves items through a per-belt internal buffer (`5` slots by default).
- Connects across belt networks with strict input/output validation.
- Can pull items from adjacent inventories and push items into downstream inventories using Forge `ITEM_HANDLER` capability.
- Renders client-side moving item visuals on belts.

## Current Scope / Limitations

- Only the conveyor block is implemented at this time.
- There is no crafting recipe JSON yet; the block is currently easiest to access from the creative tab.
- Extra runtime mod jars in `extra-mods-1.20.1/` are loaded only for local runtime/dev and are not bundled into this mod jar.

## Requirements

- JDK 17+ available locally.
- Internet access on first Gradle run to resolve dependencies.
- A Forge 1.20.1 environment to run the built jar.

## Build

From the project root:

```bash
./gradlew clean build
```

On Windows:

```bat
gradlew.bat clean build
```

### Build Outputs

- Main jar: `build/libs/satiscraftory-1.0.0.jar`
- Reobf output: `build/reobfJar/output.jar`

This project also runs `deployToModpack` automatically after `build`, which copies the built jar to:

`/Users/cyberpwn/Library/Application Support/PrismLauncher/instances/Auram/minecraft/mods`

That destination is configured by `modpack_deploy` in `gradle.properties`.

## Build Task Notes

- Skip automatic deploy copy:

```bash
./gradlew build -x deployToModpack
```

- Override deploy destination for one build:

```bash
./gradlew build -Pmodpack_deploy="/path/to/your/mods"
```

## Development Tasks

- Run client: `./gradlew runClient`
- Run dedicated server: `./gradlew runServer`
- Run game test server: `./gradlew runGameTestServer`
- Generate data: `./gradlew runData`

Runtime directories used by Gradle runs:

- Game run dir: `run/`
- Data run dir: `run-data/`

## Project Structure

- Mod entrypoint: `src/main/java/art/arcane/satiscraftory/Satiscraftory.java`
- Conveyor block logic: `src/main/java/art/arcane/satiscraftory/block/ConveyorStraightBlock.java`
- Conveyor block entity / transport logic: `src/main/java/art/arcane/satiscraftory/block/entity/ConveyorStraightBlockEntity.java`
- Mod metadata: `src/main/resources/META-INF/mods.toml`
- Build configuration: `build.gradle`, `gradle.properties`

## Updating Version

To release a new version, bump `mod_version` in `gradle.properties` and rebuild.
