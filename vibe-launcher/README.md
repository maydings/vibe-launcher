# Vibe Launcher

A free and open-source (MIT) home screen for Android TV, with no ads, ever. Plain
Java, under 1 MB, no background services (the app store build has one: its Home button); built for TVs with 1 to 2 GB of RAM. Android 8.0 (API 26) and newer, targets API 34 so Play Protect accepts it
on Android 11+ devices such as the NVIDIA Shield.

![Vibe Launcher home with a backdrop behind the dock](docs/home.jpg)

For what it does and how to install the ready-built APK, see the [main README](../README.md). This
page covers configuring, building and how the backdrops work.

## Configure

[`app/src/main/res/values/config.xml`](app/src/main/res/values/config.xml):

| Setting | What it does |
|---|---|
| `dock_apps` | Package names for the dock, in order. Missing apps are skipped. Can also be changed on the TV with long-press → Move. |
| `hidden_apps` | Packages never shown as tiles (e.g. the Live TV app, which the Inputs menu already offers). |
| `live_tv_package` | The TV's tuner app, offered at the end of the Inputs menu. Empty to omit. |
| `columns` | Default tiles per row (the dock has this many slots) when the screen's size isn't known, as on a box over HDMI. Otherwise the default comes from the size: 4 under 51", 5 up to 74", 6 from 74". On the TV, Vibe Launcher settings offers 4, 5 or 6, which overrides both. |

## Build

Needs JDK 17 and the Android SDK (platform 34, build-tools 34.0.0). On macOS:

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0"
```

Build, install on the TV and make it the home screen in one go. It uses adb, or the Python ADB
client in [`tools/`](tools/) where macOS blocks adb on the local network:

```bash
./build-and-install.sh 192.168.1.50
```

Or by hand: `./gradlew assembleAdbDebug`, then `adb install -r -g app/build/outputs/apk/adb/debug/app-adb-debug.apk`.

**Two builds** come from the same code (`app/build.gradle`):

| | `adb` (this README, the GitHub releases) | `store` (for an app store) |
|---|---|---|
| Installed with | `build-and-install.sh` or a GitHub release APK, over adb | the store, no computer |
| Stock launcher | turned off over adb (with Google TV's setup wizard) | stays on |
| Home button | comes here by itself: Vibe Launcher is the only Home | the user turns on *Vibe Launcher Home button* in Settings → Accessibility; the service keeps Home from the stock launcher: a press opens Vibe Launcher, a hold the TV's Settings; a stock home that comes up anyway (Back out of an app) gets covered |
| Accessibility service | none | `HomeKeyService`: hears only the stock launchers' screens opening, reads nothing on screen, keeps Home and passes every other key on; stops looking at keys while a game controller is connected (Home then reaches the stock launcher, which gets covered) |
| First start, and gear menu → *Make Home* | the TV's default-Home dialog, where it has one | the TV's default-Home dialog only where it can work (no stock launcher outranks it); otherwise, or if that doesn't take, a note on what the Home button service sees and the steps, then Accessibility settings. Shown until Home comes here |
| Remote's app buttons (Google TV) | the ones Google TV's launcher handled (YouTube, Free TV) stop working with it off | keep working |
| Updates | checks the GitHub releases (daily, on waking, in the gear menu) and installs them | Play installs them; while Play has a newer version, the gear shows the green dot and its row (and Settings) opens Play's update screen |
| Settings → General | version and *Check for updates* | version only; *TV → Restart…* while the Home button is on (the service opens the TV's power menu) |
| Home at power-on (Settings → General, off by default) | Home's own process, a moment after the screen turns on | the Home button service, and only if no key was pressed in that moment (without the service, as in the adb version) |
| SmartTube | Settings can install it | Settings only opens it, and only shows the button when it's on the TV |
| Install permissions | `REQUEST_INSTALL_PACKAGES`, `KILL_BACKGROUND_PROCESSES` | none |
| Code of its own | `app/src/adb` (manifest) | `app/src/store` (manifest, `HomeKeyService`, `ProbeActivity`) |

To try the store build on a TV as a store user
would get it (stock launcher and Home left alone): `FLAVOR=store ./build-and-install.sh 192.168.1.50`. For
a store upload, `./gradlew bundleStoreRelease` makes `app/build/outputs/bundle/storeRelease/app-store-release.aab`.

**Release builds** are signed with a key that never enters the repo. Put its location in
`local.properties` (ignored by git):

```properties
releaseStoreFile=/path/to/release.jks
releaseStorePassword=…
releaseKeyAlias=vibe-launcher
releaseKeyPassword=…
```

then `./gradlew assembleAdbRelease` (`app/build/outputs/apk/adb/release/app-adb-release.apk`). Without
these the release APK is built unsigned. Release builds are shrunk with R8 (rules in
`app/proguard-rules.pro`); the name map for reading their stack traces is
`app/build/outputs/mapping/adbRelease/mapping.txt`, attached to each GitHub release as `mapping-vX.txt`.

Keep the key backed up: APKs signed with a different key can't update an existing install. With the key
set, debug builds are signed with it too, so a TV can move between your debug builds and the releases (and take in-app
updates) without uninstalling. Without it, debug builds use Android's debug key: switching to a release
means uninstalling first, and the in-app updater says to update from a computer.

## Backdrops

Each dock app has a pool of up to twelve backdrops, prepared in the background and stored already cut
to the screen and darkened, so one shows almost at once when you focus the app (a different one each
time you come back to Home, never the same twice in a row):

| App | Source | Needs |
|---|---|---|
| Any app with home screen cards (Netflix, Apple TV, Prime Video, Jellyfin…) | The cards the app publishes to the system TV provider, read with the `READ_TV_LISTINGS` permission | Permission (granted with `adb install -g`, or asked for on first start) |
| Films and shows on those cards | The card's title looked up by exact match: a textless full-size backdrop plus the title's logo (or its name in Outfit when there is no logo). Otherwise the card's own art | Nothing: Stremio's free metadata (Cinemeta, metahub). With a TMDB key, TMDB instead |
| YouTube | SmartTube's cards: your own feed, at 1280×720 | [SmartTube](https://github.com/yuliskov/SmartTube) installed and signed in |
| YouTube without SmartTube | The most popular videos in the TV's country, refreshed every 4 hours (1 unit of the free 10,000 a day) | YouTube Data API key |
| YouTube with neither | Trailers of the top films in Stremio's free catalog (Cinemeta), refreshed every 4 hours | Nothing |
| HDHomeRun | What's on the tuner now, from SiliconDust's free guide: each show looked up like a card's title, the rest as 2×2 walls of the guide's 480×270 images. The tuner is found through SiliconDust's discovery and asked over plain HTTP, home-network addresses only | An HDHomeRun on the same network |
| Plex | Art of Continue Watching (On Deck, first, with where you stopped) and recently added on your server, with each title's name and logo, found through plex.tv and scaled by the server. Opened in the Plex app with `plex://movie/<slug>` or `plex://show/<slug>/season/<n>/episode/<n>` | Plex token |

