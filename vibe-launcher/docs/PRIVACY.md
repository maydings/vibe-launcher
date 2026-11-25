# Vibe Launcher privacy policy

For Vibe Launcher as this project's GitHub releases have it (installed with Downloader, adb or a file manager).

**In short:** there's no account, no analytics, no ads and no server of ours. Nothing about you or your
TV is sent to the developer. The launcher talks only to the services that make its pictures, and only for
that.

## What stays on the TV

| What | Why | Where |
|---|---|---|
| Settings (tiles per row, hidden apps, language, …) | to remember your choices | the app's private storage |
| Keys you enter: TMDB, YouTube, Plex token, Pexels, Pixabay, an OpenSky API client | to fetch artwork, titles and nearby flights from those services | the app's private storage; never shown in full again (the phone page shows the last four characters) |
| Tiles you've changed: names, pictures you chose, folders | to show your tiles as you set them up | the app's private storage |
| A backup, only when you make one: the settings and keys above, and your tiles, encrypted with a passphrase you choose | to restore your setup, or move it to another TV | the TV's Download folder (`vibe-launcher-backup.json`), or your phone when you download it there |
| Downloaded pictures (backdrops, film and airline logos, the flights' map) | to show a picture at once instead of waiting | the app's cache |
| What adsbdb said about aircraft and flight numbers seen nearby (type, registration, airline, route) | so the same aircraft or flight isn't asked about again | the app's private storage, for up to six months; Settings > Backdrops > System backdrop > Flights overhead clears it |
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
| Install packages (`REQUEST_INSTALL_PACKAGES`), close background apps (`KILL_BACKGROUND_PROCESSES`) | to install its own updates; Android asks you to confirm each install. The second closes the TV's Settings in the background so its install switch shows Vibe Launcher's name |
| See all apps (`QUERY_ALL_PACKAGES`) | so Settings > Advanced can list every app on the TV, system parts too, to turn them off and on. The list stays on the TV |
| Change secure settings (`WRITE_SECURE_SETTINGS`) | not given by installing: the TV's own ADB grants it the first time Settings > Advanced connects. Used only to turn debugging and Wireless debugging on and off (when Vibe Launcher needs it, and again when Settings closes, unless you switch that off) to set the animation speed you pick in Appearance, to turn the TV's own screensaver off or on when you switch it there, to set the TV's Private DNS when you turn on Ad blocking (below), and to turn on Vibe Launcher's own accessibility Home button when you press Agree in the window that says what it does (below); never for anything else, such as other accessibility services, turning any of them off, or the keyboard |

**Settings > Advanced** turns apps off through the TV's own network debugging, which you
turn on and allow yourself. Vibe Launcher connects only to the TV itself (`127.0.0.1`), never to another
device, with a key it keeps in the TV's Android Keystore. For Wireless debugging (Android 11 and up) it uses
a second key, stored only on the TV, encrypted with a key in the Android Keystore. Pairing it, the 6-digit code
you type on your phone goes over your home network to Vibe Launcher's own page on the TV (the link with a
random code in it, as for the API keys), and from there only to the TV's own pairing window. It runs only the commands for what you choose
there: turning an app off or on, making Vibe Launcher Home (or the TV's own home screen again), closing
background apps, restarting the TV when you pick *Restart…* (after asking), and once, granting itself the permission above. Which apps it turned off is kept with
its settings (and in a backup).

## The accessibility Home button (off unless you turn it on)

Vibe Launcher becomes Home through the device's own ADB (Settings > Advanced > Default launcher). Only for devices whose
software won't let ADB be turned on, Settings > Advanced > Home button (accessibility) offers an accessibility service
instead, which you turn on yourself in the device's Accessibility settings, after a window saying what it does; if ADB
has given Vibe Launcher the permission above, pressing Agree in that window turns it on instead. Having *Home at
power-on* on without it brings up the same window. While
it's on, it sees the remote's keys and acts only on Home (a press opens Vibe Launcher, a hold the device's Settings),
passing every other key on; and it hears only that one of the stock launcher's screens came up, to cover it. It reads
nothing on screen, keeps nothing and sends nothing anywhere. If ADB works later, Vibe Launcher offers to become Home
through it and turn the service off. Installing SmartTube
is left to Downloader: Vibe Launcher only opens it on SmartTube's code.

## What goes over the network

All of it is to show pictures, titles, the weather and the flights overhead; none of it is linked to you. Requests go straight from the TV to
each service, over HTTPS except on your home network, and carry what any web request does (the TV's
internet address). The services' own privacy policies apply to what they receive.

| Service | Sent | When |
|---|---|---|
| The artwork servers of your apps | a request for the picture the app's card names | for each card's picture |
| Stremio Cinemeta (`v3-cinemeta.strem.io`, `images.metahub.space`) | the title of a card's film or show, or the IMDb id an app put on its card (Nuvio), to find its artwork and trailer | when Stremio or Nuvio is enabled in Settings > Backdrops > Film & show sources (both are by default) |
| Streaming Catalogs, a community add-on for Stremio (`…baby-beamup.club`) | which service's popular list (Netflix, Disney+, Prime Video, Apple TV+, Max) | when that app publishes no cards, at most daily |
| TMDB (`api.themoviedb.org`, `image.tmdb.org`) | your TMDB key and a card's title; with Film and show details on, also the TV's language, for the synopsis and genres in it | once you enter a TMDB key |
| YouTube (`googleapis.com`, `i.ytimg.com`) | your YouTube key, for the list of popular videos and their pictures | once you enter a YouTube key |
| Open-Meteo (`api.open-meteo.com`) | the weather location, as a latitude and longitude rounded to the city, and °C or °F | while the weather is on (Settings > Weather; on by default), at most every 20 minutes |
| Open-Meteo geocoding (`geocoding-api.open-meteo.com`) | the city name you type, and the TV's language | when you search for a city in Settings > Weather > Location |
| ipapi.co, or ipwho.is when it doesn't answer | nothing beyond the request itself: the service works out the city from the TV's internet address | while the weather is on with Location set to Automatic, at most daily |
| OpenSky Network (`opensky-network.org`, `auth.opensky-network.org`) | the corners of an area centred on the weather location (so, in effect, that location, to the city), and your OpenSky API client's id and secret if you entered them | while the system backdrop is set to Flights overhead (Settings > Backdrops > System backdrop; it isn't unless you choose it) and Home is in front: every 30 seconds with an API client, every 4 minutes without |
| adsbdb (`api.adsbdb.com`) | the transponder address and callsign of an aircraft near you, to find its type, registration, airline and route | while Flights overhead is on, once for each aircraft and flight number not already kept on the TV |
| Virtual Radar Server's lists on GitHub (`raw.githubusercontent.com`) | requests for its list of airlines, and, when adsbdb doesn't know a flight's route or has it wrong, for that airline's list of routes and the lists of airports the flight goes between; the airline and the flight are looked up on the TV | while Flights overhead is on; each list is kept a month in the app's cache |
| OpenStreetMap (`tile.openstreetmap.org`) | requests for the map tiles around the weather location (so, in effect, that location, to the city) | while Flights overhead is on, once for that place and distance (the tiles are then kept in the app's cache) |
| GitHub (`raw.githubusercontent.com`), or Aviasales (`pics.avs.io`) where it has none | the airline code of an aircraft near you, for the airline's logo | while Flights overhead is on, once for each airline (the logo is then kept in the app's cache) |
| Fontsource (`api.fontsource.org`) | a request for its list of fonts (kept a day on the TV) | when you browse fonts in Settings > Library > Fonts |
| Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`) | the name of the font you focus (to show its name in it) or pick (to download it) | when you browse fonts in Settings > Library > Fonts |
| Wallhaven (`wallhaven.cc`, `w.wallhaven.cc`, `th.wallhaven.cc`) | what you search for, and requests for the pictures you see and pick | when you browse Wallhaven pictures in Settings > Library |
| Pexels (`api.pexels.com`, `videos.pexels.com`, `images.pexels.com`) | your Pexels key, what you search for, and requests for the videos you see and pick | when you browse Pexels videos there (needs your key) |
| Pixabay (`pixabay.com`, `cdn.pixabay.com`, `i.vimeocdn.com`) | your Pixabay key, what you search for, and requests for the pictures and videos you see and pick | when you browse Pixabay there (needs your key) |
| NASA (`images-api.nasa.gov`, `images-assets.nasa.gov`) | what you search for, and requests for the pictures and videos you see and pick | when you browse NASA there |
| Wikimedia Commons (`commons.wikimedia.org`, `upload.wikimedia.org`, `thumb.wikimedia.org`) | what you search for, and requests for the pictures and videos you see and pick | when you browse Wikimedia Commons there |
| Apple (`sylvan.apple.com`) | requests for Apple's list of its Apple TV aerial videos, for a frame and the size of each one you see, and for the videos you pick | when you browse Apple TV aerials in Settings > Library |
| Plex (`plex.tv`, and your own Plex server) | your Plex token, to find your server and list On Deck and recently added | once you enter a Plex token |
| HDHomeRun (your tuner, `api.hdhomerun.com`, `img.hdhomerun.com`) | your tuner's own guide code, for the guide's pictures | when an HDHomeRun tuner is on your network |
| Your phone, on your home network | the keys page, the tile picture page, the backdrop page (a picture or video you send to the TV), or the backup page (a backup to download, holding your keys, encrypted with your passphrase), while Settings is open; the backup page only while the TV shows the backup's QR code | when you scan the QR code on the TV, or open a page you saved from it, while Settings is open; its address has a random code, kept on the TV until you choose Settings > General > Forget phones |
| The ad-blocking DNS service you pick: AdGuard (`dns.adguard-dns.com`, `family.adguard-dns.com`), Mullvad (`adblock.dns.mullvad.net`), or the one you type | every app's DNS lookups (the names of the servers the TV connects to), sent by Android itself, encrypted | while Settings > Advanced > Ad blocking is on (off unless you turn it on). Off puts back the TV's own Private DNS setting |
| GitHub (`api.github.com`, `github.com`) | a request for this project's latest release | daily, when the TV wakes, and from the gear menu |
| GitHub (`api.github.com`, `raw.githubusercontent.com`) | requests for Clean Your TV's lists of what to turn off on each TV; nothing about your TV is sent (the list for it is picked on the TV) | when you open Settings > Advanced > Clean-up lists, and a list |

## In short, by kind of data

- **Data collected:** none sent to the developer. Card titles are sent to Cinemeta or TMDB only to look up
  their artwork, without anything that identifies you or the TV, and aren't kept by Vibe Launcher.
- **Location:** approximate only, and only for the weather and the flights overhead: the city found from the
  TV's internet address (or the one you pick) is kept on the TV and sent to Open-Meteo as coordinates, and,
  with Flights overhead on, to the OpenSky Network as an area around it and to OpenStreetMap as the map tiles there. Turn the weather off, or pick a
  city, and the internet address isn't looked up for the weather; with Flights overhead on and no city
  picked, it's looked up for the flights.
- **Data shared:** none for advertising or analytics. Keys you enter are sent only to the service they
  belong to.
- **Encrypted in transit:** yes (HTTPS), except devices on your home network.
- **Deletion:** uninstall the app or clear its data; there's nothing held elsewhere to delete.
- **Children:** not directed at children; it collects nothing from anyone.

## Contact

Questions or problems: [open an issue](https://github.com/maydings/vibe-launcher/issues).
