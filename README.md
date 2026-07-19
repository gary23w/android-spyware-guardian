# Guardian

A simple Android app by gary23w that watches your own phone for spyware and stalkerware behavior in real time.

It doesn't scan once and quit. It runs as a foreground service, takes a baseline of the phone's state, and alerts you the moment something changes that matches known spyware patterns or classic malware behavior.

## What it actually watches

Every 60 seconds, and immediately whenever a new app is installed:

- **New app installs** — logged the moment they land, along with whatever dangerous permissions they were granted
- **Accessibility service changes** — the #1 technique real-world phone spyware uses to read your screen and inject input
- **Notification listener changes** — apps that can read the text of every notification you get
- **Device admin changes**
- **Dangerous permission grants** across every installed app (mic, camera, SMS, contacts, location, overlay, call, install-other-apps)
- **Version downgrades** — an app suddenly reporting an older version than it had before, a known trick for reintroducing a patched vulnerability
- **Known spyware indicator matches** — installed package IDs, APK file names, and APK file hashes checked against a live-updated threat feed (see below)
- **Hidden apps with real permissions** — apps that hold dangerous permissions but have no icon in your app drawer, a common way spyware hides
- **Security setting tampering** — changes to Play Protect / APK verifier / ADB install confirmation settings, the kind of thing an attacker with brief physical access weakens to make a sideload stick
- **SMS and call interception receivers** — any non-system app (that isn't your default SMS or dialer app) registered to receive incoming SMS, outgoing calls, or phone state changes
- **Default SMS/dialer app changes** — malware sometimes tries to become your default SMS handler to get broader access with fewer prompts
- **SIM/carrier state changes** — operator, country, and SIM state, a rough proxy for SIM-swap or RCS provisioning hijack (see the RCS note below)
- **Root indicators** — best-effort, evadable, but worth knowing about since root undermines every other check on this list
- **Network usage anomalies** — a per-app sudden spike in background data usage over the last 24h (needs Usage Access granted, see below)

Anything new in any of these categories gets written to a log and, for the serious stuff, pops a notification.

## Settings Walkthrough

Open the app and tap **Settings Walkthrough** for a plain-language, one-by-one review of the security-relevant settings on your phone: screen lock, USB debugging, unknown sources, Play Protect, accessibility services, notification access, device admins, Guardian's own battery exemption, usage access, and root status. Each item shows its current state and a button that opens the right system settings screen to fix it, with a fallback if your OEM doesn't expose that exact screen.

## Where the spyware list comes from

Guardian pulls the same indicator feeds used by [MVT (Mobile Verification Toolkit)](https://github.com/mvt-project/mvt), the forensics tool built by Amnesty International's Security Lab. Specifically it reads the index at [`mvt-project/mvt-indicators`](https://github.com/mvt-project/mvt-indicators/blob/main/indicators.yaml), which points at STIX2 threat-intel feeds covering NSO Pegasus, Predator, Candiru, RCS Lab, QuaDream, Cellebrite forensic tooling, and a large generic stalkerware list.

This runs automatically the first time you install the app, and once every 24 hours after that, no action needed. Each run logs how many feeds it pulled and how many indicators it loaded.

**Limitation, stated plainly:** this only catches spyware that's already been documented publicly. It won't catch something brand new that nobody has published indicators for yet.

## On packet/network-level monitoring

Short version: Guardian does per-app data-usage anomaly detection (via `NetworkStatsManager`), not full packet capture. This was a deliberate call, not a shortcut.

Android's `VpnService` API has no concept of "just watch this one thing" — once an app establishes a VPN with a default route, it becomes solely responsible for handling 100% of the device's IP traffic, or the phone loses internet. Every real project that does full packet capture (PCAPdroid, NetGuard, RethinkDNS) does the actual packet relay in native code (C or Go), not pure Kotlin, because getting a NAT/TCP engine wrong doesn't fail safe — it silently breaks connectivity for every app on the device until the VPN is disabled. That's not a risk worth taking inside a tool whose whole job is to be trusted running quietly in the background. If you want real packet-level inspection, run [PCAPdroid](https://github.com/emanuele-f/PCAPdroid) alongside Guardian.

## A note on RCS and call/SMS interception

Guardian can and does detect the on-device signs of call/SMS interception: apps registered for SMS/call broadcasts that shouldn't be, and changes to your default SMS/dialer apps. What it cannot do is detect carrier-network-level RCS provisioning attacks (the kind of authentication weaknesses security researchers have published about GSMA's RCS Universal Profile) — those happen entirely on carrier infrastructure, before anything reaches a state your phone can see. The SIM/carrier-state check is a best-effort proxy for "something about your cellular connection changed unexpectedly," not a guarantee.

## What happens when something is flagged

Guardian never deletes anything on its own by default. When it finds a spyware-indicator match or a hidden app with dangerous permissions, it sends a notification with two buttons:

- **Keep** — marks it as reviewed, Guardian won't nag about it again
- **Remove** — opens Android's own uninstall prompt for that app

### Sentry Mode (off by default)

Turn this on in the app and Guardian stops asking first — it immediately opens the uninstall prompt for anything it flags, instead of waiting for you to tap a notification.

One important thing to be honest about: **Android does not let any regular app silently delete another app.** Even in Sentry Mode, the system's own uninstall confirmation screen still has to show up and you still have to tap through it. There is no version of this that silently removes apps in the background — that would require making Guardian a device owner (MDM-style control), which this app deliberately does not do, because that's a much bigger, riskier change to how your phone works. Sentry Mode just means Guardian doesn't wait for you to open a notification before starting that process.

## Permissions it asks for, and why

| Permission | Why |
|---|---|
| `INTERNET` | Fetch the daily spyware indicator feeds |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the monitor running continuously |
| `POST_NOTIFICATIONS` | Alert you when something is found |
| `RECEIVE_BOOT_COMPLETED` | Restart monitoring after a reboot |
| `REQUEST_DELETE_PACKAGES` | Open the uninstall prompt for flagged apps |
| `READ_PHONE_STATE` | Read SIM operator/country/state for the SIM-swap-adjacent check — read-only, Guardian never reads call content or numbers |
| `PACKAGE_USAGE_STATS` (special, granted manually) | Per-app network usage totals for anomaly detection |
| `QUERY_ALL_PACKAGES` | See every installed app, not just ones Guardian has interacted with — required to actually check permissions and indicators app-wide |

Guardian does not request accessibility service access, notification listener access, or camera/mic/SMS/location permissions for itself. It only reads *other* apps' permission grants through normal `PackageManager` APIs, which any app can do. `READ_PHONE_STATE` is the one sensitive permission Guardian holds for itself, and it's used for exactly one read-only check.

## Staying alive in the background

Foreground services can still get throttled by OEM battery managers (Samsung's especially). For reliable long-term monitoring, exempt Guardian from battery optimization — either through the Settings Walkthrough screen in the app, or manually:

Settings → Apps → Guardian → Battery → Unrestricted

**Honest limitation:** if the app is force-stopped by you or by the OS, nothing can auto-restart it until you open it again — that's an Android platform limit for apps without system-level privileges, not something Guardian can work around.

## Building it yourself

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17, Android SDK platform 34, build-tools 34.0.0.

## What this is not

Guardian is a personal, defensive tool for watching your own device. It's not a commercial-grade EDR product, it doesn't have a backend, it doesn't phone home anywhere except to fetch the public MVT indicator feeds, and it's not a replacement for a full forensic pass (tools like MVT itself, run from a computer, look a lot deeper than an on-device app safely can).
