# Nirobo Browser – working notes (read this first, skip re-exploring)

Android WebView browser, pure Kotlin, **no AndroidX / no external deps** (keep it that way unless a feature truly needs one).
Flat repo: GitHub Actions (`.github/workflows/build.yml`) copies every `*.kt` into `app/src/main/java/org/nirobo/browser/`, so a new .kt file needs no CI change. minSdk 28, target 34, Java 17.

## File map
- `MainActivity.kt` (~2200 lines): `Tab`, `SuggestAdapter`, `BrowserActivity` (open class; `PrivateActivity` and `SandboxActivity` subclasses at the bottom via `isPrivate` / `isSandbox`). UI is built in code (`buildUi()`), no XML layouts.
  - Tabs: `createTab`, `newTab`, `loadInto` (also routes `about:` pages), `switchTo`, `closeTab`, `closeAll`, tab switcher `showSwitcher/refreshSwitcher`, desktop-style tab strip `rebuildTabStrip`.
  - Menu: `showMenu()` (PopupWindow, helper `item(glyph, name, trailing){}`), page long-press `onLongPress`/`showPageMenu`/`showLinkMenu`.
  - Privacy: ad/tracker shield (`blocklist.txt`, `isBlocked`, `cleanTracking`), per-site switch, HTTPS-only (`toUrl` upgrades http→https), cookie policy, GPC/DNT headers.
  - Other: find-in-page, reader mode, desktop site, print, voice search, downloads (`enqueueDownload`), fullscreen video, file chooser, geolocation/permission prompts.
- `Store.kt`: all persistence in one SharedPreferences as JSON (settings, bookmarks, history ≤2000, downloads, per-site shield, session, closed tabs).
- `AboutPages.kt` (~2400 lines): internal `about:` pages as HTML strings (home/settings/history/bookmarks/downloads/version/news…). Talks to Kotlin through `Bridge` (JS interface, only attached on about: pages).
- `Downloader.kt`: OUR OWN download engine (no phone DownloadManager). `DlItem`, `object Downloader`, `DownloadService` (foreground, Pause/Cancel buttons), `BlobSaver` (blob: files), helpers `cleanFileName`, `parseDisposition`, `friendlyError`.
- `FileViewer.kt`: `FileOpener` (decides how a finished download opens: `kindOf`, `open`, `external`, `share`), `FileViewerActivity` (in-app image / video / audio / text-xml-json-csv / html viewer, buttons "Open with" + "Share"), `ZoomImageView` (pinch + drag + double-tap). PDF -> `PdfReaderActivity`, APK -> installer (asks "install unknown apps" first), other types -> `ACTION_VIEW` with clipData + grant, then chooser.
- `Updater.kt` (self-update), `NewsSaver.kt` (daily offline news), `NiroboWidgets.kt`, `PdfReaderActivity.kt`, `BootReceiver.kt`.

## Rules for editing
- Private/sandbox windows must never write history, session, closed tabs, or show suggestions.
- Do not add dependencies. Network for builds is only on GitHub Actions; cannot compile locally in the Claude sandbox, so review edits carefully (nullable types, `this@BrowserActivity` inside anonymous objects).
- Match existing style: `internal`/`private` visibility, `toast()`, `dp()`, `label()` helpers, glyph icons (no drawables).

## Done so far
- Session restore: open tabs saved in `onPause` (`saveSession`), restored lazily at start (`restoreSession`, `Tab.pending` loads on first view). Link intents open on top of restored tabs.
- Recently closed tabs (max 10) + menu item "Reopen closed tab". Cleared by "Clear browsing data".
- Address-bar suggestions from bookmarks + history (`AutoCompleteTextView`, `suggestionsFor`), off in private/sandbox.

- Long-press downloads: images (existing) + video/audio + covered images via `probeMedia()` (JS `elementFromPoint` + position scan of `<video>`), `showMediaMenu`, `downloadMedia`. Extra image items: Share image link, Google Lens. blob: videos go through `saveBlob`/`BlobSaver`; m3u8/mpd streams still show a "can't be saved" toast (roadmap 15).
- Pull-down-to-refresh: touch listener in `createTab` (`pullStartY`, 140dp drag at scrollY==0).
- "Add to Home screen" (`addToHomeScreen`, pinned shortcut) in the menu.
- Widgets are now one row high (`layout__widget_card.xml` compact). New: `SearchWidget` (search box + mic, `layout__widget_searchbox.xml`), `QuickBarWidget` (6 buttons: search, voice, new tab, private tab, downloads, bookmarks; `layout__widget_bar.xml`, `xml__widget_bar.xml`, registered in manifest). Widget tap = `widgetTap(ctx, action)`; actions handled in `handleWidget()`: search, voice, charge, speed, news, newtab, private, downloads, bookmarks, history.

