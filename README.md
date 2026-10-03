# Thunderbird Android EWS/EAS backend — community snapshot

This repository is a community-oriented extraction of the Exchange backend developed for DirectMail, a Thunderbird for Android downstream fork.

It is shared **as-is** because it solved a real interoperability problem with an Exchange-like / EWS-compatible server and may be useful to Thunderbird Android developers or other contributors.

This is not an official Mozilla or Thunderbird project, not a claim of universal Microsoft Exchange compatibility, and not intended to define the long-term architecture for Microsoft 365.

## What is included

The snapshot keeps the working combination used by DirectMail:

- **EWS** for authoritative mailbox operations
- **EAS** for provisioning/state used by mobile push and Ping-based wake-ups
- Thunderbird `Backend` integration
- unit/regression tests for the Exchange implementation
- reference integration files used to expose Exchange accounts in Thunderbird Android

The core implementation is under:

```text
backend/exchange/
```

The copied Thunderbird integration files are under:

```text
integration/overlay/
```

Additional changes that DirectMail applies directly to upstream Thunderbird source are described in [`INTEGRATION.md`](INTEGRATION.md).

## Source and tested baseline

This snapshot corresponds to the Exchange code used by the verified DirectMail stable build:

- DirectMail source commit: `315edf9e69005f2c54527831f0fc9243a06bdbc5`
- DirectMail stable CI run: `37080565643`
- Thunderbird Android upstream pin: `917c3c0ba50090811082c62b7224b7d78c34f1a6`

Later DirectMail commits used to prepare this export did not change the Exchange backend code.

## Implemented behavior

The current backend contains support for:

- EWS folder discovery and hierarchy handling
- full and incremental synchronization using EWS sync state
- MIME message download and partial-message handling
- message sending
- move, copy, trash and upload operations exposed through Thunderbird's backend API
- read/flagged/replied/forwarded state synchronization
- server-side search compatibility
- Exchange server settings validation
- EAS Provision, FolderSync/Sync state and Ping-based push wake-ups
- recovery from stale/invalid EAS synchronization state
- EWS reconciliation for folders that do not receive direct EAS push events

The test suite is included with the module.

## Scope and known limitations

This code was developed for a real Exchange-like server environment. It should be evaluated in that context.

Known scope limitations include:

- authentication currently reflects the server environment for which DirectMail was developed; the EWS client currently uses username/password HTTP Basic authentication
- this is not presented as a complete Exchange Online / Microsoft 365 solution
- EAS is not used as a complete mail backend; it complements EWS, primarily for provisioning and push
- calendar and contacts are outside this implementation
- the code still uses the historical `it.directmail.backend.exchange` package namespace
- some Thunderbird integration patches are downstream-specific and would need architectural review before upstream adoption

## Why include EAS push

The EAS part is intentionally included because it is part of the working DirectMail behavior rather than a separate experiment. EWS remains the authoritative mail protocol; EAS Ping provides a useful mobile wake-up mechanism and is combined with EWS synchronization.

Removing it would make this snapshot less representative of the implementation that was actually tested in use.

## Upstream intent

The intent of sharing this work is simple: Thunderbird Android currently lacks this class of Exchange support, while the implementation here solved a concrete personal/server interoperability need.

If Thunderbird developers find the backend, tests, protocol work or integration approach useful, they are welcome to reuse or adapt it. If the project prefers another architecture, this repository can still serve as a working reference implementation.

A suitable way to present it upstream is:

> This solved my Exchange-like server use case on Thunderbird Android. I'm sharing the working EWS backend and EAS-assisted push implementation as-is in case any part of it is useful to the Thunderbird community. It is not intended as a universal Exchange/M365 solution or as a demand that Thunderbird adopt this architecture.

## Integration

See [`INTEGRATION.md`](INTEGRATION.md) for the exact DirectMail integration points and which changes are core versus downstream-specific.

## Licensing and attribution

Thunderbird Android is licensed under Apache License 2.0. This repository contains code designed for and, in some integration files, derived from Thunderbird Android. The full Apache-2.0 license is included in [`LICENSE`](LICENSE), and applicable upstream notices and attribution should be retained.

See [`LICENSE-NOTICE.md`](LICENSE-NOTICE.md).

## Development disclosure

AI assistance was used during parts of DirectMail development, debugging and integration work. Any upstream contribution should disclose that fact according to the receiving project's contribution requirements.
