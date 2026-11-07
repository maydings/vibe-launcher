# Vibe Launcher privacy policy

For both builds of Vibe Launcher: the one from this project's GitHub releases (installed with adb or a file
manager), and the one from an app store. Where they differ, it says so.

**In short:** there's no account, no analytics, no ads and no server of ours. Nothing about you or your
TV is sent to the developer. The launcher talks only to the services that make its pictures, and only for
that.

## What stays on the TV

| What | Why | Where |
|---|---|---|
| Settings (tiles per row, hidden apps, language, …) | to remember your choices | the app's private storage |
| Keys you enter: TMDB, YouTube, Plex token, Pexels, Pixabay | to fetch artwork and titles from those services | the app's private storage; never shown in full again (the phone page shows the last four characters) |
| Tiles you've changed: names, pictures you chose, folders | to show your tiles as you set them up | the app's private storage |
| A backup, only when you make one: the settings and keys above, and your tiles | to restore your setup, or move it to another TV | the TV's Download folder (`vibe-launcher-backup.json`), or your phone when you download it there |
| Downloaded pictures (backdrops, logos) | to show a picture at once instead of waiting | the app's cache |
| The Wi-Fi network's name | shown in the network pill | read from Android when shown; not stored |

Uninstalling Vibe Launcher, or clearing its data in Settings > Apps, deletes all of it, except a backup you
saved in the Download folder, which stays until you delete it.

## Permissions

| Permission | Why |
|---|---|
| TV listings (`READ_TV_LISTINGS`) | to read the cards your other apps publish for the home screen (Continue watching, recommendations) and show their pictures |
| Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Android shows the Wi-Fi network's name only to apps with location. Vibe Launcher uses it for that name alone; it never asks Android where the TV is |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | to download pictures, and show the network pill |
| Uninstall requests (`REQUEST_DELETE_PACKAGES`) | so the tile menu can open Android's own uninstall screen |
| Storage (`WRITE_EXTERNAL_STORAGE`, Android 10 and older only) | to save a settings backup in the Download folder and read it back, asked the first time you do |
| **GitHub build only:** install packages (`REQUEST_INSTALL_PACKAGES`), close background apps (`KILL_BACKGROUND_PROCESSES`) | to install its own updates; Android asks you to confirm each install. The second closes the TV's Settings in the background so its install switch shows Vibe Launcher's name |
| See all apps (`QUERY_ALL_PACKAGES`) | so Settings > Advanced can list every app on the TV, system parts too, to turn them off and on. The list stays on the TV |
| Change secure settings (`WRITE_SECURE_SETTINGS`) | not given by installing: the TV's own ADB grants it the first time Settings > Advanced connects. Used only to turn debugging and Wireless debugging on and off (when Vibe Launcher needs it, and again when Settings closes, unless you switch that off) and to set the animation speed you pick in Appearance; never for anything else, such as accessibility services or the keyboard |