Some apps (Prime Video) only publish their cards when the home screen asks them to, which the
stock launcher does after each install or update. Vibe Launcher asks too, once per app and version.

Art narrower than 1280 px (Netflix publishes 853×480) is shown as a 2×2 wall of four cards; cards
with stickers baked in (Netflix's "Recently added", "Top 10") are only used in the wall. Apps with
nothing usable get an ambient glow in their icon's colour.

The pools are refreshed every six hours while the TV is on (when Home opens, or on a timer set for
when the next one is due while Home is on screen). Showing and filling are separate: focusing an app
only ever picks a picture already in its pool, while one background thread fills every app's pool,
breadth first: the apps take turns a card at a time, and apps with no picture yet (just moved to the
top row, say) go first until each has one. Each new picture replaces that app's oldest one as it
arrives, so there is never a second set on disk, and a failed or partial refresh (offline, a service
down) leaves the rest in place. A picture joins the pool only once it is written in full, and the
one it replaces leaves at the same moment (or, if it is on screen, at the next visit to Home).
Changing a key refreshes an app's pool the next time it is shown, and the pictures made with the old
key go once a new one is in. Downloads are kept for two days, title matches for a
month. Stremio's metadata is not an official public API; if it stops answering, the cards' own
art is used.

### The picture alone, and the slideshow

Up on the dock slides the rows down out of sight (and the top bar up, if it shows), leaving the
backdrop. The glass and the app's name fade with the carets before anything moves; then a Play pill
slides in from off the screen's left edge, level with the first tile and 12dp above the dock's band. The carousel reads the TV provider's Watch Next table afresh
(rows of type CONTINUE or NEXT, not dismissed) and opens on the pool's stills whose link is one of
them, the last engaged first, then the rest of the pool; Left and Right step through that order. Under
the pill, the time left (duration minus last playback position, formatted with ICU's MeasureFormat in
Home's language) when the app gives both. Building a pool, Watch Next rows come first, so what you're
watching is in it. Holding OK or Play fills the pill over Android's long-press time; the long press
opens the picture's own link (the card's intent URI, or a YouTube video's address), always in the dock
app that was picked, or that app itself when there's no link. Carets on the screen's edges (25 %
opacity) show where the remote goes: they fade out before anything moves and back in once it has
stopped. With Auto-hide on the top bar is only on screen while it or the grid below the
dock has focus: Up from the picture alone brings the bar in and leaves the rows down, Down goes back to
the picture. With it off (the default), the bar always shows, and Up on the dock waits 0.3 s so quick scrolling passes
straight through to it.

After a minute without a key on Home (not while a menu, Settings or a tile's move is open), the same
view runs as a slideshow: a random still from any dock app's pool every 10 s, with no carets, glass or
play button; only the top bar's time and AM/PM, where they are on the bar (`slideClock`; Settings →
Appearance → Screensaver clock). Any key stops it on the still showing, with its app picked, so the play button opens it. The
screen is kept on for it only until the TV's own screen-off time since the last key. Settings → General
→ Reload backdrops empties every pool and the downloads and builds them again from scratch.

Keys are entered in **Settings → Vibe Launcher settings → API keys**, on the TV or from a phone: *Paste
keys from your phone* shows a QR code for a small page the launcher serves on your home network while
Settings is open. The
link carries a random code that changes every time, and the page shows saved keys hidden but for the
last four characters.

### What goes over the network

Only what the backdrops need: image downloads from the apps' own artwork servers; title art from
Stremio (`v3-cinemeta.strem.io`, `images.metahub.space`) or, with a key, TMDB (`api.themoviedb.org`,
`image.tmdb.org`); YouTube (`googleapis.com`, `i.ytimg.com`) and plex.tv plus your Plex server once
their keys are set; film trailers from Cinemeta (`v3-cinemeta.strem.io`) without a YouTube key; what's popular on
Netflix, Disney+, Prime Video, Apple TV+ or Max from the Streaming Catalogs add-on for Stremio
(`…baby-beamup.club`) when that app publishes no cards; an
HDHomeRun tuner on your network and SiliconDust's guide (`api.hdhomerun.com`, `img.hdhomerun.com`);
and GitHub, for this project's updates and, when you ask, SmartTube. No analytics, no accounts,
nothing else.
The full list, with what each is sent, is in the [privacy policy](docs/PRIVACY.md).

## Code map

| File | What it does |
|---|---|
| `MainActivity` | Home screen, dock and grid focus, top bar menus, the Inputs menu with its HDMI-CEC devices |
| `SettingsDialog` | Vibe Launcher settings: General (language, reload backdrops, Home at power-on, Restart…, software version), Appearance (tiles per row, inputs, auto-hide, screensaver clock) and API keys (the keys, their explanations, the QR code); full screen, its lists sliding left as pages open |
| `Lang` | The 50 languages and applying the one chosen in settings (or the TV's own) |
| `Updater`, `UpdateReceiver` | Checks the GitHub releases, downloads and verifies the APK, hands it to Android's installer |
| `AppAdapter`, `TileAnimator`, `DockDecoration` | Tiles, their animations and the dock band |
| `GlassView`, `FrostView` | The glass focus highlight and the frosted top bar (software blur; Android 9 has no live blur) |
| `Backdrops` | Builds each dock app's pool of backdrops and serves one per visit. One for the whole app, kept when Home is rebuilt |
| `ChannelSetup` | Asks apps to publish their home screen cards, as the stock launcher did |
| `DefaultHome` | Offers to become the default Home through the TV's own dialog or chooser, where it has one |
| `TitleArt`, `Tmdb`, `Stremio` | Clean backdrops and logos for films and shows by title: TMDB with a key, Stremio without |
| `YouTubePopular`, `Plex`, `HdHomeRun` | The YouTube (popular videos or film trailers), Plex and HDHomeRun backdrop sources |
| `SmartTube` | Installs SmartTube from its GitHub releases, or opens it to sign in |
| `Http` | Plain GET requests for all of the above, and the HDHomeRun's plain HTTP on the home network |
| `VibeSettings`, `PhoneLink` | Stored keys and the phone page with its QR code |
| `CpuInfo` | CPU use where the device allows it |
| `PowerOnHome` | Home at power-on: Home shortly after the screen turns on (from the Home button service while it's bound) |
| `HomeButtonLink` | What the store build's Home button service lends the app while bound: the TV's power menu for Restart… |
| `CrashGuard` | Falls back to the stock launcher's App info after repeated crashes |

Contributions: see [CONTRIBUTING.md](CONTRIBUTING.md).
