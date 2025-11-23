# GboardHook

**ALL CREDIT GOES TO <https://github.com/chenyue404/GboardHook> THIS IS A FORK FOR MY PERSONAL USE**

GboardHook
Modify clipboard display count and expiration time

enable_clipboard_entity_extraction affects the number of clipboard entries that can be read. The principle is unclear, and the key code hasn't been found, so it can only be hardcoded to false for now

## Building and security notes

Gradle requires native access when running on Java 25+ binaries. To avoid the `java.lang.System::load` warning, run Gradle with the flag:

```bash
./gradlew build
```

You can also set `JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED"` so every Gradle invocation inherits the setting.

Dependency updates/inventory are handled by the Ben Manes Versions plugin. To list outdated dependencies and help review Dependabot alerts, run:

```bash
./gradlew dependencyUpdates
```
