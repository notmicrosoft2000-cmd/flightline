# Repository Guidelines

## Project Structure & Module Organization

This repository is a Fabric mod for Minecraft 26.3, built with Gradle and Fabric Loom. It changes player flight behavior; it does not add a plane entity. Common, server-authoritative flight logic and networking belong under `src/main/java/dev/flightline/`; client-only input, HUD, and audio code belong under `src/client/java/dev/flightline/client/`. Fabric metadata is in `src/main/resources/`; assets belong under `src/main/resources/assets/flightline/`.

## Build, Test, and Development Commands

- `./gradlew build` compiles the mod and creates distributable artifacts in `build/libs/`.
- `./gradlew runClient` launches a development Minecraft client for in-game checks.
- `./gradlew test` runs the Gradle test task; add tests under `src/test/java/` as the project grows.

The Gradle wrapper pins Gradle 9.8.2, matching the current Fabric 26.3 example project.

## Coding Style & Naming Conventions

Use Java 25 and UTF-8, as configured in `build.gradle`. Follow standard Java formatting with four-space indentation, braces on the declaration line, and one top-level public class per file. Use `PascalCase` for classes, `camelCase` for methods and fields, and uppercase `SNAKE_CASE` for constants (for example, `MOD_ID`). Keep shared logic out of client-only packages so it can load in dedicated servers.

## Testing Guidelines

No testing framework or coverage requirement is configured yet. When adding automated tests, use Gradle's `test` task and name test classes after the subject with a `Test` suffix (for example, `FlightPhysicsTest`). For changes affecting mod loading, mixins, rendering, or gameplay, also run `gradle runClient` and record any relevant manual checks in the pull request.

## Commit & Pull Request Guidelines

No usable Git history is present to establish a repository-specific commit convention. Write concise, imperative commit subjects, such as `Add flight warning indicator`. Pull requests should explain the player-facing change, note implementation or compatibility concerns, link related issues, and include screenshots or reproduction steps for visible or gameplay changes. Mention the Gradle commands you ran and any checks still needed.

## Configuration & Compatibility

Keep Minecraft, loader, Fabric API, and Java requirements consistent across `gradle.properties`, `build.gradle`, and `fabric.mod.json`. Minecraft 26.3 uses unobfuscated names, so do not add Yarn mappings. Do not commit generated `build/`, `.gradle/`, or local run data; these are ignored by `.gitignore`.
