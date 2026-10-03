# Source manifest

This export is intentionally a snapshot of code that was already exercised in the DirectMail stable build rather than a rewritten/upstream-styled variant.

## Verified DirectMail baseline

- source commit: `315edf9e69005f2c54527831f0fc9243a06bdbc5`
- stable workflow run: `37080565643`
- upstream Thunderbird Android pin: `917c3c0ba50090811082c62b7224b7d78c34f1a6`

## Core module

Copied unchanged from:

```text
fork/overlay/backend/exchange/
```

The directory includes its Gradle module definition, production Kotlin sources and tests.

## Copied integration files

The exact DirectMail overlay versions of these files are included under `integration/overlay/`:

```text
feature/account/common/src/main/kotlin/app/k9mail/feature/account/common/domain/entity/IncomingProtocolType.kt
feature/account/server/settings/src/main/kotlin/app/k9mail/feature/account/server/settings/ui/incoming/IncomingServerSettingsStateExtensions.kt
feature/account/server/validation/src/main/kotlin/app/k9mail/feature/account/server/validation/ServerValidationModule.kt
feature/account/server/validation/src/main/kotlin/app/k9mail/feature/account/server/validation/domain/usecase/ValidateServerSettings.kt
legacy/common/src/main/java/com/fsck/k9/account/DefaultDeletePolicyProvider.kt
legacy/common/src/main/java/com/fsck/k9/backends/ExchangeBackendFactory.kt
legacy/common/src/test/java/com/fsck/k9/account/DefaultDeletePolicyProviderTest.kt
legacy/core/src/main/java/com/fsck/k9/preferences/ServerTypeConverter.kt
legacy/core/src/test/java/com/fsck/k9/preferences/ServerTypeConverterTest.kt
```

## Integration edits not represented as full copied files

DirectMail applies additional targeted edits to upstream files rather than replacing the full file. These are described in `INTEGRATION.md` and originate from `fork/apply-thunderbird-overlay.py`.

They include Gradle registration, Koin backend registration, account-setup routing, initial sync, push bootstrap/compatibility, and Exchange-specific `MessagingController` behavior.

## Excluded product-specific material

Not copied:

- DirectMail branding/resources
- application ID changes
- keystores/signing configuration
- release workflows
- onboarding customization
- crash/EWS report activities and UI
- unrelated IMAP-specific patches

This keeps the export focused on the Exchange implementation while preserving the exact tested backend code.
