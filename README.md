# MCArgus

**English** | [简体中文](README.zh-CN.md)

A smart launcher for Minecraft servers: it finds the right launch target (Forge / NeoForge args file, Fabric / Quilt / vanilla jar), keeps the mods folder tidy, and starts the server with the host's native Java.

Built for panels such as Pterodactyl, but works anywhere Java runs.

## Features

- **Automatic launch-target discovery**
  - Finds Forge/NeoForge `unix_args.txt` / `win_args.txt` and picks the one matching the current OS.
  - Caches the result and re-scans automatically when paths break, files change, or the loader is updated.
  - Falls back to `fabric-server-launch.jar`, `quilt-server-launch.jar`, or `server.jar` when no args file exists.
  - Detects Forge installer jars and tells you to run `--installServer` first instead of failing mysteriously.
- **Native Java, no library hacks**
  - Always launches with the system Java and never replaces the host's dynamic linker or libc.
  - If a mod's native library needs a newer GLIBC, the log explains it and suggests upgrading the OS.
- **YSM / GLIBC_2.18 compatibility**
  - On Linux x86_64 with glibc older than 2.18, automatically preloads a small bundled shim for Yes Steve Model (YSM).
  - Affects only the server child process; no system libraries, jars, or mod files are modified.
- **mods/ filename cleanup**
  - Removes download-site decorations such as `[JEI背包管理器[Forge]]jei.jar` → `jei.jar`.
  - Skips name collisions with a warning so duplicate mods are not introduced.
  - Runs before launch, with a fast fingerprint check to skip unchanged folders.
- **Archive auto-install**
  - If no launch target exists, looks for a server archive (`zip`, `tar`, `tar.gz`/`tgz`, `tar.bz2`/`tbz2`, `tar.xz`/`txz`, `7z`, `rar`) and installs it.
  - Never overwrites existing files.
  - RAR extraction uses the bundled junrar library under the UnRAR License; it is used only to extract archives and must not be used to build a RAR-compatible archiver. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
  - Recognizes CurseForge client modpacks and refuses them with a clear message (use the Server Pack instead).
  - Stops instead of guessing when several archives are present.
- **Operations friendly**
  - `--doctor` prints an environment report (Java, OS, GLIBC, launch target, mods preview).
  - `--dry-run` prints the exact command without launching.
  - Exit codes: `0` OK, `2` usage error, `3` preflight failure, otherwise the server's own code.
  - Console + rotating log file (`mcargus/logs/mcargus.log`, daily, kept 14 days).
- **Localization**
  - Simplified Chinese by default, English available.
  - Switch with `locale=en_us` in the config file (see below).

## Requirements

- **To run:** Java 8 or newer for MCArgus itself. The Minecraft server still needs whatever Java version it requires (for example Java 17 or 21); point MCArgus at it with `--java` or `java.path`.
- **To build:** JDK 8+ (JDK 17 recommended) and the included Gradle wrapper.

## Build

```bash
# Fat jar: build/libs/mcargus.jar
./gradlew jar

# Distribution copies: build/dist/mcargus.jar and build/dist/server.jar
./gradlew packageAll
```

`server.jar` is the same jar under a different name, ready for the default Pterodactyl egg (`java -jar server.jar`).

## Usage

Place `mcargus.jar` in the server root and run:

```bash
java -jar mcargus.jar
```

You can also rename it to `server.jar` and keep the panel's start command unchanged.

### Options

| Option | Description |
| --- | --- |
| `--dir <dir>` | Server root (default: current directory; `/home/container` under Pterodactyl) |
| `--java <path>` | Java used for the server (executable or `JAVA_HOME` directory; default: auto) |
| `--args-file <file>` | Use a specific args file and skip auto-discovery |
| `--jvm "<args>"` | Extra JVM arguments (space-separated), overrides `jvm.extraArgs` |
| `--program-args "<args>"` | Server program arguments (default `nogui`; pass an empty string to clear) |
| `--rescan` | Ignore cache and scan state; force a full re-scan |
| `--sanitize` | Fully scan and fix `mods/` filenames, then exit |
| `--no-sanitize` | Skip the mods scan and cleanup for this run |
| `--dry-run` | Print the full command without launching |
| `--doctor` | Print an environment diagnostics report |
| `--config <file>` | Config file path (default `<server dir>/mcargus/mcargus.properties`) |
| `--version`, `--help` | Show version or help |

Arguments after `--` are passed through to the server.

### Examples

