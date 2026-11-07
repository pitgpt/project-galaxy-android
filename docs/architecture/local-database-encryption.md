# Local database encryption — design and migration plan

## Status

Implementation in progress on this Draft PR. The SQLCipher dependency, Keystore-wrapped key store, plaintext-to-encrypted bootstrap, Room open-helper integration, and Android backup exclusions are now present. Device-level migration and restore tests are still required before release.

## Current state

- Room 2.7.0 opens `galaxy.db` through the platform SQLite implementation.
- The database is schema version 2.
- Conversation message content and memory content are stored as plaintext columns.
- Character profiles (including persona instructions) are stored in the same database and are also private user data.
- Android backup is enabled. The current backup rules exclude authentication and provider-secret preferences, but do not exclude the Room database.
- Provider API credentials already use an AES-GCM key held by Android Keystore. That implementation is a useful pattern, but the database key must have its own lifecycle and failure handling.

## Recommended direction

Use the maintained `net.zetetic:sqlcipher-android` library with Room's `SupportOpenHelperFactory`, rather than the deprecated `net.zetetic:android-database-sqlcipher` artifact.

Keep Room and the existing DAO/repository model. SQLCipher encrypts the complete database file, including character profiles, message content, memory content, indexes, and schema metadata. This avoids scattering encryption/decryption logic across entities and repositories.

