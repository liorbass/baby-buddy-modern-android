# Baby Buddy Companion (Android)

An unofficial Android client for a self-hosted [Baby Buddy](https://github.com/babybuddy/babybuddy) server — track feeding, sleep, diapers, pumping, tummy time and more, with a live nursing timer that syncs between parents' phones in real time, home-screen widgets, lock-screen notifications and offline-first sync.

<p>
  <a href="https://github.com/liorbass/baby-buddy-modern-android/releases/latest"><img src="https://img.shields.io/github/v/release/liorbass/baby-buddy-modern-android?label=Download%20APK&logo=android&style=for-the-badge&color=9A442D" alt="Download the latest APK" height="40"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22dev.liorbass.babybuddy%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fliorbass%2Fbaby-buddy-modern-android%22%2C%22author%22%3A%22liorbass%22%2C%22name%22%3A%22Baby%20Buddy%20Companion%22%7D"><img src="docs/images/badge_obtainium.png" alt="Get it on Obtainium" height="40"></a>
</p>

> **Requires a Baby Buddy server.** This repo is *just the Android app*: it contains no backend and no database of its own. All of your data lives on (and is read from) a self-hosted [Baby Buddy](https://github.com/babybuddy/babybuddy) instance over its REST API. Without one you can still look around in the built-in **demo mode** (sample data, everything works except syncing). Go set up the excellent open-source [babybuddy/babybuddy](https://github.com/babybuddy/babybuddy) server first — huge props to that project; everything here builds on its API.

Built with Kotlin, Jetpack Compose and Material 3. Originally generated with Google AI Studio.

<table>
  <tr>
    <td align="center"><b>Today Dashboard (Day)</b></td>
    <td align="center"><b>Today Dashboard (Night)</b></td>
    <td align="center"><b>Quick Log</b></td>
  </tr>
  <tr>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-today-dashboard.png" alt="Today dashboard (Day)" width="250"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/06-today-dashboard-night.png" alt="Today dashboard (Night)" width="250"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-quick-log.png" alt="Log screen" width="250"></td>
  </tr>
  <tr>
    <td align="center"><b>24h Trends</b></td>
    <td align="center"><b>History Timeline</b></td>
    <td align="center"><b>Settings</b></td>
  </tr>
  <tr>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-trends.png" alt="Trends" width="250"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/04-history.png" alt="History" width="250"></td>
    <td><img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-settings.png" alt="Settings" width="250"></td>
  </tr>
</table>

<sub>Screenshots use the built-in demo data. More in [docs/FEATURES.md](docs/FEATURES.md).</sub>

## Features

- **Today dashboard**: last feed, sleep, diaper, bath, tummy time and milk stash at a glance, what's next, optional cards (tummy time goal, temperature, notes, growth) and a **While you were away** summary of what other caregivers logged.
- **Nursing timer**: one-tap Left/Right, per-side corrections, pause/resume, auto-end after 30 minutes idle. No save step: every tap goes through the sync queue.
- **Shared timers**: a feed or sleep started on one phone shows up live on the other parent's phone through the Baby Buddy timers API; either phone can switch sides or end it.
- **Sleep timer**, and a **Log screen** for breastfeeding, bottle, diaper, sleep, pumping, bath and growth.
- **Home-screen widgets, lock-screen controls, Quick Settings tiles and launcher shortcuts** for logging without opening the app.
- **Reminders**: feed due, diaper change due, awake for, asleep for, with an optional heads-up; they check the server first, so a feed logged on another phone cancels them.
- **Rhythm models** (optional): on-phone AutoML that learns your baby's rhythm and can time the reminders and "next" estimates.
- **Growth**: weight, length and head circumference against the [WHO Child Growth Standards](https://www.who.int/tools/child-growth-standards).
- **History & Trends**: editable week-by-week history and 24-hour rhythm charts.
- **Offline-first** write queue, **night mode**, 12/24-hour clock, per-phone hidden items, **demo mode** with 90 days of sample data.
- **Languages**: English, French, German, Dutch, Spanish and Hebrew; the phone's language by default, or pick one in Settings → Appearance. French was checked by a native speaker; the others are machine translations using the Baby Buddy server's own terms, so corrections are welcome (see [CONTRIBUTING.md](CONTRIBUTING.md#translations)).

Details and screenshots: **[docs/FEATURES.md](docs/FEATURES.md)** (widgets, lock screen, tiles and shortcuts, reminders, rhythm models).

## Getting started

1. **Have a [Baby Buddy](https://github.com/babybuddy/babybuddy) server** (Docker or bare metal). This app is only a client; the server stores everything. No server yet? Use **Try the demo** on the login screen.
2. **Install** the APK from the [latest release](https://github.com/liorbass/baby-buddy-modern-android/releases/latest), or with [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates (badge above, or add this repository's URL; config in [`docs/obtainium/`](docs/obtainium/dev.liorbass.babybuddy.json)). Android 7.0+.
3. **Log in** with your server URL (e.g. `https://baby.example.com`) and API token (Baby Buddy web UI → your user profile). Optional: a Cloudflare Access service token. For a self-signed certificate, turn on **Allow self-signed certificates**: the app shows the certificate's fingerprint once and then trusts only that certificate. Plain `http://` works on a home network, but sends the token unencrypted.


Building from source and contributing: **[CONTRIBUTING.md](CONTRIBUTING.md)**. How the app uses the server's API and which fields carry what: [docs/ICD.md](docs/ICD.md). Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy

- The app talks only to your own Baby Buddy server (and Cloudflare Access, if you configure it). No analytics, ads or tracking.
- Your API token and Cloudflare secret are encrypted on the phone with an Android Keystore key and excluded from backups, as are the offline queue and cache.

## Permissions

| Permission | Why |
|---|---|
| Internet | Talk to your Baby Buddy server |
| Notifications (Android 13+, asked when lock-screen timers or reminders are on) | Lock-screen feed/sleep timers and reminders |
| Run at startup | Set reminders again after the phone restarts (alarms don't survive a restart) |
| Run in the background (foreground service) | Only while a feed or sleep is being timed: keeps the lock-screen timer in step with the other parent's phone, so a feed ended there also ends here |

No location, contacts, camera, microphone or storage access.

## Medical disclaimer

Growth percentiles and charts are for information only. They are not medical advice and not a diagnosis; talk to your pediatrician or health visitor about your child's growth, feeding or sleep.

## Credits

- [Baby Buddy](https://github.com/babybuddy/babybuddy) — the server, data model and REST API this app is built on.
- [WHO Child Growth Standards](https://www.who.int/tools/child-growth-standards) — growth reference data (WHO Multicentre Growth Reference Study Group, 2006), from the WHO's published expanded tables; see `WhoGrowthStandards.kt`.

## Disclaimer

This is an independent, unofficial client. It is not affiliated with or endorsed by the Baby Buddy project. All credit for the Baby Buddy data model, REST API and the original web app goes to [babybuddy/babybuddy](https://github.com/babybuddy/babybuddy) and its contributors — this repo is only a mobile front end for it.
This repo was vibe coded for my own personal use, if you don't like something/want a feature, submit an issue, if you don't like the response, go and fork yourself.

## License

[Apache License 2.0](LICENSE)