**Settings > Advanced** turns apps off through the TV's own network debugging, which you
turn on and allow yourself. Vibe Launcher connects only to the TV itself (`127.0.0.1`), never to another
device, with a key it keeps in the TV's Android Keystore. For Wireless debugging (Android 11 and up) it uses
a second key, stored only on the TV, encrypted with a key in the Android Keystore. Pairing it, the 6-digit code
you type on your phone goes over your home network to Vibe Launcher's own page on the TV (the link with a
random code in it, as for the API keys), and from there only to the TV's own pairing window. It runs only the commands for what you choose
there: turning an app off or on, making Vibe Launcher Home (or the TV's own home screen again), closing
background apps, restarting the TV when you pick *Restart…* (after asking), and once, granting itself the permission above. Which apps it turned off is kept with
its settings (and in a backup).

## No accessibility service

Vibe Launcher becomes Home through the TV's own ADB (Settings > Advanced > Default launcher), so it has no
accessibility service and never sees your key presses in other apps or anything on screen. Installing SmartTube
is left to Downloader: Vibe Launcher only opens it on SmartTube's code.

## What goes over the network

All of it is to show pictures, titles and the weather; none of it is linked to you. Requests go straight from the TV to
each service, over HTTPS except on your home network, and carry what any web request does (the TV's
internet address). The services' own privacy policies apply to what they receive.

| Service | Sent | When |
|---|---|---|
| The artwork servers of your apps | a request for the picture the app's card names | for each card's picture |
| Stremio Cinemeta (`v3-cinemeta.strem.io`, `images.metahub.space`) | the title of a card's film or show, or the IMDb id an app put on its card (Nuvio), to find its artwork and trailer | when Stremio or Nuvio is enabled in Settings > Backdrops > Film & show sources (both are by default) |
| Streaming Catalogs, a community add-on for Stremio (`…baby-beamup.club`) | which service's popular list (Netflix, Disney+, Prime Video, Apple TV+, Max) | when that app publishes no cards, at most daily |
| TMDB (`api.themoviedb.org`, `image.tmdb.org`) | your TMDB key and a card's title | once you enter a TMDB key |
| YouTube (`googleapis.com`, `i.ytimg.com`) | your YouTube key, for the list of popular videos and their pictures | once you enter a YouTube key |
| Open-Meteo (`api.open-meteo.com`) | the weather location, as a latitude and longitude rounded to the city, and °C or °F | while the weather is on (Settings > Weather; on by default), at most every 20 minutes |
| Open-Meteo geocoding (`geocoding-api.open-meteo.com`) | the city name you type, and the TV's language | when you search for a city in Settings > Weather > Location |
| ipapi.co, or ipwho.is when it doesn't answer | nothing beyond the request itself: the service works out the city from the TV's internet address | while the weather is on with Location set to Automatic, at most daily |
| Wallhaven (`wallhaven.cc`, `w.wallhaven.cc`, `th.wallhaven.cc`) | what you search for, and requests for the pictures you see and pick | when you browse Wallhaven pictures in Settings > Backdrops > Custom wallpapers |
| Pexels (`api.pexels.com`, `videos.pexels.com`, `images.pexels.com`) | your Pexels key, what you search for, and requests for the videos you see and pick | when you browse Pexels videos there (needs your key) |
| Pixabay (`pixabay.com`, `cdn.pixabay.com`, `i.vimeocdn.com`) | your Pixabay key, what you search for, and requests for the pictures and videos you see and pick | when you browse Pixabay there (needs your key) |
| NASA (`images-api.nasa.gov`, `images-assets.nasa.gov`) | what you search for, and requests for the pictures and videos you see and pick | when you browse NASA there |
| Wikimedia Commons (`commons.wikimedia.org`, `upload.wikimedia.org`, `thumb.wikimedia.org`) | what you search for, and requests for the pictures and videos you see and pick | when you browse Wikimedia Commons there |
| Plex (`plex.tv`, and your own Plex server) | your Plex token, to find your server and list On Deck and recently added | once you enter a Plex token |
| HDHomeRun (your tuner, `api.hdhomerun.com`, `img.hdhomerun.com`) | your tuner's own guide code, for the guide's pictures | when an HDHomeRun tuner is on your network |
| Your phone, on your home network | the keys page, a tile's picture page, the wallpaper page (a picture or video you send to the TV), or the backup page (a backup to download, holding your keys), while Settings or the tile's Edit menu is open | when you scan the QR code on the TV; it has a random address that changes each time |
| **GitHub build only:** GitHub (`api.github.com`, `github.com`) | a request for this project's latest release | daily, when the TV wakes, and from the gear menu |
| GitHub (`api.github.com`, `raw.githubusercontent.com`) | requests for Clean Your TV's lists of what to turn off on each TV; nothing about your TV is sent (the list for it is picked on the TV) | when you open Settings > Advanced > Clean-up lists, and a list |

The app store build doesn't check GitHub for its own updates: Google Play updates it. When Home comes back, it asks the Play Store app on the TV whether Play has a newer version, for the green dot on the gear.

## For the app store's data form

- **Data collected:** none sent to the developer. Card titles are sent to Cinemeta or TMDB only to look up
  their artwork, without anything that identifies you or the TV, and aren't kept by Vibe Launcher.
- **Location:** approximate only, and only for the weather: the city found from the TV's internet address
  (or the one you pick) is kept on the TV and sent to Open-Meteo as coordinates. Turn the weather off, or
  pick a city, and the internet address isn't looked up.
- **Data shared:** none for advertising or analytics. Keys you enter are sent only to the service they
  belong to.
- **Encrypted in transit:** yes (HTTPS), except devices on your home network.
- **Deletion:** uninstall the app or clear its data; there's nothing held elsewhere to delete.
- **Children:** not directed at children; it collects nothing from anyone.

## Contact

Questions or problems: [open an issue](https://github.com/maydings/vibe-launcher/issues).
