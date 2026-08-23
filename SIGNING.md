# ClearMic update signing

ClearMic uses one permanent app-signing identity for upgrade-compatible release APKs.

## Public certificate identity

- Alias: `clearmic-update`
- Algorithm: RSA 4096
- Certificate validity: 2026-08-23 to 2056-08-15
- SHA-256: `E3:CD:30:2D:6A:71:6D:77:E5:96:A4:56:31:8C:14:E7:59:1B:87:23:FB:71:AD:12:AA:6F:EF:BB:67:54:45:3D`
- SHA-1: `6B:6B:13:04:80:FD:D2:49:85:F0:57:89:C7:7E:AD:05:C4:1E:C7:54`

The private `.jks` file and passwords must never be committed to this public repository.

## GitHub Actions secrets

The workflow can create a signed release when these repository secrets exist:

- `CLEARMIC_KEYSTORE_BASE64` — Base64 representation of the permanent `.jks`
- `CLEARMIC_KEYSTORE_PASSWORD` — keystore password
- `CLEARMIC_KEY_ALIAS` — `clearmic-update`
- `CLEARMIC_KEY_PASSWORD` — private-key password

Without these secrets, CI still builds the debug APK and validates the project normally.

## Update rule

Every APK intended to update an already installed release must keep:

1. the same `applicationId` (`io.github.astromg01.clearmic`),
2. a higher `versionCode`, and
3. the same permanent signing certificate.

Losing the private signing key means direct APK updates signed by this identity can no longer be produced. Keep at least two secure backups outside the repository.