- **Own downloader (Nov 22, replaces Chat B item 1; `Downloader.kt.txt` and the download part of `HANDOFF_SNIPPETS.md` are OBSOLETE, may be deleted).** Works like Chrome: Referer + cookies + UA on the request; file name from Content-Disposition (`filename*` first), then URL, then MIME; names made safe; bytes go to `<app files>/dl/<id>.part`, then are moved to Downloads (MediaStore on API 29+, "name (1).ext" for duplicates); resume = `Range` + `If-Range` (ETag, else Last-Modified) and only when the server gave a validator + `Accept-Ranges`; response checks (206 must start at the asked byte, 200 = start over, 416 = done or start over); retries 4x with growing wait, then "Waiting for the network" and auto-resume through `registerDefaultNetworkCallback` (also after app restart); 3 parallel downloads; real error text for 401/403/404/5xx/no internet/SSL/disk full; https-only incl. redirects. State list persisted in Store key `downloads` (old DownloadManager entries migrate as "done"). Private-window items are never persisted and only shown in private windows.
  - Notifications: ongoing one with Pause/Cancel (Pause all when many), "Paused - Resume" one, "Download failed" with the reason, "Download complete" (tap opens the file via widget action `open:<id>`).
  - `blob:` links: `BlobSaver` (JS interface `NiroboBlob`, one-time random token, XHR + FileReader, 256 KB base64 chunks, 64 MB cap). `data:` links decoded in Kotlin. "Download page" (`saveWebArchive` -> .mht) in the page menu. APK allowed after a "can harm your phone" Keep/Discard dialog; .exe/.bat... still blocked while `store.dangerFiles`.
  - Downloads page (`about:downloads`, `downloads()` in AboutPages.kt): usage line, search, type chips (All/Images/Video/Audio/Documents/Other), "In progress" section, rest grouped by day, per-item Open/Share/Copy link/Delete or Pause/Resume/Cancel/Retry, Pause all/Resume all/Clear list, collapsible "Download from a link". Bridge: `downloadsLive, openDownload, shareDownload, copyDownloadLink, pauseDownload, retryDownload(=resume), deleteDownload, pauseAllDownloads, resumeAllDownloads, clearDownloads, downloadLink`.
  - Not possible / not done: POST downloads (WebView gives no body), HLS/DASH streams, parallel multi-connection download, "ask where to save", rename.

- **Opening downloads (Nov 22):** `MainActivity.openDownload` -> `FileOpener.open` (checks the file is readable first, real error text on failure). Downloads page: tap the icon/name of a finished file opens it. Notification tap (`open:<id>`) uses the same path.

## Chrome features still missing (priority order)
(1 pull-to-refresh: DONE)
15. (blob: DONE) Still missing: HLS/DASH (.m3u8/.mpd) videos -> sniff real media URLs through `shouldInterceptRequest` and show a "media found on this page" list.
16. Widgets: a widget picker note in Settings, a bookmarks-list widget (RemoteViews collection), battery widget with progress bar, theme (light/dark) follow.
2. Settings toggle for `store.restoreTabs` ("Continue where you left off") in `about:settings` (AboutPages.kt + Bridge method).
3. Omnibox: also suggest search queries (history of searches), show title under URL, "Search for …" first row.
4. Tab groups / pinned tabs; tab thumbnails in the switcher.
5. Site settings per origin (camera, mic, location, notifications, JS) with a manager page; currently `onPermissionRequest` / geolocation are handled globally.
6. Password manager / autofill service integration (`autofillState()` exists, Chrome-style save/fill prompt does not).
7. Add to Home screen (ShortcutManager pinned shortcut), QR code scan + share-as-QR.
8. Save page offline (MHTML via `WebView.saveWebArchive`) and an offline-pages list.
9. (DONE Nov 22) Download manager. Left: multi-select delete, rename, open-in-folder, per-site download permission.
10. Swipe left/right gestures for back/forward and swipe on tab bar to switch tabs.
11. Chrome-like `about:` pages: `about:flags`-style experiments, `about:inspect` (WebView remote debugging toggle).
12. Safe Browsing (`WebSettings.setSafeBrowsingEnabled`, `WebViewClient.onSafeBrowsingHit`), certificate error page with "Proceed" option.
13. Sync/export: bookmarks + history import/export as HTML/JSON.
14. Dark mode for web content (`WebSettings.setAlgorithmicDarkeningAllowed` is partly used; verify on API 33+).

