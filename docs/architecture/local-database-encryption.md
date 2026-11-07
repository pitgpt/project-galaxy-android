# Local database encryption — design and migration plan

## Status

Design only. No database code, schema, backup rules, or user data are changed by this document.

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

The SQLCipher native library must be loaded before opening the database. The Room 2 integration uses `SupportOpenHelperFactory`. Pin and verify an exact release compatible with the project's Android Gradle Plugin, Room 2.7.0, supported ABIs, and 16 KB page-size requirements before implementation.

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
4. Create a new encrypted temporary database using the newly generated key.
5. Export the complete schema and data into the encrypted database.
6. Verify the encrypted copy can be reopened with the key, passes SQLCipher integrity checks, has the expected Room schema version, and has matching row counts for every table (characters, messages, memories).
7. Only after verification, atomically promote the encrypted file into the canonical database path using same-filesystem renames.
8. Retain a clearly marked rollback copy only during the migration window. On the next successful startup, verify the encrypted database again and securely remove the temporary plaintext rollback copy.
9. Record migration state so an interrupted migration can deterministically resume or roll back. Never respond to an unknown/partial state by deleting either database.
10. Run Room migrations on the encrypted database after conversion, not during the plaintext export.

If any stage fails, keep the original database intact, report a non-destructive error, and allow retry. No destructive fallback such as `fallbackToDestructiveMigration` is acceptable.

## Backup and recovery decision — must be settled before shipping

A database encrypted with a key that is device-bound to Android Keystore cannot be restored to another device unless the key is also recoverable.

Two valid product choices exist:

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
- Cloud backup and device transfer match the chosen recovery policy.
- Existing Room repository and DAO tests continue to pass.

## Acceptance criteria

- All persisted app data in `galaxy.db` is encrypted at rest.
- Existing users retain all existing data through a verified, restart-safe migration.
- No silent key replacement or destructive migration fallback exists.
- Backup/recovery behavior is explicit, documented, and tested.
- CI builds and tests all supported ABI variants; an installable debug APK is produced.
