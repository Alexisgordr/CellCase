<div align="center">
  <img src="docs/img/cellcase-icon.png" alt="CellCase icon" width="160">
  <h1>CellCase</h1>
  <p><strong>Turn ICdetector exports into verifiable PDF reports, entirely on your phone.</strong></p>
  <p>
    <a href="LICENSE"><img alt="License: GPL-3.0" src="https://img.shields.io/badge/License-GPL--3.0-blue.svg"></a>
    <a href="#requirements"><img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84.svg?logo=android&logoColor=white"></a>
    <img alt="Version 0.2.0" src="https://img.shields.io/badge/version-0.2.0-orange.svg">
    <img alt="Kotlin 2.4" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF.svg?logo=kotlin&logoColor=white">
    <a href="#tests"><img alt="329 tests" src="https://img.shields.io/badge/tests-329%20passing-brightgreen.svg"></a>
    <a href="https://github.com/Alexisgordr/CellCase/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/Alexisgordr/CellCase/actions/workflows/ci.yml/badge.svg"></a>
  </p>
  <p><em>No accounts · No network · No analytics · Open source</em></p>
  <p>
    <a href="MANUAL.md"><strong>User manual</strong></a> ·
    <a href="#documentation">Design documents</a> ·
    <a href="https://github.com/Alexisgordr/ICdetector">ICdetector</a> (supported up to 3.1.1)
  </p>
</div>

---

## What it is

