# Claude project instructions

Read and follow [AGENTS.md](AGENTS.md) for repository layout, coding constraints,
and validation requirements. [CONTRIBUTING.md](CONTRIBUTING.md) provides the shared
contribution workflow; [SECURITY.md](SECURITY.md) covers vulnerability reporting.

MaceManager uses Java 21, the Gradle wrapper, and JUnit 5. It compiles against the
Paper 1.21.4 API while aiming to preserve Bukkit/Spigot compatibility.

- Inspect source and relevant tests before proposing or making changes.
- Keep Bukkit operations on the server thread and preserve tracked mace identity,
  permissions, persistence ordering, and cooldown behavior.
- Keep changes focused and preserve unrelated edits.
- Run relevant checks and state their results. Never claim server testing or
  performance measurements that were not performed.
- Use `.\gradlew.bat test build` on Windows or `./gradlew test build` on Linux/macOS
  for code validation. Documentation-only edits do not require a build.
- Do not expose credentials, private vulnerability details, or player data.

The project uses GPL-3.0-only. Preserve existing attribution and third-party
license notices.
