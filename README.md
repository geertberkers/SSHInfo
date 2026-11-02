# SSH Key Checker

A small Kotlin Compose Multiplatform (desktop) app that scans your `~/.ssh` folder, shows which private keys are passphrase protected, and lets you verify a passphrase against a key.

## Features

- Lists every private key in `~/.ssh` (skips `.pub`, `known_hosts`, `known_hosts.old`, `config`, `authorized_keys`).
- Shows each key's status:
  - **No passphrase**: the key can be used by anyone who can read the file.
  - **Passphrase protected**
  - **Permissions too open**: OpenSSH refuses to use the key until file permissions are fixed.
  - **Unreadable**
- **Check** a passphrase: reports whether it is correct for the selected key.
- **Save** a passphrase per key, with a **Show/Hide** toggle on the input field.
- **Config tab**: reads `~/.ssh/config` and shows each `Host`/`Match` block with all its options (HostName, User, Port, ProxyJump, ...). Each `IdentityFile` is matched to your scanned keys and shows that key's status.
- **Rescan** to refresh keys and config.

## How it works

The app shells out to the OpenSSH `ssh-keygen` that ships with Windows 10/11, macOS and Linux:

```
ssh-keygen -y -P <passphrase> -f <keyfile>
```

This derives the public key and only succeeds when the passphrase is right. An empty passphrase that succeeds means the key is unprotected. There is no custom crypto, and no guessing or wordlists: it only tests the one passphrase you type.

## Requirements

- JDK 17+ (a full JDK with `jpackage` is needed to build the exe; Android Studio's bundled JBR does not include it)
- `ssh-keygen` on the `PATH`

## Run

```bash
./gradlew run
```

On Windows use `gradlew.bat run`.

## Build a standalone exe (Windows)

Double-click **`build-exe.bat`**. It rebuilds the app and writes the result to:

```
ssh-key-checker\ssh-key-checker.exe
```

Keep the whole `ssh-key-checker` folder together: the exe needs the bundled runtime next to it. The folder is git-ignored.

The script looks for a JDK with `jpackage` (Eclipse Adoptium or Java under `C:\Program Files`), or uses `JAVA_HOME` if it already points to one.

## Project layout

```
build.gradle.kts           Kotlin 2.1.20, Compose Multiplatform 1.8.0, Material 3
settings.gradle.kts
build-exe.bat              One-click exe build
src/main/kotlin/Main.kt    Key scanning, ssh-keygen calls, passphrase store, UI
```

## Security notes

- **Saved passphrases are only base64 encoded, not encrypted.** They are stored one per line as `keyname:base64` in `~/.ssh/passphrases.b64`. Anyone who can read that file can decode them instantly. The app restricts the file to the current user where the OS allows it. Prefer an OS credential store or a password manager for anything important.
- The passphrase is passed to `ssh-keygen` as a command-line argument, so it is briefly visible in the process list on your machine.
- Use this only on keys you own.
