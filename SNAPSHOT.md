# Verified snapshot manifest

This repository contains an exact source snapshot extracted from the DirectMail Thunderbird Android downstream.

## Provenance

- DirectMail repository: `sport80/directmail-android`
- DirectMail source baseline: `315edf9e69005f2c54527831f0fc9243a06bdbc5`
- Verified stable CI run: `37080565643`
- Thunderbird Android upstream: `thunderbird/thunderbird-android`
- Thunderbird Android pin: `917c3c0ba50090811082c62b7224b7d78c34f1a6`

## Exact Git tree identities

The extracted trees in this repository are byte-for-byte identical to the corresponding DirectMail snapshot trees:

```text
backend/exchange
50615de22545ab04b3df6515b220024397f49de0

integration
a97aa2b4191ca1487cae7a2dc9bfc66090456b24
```

Because Git tree object IDs cover file names, modes, child tree identities and blob identities, matching tree IDs verify that the copied source layout and contents are identical to the source snapshot.

## Important core blob identities

```text
backend/exchange/src/main/kotlin/it/directmail/backend/exchange/EasClient.kt
2e7849bb3a6c438e6ea82c2685dad4b7ce969356

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/EasReadStateStore.kt
077b4f9612e1f33daf6c7870a0aa2a11f2be7af4

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/EasWbxml.kt
99216fe6ab434a6fc0afcd5f60469ed4cdaf8825

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/EwsClient.kt
76a19f244bd951c278d720273244592f4f3fc1a7

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/ExchangeBackend.kt
1479da865ebdcff8825b900677a2befbec82f943

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/ExchangeBackendPusher.kt
758e59304b20669c3714f7e7689111c575e41d29

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/ExchangeSearchCompatibility.kt
112eb1823095939e31aa50c31a66ce9a6efacd2f

backend/exchange/src/main/kotlin/it/directmail/backend/exchange/ExchangeServerSettingsValidator.kt
e7a333425a3a5c85fec9bfc2b35e3082a199365e
```

The complete test directory is covered by the `backend/exchange` tree identity above.

## Validation status

The module in this repository is not a standalone Gradle project. Its Gradle file references Thunderbird Android build plugins and project modules. The implementation was validated in the pinned Thunderbird checkout as part of the DirectMail stable workflow, including the Exchange regression suite and release build.

No claim is made that this isolated repository can be built independently without the corresponding Thunderbird Android source tree and integration changes described in `INTEGRATION.md`.
