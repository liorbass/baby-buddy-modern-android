# Contributing

Issues and pull requests are welcome. This page covers building the app, the checks every commit goes through, tests and where things live.

Ground rules:

- Never put real names, server addresses or tokens in code, docs or screenshots; demo data uses "Sam" and "Alex".
- Keep to the stock Baby Buddy API; anything the server has no field for is encoded as described in the [ICD](docs/ICD.md) (§5), so other clients can read it.

Requirements to build: [Android Studio](https://developer.android.com/studio) (latest, AGP 9.4 / compileSdk 37), or JDK 17+ (21 recommended) with the Gradle wrapper.

## Build

```bash
git clone https://github.com/liorbass/baby-buddy-modern-android.git
cd baby-buddy-modern-android
./gradlew assembleDebug        # APK at app/build/outputs/apk/debug/app-debug.apk
```

or open the project in Android Studio and run the `app` configuration.

Notes:

- **Signing:** debug builds use the standard Gradle debug key (or a gitignored `debug.keystore` in the repo root, if present). An APK you build yourself can't update one installed from the releases (different signing key), and vice versa.

## Pre-commit checks

Checks run through [pre-commit](https://pre-commit.com) (`.pre-commit-config.yaml`): file hygiene, gitleaks secret scanning, ktlint formatting (Java 11+ on `PATH`), actionlint, and blocks on keystores, `.env`, APKs and data exports. Install once per clone, from the same environment you commit from (on Windows: a Windows terminal, so Android Studio's commits use it):

```bash
pipx install pre-commit          # or: uv tool install pre-commit / pip install pre-commit
pre-commit install
cp tools/hooks/blocklist.example tools/hooks/blocklist.local   # names/addresses to keep out of git (gitignored)
```

`pre-commit run --all-files` checks everything; `pre-commit autoupdate` bumps hook versions. The same checks run in CI (`.github/workflows/checks.yml`).

## Run tests

```bash
./gradlew testDebugUnitTest        # JVM/Robolectric unit tests
./gradlew connectedDebugAndroidTest # instrumented tests (needs a device/emulator)
```

## Translations

All UI text lives in `app/src/main/res/values/strings.xml`; a translation is a `values-<lang>/strings.xml` with the same names (the language then shows up in the phone's per-app language setting, Android 13+).

- In composables use `stringResource` / `pluralStringResource`. Elsewhere (view model, repository, widgets, notifications, enum labels) use `context.getString` when there is a `Context`, else `AppText.get` / `AppText.plural` (`data/model/AppText.kt`).
- Anything written to the server stays English, so other clients can read it: the note segments in the [ICD](docs/ICD.md) (§5), tags, shared timer names and the stored diaper values (amount, consistency, color, cream). Show those through `diaperValueLabel`.
- Use positional arguments (`%1$s`, `%2$d`) when a string has more than one, and `plurals` for counts.
- Use the Baby Buddy server's own words, so the app and the web UI match: `python3 tools/i18n/server_glossary.py <lang>` (the server's locale folder: `de`, `pt_BR`, `zh_Hans`, …) lists the app strings the server already translates and the server's short terms.
- `python3 tools/i18n/check_strings.py` checks every translation against the English file (same names and kinds, the same format arguments, escaped `'` and `"`, an `other` form in each plurals entry); it runs in pre-commit.
- French was checked on phones by a French speaker; German, Dutch, Spanish and Hebrew were machine-translated with the server glossary and need a native speaker's review. Corrections are welcome as PRs.
- Not translated yet: the rhythm models' internals (model families, settings and input names in `data/ml/`), which also key saved models and tests, and the low-level `HTTP … loading …` errors.

## Project layout

```
app/src/main/java/com/example/
├── BabyBuddyApplication.kt   # app entry; attaches repository, syncs widgets/notifications
├── MainActivity.kt           # navigation switch, theming, screen host
├── data/
│   ├── api/                  # Retrofit service + DTOs for the Baby Buddy REST API
│   ├── ml/                   # rhythm models: features, regressors, AutoML (pure Kotlin)
│   ├── model/                # UI models, time formatting
│   └── repository/           # BabyBuddyRepository (single source of truth), offline
│                             # store, write queue, shared timers, secret cipher
├── ui/
│   ├── MainViewModel.kt      # UI state, toasts, screen navigation
│   ├── components/           # shared composables (forms, dialogs, pickers, widget cards)
│   ├── screens/              # Today, Log, History, Trends, Sleep, Settings, ...
│   └── theme/                # colors (incl. night mode), type, theme
└── widget/                   # home-screen widgets, notifications, reminders
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a detailed architecture guide (state flow, sync/write queue, shared timers, server field mappings, navigation) and [docs/ICD.md](docs/ICD.md) for the interface control document (how the app talks to the Baby Buddy backend).