The SQLCipher native library must be loaded before opening the database. The Room 2 integration uses `SupportOpenHelperFactory` with `androidx.sqlite`. The official repository currently lists v4.20.0 as the latest stable release; v5.0.0 is still a beta and should not be selected for this migration. The v4.19.1 release notes explicitly update the Android build to compile/target SDK 36, matching this project's current SDK level, while the current 4.20.0 release is a maintenance update. Before pinning 4.20.0, verify its published Maven artifact, compile SDK requirement, ABI contents, and 16 KB page-size alignment in CI. The library supports API 23+ and the project's minSdk 26 is within that range. [Official releases](https://github.com/sqlcipher/sqlcipher-android/releases).

## Key management

1. Generate a cryptographically random database key (at least 256 bits) once.
2. Wrap that key with a dedicated AES-GCM key generated in Android Keystore.
3. Persist only the wrapped database key and its format/version metadata in a private preferences file.
4. Exclude the wrapped key from cloud backup and device transfer unless a separately designed user-controlled recovery mechanism is implemented.
5. Never silently generate a replacement key when an encrypted database already exists. Missing, invalidated, or undecryptable key material must produce a recoverable, explicit locked-data state.
6. Never log the raw database key, wrapped key, passphrase, or SQLCipher keying statements.

The SQLCipher passphrase byte array should be short-lived and cleared when the library permits. A single-use open-helper factory is preferable if the library clears the passphrase after opening.

## Existing plaintext database migration

Do not point Room/SQLCipher directly at the existing plaintext `galaxy.db`. First convert it to a separate encrypted temporary database using SQLCipher's supported plaintext-to-encrypted export mechanism.

Required sequence:

1. Acquire a process-wide migration lock and ensure no Room instance or DAO operation is active.
2. Checkpoint and close the old database cleanly so its WAL is not omitted.
3. Preserve the original database and any required sidecar state until the encrypted copy is verified.
4. Create a new encrypted temporary database using the newly generated key. Use SQLCipher's documented plaintext-to-encrypted `ATTACH DATABASE ... KEY ...` plus `sqlcipher_export()` flow, or an equivalently verified library-supported export API. The source must remain read-only during export where practical.
5. Export the complete schema and data into the encrypted database. Exercise the exact `rawExecSQL`/query behavior against the pinned SQLCipher Android version in an instrumentation test; do not suppress export errors.
6. Verify the encrypted copy can be reopened with the key, passes SQLCipher integrity checks, has the expected Room schema version, and has matching row counts for every table (characters, messages, memories).
7. Only after verification, atomically promote the encrypted file into the canonical database path using same-filesystem renames.
8. Retain a clearly marked rollback copy only during the migration window. On the next successful startup, verify the encrypted database again and remove the temporary plaintext rollback copy. Do not claim guaranteed secure erasure: flash storage wear-leveling and filesystem behavior make physical overwrite guarantees unreliable. Keep the rollback window short, restrict file permissions, and never include plaintext temporary files in backup.
9. Record migration state so an interrupted migration can deterministically resume or roll back. Never respond to an unknown/partial state by deleting either database.
10. Run Room migrations on the encrypted database after conversion, not during the plaintext export.

If any stage fails, keep the original database intact, report a non-destructive error, and allow retry. No destructive fallback such as `fallbackToDestructiveMigration` is acceptable.

## Selected initial backup policy

The product decision is confirmed: v1 uses device-only storage. Conversation history, character profiles, memories, the wrapped SQLCipher key, and migration staging files must not be restored by Android cloud backup or device-to-device transfer. Both Android 11-and-lower legacy rules and Android 12+ cloud/device-transfer rules are configured accordingly. The app manifest targets API 35, so both XML rule formats are required. The XML changes are implemented in this PR; actual backup/restore behavior still needs device-level validation before release.

V1: device-only storage. Exclude `galaxy.db`, its WAL/SHM sidecars, the wrapped database-key preferences, and migration temporary files from both cloud backup and device-to-device transfer. Android Auto Backup otherwise includes most app data by default, and Android 12+ uses separate cloud-backup and device-transfer rule sections; legacy devices still require the older `full-backup-content` rules. Verify the exact domain/path exclusions against Android's current Auto Backup documentation and an actual restore/transfer test. [Android Auto Backup documentation](https://developer.android.com/identity/data/autobackup).

This means reinstalling or moving phones will not automatically restore conversation history in v1. Add explicit encrypted export/import with a user-controlled recovery secret before promising cross-device recovery.

## Future recovery path

A database encrypted with a key that is device-bound to Android Keystore cannot be restored to another device unless the key is also recoverable.

A future export/import implementation may choose between:

- **Device-only storage:** exclude the database and wrapped key from both cloud backup and device transfer. Add explicit encrypted export/import with a user-controlled recovery secret before promising cross-device recovery.
- **User-recoverable backup:** introduce a separately designed recovery secret or account-backed key-wrapping protocol. The recovery mechanism must be tested independently and must not depend on copying a non-exportable Keystore key.

Do not ship a state where Android silently backs up the encrypted database but omits its key. Do not assume the current backup rules are sufficient; verify both cloud backup and device transfer behavior on supported Android versions.

## Required tests

- Fresh install creates and opens an encrypted database.
- Existing schema-v1 and schema-v2 plaintext databases migrate without losing characters, messages, memories, timestamps, indexes, or nullable owner IDs.
- Empty database migration.
- Migration interrupted at every stage; next launch safely resumes or rolls back.
- Corrupt plaintext source, corrupt encrypted temporary file, wrong key, missing key, invalidated Keystore key, and unreadable wrapped-key preferences all fail without deleting source data.
- SQLCipher integrity check succeeds after migration and after normal writes.
- Database file does not expose the SQLite header/plaintext table content.
- WAL checkpoint/close and sidecar handling are correct.
- Cloud backup and device transfer match the chosen recovery policy on Android 11 and lower and Android 12+.
- Existing Room repository and DAO tests continue to pass.

## Acceptance criteria

- All persisted app data in `galaxy.db` is encrypted at rest.
- Existing users retain all existing data through a verified, restart-safe migration.
- No silent key replacement or destructive migration fallback exists.
- Backup/recovery behavior is explicit, documented, and tested.
- CI builds and tests all supported ABI variants; an installable debug APK is produced.


## Implemented on the working branch

- SQLCipher for Android 4.20.0 is integrated with Room 2.7.0 using `SupportOpenHelperFactory`.
- A 256-bit random database passphrase is wrapped by a dedicated AES-GCM key in Android Keystore. The wrapped key is stored in private preferences.
- If a database exists but its wrapped key is missing or cannot be decrypted, startup fails closed. It does not create a replacement key.
- Existing plaintext SQLite databases are checkpointed and exported into a separate encrypted temporary file with SQLCipher's documented `sqlcipher_export()` mechanism.
- Before promotion, the encrypted copy is reopened and checked for `integrity_check`, table row counts, `user_version`, and `application_id`.
- The plaintext original is renamed to a rollback file only after the encrypted copy passes verification. The rollback copy is kept until Room opens the encrypted database successfully.
- Recovery paths handle process interruption before and after promotion; WAL/SHM sidecars are preserved or restored where applicable.
- Android cloud backup and device-to-device transfer exclude the complete database domain and the wrapped database-key preferences.

## Validation still required

- CI compilation and tests for the current PR head.
- Physical-device/emulator verification of SQLCipher native library loading and Room open.
- Fresh encrypted install and migration from schema versions 1 and 2.
- Process interruption at each migration/promotion stage, including WAL and SHM sidecars.
- Missing, corrupted, and Keystore-invalidated wrapped-key scenarios.
- Backup and device-transfer behavior on supported Android versions.
- Verify the plaintext rollback file is removed after successful Room open and remains recoverable on every failure path.

Do not remove the Draft status until the above checks have passed.


## Android instrumentation coverage

The PR adds an Android emulator test suite in `app/src/androidTest` that uses the actual SQLCipher native library and the framework SQLite implementation:

- Encrypts a populated plaintext database and verifies table row counts, `user_version`, `application_id`, and SQLCipher integrity.
- Re-enters migration after encrypted-file promotion to simulate a process restart before rollback-file cleanup.
- Rolls back to the original plaintext database and verifies its rows and schema version.
- Supplies a wrong key after promotion and verifies that the encrypted database and plaintext rollback copy remain present.

The shared CI workflow on the PR base branch now includes an API 35 x86_64 Android emulator stage running `connectedDebugAndroidTest`; APK assembly waits for that stage as well as unit and backend tests.
