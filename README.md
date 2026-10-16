# AutoRod (Forge 1.8.9, Java 8)

## Build on GitHub
1. Push this folder to a GitHub repo.
2. Open the **Actions** tab, run **Build JAR** (or just push).
3. Download the `AutoRod-jar` artifact. Use `AutoRod-1.0.jar` (not `-sources`/`-dev`).
4. Drop it in `.minecraft/mods` with Forge 1.8.9 (11.15.1.2318).

## Build locally
Gradle 2.14.1 + JDK 8:
    gradle setupDecompWorkspace build
Output: build/libs/AutoRod-1.0.jar

Toggle key: Z (rebindable in Controls > AutoRod).
Configurable fields: top of AutoRod.java (minDistance, cooldownTicks, ...).
