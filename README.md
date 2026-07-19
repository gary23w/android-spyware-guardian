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
- **Known spyware indicator matches** — installed package IDs, APK file names, and APK file hashes checked against a live-updated threat feed (see below)
- **Hidden apps with real permissions** — apps that hold dangerous permissions but have no icon in your app drawer, a common way spyware hides

Anything new in any of these categories gets written to a log and, for the serious stuff, pops a notification.

## Where the spyware list comes from

Guardian pulls the same indicator feeds used by [MVT (Mobile Verification Toolkit)](https://github.com/mvt-project/mvt), the forensics tool built by Amnesty International's Security Lab. Specifically it reads the index at [`mvt-project/mvt-indicators`](https://github.com/mvt-project/mvt-indicators/blob/main/indicators.yaml), which points at STIX2 threat-intel feeds covering NSO Pegasus, Predator, Candiru, RCS Lab, QuaDream, Cellebrite forensic tooling, and a large generic stalkerware list.

This runs automatically the first time you install the app, and once every 24 hours after that, no action needed. Each run logs how many feeds it pulled and how many indicators it loaded.

**Limitation, stated plainly:** this only catches spyware that's already been documented publicly. It won't catch something brand new that nobody has published indicators for yet, and it doesn't inspect network traffic (no VPN capture in this version), so domain indicators in the feed are stored but not actively matched yet.

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
| `QUERY_ALL_PACKAGES` | See every installed app, not just ones Guardian has interacted with — required to actually check permissions and indicators app-wide |

Guardian does not request accessibility service access, notification listener access, or camera/mic/SMS/location permissions for itself. It only reads *other* apps' permission grants through normal `PackageManager` APIs, which any app can do.

## Staying alive in the background

Foreground services can still get throttled by OEM battery managers (Samsung's especially). For reliable long-term monitoring, exempt Guardian from battery optimization:

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
