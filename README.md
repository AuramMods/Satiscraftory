# Satiscraftory

Satiscraftory is a Minecraft Forge mod that currently adds a functional conveyor belt system inspired by factory automation gameplay.

## Version And Compatibility

| Component | Value |
| --- | --- |
| Mod ID | `satiscraftory` |
| Mod Name | `Satiscraftory` |
| Mod Version | `1.1.2` |
| Minecraft Version | `1.20.1` |
| Forge Version | `47.4.10` |
| Java Target | `17` (toolchain) |
| Gradle Wrapper | `8.8` |

The authoritative values live in `gradle.properties` and are injected into `mods.toml` during `processResources`.

## Guidebook

Install Patchouli to use the Satiscraftory Field Guide. Craft it with a book, a copper ingot, and yellow dye in any arrangement, or find it in the Satiscraftory creative tab.

The orange and yellow guide covers belt placement, routing modes, all six belt tiers, tier swaps, item pickup, splitters, Smart Splitter output filters, mergers, and the Smelter, Constructor, Assembler, and Manufacturer. Patchouli is optional.

## What The Mod Does

- Six conveyor tiers with straight and spline routing, animated cargo, inventory connections, and splitter/merger routing.
- A Smart Splitter with independent Unassigned, Overflow, Whitelist, and Blacklist rules, nine ghost filters per output, and optional JEI dragging.
- Belt selection, mining, item pickup, tier swaps, and pick-block targeting along the entire rendered belt.
- An instant Smelter using 1,000 RF per smelted item and a 10,000 RF buffer. Items without a smelting recipe pass through unchanged.
- Recipe-configured crafting machines:

| Machine | Ingredient types | RF cost | RF buffer |
| --- | --- | --- | --- |
| Constructor | 1 | 500 per output item | 5,000 |
| Assembler | Up to 2 | 1,000 per craft | 10,000 |
| Manufacturer | Up to 4 | 4,000 per craft | 40,000 |

Select a crafting output using the ghost template slot or drag an item from JEI, then choose a supported recipe. Assemblers and Manufacturers use one buffer per item type, capped at two crafts' worth. Existing surplus remains available to craft or remove. Crafting remainders and blocked outputs are preserved.

JEI and Patchouli are optional. Extra runtime jars in `extra-mods-1.20.1/` are used for local development and are not bundled into releases.

## Requirements

- JDK 17+ available locally.
- Internet access on first Gradle run to resolve dependencies.
- A Forge 1.20.1 environment to run the built jar.

## Build

Run all builds and tests locally using the Gradle wrapper so they can use the local Gradle cache and available CPU cores. This project does not use GitHub Actions. Do not add or run Actions workflows for builds, tests, or releases. Publish release jars built locally.

From the project root:

```bash
./gradlew clean build
```

On Windows:

```bat
gradlew.bat clean build
```

### Build Outputs

- Main jar: `build/libs/satiscraftory-1.1.2.jar`
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
- Run game test server: `./gradlew runGameTestServer` (includes crafting-output and Smart Splitter regressions in `src/gameTest`; these are excluded from production jars)
- Generate data: `./gradlew runData`

Runtime directories used by Gradle runs:

- Game run dir: `run/`
- Data run dir: `run-data/`

## Project Structure

- Mod entrypoint: `src/main/java/art/arcane/satiscraftory/Satiscraftory.java`
- Conveyor block logic: `src/main/java/art/arcane/satiscraftory/block/ConveyorBlock.java`
- Conveyor block entity / transport logic: `src/main/java/art/arcane/satiscraftory/block/entity/ConveyorBlockEntity.java`
- Mod metadata: `src/main/resources/META-INF/mods.toml`
- Build configuration: `build.gradle`, `gradle.properties`

## Updating Version

To release a new version, bump `mod_version` in `gradle.properties` and rebuild.