[ICdetector](https://github.com/Alexisgordr/ICdetector) watches a phone's connection to cell towers and records anything that looks unusual. CellCase 0.2.0 reads its exports from 2.10.5 up to 3.1.1, the current version. CellCase is its companion: an independent Android app that **imports those exports, validates them, keeps their provenance and writes a technical report in PDF** that answers one question honestly:

> *What do these data allow us to conclude about possible fake base stations (IMSI catchers), and what should be checked next?*

It is designed for the person who captured the data, for whoever helps them read it, and for anyone who later needs to verify that the report matches the original files. Everything runs locally; the PDF is the only thing that ever leaves the phone, and only when you choose to save it.

## Highlights

- **Two reports from one analysis.** A *plain-language* report (2–3 pages, for people without technical knowledge) and a *full technical* report (incidents, measurements, methodology, sources, hashes and rules). Both reach the same conclusion; only the presentation changes.
- **Honest by design.** CellCase never claims to have found an IMSI catcher. It separates *what ICdetector recorded* from *what CellCase verified in the measurements*, and *not assessable* from *not contradicted*. Specialist attribution criteria are explicitly marked as not evaluated.
- **Every ICdetector export, including the 3.0 and 3.1.x backups.** History, Radio, Topology, Stable-Site, Geometry and forensic cases (CSV and ZIP, from 2.10.x, 3.0, 3.1.0 and 3.1.1), or a single `.icdbackup` file that contains all of them, read with a built-in SQLite reader.
- **Ready for ICdetector 3.1.x.** 3.1.1 only fixes the app and keeps the 3.1.0 formats. The new cell-churn columns (H17) are shown as shadow context in the technical report, without thresholds and without affecting any assessment; the retired external verification and the 3.1.0 dataset cut are explained instead of being misread.
- **Provenance you can check.** Each imported file is kept as a private copy with its SHA-256; every finding in the technical report cites the file, entry and physical line it comes from; `SHA256SUMS` inside ZIPs is verified.
- **Hostile input is expected.** Size, row and compression-ratio limits are enforced *before* anything is loaded; malformed CSV, damaged ZIPs, zip bombs and crafted SQLite pages are rejected with a reason, never with a crash.
- **Privacy controls on the report.** Coordinates can be omitted or rounded (~1 km / ~100 m); TAC and Cell ID can be masked. Rounding is visual only: the originals keep full precision.
- **Spanish and English**, for both the interface and the reports; the report language is chosen at generation time, independently of the app language.

## How it works

```
 ICdetector exports ──▶ Import ──▶ Analysis ──▶ Report
 (CSV · ZIP · .icdbackup)   │          │           │
                            │          │           └─ plain-language or full technical PDF,
                            │          │              with privacy options and hashes
                            │          └─ signals → episodes → groups;
                            │             priority (technical interest) and
                            │             spoofing relevance, as two separate axes
                            └─ private copy + SHA-256, content detection,
                               per-file validation, limits, duplicate detection
```

1. **Create a case**: name, capture device, time zone (saved as a *declared assumption*) and data origin (real, synthetic, mixed or not determined).
2. **Add files**: several at once. Each one is processed separately, so a damaged file never blocks the others. Identical files (same SHA-256) are detected and not counted twice.
3. **Read the analysis** on screen: incidents, history quality, agreements and differences between sources, ICdetector's local profile (shadow mode).
4. **Generate the PDF** in the mode and language you need, choose the privacy options, and save a copy wherever you want.

The analysis itself groups every ICdetector signal (a flagged history row, a forensic case, a Stable-Site episode) into **episodes** by cell and pattern, then assesses each one on two independent axes:

| Axis | Levels | What drives it |
|---|---|---|
| **Technical interest** (priority of review) | Priority review · To review · Context | ICdetector alarm, behaviour verified in the measurements, recurrence or persistence, data quality |
| **Spoofing relevance** | Insufficient evidence · Observations that need more data · *(Evidence requiring specialist investigation — never assigned: no evaluated criteria exist yet)* | Pattern kind (identity-related patterns weigh more), alarms, forensic cases |

Repetition of a common behaviour can raise the technical interest without raising suspicion. The rules are documented in [`docs/episode-rules.md`](docs/episode-rules.md) and versioned (`episodes/7`); every report states which version produced it.

## What it does not do

- It does **not** confirm or rule out a fake base station. It assesses whether the data give reasons to investigate.
- It does **not** recalculate ICdetector's heuristics or adopt their wording: ICdetector's reasons are quoted literally and marked as such.
- It does **not** verify the authenticity of the files, the device or the capture time zone: those are declarations of whoever created the case, and the report says so.
- It has **no network access**: no cloud, no public cell databases, no telemetry. The OpenCellID verification states that ICdetector up to 3.0 could record (VERIFIED, NOT_FOUND…) are reported as context only. ICdetector 3.1.0 removed that lookup (and the latency probe behind H12), so its rows are listed as *no external verification (removed)*, not as pending.

## Requirements

- Android 8.0 (API 26) or newer.
- ICdetector exports: CSV/ZIP from version 2.10.5, 3.0, 3.1.0 or 3.1.1, or a `.icdbackup` from 3.0.0, 3.1.0 or 3.1.1 (schemas 25 and 26).
- No permissions are requested. Files are chosen with the system picker.

## Install

Download the APK from the [Releases](https://github.com/Alexisgordr/CellCase/releases) page, check its SHA-256 against the one published there, and install it. A listing on F-Droid is planned.

## Build from source

Requirements: JDK 17 or newer and an Android SDK with platform 37.

```bash
./gradlew :core:test :app:testDebugUnitTest   # unit tests (core + app)
./gradlew :app:assembleDebug                  # debug APK → app/build/outputs/apk/debug/
./gradlew :app:assembleRelease                # unsigned release APK (sign it with your own key)
./gradlew :app:connectedDebugAndroidTest      # instrumented tests (device or emulator)
./gradlew :core:sampleReport                  # regenerate a synthetic sample PDF
```

Without access to Maven repositories, `tools/local/build-core.sh` compiles and tests the `:core` module with the Kotlin compiler bundled in a local Gradle installation and regenerates all sample PDFs.

Toolchain: AGP 9.4.0 · Gradle 9.6.0 · Kotlin 2.4.10 · Compose BOM 2026.09.00 · minSdk 26 · targetSdk 37. The release build disables `dependenciesInfo`, as F-Droid requires. Configuration cache and build cache are enabled.

## Project layout

```
core/      Pure Kotlin/JVM, no Android: csv · time · model · importing · backup · analysis · report · pdf · i18n
app/       Android: data (SQLite + private files) · ui (Compose) · logic (testable without Android)
docs/      Design documents, rules, limits, decisions, open questions
samples/   SYNTHETIC samples of every format, hostile files, and the PDFs the core generates from them
tools/     Sample generators, verification against ICdetector's source, i18n checks, local build
```

The `:core` module has no Android dependency, so the whole import → analysis → PDF pipeline runs and is tested on a plain JVM. The hand-written PDF writer uses the standard Type 1 fonts with WinAnsi encoding, which covers Spanish and English completely.

## Tests

| Suite | Count | Where |
|---|---|---|
| Core (JUnit 4, JVM) | 302 | `core/src/test` |
| App logic (JVM) | 20 | `app/src/test` |
| Instrumented (device/emulator) | 7 | `app/src/androidTest` |

The CI workflow (`.github/workflows/ci.yml`) runs on every push and pull request: core and app tests, lint, debug and release APKs, text-catalogue consistency (same keys, placeholders and plural forms in Spanish and English), determinism of the 3.0 and 3.1 synthetic samples, verification of the samples against the source code of ICdetector v2.10.5, v3.0.0 and v3.1.0, and the instrumented tests on an emulator. It signs and publishes nothing.

All samples under `samples/` are **synthetic** and marked as such; the first real exports were analysed on the author's phone after the code was frozen for 0.1.0.

## Documentation

| Document | Contents |
|---|---|
| [`MANUAL.md`](MANUAL.md) | **User manual**: screens, importing, reading the analysis, generating and sharing reports, privacy, troubleshooting |
| [`docs/export-formats.md`](docs/export-formats.md) | Inventory of every ICdetector export, deduced from its source code |
| [`docs/backup-format.md`](docs/backup-format.md) | The `.icdbackup` file of ICdetector 3.0.0 and 3.1.x (schemas 25 and 26) and how CellCase reads it |
| [`docs/episode-rules.md`](docs/episode-rules.md) | How signals become episodes, priorities and relevance (`episodes/7`) |
| [`docs/analysis-rules.md`](docs/analysis-rules.md) | Descriptive rules applied to the history |
| [`docs/cross-sources.md`](docs/cross-sources.md) | Cross-checks between sources (`cross/4`) |
| [`docs/churn.md`](docs/churn.md) | ICdetector 3.1.0's cell churn (H17), shown as shadow context (`churn/1`) |
| [`docs/local-profile.md`](docs/local-profile.md) | ICdetector 3.0's local profile, shown as context |
| [`docs/limits.md`](docs/limits.md) | Import and memory limits, with measurements |
| [`docs/i18n.md`](docs/i18n.md) | How texts are organised in Spanish and English |
| [`docs/apk-size.md`](docs/apk-size.md) | APK size and the effect of R8 |
| [`docs/licensing.md`](docs/licensing.md) | Licences of every dependency |
| [`docs/decisions.md`](docs/decisions.md) | Design decisions, numbered and dated |
| [`docs/open-questions.md`](docs/open-questions.md) | Open questions about the formats, with proposals |

## Privacy

CellCase stores cases, private copies of the imported files and generated PDFs in its own app-private storage. Android's cloud backup and device-to-device transfer are disabled for all app data. Deleting a case removes everything CellCase holds about it in two phases (trash, then purge), and never touches the original files you chose with the picker or the PDFs you saved elsewhere.

## Contributing

Bug reports and feedback are welcome, especially real exports that break an assumption documented in `docs/`. When reporting an import problem, please include the file's state as shown in the *Import result* dialog and, if you can, the export itself. Every new function must come with its tests.

## License

CellCase is free software under the [GNU General Public License v3.0](LICENSE), the same licence as ICdetector. The icon is original work by the author.

---

<p align="center"><sub>Built by a single developer, with a lot of care and many hours. If it has been useful to you, a ⭐ or a bug report helps.</sub></p>
