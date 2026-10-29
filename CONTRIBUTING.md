# Contributing to MaceManager

## Getting started

MaceManager is a Java 21 Minecraft plugin built with Gradle. It compiles against
the Paper 1.21.4 API and aims to use APIs available on Bukkit and Spigot as well.
Compatibility beyond the tested server environment should be verified explicitly.

Use the checked-in Gradle wrapper:

```powershell
# Windows
.\gradlew.bat test build
```

```sh
# Linux/macOS
./gradlew test build
```

Install a Java 21 JDK that Gradle can discover. The first build requires network
access to download Gradle and dependencies. Plugin JARs are written to
`build/libs/`.

To compile and run the same regression suite against Paper 1.21.11:

```powershell
.\gradlew.bat test build '-PpaperApiVersion=1.21.11-R0.1-SNAPSHOT'
```

```sh
./gradlew test build -PpaperApiVersion=1.21.11-R0.1-SNAPSHOT
```

The default remains Paper 1.21.4. Listener tests use mocked server objects and
exercise cooldown enforcement, crafting limits, pickup ownership, and preservation
of offline owners. These API checks do not boot Minecraft or verify server event
dispatch, real inventory transactions, or interactions with other plugins.

## Making changes

- Keep each contribution focused and explain the problem it solves.
- Follow the existing Java style, package structure, and naming conventions.
- Keep Bukkit inventory, entity, world, and registry mutations on the server
  thread. Do not move Bukkit API calls to background tasks for performance.
- Persist tracked records in SQLite (`maces.db`). Preserve transactional writes,
  one-time YAML migration, failed-write retries, and ordered shutdown flushing.
- Preserve tracked mace IDs, duplicate detection, offline ownership, configurable
  limits, permission checks, and cooldown behavior.
- Keep configuration defaults and `plugin.yml` consistent with behavior changes.
- Add regression tests for behavior changes where useful. Documentation-only
  changes do not require new tests.
- Do not commit generated build output, Gradle caches, credentials, or server data.

## Validation and pull requests

Run the relevant tests and build before submitting code. For inventory, crafting,
event, or permission changes, also test on a Minecraft server when available.
State the server implementation/version and relevant plugin combinations tested.
Unit tests alone do not establish live-server compatibility.

For storage releases, run the following on Paper 1.21.4 and a shelf-capable server
(Paper 1.21.11), then repeat on the Spigot version being deployed. These checks
require two players and a disposable world/database:

- Store and retrieve a tracked mace in every enabled destination. Have the second
  player view it, then take it: viewing retains the owner; possession transfers it.
- Exercise clicks, shift-clicks, drags, hotbar/offhand swaps, hopper transfers, and
  single/double/triple powered shelves. Repeat with the destination disabled.
- Disconnect, restart, and unload/reload the storage chunk. Verify the same ID,
  owner, location, and occupied cap slot survive. Repeat with nested bundles and
  shulkers; place/break the shulker and carry it between players.
- Break containers and frames, collect their drops, then test actual destruction
  and despawning. A surviving duplicate must retain its record and cap slot.
- Disable storage via reload while its chunk is unloaded, then load it. The mace
  must be ejected once without losing identity. Repeat with the cap disabled.
- Remove an ID administratively while its owner is offline or storage unloaded;
  restart with the cap disabled, then return/load it. All copies of that ID,
  including nested copies, must be removed without deleting unrelated contents.
- Use a test plugin to cancel transfers/pickups after validation. Verify ownership
  follows the resulting inventory, and test with the deployment's other plugins.

Record server builds, Java version, plugin combinations, observed results, and
failures. The automated lifecycle suite uses mocked inventory transactions and
real SQLite persistence; its abrupt-process-exit test verifies database rollback,
not coordination between Minecraft world saves and plugin writes. An abrupt
server crash may lose the most recent unflushed tick; recovery must also be tested
on the disposable server before deployment.

Persistence tests use actual SQLite files in temporary directories. Packaging
tests open a database using only the built JAR and JDK classes, checking that the
bundled driver works without server-supplied dependencies.

Describe the resulting behavior, why it changed, and the validation performed.
Clearly identify checks that could not be run. Performance claims should include
measurements, workload details, and a comparison with the previous behavior.

Report ordinary bugs with reproduction steps, expected and actual behavior,
plugin/server/Java versions, and sanitized logs. Follow [SECURITY.md](SECURITY.md)
for vulnerabilities.

## License and credit

MaceManager is licensed under the GNU General Public License version 3 only
(`SPDX-License-Identifier: GPL-3.0-only`). See [LICENSE](LICENSE) for the full terms.
Unless explicitly stated otherwise and agreed with the maintainers, contributions
are submitted under those same terms. Contributors retain their copyright.
Include required notices for any third-party material and ensure it is compatible
with the project's license.

You may add your preferred attribution to [CONTRIBUTORS.md](CONTRIBUTORS.md).
