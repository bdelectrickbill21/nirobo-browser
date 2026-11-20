# Nirobo Browser 1.1 — build guide

This is the upgraded Nirobo Browser: a real Android app (Kotlin + Android System
WebView), not a website in a frame. It builds to an APK with GitHub Actions, so
you do not need Android Studio on your computer.

---

## 1. What is in the zip

All files sit flat in one folder, exactly like your current repo. The build
workflow arranges them into the folder tree Android needs.

| File | What it is |
|---|---|
| `MainActivity.kt` | The browser: tabs, address bar, menu, find-in-page, reader mode, shield, dark mode |
| `AboutPages.kt` | Every `about:` page, plus the JavaScript bridge they use |
| `Store.kt` | Settings, bookmarks, history, downloads, per-site shield switches |
| `Updater.kt` | Checks the GitHub release for a newer build, downloads it, checks it, and installs it |
| `NewsSaver.kt` | Downloads NewsHub once a day at UTC midnight and keeps the copy |
| `BootReceiver.kt` | Puts the daily alarm back after a phone restart |
| `AndroidManifest.xml` | Permissions and the two activities |
| `app-build.gradle.kts` | App id, version, SDK levels |
| `build.gradle.kts` | Plugin versions |
| `settings.gradle.kts` | Project name |
| `gradle.properties` | Gradle memory settings |
| `blocklist.txt` | The ad and tracker list that ships inside the app |
| `ic_launcher.png` | The app icon |
| `.github/workflows/build.yml` | Builds the APK on GitHub and publishes it as a Release |

**Upload all of them to the root of your repo, replacing the old ones.** The
workflow file must stay at `.github/workflows/build.yml`.

---

## 2. Build the APK

1. Push the files to your repo's `main` branch.
2. Open the repo on GitHub → **Actions** tab.
3. Click **Build Android app** on the left → **Run workflow** → **Run workflow**.
4. Wait about 5–8 minutes. A green tick means it worked.
5. Open the **Releases** page of your repo (right-hand sidebar, or
   `https://github.com/<you>/<repo>/releases`).
6. Under **Nirobo Browser (latest build)**, download **`nirobo-browser.apk`**.

The workflow also runs automatically on every push to `main`.

---

## 3. Install it on your phone

1. Copy `nirobo-browser.apk` to your phone (download it directly on the phone,
   or send it to yourself).
2. Tap the file. Android will say the app is from an unknown source — tap
   **Settings** in that message and allow **Install unknown apps** for your
   browser or file manager.
3. Tap **Install** → **Open**.

The app opens straight into Nirobo, full screen, with no address bar from
another browser.

---

## 4. The internal pages

Type any of these in the address bar. They work with no internet at all.

| Page | What it does |
|---|---|
| `about:blank` | A truly blank page |
| `about:internet` | Download and upload speed test, latency and jitter |
| `about:web` | SEO and site-health check: title, description, H1, canonical, robots.txt, sitemap, Open Graph, viewport, alt text, security headers, and a score out of 100 |
| `about:phone` | Battery level, charging state, temperature, voltage, health, current, watts, time to full, plus model, Android version, CPU, screen, RAM and storage |
| `about:bookmarks` | Saved pages |
| `about:history` | Pages you visited |
| `about:downloads` | Files you downloaded |
| `about:shield` | Ad-shield switches, including per site |
| `about:news` | The saved NewsHub copy |
| `about:settings` | Theme, reader size, shield, cookie blocking, data |
| `about:version` | Version and a list of every internal page |

---

## 5. The other new features

- **NewsHub saved copy** — the app downloads `https://nirobo.pages.dev/data/news.json`
  once a day at **UTC midnight** and keeps the newest 120 stories on the phone.
  When a page fails to load because there is no internet, the error page offers
  a **Read the saved news** button. The alarm is restored after a phone restart.
- **Bookmarks and history** — from the ⋮ menu, or the `about:` pages.
- **Find in page** — ⋮ menu → Find in page. ▲ ▼ to step through matches.
- **Reader mode** — ⋮ menu → Reader mode. Strips navigation and ads, keeps the
  article, and remembers your text size.
- **Ad shield** — tap the 🛡 button in the address bar to switch blocking on or
  off for the site you are on, or to toggle cookie-pop-up blocking. The master
  switch is in `about:shield`.
- **Download list** — every download is recorded and listed in `about:downloads`.
- **Dark mode** — Settings → Theme: Follow phone / Light / Dark. It also darkens
  ordinary web pages on Android 10 and newer.

---

## 6. If the build fails

Open the failed run in **Actions**, click the red step, and read the last 30
lines. The most common causes:

- A file was uploaded to a subfolder instead of the repo root.
- `.github/workflows/build.yml` was not uploaded, so there is no workflow to run.
- The repo's Actions are disabled: **Settings → Actions → General → Allow all actions**.

Send me the error text and I will fix it.

---

## 7. What was and was not checked

**Checked here:** every Kotlin file is brace- and paren-balanced; all 19
JavaScript bridge methods match the names the pages call; no page still uses the
old callback style; the NewsHub feed URL and its JSON shape were confirmed
against the live site (660 items, keys `t`, `u`, `d`, `s`, `i`).

**Not checked here:** the app was not compiled and not run on a phone — this
environment has no Android SDK and no device. The first real compile happens in
your GitHub Actions run.

**The Charge Meter APK was never opened, decompiled or modified.** `about:phone`
is written from scratch in Kotlin using Android's own `BatteryManager` and
`Build` APIs.

---

## 8. New in 1.1

**Buttons like Chrome, Firefox and Brave**
- Top bar: a lock / info button on the left (tap for site info), reload / stop on the right, and the shield with its counter (Brave style).
- Bottom bar: back, forward, **new tab**, home, tabs, menu.
- The menu opens with an icon row (forward, bookmark, downloads, info, reload) and lists Bookmarks, History and Downloads like Chrome. The tools (`about:internet`, `about:web`, `about:phone`, `about:update`...) stay hidden: type them in the address bar.

**Sandbox** — press and hold a link and choose **Open in sandbox**, or type `sandbox:example.com`, or type `about:sandbox`. It is a separate red window: scripts off, no cookies, no stored data, a page can load files only from its own site, no downloads, no pop-ups, no uploads, no other apps, screenshots blocked, erased when closed. Menu → "Sandbox: turn scripts ON" if a page needs them.

**Block mirror** — Settings → *Block screen mirroring & screenshots*. Casting and recording show black. Private and sandbox windows always have it on.

**Aliases** — `about:charge` and `about:battery` open `about:phone`.

---

## 9. Automatic updates

How it works: every build on GitHub puts `nirobo-browser.apk` and `version.json` on the **latest** release. When the app opens it reads `version.json`. If the number is higher it asks **Update now?**, downloads the file, checks its fingerprint, and hands it to Android's installer. Your bookmarks and settings stay.

You must do three things, once:
1. **Make the repo public** (Settings, scroll to the bottom, Change visibility). The app cannot download from a private repo. The app has no secrets in it.
2. **Add the two signing secrets** (see `SIGNING_SETUP.txt`). Android only accepts an update signed with the same key as the installed app.
3. **Uninstall the old Nirobo Browser once**, then install the new file. Its old key is different. After this, you never reinstall by hand again.

Menu → **Check for updates**, or type `about:update`, to check by hand. The first update asks Android's "allow installs from this app" once. On newer Android, later updates can install with no question.

What was not checked: this was not compiled or run here. The first compile happens in your GitHub run. If it fails, send me the red error lines.
