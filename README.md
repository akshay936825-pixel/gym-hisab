# Gym Hisab (single gym, offline-first)

Kotlin + Jetpack Compose + Room (`hisab.db`), package `com.gym.hisab`.

## Status of this build (stage 1: safe foundation)
- Google Sheet sync is OFF. Nothing is read from or written to your Sheet.
- New month-wise fee ledger (invoices + allocations) and Room migration v1 -> v2 are in the code and unit-tested.
- The screens are NOT yet changed to show the month-wise ledger. That is stage 2.
- Not built or run by me: no Android SDK in my environment. The first GitHub Actions run is the first real build.

## Upload to GitHub
1. Unzip. 2. In your repo, delete the old files (or use a new empty repo). 3. Upload the unzipped contents so `app/`, `.github/` and `settings.gradle.kts` are at the top level. Make sure the hidden `.github` folder is included. 4. Commit to `main`.

## Download the APK
GitHub > Actions > latest "Android build" run > scroll to Artifacts > `gym-hisab-debug-apk` > download, unzip, install `app-debug.apk`. The debug build has package `com.gym.hisab.debug`, so it installs beside any existing app and does not replace or erase it.

## Safety
Never uninstall the existing app or clear its data. See `docs/MIGRATION_PLAN.md`.