```bash
# Diagnose an environment
java -jar mcargus.jar --dir /srv/mc --doctor

# See the command that would run
java -jar mcargus.jar --dir /srv/mc --dry-run

# Clean mod filenames only
java -jar mcargus.jar --dir /srv/mc --sanitize

# Use a specific Java and force a full scan
java -jar mcargus.jar --dir /srv/mc --java /usr/lib/jvm/java-21/bin/java --rescan
```

## Configuration

MCArgus creates `<server dir>/mcargus/mcargus.properties` on first run. The file contains user settings and an auto-maintained cache.

Common keys:

| Key | Default | Description |
| --- | --- | --- |
| `locale` | `zh_cn` | UI language: `zh_cn` (Simplified Chinese) or `en_us` (English) |
| `java.path` | *(empty)* | Server Java (executable or `JAVA_HOME`); empty = auto |
| `jvm.extraArgs` | *(empty)* | Extra JVM arguments |
| `jvm.forwardParentArgs` | `true` | Forward memory/GC args (`-Xmx`, etc.) from the MCArgus launch command |
| `jvm.useUserJvmArgs` | `true` | Read `user_jvm_args.txt` (Forge/NeoForge standard) |
| `program.args` | `nogui` | Arguments passed to the server |
| `mods.sanitize` | `true` | Clean download-site prefixes in `mods/` filenames before launch |
| `ysm.compat` | `true` | Enable the YSM GLIBC_2.18 compat layer when needed (Linux x86_64, glibc < 2.18, YSM in `mods/`) |
| `install.enabled` | `true` | Auto-install from a server archive when no launch target exists |
| `install.zipEncoding` | `GBK` | Encoding for non-UTF-8 zip entry names (Chinese packaging tools) |

`cache.*` and `scan.*` keys are managed automatically. Delete the config file to force a full re-scan.

### Switching language

1. Start MCArgus once so the config file is created.
2. Edit `mcargus/mcargus.properties`:

   ```properties
   locale=en_us
   ```

3. Restart MCArgus. `--help`, logs, diagnostics, and errors are now in English.

Set `locale=zh_cn` (or remove the key) to return to Simplified Chinese.

### YSM / GLIBC_2.18 compatibility

Some YSM (Yes Steve Model) native builds require `__cxa_thread_atexit_impl@GLIBC_2.18`. On CentOS 7 (glibc 2.17) that version node does not exist, and the server fails with `version 'GLIBC_2.18' not found`.

MCArgus bundles a 15 KB `libysmboot.so`. When all of the following are true, it extracts the shim to `mcargus/native/libysmboot.so` and prepends it to the server process's `LD_PRELOAD`:

- Linux x86_64;
- host glibc < 2.18 (musl / Alpine is skipped);
- `mods/` contains a YSM jar (`ysm*`, `yes_steve_model*`, `yesstevemodel*`);
- `ysm.compat=true` (the default).

The shim appends the missing GLIBC version node in the process's memory (rewriting only the 4-byte `vd_next` pointer at the end of libc's in-memory verdef chain) and implements the symbol. It does not modify the libc file, the server jar, or any mod file, so YSM's integrity check still passes. It applies only to the server child process; MCArgus itself runs without it.

The log shows a line such as:

```text
glibc 2.17 detected; enabled the GLIBC_2.18 compat layer for YSM (server process only, system libraries untouched).
```

To disable it, set `ysm.compat=false` in `mcargus.properties`. On glibc 2.18+ the shim is skipped automatically, so no cleanup is needed.

If a minimal container lacks both `getconf` and `ldd`, specify the version on **MCArgus's own** command line:

```bash
java -Dmcargus.ysm.glibc=2.17 -jar mcargus.jar
```

Do not put this in `jvm.extraArgs`: those arguments are forwarded to the server JVM, not read by MCArgus.

Limitations: x86_64 only; it covers only `__cxa_thread_atexit_impl`. The native source is in [`src/native/`](src/native/).

## How it works

1. Parse options and initialize the locale.
2. Load (or create) the config.
3. Find a launch target: `--args-file` → root args file → cached target → full scan → jar fallback.
4. Check the `mods/` fingerprint; run a full scan and filename cleanup only when needed.
5. If nothing is launchable and `install.enabled=true`, inspect server archives and install the best candidate.
6. Build the final command with the selected Java; on affected systems, attach the YSM GLIBC_2.18 shim to the child process.
7. Launch, forwarding console I/O and shutdown signals.

## License

MCArgus is released under the [MIT License](LICENSE) © 2026 AweiMC.

The fat jar bundles third-party components under their own licenses. In particular, RAR extraction uses **junrar** under the **UnRAR License**; it may not be used to develop a RAR-compatible archiver. See [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for details.