## Nov 22 (evening) - ads update
- DONE: siteShield bug; `gamblingHost` regex (3rd-party gambling/betting hosts blocked); `ytAdUrl` + `isYoutube()` (YouTube ad calls blocked in `shouldInterceptRequest`); `YT_JS` (skip ads, hide ad cards + gambling / "unverified advertiser" cards) injected from `injectShieldScripts` on youtube.com only. Gated by `store.adHide` + `store.adPatterns` + per-site shield.
- UNTESTED on a phone (cannot compile here). If YouTube changes class names, edit selectors inside `YT_JS`. Ads inside the YouTube video stream itself cannot be blocked by URL, only skipped by JS.
- Repo can be cloned in the sandbox with plain `git clone https://github.com/bdelectrickbill21/nirobo-browser.git` (public, works); pushing is not possible, user uploads files by phone.
- NEXT: audit items 2 (narrow adPattern), 3 (https fallback), 6 (bigger host list), 7 (AD_HIDE_JS observer).

## How to continue cheaply
Open this file, pick the next item, edit only the functions named above (use grep for the function name, don't read whole files), update "Done so far" when finished.

---
# PENDING WORK from "Chat B" (designed + coded, NOT merged into the repo)
Written against an OLDER MainActivity/AboutPages. **Merge by hook, never overwrite whole files.**
Reference code is in `HANDOFF_SNIPPETS.md`, `Downloader.kt.txt`, `LinkCheck.kt.txt` (the `.txt` keeps CI from compiling them; rename to `.kt` when merging).
Can't compile in the sandbox: all page JS was syntax-checked with node; QR was verified by decoding with OpenCV (6/6).
Kotlin raw-string pages: never write a bare `$` in page JS (use `${'$'}`).

## What the user asked for (Nov 21) and the plan, in order
1. ~~Own downloader~~ **DONE (see Done so far).** Still to do from this item: nothing.
2. **Pink site / no animation** – cause: `s.forceDark = if (isDark()) ON else OFF` recolours websites. Replace by `store.darkPages` (default false) + `applyDark()`. Autoplay: `mediaPlaybackRequiresUserGesture = !store.autoplay` (default allow), `MUTE_JS` mutes videos that start without a tap in 3 s (audio stays allowed), toggles on `about:protect` ("Media and display": autoplay, mute videos, dark pages). Store: `autoplay`, `muteVideo`, `darkPages`, `checkShort`.
3. **Link safety checker** – `LinkCheck.kt.txt` (follows redirects by hand, no cookies, blocks private-IP hops, scans first 150 KB of HTML: gambling, 18+, scam words, meta/JS redirects, auto-download, hidden iframes, obfuscation, fake brand mirrors, download of apk/exe). Page `about:check` (snippet), Bridge: `startCheck(url)` -> id, `checkResult(id)` ("" while running), `checkTarget()`, `openSandbox(url)`, `copyText(text)`. MainActivity: fields `checkTarget`, `qrTarget`; `sandboxUrl()`; menu items "Check link safety" (link menu) and "Check this page's safety" (page menu); short-link prompt in `shouldOverrideUrlLoading` (hosts: bit.ly, tinyurl.com, t.co, goo.gl ... option `store.checkShort`). Replace the JS-only "Test a link" block on the Safety page by a row to `about:check`.
4. **News**: (a) country picker – "BD" = nirobo.pages.dev feed (full text + pictures), other countries = Google News RSS headlines (`news.google.com/rss?hl=..&gl=CC&ceid=CC:lang`); Store `newsCountry`, `newsSavedCountry`; NewsSaver functions `fetchNirobo/fetchRss/countriesJson/status`; Bridge `newsCountries/newsCountry/setNewsCountry/newsStatus/redownloadNews`. (b) listen mode – TextToSpeech in MainActivity (`speak/speakNow/stopSpeaking`, Bangla voice bn-BD when text has Bengali letters), Bridge `speak/speakMany/stopSpeak`, buttons on the news page.
5. **Charge page redesign** (`about:phone`, user said the current one looks poor): new page snippet copies the Charge Meter app (gauge with ticks, 3-colour arc, big mA, light-use chip, lasts-about, lifetime max/min + Reset, capacity card, tiles). Needs `phoneInfo()` extras: `currentMa` (signed, normalised), `nowMah`, `fullMah` (= now / level%), `designMah` (reflection on `com.android.internal.os.PowerProfile.getBatteryCapacity`), `healthPct`, `cycles` (EXTRA_CYCLE_COUNT or /sys/class/power_supply/battery/cycle_count), `curMax/curMin` (Store ints) + Bridge `resetLife()`.
6. **Page long-press menu like Chrome**: ("Download page" DONE) add "Download video" (JS `VIDEO_JS` finds direct https <video>/<source>, no blob/m3u8), "Create QR code for this page" (`about:qr`, JS QR maker in snippets, Bridge `qrText()`), keep Print/Share/Reader/Translate. "Send to your device" is NOT possible (needs a sign-in account).

## Already merged earlier (do not redo)
Protection layers page `about:protect` (5 ad layers + 7 protections), Safety check page, Email & passwords page `about:mail`, voice search mic, Windows-style desktop layout (tab strip + right rail), five widgets, offline news with pictures, adaptive icon, custom search engines with favicon, calculator at `about:calc` (hidden, not in menu).

## User preferences for the helper
Simple words, one clear step at a time, **one big upload instead of many small ones**, low token use (read CLAUDE.md only, grep function names, don't re-read old chats), never ask the user to paste secrets. User works only from a phone and uploads files through the GitHub website.

---
# NEW PLAN (Nov 22, user request) - "All-in-one + safe mail" - do in this order
User: has personal + work SIM/accounts; wants one app instead of many (Facebook/Lite, Messenger, WhatsApp, Telegram, Discord, imo, email, YouTube, TikTok, Likee, Odysee, BitChute; "chanfchat" unknown - ask user). Wants mail link safety (phishing, adsterra/monetag, 18+, gambling). Goal: small, no tracking, slows bad actors.
1. **Spaces (Personal / Work)**: separate cookies/logins. No AndroidX allowed -> use separate Android processes like PrivateActivity: `PersonalActivity`/`WorkActivity` subclasses with `android:process=":work"` in manifest + `WebView.setDataDirectorySuffix("work")` BEFORE the first WebView (in `onCreate` / Application). Store per-space: tabs, session, shield. Menu: "Switch space". Private/sandbox rules still apply.
2. **Apps hub `about:apps`**: tiles that open web versions: Facebook (m / mbasic = Lite), Messenger, WhatsApp Web (desktop UA), Telegram Web (web.telegram.org/k), Discord, imo (imo.im), YouTube, TikTok, Likee, Odysee, BitChute, Gmail/Outlook/Yahoo. Each tile -> chosen space; "Add to Home screen" reuse `addToHomeScreen`. Limits to tell user: no push notifications in WebView, camera/mic calls need roadmap item 5 (per-site permission prompt) FIRST.
3. **Mail link inspector** (on mail sites only: mail.google.com, outlook.live.com, mail.yahoo.com): JS shows real href under each link; red warning when link text domain != href domain, lookalike brand names, shorteners, IP hosts, punycode, adsterra/monetag/propellerads/popads hosts, gambling/18+ words; use `LinkCheck.kt.txt` for "Check link". Block remote tracking pixels (1x1 images from tracker hosts). Cannot truly verify the sender from a WebView - say "look at the real address" not "safe".
4. Add to `blocklist.txt`: adsterra, monetag, propellerads, popads, exoclick, juicyads, trafficjunky, clickadu, hilltopads + missing trackers from audit item 6.
5. Then audit items 3 (https fallback), 4/5 (permissions per site), 2, 7.
Principles: zero telemetry, no accounts, no extensions (extensions are how emails get harvested), weekly list updates, app stays small.

## Decisions (Nov 22, late)
- **MENU RULE: add at most ONE new item to the 3-line menu: "Apps & Spaces" (opens `about:apps`).** Space switching, app tiles, mail settings live inside that page. Do not add other menu items.
- Overcoming limits: (a) calls -> per-site camera/mic/location prompt (roadmap 5) FIRST, remember choice per origin in Store; (b) notifications -> no Web Push in WebView; instead keep the tab alive with a foreground service and turn title counters like "(3) WhatsApp" / unread badges (`onReceivedTitle`) into local notifications; works only while app is running, say so; (c) sender check -> on mail sites read From name + address from the page, warn if brand in name != address domain or lookalike (paypa1, g00gle, punycode); link inspector as planned.
- Order now: 1 permission prompt, 2 Spaces, 3 `about:apps` + 1 menu item, 4 title->notification, 5 mail inspector, 6 blocklist additions.

## Nov 23: step 1 DONE (per-site permission prompt) - UNBUILT, check build log first
- `Store.sitePerm/setSitePerm/clearSitePerms` (key `sitePerms`, JSON host -> {cam,mic,loc: allow|deny}).
- MainActivity: `askSite` dialog (Allow / Only this time / Block; private = no remembering, sandbox = always deny, https only), `handleSitePermission`, `grantSite`, `handleSiteLocation`, `grantLocation`, `onRequestPermissionsResult` (codes 710 cam/mic, 711 location). Manifest: CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, ACCESS_FINE/COARSE_LOCATION + optional uses-feature.
- Still missing: a manager page to review/reset saved site permissions (call `store.clearSitePerms()` from "Clear browsing data" and add a list on about:settings later); notifications permission per site.
- NEXT: step 2 Spaces (Personal/Work).

## Nov 23: step 2 DONE (Work space) - UNBUILT
- `Store(ctx, space)`: space "work" -> own file `nirobo_store_work`, seeded once with settings copied from the main file (not bookmarks/history/session/downloads/perms/sites). `Downloader.init(ctx, space)` same.
- `open val space` in BrowserActivity; `WorkActivity` (process `:work`, `setDataDirectorySuffix("work")`, bar colour #0B3A5B, manifest entry). `openSpace(work, url)`. ONE menu item: "Switch to Work space" / "Switch to Personal space" (this slot becomes "Apps & Spaces" in step 3 - do not add another).
- Known limits: DownloadService notification runs in the main process, so Work downloads work but may not show the live notification; Work space does not run the daily news saver; link intents always open in Personal.
- NEXT: step 3 `about:apps` (tiles open in Personal or Work; replace the menu item label with "Apps & Spaces").

## Nov 23: MEDIA MODE built (user's idea; replaces the "Switch to Work space" menu item) - UNBUILT, UNTESTED
- New file `MediaActivity.kt` (own screen, main process = shares logins with the browser). ONE menu item "Media mode" in `showMenu()`.
- Top: site chips; bottom: section chips (Home/Shorts/Subscriptions... + Upload + reload + open in Browser). One lazy WebView per site, own light shield (blocklist.txt hosts + `BrowserActivity.AD_HIDE_JS` / `YT_JS`, now `internal`). Fullscreen video supported. No app-link opening.
- "+ Post" composer: pick files (ACTION_OPEN_DOCUMENT), caption, tick sites -> Start. For each site: opens upload page (desktop UA when `uploadDesktop`), `onShowFileChooser` answers with the file (if `accept` matches), `FILL_JS` types title/caption (waits <= 2 min). Banner has Next / Stop. NEVER presses Publish (user does).
- Also receives Android Share (SEND / SEND_MULTIPLE) -> composer opens with files + text.
- ALL site addresses live in `MediaPlatforms.all` at top of MediaActivity.kt (best-effort, NOT verified on a phone): fix there if a page does not open. TikTok/YouTube Studio/BitChute/Odysee upload pages are desktop-only layouts and may be awkward on a phone.
- Limits to tell user: no single merged feed (each site needs its own API key/account); no push notifications; sign-in popups (Google login) are blocked (`javaScriptCanOpenWindowsAutomatically=false`) - use e-mail login or sign in via the Browser first (cookies shared); X/TikTok may detect WebView.
- Work space code (`WorkActivity`, `Store(ctx, "work")`, `openSpace`) is kept but has NO menu item now. Re-add later inside Media mode / settings if wanted.
- NEXT ideas: YouTube channel Community tab shortcut, scheduled posting reminder, per-site caption limits (X 280), shorten-link-free caption check, Work-space link from Media mode header.

## Nov 23 (later): MEDIA MODE REMOVED from the browser - it will be built as a SEPARATE APP
- Removed: menu item, manifest entry, `internal` on AD_HIDE_JS/YT_JS (back to private). Browser has NO mode switching and no Media mode.
- Kept for the new app: `MediaActivity.kt` + `MEDIA_MANIFEST.xml` - these are for the NEW repo ONLY. DELETE both from the browser repo (CI would compile them there; they are standalone, own copies of AD_HIDE_JS/YT_JS, no MainActivity needed, still need assets/blocklist.txt). Design inside: `MediaPlatforms.all` site list, chips UI, lazy WebView per site, composer (pick file + caption + tick sites), upload run (file chooser auto-answer + `FILL_JS` caption typing, user presses Publish), Android Share receiver, light ad shield from blocklist.txt. Copy ideas, not the file, because the new app needs its own package/manifest/build.
- Work space (`WorkActivity`, `Store(ctx,"work")`, `openSpace`) still in code, no menu item. Safe to delete later if unused.
- Browser roadmap continues with: build check of steps 1-2 (permission prompt, Work space), then audit items 3 (https fallback), 2, 6, 7, mail link inspector, blocklist additions.
- New app = ask user for: app name, package id, platforms, whether repo is separate (suggest new repo `nirobo-media` with the same GitHub Actions pattern).

## Nov 23: NIROBO PLAYER look (old DVD-player UI, ALWAYS ON - there is NO theme switch for it) - UNBUILT
User wanted the whole app to feel like old DVD player software (WinDVD/PowerDVD era), not a colour theme and not a mode switch.
Research (thin): PowerDVD = floating translucent control panel, swappable skins, dock of controls along the bottom; classic DVD menus = short list Play / Set-up / Scenes in a themed looping scene, gold/blue highlight selector. Rest from common memory of those players (black/silver brushed metal, glossy round keys, glowing LCD readout, blue menu screens).
- MainActivity: `dvd` always true. Dark brushed-metal top + bottom bars (`glossBar()`, tinted violet in private, red in sandbox). Address bar = LCD panel (`lcdPanel`, monospace, cyan `lcd` text; violet private, red sandbox). Bottom bar = transport keys (`silverKey`): back ◀◀, forward ▶▶, new tab ▲ (eject), home ■ (stop), tab counter as LCD "01" (`updateTabCount` formats %02d), MENU key. Cold start "LOADING DISC..." spinning-disc splash (`showDiscSplash`, 1.7 s, tap to skip, normal window only).
- AboutPages: `dvd` default true; `dvdCss()` always applied; chrome-text titles, glossy cards, silver pill buttons, gold chapter headings, gold selector arrow on pressed rows, scanlines + "PLAY" corner mark, bouncing NIROBO DVD logo on about:blank. Pages are built with dark=true always. Settings theme select is back to Follow phone / Light / Dark (that only affects websites + popups now).
- Not skinned yet: menu popup (`showMenu`), tab switcher, find bar, dialogs, suggestions dropdown, real websites. NEXT: skin those; optional click sounds are NOT planned (no audio assets); optional setting to turn the splash off.
- Removed from the browser: theme value 3 option. If a phone saved theme=3 it just behaves like Follow phone.

## Nov 23 (evening): menu/switcher/find skin + HTTP fallback - UNBUILT
- Player skin now also on: menu popup (`showMenu`: `metalPanel`, "MAIN MENU" LCD title, round silver icon keys, gold `selectorBg` press glow), tab switcher = "SCENE SELECTION" (`chapterBg`, LCD chapter numbers 01.., gold ▶ on current tab), find bar (LCD input, metal bar). Still plain: AlertDialogs, suggestion dropdown, long-press link/page/media menus.
- AUDIT ITEM 3 DONE (https fallback): https is still tried first (`toUrl`, `shouldOverrideUrlLoading`). Main-frame https error codes {-1,-6,-7,-8,-11} while `hasInternet()` -> error page offers "Open once on http" / "Always open this site on http" (links `about:openhttp?always=0|1&u=<enc http url>`, handled in `shouldOverrideUrlLoading`). `httpAllowed(host)`: private/LAN hosts always (also typed `192.168.1.1` or `router.local` now gets http), session set `httpSession`, saved set `Store.httpAllowed/addHttpAllowed/clearHttpAllowed` (key `httpHosts`; private/sandbox never save). "Clear browsing data" also clears http sites + site permissions. Site-info dialog says "NOT secure (http)". Mixed-content block unchanged.
- Not done: a page to list/remove saved http sites; lock icon colour for http pages.
