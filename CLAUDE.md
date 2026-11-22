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
- `Updater.kt` (self-update), `NewsSaver.kt` (daily offline news), `NiroboWidgets.kt`, `PdfReaderActivity.kt`, `BootReceiver.kt`.

## Rules for editing
- Private/sandbox windows must never write history, session, closed tabs, or show suggestions.
- Do not add dependencies. Network for builds is only on GitHub Actions; cannot compile locally in the Claude sandbox, so review edits carefully (nullable types, `this@BrowserActivity` inside anonymous objects).
- Match existing style: `internal`/`private` visibility, `toast()`, `dp()`, `label()` helpers, glyph icons (no drawables).

## Done so far
- Session restore: open tabs saved in `onPause` (`saveSession`), restored lazily at start (`restoreSession`, `Tab.pending` loads on first view). Link intents open on top of restored tabs.
- Recently closed tabs (max 10) + menu item "Reopen closed tab". Cleared by "Clear browsing data".
- Address-bar suggestions from bookmarks + history (`AutoCompleteTextView`, `suggestionsFor`), off in private/sandbox.

## Chrome features still missing (priority order)
1. Pull-to-refresh (custom touch handling on the WebView, no AndroidX SwipeRefreshLayout).
2. Settings toggle for `store.restoreTabs` ("Continue where you left off") in `about:settings` (AboutPages.kt + Bridge method).
3. Omnibox: also suggest search queries (history of searches), show title under URL, "Search for …" first row.
4. Tab groups / pinned tabs; tab thumbnails in the switcher.
5. Site settings per origin (camera, mic, location, notifications, JS) with a manager page; currently `onPermissionRequest` / geolocation are handled globally.
6. Password manager / autofill service integration (`autofillState()` exists, Chrome-style save/fill prompt does not).
7. Add to Home screen (ShortcutManager pinned shortcut), QR code scan + share-as-QR.
8. Save page offline (MHTML via `WebView.saveWebArchive`) and an offline-pages list.
9. Download manager upgrades: pause/resume, progress UI, open folder.
10. Swipe left/right gestures for back/forward and swipe on tab bar to switch tabs.
11. Chrome-like `about:` pages: `about:flags`-style experiments, `about:inspect` (WebView remote debugging toggle).
12. Safe Browsing (`WebSettings.setSafeBrowsingEnabled`, `WebViewClient.onSafeBrowsingHit`), certificate error page with "Proceed" option.
13. Sync/export: bookmarks + history import/export as HTML/JSON.
14. Dark mode for web content (`WebSettings.setAlgorithmicDarkeningAllowed` is partly used; verify on API 33+).

## How to continue cheaply
Open this file, pick the next item, edit only the functions named above (use grep for the function name, don't read whole files), update "Done so far" when finished.
