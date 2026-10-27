# Repository instructions for coding agents

## Project

MaceManager is a Java 21 Minecraft plugin. Gradle compiles against Paper 1.21.4;
the implementation aims to remain compatible with Bukkit and Spigot APIs.

## Layout

- `src/main/java/com/macemanager/`: plugin lifecycle and implementation.
- `command/`, `config/`, `cooldown/`, `limit/`, `permission/`, `text/`: feature packages.
- `src/main/resources/config.yml`: default settings and messages.
- `src/main/resources/plugin.yml`: plugin entry point, commands, and permissions.
- `src/test/java/`: JUnit 5 regression tests.
- `OPTIMIZATION_REVIEW.md`: prior performance changes and open investigation areas.

## Working rules

- Read the relevant source before editing. Preserve unrelated user changes.
- Follow existing style and keep edits focused on the requested task.
- Keep Bukkit world, entity, inventory, and registry access on the server thread.
- Preserve mace identity, duplicate enforcement, offline ownership, and permission
  behavior. Do not remove safety audits solely to reduce scan cost.
- Registry persistence batches writes until the next tick and flushes on shutdown.
  Preserve write ordering and dirty-state recovery when changing persistence.
- Use monotonic elapsed time for in-memory cooldowns. Avoid overflow when applying
  enchantment modifiers or converting seconds to ticks.
- Maintain Bukkit/Spigot compatibility unless the requested change explicitly
  narrows it. Flag server-specific dependencies and validate their behavior.
- Update configuration defaults and plugin metadata when changing their contract.
- Do not edit generated `build/` or `.gradle/` files as source changes, and do not
  commit credentials, player data, or local server files.

## Verification

On Windows, use `.\gradlew.bat test build`; on Linux/macOS, use
`./gradlew test build`. Use the checked-in wrapper and a discoverable Java 21 JDK.
Run checks appropriate to the change; documentation-only edits do not require a
build. Do not add tests that merely mirror the implementation.

Inventory/event changes need live-server validation when available. Clearly report
unrun checks and distinguish measured performance from expected improvements.

Follow [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and the
GPL-3.0-only terms described in [LICENSE](LICENSE) and the contribution guide.
