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
