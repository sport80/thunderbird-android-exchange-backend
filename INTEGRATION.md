# Thunderbird Android integration notes

This document records how the DirectMail Exchange backend is integrated into the pinned Thunderbird Android source tree. It deliberately separates the **Exchange module itself** from changes made to Thunderbird application/controller code.

The goal is to let upstream reviewers see the real working integration without pretending that every downstream patch is necessarily the architecture Thunderbird should adopt.

## 1. Files copied directly into Thunderbird

The `integration/overlay/` directory contains exact snapshots of the DirectMail files used by the verified stable build.

### Account protocol and settings

- `feature/account/common/.../IncomingProtocolType.kt`
  - adds `EXCHANGE`
  - default TLS connection security
  - default port 443
- `feature/account/server/settings/.../IncomingServerSettingsStateExtensions.kt`
  - currently allows password authentication for Exchange
- `feature/account/server/validation/.../ServerValidationModule.kt`
  - registers `ExchangeServerSettingsValidator`
- `feature/account/server/validation/.../ValidateServerSettings.kt`
  - routes server type `exchange` to the Exchange validator

### Legacy/backend integration

- `legacy/common/.../backends/ExchangeBackendFactory.kt`
  - instantiates `ExchangeBackend`
  - the current DirectMail version also writes optional local EWS diagnostics; that diagnostic sink is downstream-specific and can be removed/simplified upstream
- `legacy/common/.../account/DefaultDeletePolicyProvider.kt`
  - gives Exchange the normal server-delete policy used by the working fork
- `legacy/core/.../preferences/ServerTypeConverter.kt`
  - preserves Exchange account type during settings import/export

The corresponding DirectMail regression tests for delete-policy and server-type conversion are copied too.

## 2. Gradle/module registration applied by DirectMail

DirectMail's overlay script makes these source-tree edits against the pinned Thunderbird revision:

1. `settings.gradle.kts`
   - add `:backend:exchange`
2. `legacy/common/build.gradle.kts`
   - add `implementation(projects.backend.exchange)`
3. `feature/account/server/validation/build.gradle.kts`
   - add `implementation(projects.backend.exchange)`

## 3. Backend factory registration

`legacy/common/src/main/java/com/fsck/k9/backends/KoinModule.kt` is patched so that:

- protocol name `exchange` maps to `ExchangeBackendFactory`
- `DefaultExchangeBackendFactory` is registered with Koin

This is required for Thunderbird's existing backend lookup to instantiate the Exchange backend for an Exchange account.

## 4. Account setup flow

`feature/account/setup/.../AccountSetupNavHost.kt` is patched so an incoming Exchange configuration does not proceed to SMTP setup.

The working DirectMail behavior is:

- read the incoming settings from the account state repository
- if `incomingSettings.type == "exchange"`, copy those settings into the required non-null outgoing-settings slot used by the legacy account model
- skip the outgoing SMTP configuration/validation screen
- continue to display options

This is because message sending is performed through EWS rather than SMTP for an Exchange account.

## 5. Initial synchronization

`app-common/.../AccountCreator.kt` is patched to force one initial mailbox check for a newly-created Exchange account after folder discovery.

This avoids a freshly-created account appearing empty while waiting for the normal polling/push lifecycle to begin.

## 6. EAS-assisted push integration

The backend reports `isPushCapable = true`. `ExchangeBackendPusher` combines EAS Ping with EWS synchronization.

DirectMail also patches Thunderbird's push-folder tracking so that, once, an Inbox can be enabled for sync/push/notifications when an older account has no explicit push-enabled folders. The current downstream marker is:

```text
directmail.push-defaults-v1
```

This bootstrap is useful for DirectMail migration/history but is not necessarily the implementation upstream should use for newly-designed Exchange account defaults.

### Exact-alarm compatibility

DirectMail contains an additional global push compatibility change: it avoids disabling all push accounts solely because Android denied exact-alarm permission. EAS Ping itself does not require an alarm.

The downstream also changes `AndroidAlarmManager` to use an inexact allow-while-idle fallback when exact alarms are unavailable. This affects IMAP push behavior too, so it should be reviewed independently rather than treated as an intrinsic EWS requirement.

## 7. Sending and Thunderbird Outbox integration

The working fork applies Exchange-specific changes to `legacy/core/.../MessagingController.java`:

- Exchange messages in the local Outbox are not rejected solely because stale draft/identity metadata is still present
- freshly queued Exchange messages are sent synchronously from the existing background send task so replies do not remain parked until a later process lifecycle

These patches bridge Thunderbird's legacy send queue to an EWS-backed `Backend.sendMessage()` implementation.

## 8. Flags and message actions

`ExchangeBackend` intentionally leaves generic `supportsFlags = false` because Thunderbird's boolean capability is broader than the subset implemented by DirectMail.

DirectMail therefore adds targeted controller exceptions for Exchange for the states it does implement:

- SEEN
- FLAGGED
- ANSWERED
- FORWARDED
- mark-all-as-read

This is an important upstream design point: a more expressive backend-capability model would remove the need for these targeted controller exceptions.

## 9. Pending-command recovery

The working fork also contains two resilience changes in `MessagingController` for Exchange:

- a failed Exchange pending mutation is kept queued while later pending commands are allowed to run; the first failure is surfaced after the pass
- move/copy retry recovery attempts `findByMessageId()` in the destination folder before repeating a remote mutation, covering the case where the EWS operation completed remotely but Thunderbird failed before replacing its local placeholder UID with the returned server ItemId

These changes were added for observed real-world failure/retry behavior and should be evaluated separately by upstream maintainers.

## 10. Deliberately excluded from this community export

The following DirectMail downstream changes are not part of the Exchange feature itself and are intentionally not copied into this snapshot:

- DirectMail application ID and branding
- release/debug signing configuration and keystores
- DirectMail onboarding customization
- DirectMail About/settings branding
- crash-report activities
- the DirectMail EWS diagnostic UI
- unrelated IMAP IDLE reconciliation changes
- release CI and APK-signing infrastructure

The backend factory still contains the small diagnostic-sink hook used by the working DirectMail build so the copied source remains exact. It can safely be simplified during an upstream port.

## 11. Suggested upstream decomposition

If this is ever proposed as pull requests, a review-friendly order would be:

1. protocol/account type + module registration
2. `backend/exchange` with EWS operations and tests
3. account setup/validation integration
4. EWS send/action controller integration
5. EAS-assisted push
6. resilience/retry patches that touch shared controller code

That ordering is only a suggestion. The purpose of this snapshot is to expose the complete working implementation, not to prescribe Thunderbird's merge strategy.
