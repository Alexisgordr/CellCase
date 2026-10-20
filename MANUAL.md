# CellCase · User manual

Version 0.1.0 · rules `episodes/6` · readers `readers/2.3.0`

This manual explains how to use CellCase, how to read what it shows and what its reports can and cannot say. For what the app is and how to build it, see the [README](README.md). For the rules in detail, see the documents under [`docs/`](docs/).

## Contents

1. [Before you start](#1-before-you-start)
2. [Getting exports out of ICdetector](#2-getting-exports-out-of-icdetector)
3. [The case list](#3-the-case-list)
4. [Creating a case](#4-creating-a-case)
5. [Adding files](#5-adding-files)
6. [Reading the case screen](#6-reading-the-case-screen)
7. [Period of interest and analyst's notes](#7-period-of-interest-and-analysts-notes)
8. [Generating a PDF report](#8-generating-a-pdf-report)
9. [Reading the reports](#9-reading-the-reports)
10. [How the analysis works](#10-how-the-analysis-works)
11. [Privacy and storage](#11-privacy-and-storage)
12. [Limits](#12-limits)
13. [Troubleshooting](#13-troubleshooting)
14. [Glossary](#14-glossary)

---

## 1. Before you start

CellCase does not capture anything by itself. It reads the files that [ICdetector](https://github.com/Alexisgordr/ICdetector) exports and turns them into a report. You need:

- A phone with Android 8.0 or newer with CellCase installed.
- One or more ICdetector exports, or an ICdetector 3.0.0 backup (`.icdbackup`), copied to the phone where CellCase runs (it can be the same phone or another one).

CellCase asks for no permissions and never connects to the network. Everything stays on the phone until you save a PDF somewhere.

**What the report answers.** Whether the data give reasons to investigate a possible fake base station (IMSI catcher), and what to check next. **What it never says.** That there was, or was not, an attack. CellCase has no evaluated criteria for that attribution, and it says so in every report.

## 2. Getting exports out of ICdetector

The simplest way, with ICdetector 3.0.0, is one file:

- **Settings → Backup** in ICdetector creates an `ICdetection-backup-<date>-<time>.icdbackup` file that contains the whole database. CellCase extracts from it the same six exports that ICdetector would produce one by one.

The individual exports (ICdetector 2.10.5 or 3.0) also work, alone or combined:

| Export | Where in ICdetector | File name |
|---|---|---|
| History of observations | History → Export CSV | `icdetector_history_<number>.csv` |
| Radio (service-state events) | Radio → Export | `icdetector_service_state_<number>.csv` |
| Forensic case | History → a case → Export | `ICD-YYYY-MM-DD-NNNN.zip` or `ICD-OBS-…zip` |
| Topology | History → Topology → Export | `ICD-topology-<number>.zip` |
| Geometry | Geometry tab → Export | `ICD-geometry-<number>.zip` |
| Stable-Site | History → Stable-Site → Export | `ICD-stable-site-<number>.zip` |

CellCase recognises each file **by its content**, not by its name, so renamed files are fine. The history is the most important one: without it there are no measurements to check anything against, although the other formats are still analysed.

## 3. The case list

The first screen lists your **cases**. A case is a folder for one investigation: a set of files from one phone, their analysis and the reports generated from them.

- **New case** creates one (section 4).
- Tap a case to open it. Each row shows its origin declaration, time zone and creation date.
- **Language** (top-right menu) changes the app's texts: *Follow the system language*, *Español* or *English*. It does not change cases, files or saved reports; the language of each report is chosen when it is generated.
- From the list or from inside a case you can **Edit** or **Delete** it. Deleting shows exactly what will be removed (section 11).

## 4. Creating a case

The *New case* form has five parts.

**Name and description.** Free text; the name appears in the report and in the PDF file name.

**Analysis device detected.** CellCase shows the phone it is running on (model, Android version) and its current time zone. This is *where the analysis runs*; it does not prove where the files came from.

**Where the captures come from (your declaration).** *The captures come from this phone*, *They come from another device* (then give its make and model) or *I am not declaring it*. It is saved as a declaration.

**Time zone during the capture.** ICdetector writes most times in the phone's local time without recording the zone, so CellCase needs to know which zone was in use. It suggests the current one; confirm it with the checkbox *I confirm the capture phone used this time zone*. The report will say whether the zone was confirmed. The zone must be confirmed to save the case. Without a confirmed zone, checks that need an exact instant are marked *not assessable* rather than guessed.

**Data origin** (only when creating; afterwards use the **Origin** button on the case screen). One of:

- *Not determined (I am not declaring it yet)* — the default.
- *Real: captures from a real phone.* This is your declaration; it does not verify authenticity.
- *Synthetic: generated test data.*
- *Mixed: real and synthetic data in the same case.*

The declaration is printed on the report. If CellCase finds automatic signs of test data (test MCC `001`, test operator `00101`, a device marked synthetic) in a case declared real, it warns you to review the declaration. You can change the origin later from the case screen.

## 5. Adding files

Inside a case, tap **Add files** and select one or several files with the system picker. CellCase then does, for each file separately:

1. Makes a **private copy** and computes its **SHA-256**. If an identical file is already in the case, it reports *Already present* and does not count it twice.
2. **Detects the content** (history, Radio, a ZIP of one of the four kinds, or a backup).
3. **Validates and reads it**, applying the limits of section 12.
4. **Records** the result. A problem in one file never affects the others.

The *Import result* dialog lists every file with its state:

| State | Meaning |
|---|---|
| **Imported** | Read correctly. Tap it to see counts, period, integrity and, for ZIPs, their entries. |
| **File not recognised** | Not an ICdetector export (or an unsupported version). The file is kept but not analysed. |
| **Damaged ZIP** | The ZIP is truncated or inconsistent. |
| **ZIP rejected for safety** | It exceeds an entry, size, count or compression-ratio limit. |
| **Incompatible content** | Recognised, but its structure is not the expected one. |
| **Importer not implemented** | A known format that CellCase does not read yet. |
| **Read error** | The content could not be parsed; the message says why. |
| **File too large** | It exceeds a size or row limit (section 12). |

A backup is shown as one file with several parts (`history.csv`, `service_state.csv`, `topology.zip`…), each with its own state, so one oversized forensic case inside a backup does not prevent the rest from being imported.

You can add more files at any time. When a case is opened, all its originals are re-read with the current readers, so an update of CellCase can improve the analysis of old files.

**Cancel** stops the selection: the files already finished are kept; the one in progress is discarded and can be added again.

**Check originals** recomputes the SHA-256 of every private copy and compares it with the one recorded at import, to make sure nothing was altered on the device.

## 6. Reading the case screen

The sections appear in this order.

**Case.** Name, device, time zone and origin, with the buttons to edit, change the origin or delete.

**Files.** The list of imported files. Tap one to see its content and issues.

**Import result** and **Duplicate review.** The result of the last selection, and any forensic case that was exported more than once (CellCase keeps every version and tells you how they relate; it never deletes a file).

**Incidents.** The core of the analysis: the overall status for the spoofing question, how many episodes are *priority review*, *to review* and *context*, and the list of episodes (number, date, pattern, cell, technical interest and spoofing relevance). Section 10 explains how these are computed.

**Agreements and differences between sources.** Totals of the cross-checks between files (X-SVC, X-CELL, X-ROUTE, X-AGG, X-SHADOW, X-FOR, X-VER…) and, under *Show details*, the ones linked to an episode. Most sources come from ICdetector itself, so an agreement checks that the files are consistent; it is not independent confirmation. These checks never change the assessment.

**ICdetector local profile (shadow mode).** For histories from ICdetector 3.0: how far each reading deviated from what that phone considers normal for that cell, as ICdetector computed it. Shown as context; it changes no conclusion.

**History: period and quality.** Period covered, rows read and discarded, share of valid fields, gaps of 60 minutes or more, and how many rows have a unique instant in the declared zone.

**Period of interest**, **Analyst's notes** and **PDF report** are described in the next two sections.

While an operation runs (loading, importing, generating a report) the screen shows what it is doing; **Cancel** stops it at the next safe point. Editing controls are disabled meanwhile.

## 7. Period of interest and analyst's notes

**Period of interest (optional).** A start and end in the capture time zone, with a label. It narrows what the report focuses on (episodes, counts and figures) without hiding the rest. Times that do not exist or are ambiguous because of a daylight-saving change are refused with a message; choose another time.

**Analyst's notes.** Free text that goes into the report in its own section, clearly separated from the automatic text and never translated. Use it for what the data cannot know: where the phone was, what happened that day, what you want a reader to check.

Changing the time zone or the period recalculates the analysis. Reports generated before are kept and marked *Generated with an earlier configuration*.

## 8. Generating a PDF report

In the **PDF report** section:

**Type.**
- *Plain-language* (recommended): 2–3 pages for people without technical knowledge. It answers what the data allow us to conclude and what to do.
- *Full technical*: incidents one by one with measurements and figures, chronology, network context, cross-checks, local profile, sources and quality, methodology, limitations, notes and annexes with hashes and references.

Both use the same analysis and reach the same conclusion.

**Report language.** *Español* or *English*, independent of the app language. Only the presentation changes.

**Coordinates in the report.** *Omit* (default), *Round (~1 km)*, *Round (~100 m)* or *Full*. Rounding is visual only: the originals keep full precision.

**Mask TAC and CID.** Masks the tracking-area and cell identifiers in the report, keeping only their last two digits, so it can be shared without revealing exactly which cells were seen.

**Include annex with the full list of cross-checks.** Off by default. The summary keeps the same totals; with large datasets the annex can add hundreds of pages.

Tap **Generate PDF**. The report is stored inside the case with its mode, language, date, page count and the configuration it was generated with. **Save copy** writes it wherever you choose with the system picker (file name `CellCase-<case>-<mode>-<language>-<id>.pdf`).

## 9. Reading the reports

### The plain-language report

1. **What can the data tell us about possible fake base stations?** The overall status in one line, with a plain explanation of what a fake base station is and of what the report can and cannot say.
2. **Which observations deserve attention, and why?** Episodes of the same pattern and relevance are grouped into *observations* ("Frequency changes within the 4G network, 3 times") with a one-sentence verdict each.
3. **What can you do next?** Concrete steps, or "keep ICdetector running as usual".
4. **The observations, one by one.** For each: what happened, normal explanations, what is missing, what to do, what other sources show, what the local profile says. A timeline figure shows when each observation happened.
5. **What this report cannot say.**

### The full technical report

1. **Summary**: assessment for the spoofing question and a sentence on the technical interest.
2. **Incidents**: a table in chronological order (observation by ICdetector, evidence by CellCase, interpretation, technical interest, spoofing), and the table of short cell identifiers (C1, C2…) used throughout.
3. **Detail of the incidents**: for each episode, *what was observed* (ICdetector's text quoted literally), *what evidence supports it* (CellCase's own checks), *what it means and what it does not allow us to conclude*, *what to check next*, figures of band and power around the episode, serving cells before/during/after, and the file and line of every reference.
4. **Chronology**: signals per source, history rows per day, Radio state changes.
5. **Network context**: Topology (routes, handovers, a graph of the most relevant cells), Radio, Stable-Site and Geometry aggregates.
6. **Agreements and differences between sources**: totals, one row per check type with examples, and the checks linked to episodes.
7. **ICdetector local profile (shadow mode)**, only when the data come from ICdetector 3.0.
8. **Sources and data quality**: every file with its state, period and counts; history quality; which analyses are not available and why.
9. **Methodology**, 10. **Conclusion and next steps**, 11. **Limitations**, 12. **Analyst's notes**.
- **Annexes**: A. files, entries and integrity (SHA-256 of each file, `SHA256SUMS` results); B. validation issues per file; C. evidence references per episode; D. statistical description of the history; E. rules and thresholds; F. privacy and rounding applied; G. (optional) the full list of cross-checks.

Every page carries the generation time, the CellCase version and the rule versions (`cellcase-rules/1 · episodes/6`), and a banner when the data are declared synthetic.

### Reading the two axes

Each episode is rated on **two independent axes**:

- **Technical interest** (*Priority review*, *To review*, *Context*) answers "is this worth a technician's time?". Repetition of a common behaviour can raise it without raising suspicion.
- **Spoofing relevance** (*Insufficient evidence to attribute an attack*, *Observations that need more data*) answers the report's question. A third level, *Evidence requiring specialist investigation*, exists in the rules but is **never assigned** in 0.1.0 because no criteria have been evaluated against reference data.

The case's status is the highest of its episodes. None of these is a probability.

## 10. How the analysis works

In short (full rules in [`docs/episode-rules.md`](docs/episode-rules.md), [`docs/analysis-rules.md`](docs/analysis-rules.md) and [`docs/cross-sources.md`](docs/cross-sources.md)):

**Signals.** A history row whose `FailedHeuristics` is not `OK` (with or without the `[sub-threshold]` mark), a forensic case window, a Stable-Site shadow episode. Only signals with a unique UTC instant form episodes. Radio states (`OUT_OF_SERVICE`, `EMERGENCY_ONLY`, searching, operator different from the SIM) are context attached to nearby episodes.

**Patterns.** Deduced from ICdetector's reason text: band change, alternating PCI, power deviation, TAC, Timing Advance, transition. ICdetector's wording ("forced") is quoted, never adopted.

**Episodes and groups.** Signals of the same cell and pattern within 30 minutes form an episode; a forensic case without identity links to what overlaps its window (±2 min); episodes within 15 minutes are shown as a group (G1, G2…) without merging. Radio joins an episode if a degraded state falls within 5 minutes. Signals within 60 s count as one moment; *persistence* is 3 or more moments over 10 minutes or more; *recurrence* is the same cell and pattern on 2 or more local dates.

**CellCase's own checks.** Where the history allows it, CellCase verifies the pattern in the measurements: a band change against the previous row's band (within 10 minutes); an alternating PCI against the series of PCI per identity and carrier (±30 minutes; A→B→A or alternation is *visible*, A→B alone is partial); a power deviation against the cell's median (10 dB or more, with at least 5 rows). The result is one of *verified*, *not contradicted* or *not assessable*, and the last two are never confused.

**Priority.** *Priority review* needs an ICdetector alarm (not sub-threshold, or a forensic case of origin `ALARM`), good data quality, behaviour verified in the measurements, and persistence or recurrence. *To review* needs an alarm, or verified behaviour with recurrence or persistence. Everything else is *Context*. Verification states (VERIFIED, NOT_FOUND…), how usual a cell is, and coincidence with Radio are reported but never change the priority. A sub-threshold signal never reaches priority review.

**Relevance.** Common network behaviours without alarm or forensic case are *insufficient evidence*. Alarms, forensic cases of origin `ALARM`, a drop to 2G/3G, and identity-related patterns (PCI, MCC/MNC, ciphering, TAC, TA, isolated cell, spectrum) are *observations that need more data*.

**Cross-checks.** X-SVC compares the history's service state with Radio; X-CELL and X-ROUTE compare serving cells and cell changes with the Topology, Geometry and Stable-Site aggregates; X-AGG compares the same route in Topology and Geometry; X-SHADOW relates Stable-Site episodes with history rows (±5 s) and routes (±1 min); X-FOR, X-FOR-RAD and X-FOR-AGG relate forensic cases with the history, Radio and the aggregates; X-VER handles several exports of the same aggregate whose order cannot be proven. Results are *agreement*, *difference*, *supplementary* or *not comparable*; none changes the assessment.

## 11. Privacy and storage

- **No permissions, no network.** Files are chosen with the system picker; CellCase cannot see anything else on the phone and sends nothing anywhere.
- **Where things are.** Cases (a SQLite database), private copies of the originals (named by their SHA-256) and generated PDFs live in CellCase's app-private storage. A backup's database is unpacked to a temporary cache file while it is read and always deleted afterwards. Android's automatic backup and device-to-device transfer are disabled for all of it.
- **Integrity.** Each original is stored with its SHA-256; *Check originals* verifies them at any time. Files are written atomically (temporary file, sync, rename), so an interruption never leaves a half-written case.
- **What a report reveals.** The technical report contains full cell identities (MCC-MNC-TAC-CID), times, and, if you choose so, coordinates. Use *Omit* coordinates and *Mask TAC and CID* before sharing it with someone who should not know where the phone was. The plain-language report names cells only as C1, C2….
- **Deleting a case** removes the case, its notes, period and options; the derived records (analysis, issues, report history); and the private copies and the PDFs saved inside CellCase. It does **not** delete the original files you picked or the PDFs you saved outside CellCase. Deletion happens in two phases (trash, then purge) so that an interruption can be completed at the next start. It cannot be undone.

## 12. Limits

Limits are applied **before** loading anything, so an oversized file is rejected with a message rather than exhausting memory. Values in 0.1.0 (`readers/2.3.0`; measurements in [`docs/limits.md`](docs/limits.md)):

| Limit | Value |
|---|---|
| Any file | 6 MiB |
| History CSV | 16 MiB |
| Backup `.icdbackup` | 512 MiB (database inside: 1 GiB; read from disk, not into memory) |
| Rows per table | 60,000 |
| CSV field | 8 KiB |
| Decompressed ZIP entry / whole ZIP | 6 MiB / 8 MiB |
| Entries per ZIP · compression ratio | 64 · 200 |
| **History rows per case** | 40,000 in total (about 28 weeks of continuous capture) |
| **Decompressed ZIP per case** | 8 MiB in total |
| Forensic case inside a backup | the ZIP limits above; a larger one is marked *too large* without being loaded |

The case budget is reserved in import order: the first files have priority, and the one that does not fit is marked *File too large* and kept, not interpreted.

## 13. Troubleshooting

**"File not recognised" for a real ICdetector export.** Histories older than ICdetector 2.1 (fewer than 23 columns) are not supported, and Radio exports exist only from 2.10.4. Backups from 3.0 betas (database schema 20–24) are not interpreted. For any other export, please open an issue with the file if you can share it.

**Many episodes marked "not assessable".** Usually the time zone was not confirmed, or the history was not imported. Confirm the zone in *Edit case* and make sure the history file (or the backup) is in the case.

**Episodes appear only as "Context".** That is the expected result for most captures: ICdetector recorded sub-threshold notes, CellCase verified them where possible, and nothing met the review criteria. It is not a defect of the data.

**A report says "Generated with an earlier configuration".** You changed the zone or the period after generating it. Generate it again.

**Radio shows many "out of service" states.** Radio records every state change; a phone that moves through areas of poor coverage produces many. They are context unless they coincide with an episode.

**The app says "An operation is already in progress".** Wait for it to finish or tap *Cancel*.

**Importing was interrupted (crash, reboot).** Nothing half-written is kept: a file is either fully imported with its record or absent. Add it again.

## 14. Glossary

- **Alarm** — an ICdetector notice that reached its alarm threshold (not marked `[sub-threshold]`), or a forensic case of origin `ALARM`.
- **Case** — a folder in CellCase: files, analysis and reports of one investigation.
- **Cell identity** — `MCC-MNC-TAC-CID-technology`; in GSM/UMTS the TAC column holds the LAC and the PCI column the PSC.
- **Episode** — signals of the same cell and pattern within 30 minutes.
- **Forensic case** — a ZIP ICdetector exports for one incident, with its fine timeline.
- **Observation** — in the plain-language report, the episodes of the same pattern and relevance grouped together.
- **Pattern** — the kind of behaviour ICdetector noted: band change, alternating PCI, power deviation, TAC, Timing Advance, transition.
- **Shadow mode** — a layer that records but never changes a conclusion (Stable-Site episodes, ICdetector's local profile).
- **Sub-threshold** — an ICdetector note that did not reach its alarm threshold.
- **Verified / not contradicted / not assessable** — the three outcomes of CellCase's own checks; the last two are never equated.
