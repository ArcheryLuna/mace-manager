# Security policy

## Supported versions

Security fixes target the latest maintained MaceManager version. Users of older
versions may need to upgrade to receive a fix. There is no guaranteed response
time, support period, or backport schedule.

## Reporting a vulnerability

Please report vulnerabilities privately before publishing exploit details.

If the project's GitHub repository has private vulnerability reporting enabled,
use **Security → Report a vulnerability**. Otherwise, use a private contact channel
explicitly published by the project maintainers. No dedicated security email or
private reporting URL is currently documented in this source tree.

If neither private route is available, open an issue asking the maintainers for a
private reporting channel. Include only that request; do not attach exploit steps,
sensitive logs, credentials, or affected server details to the public issue.

Include the following in the private report:

- Affected MaceManager, Minecraft server, and Java versions.
- Relevant configuration and interacting plugins, with secrets removed.
- A description of the impact and any permissions or conditions required.
- Minimal reproduction steps or a proof of concept using a test server.
- Sanitized logs and any suggested mitigation or fix.

Examples include permission bypasses, tracked mace duplication, unauthorized item
creation/deletion, and resource exhaustion caused by plugin behavior. Ordinary
configuration questions and non-sensitive bugs can use the normal issue tracker.

## Handling and disclosure

Maintainers should assess reports privately, coordinate reproduction and fixes with
the reporter, and communicate affected versions and mitigations when a fix is
available. Coordinate public disclosure to give server operators an opportunity
to update. Do not publish reporters' identities without their permission.

## Server operator guidance

Use trusted plugin downloads, keep your server and Java runtime updated, restrict
administrative permissions, and back up server/plugin data before upgrades. Test
reproduction steps on a separate server and remove personal information and secrets
from shared logs or configuration files.
