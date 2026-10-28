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
| Keys you enter: TMDB, YouTube, Plex token | to fetch artwork and titles from those services | the app's private storage; never shown in full again (the phone page shows the last four characters) |
| Downloaded pictures (backdrops, logos) | to show a picture at once instead of waiting | the app's cache |
| The Wi-Fi network's name | shown in the network pill | read from Android when shown; not stored |

Uninstalling Vibe Launcher, or clearing its data in Settings > Apps, deletes all of it.

## Permissions

| Permission | Why |
|---|---|
| TV listings (`READ_TV_LISTINGS`) | to read the cards your other apps publish for the home screen (Continue watching, recommendations) and show their pictures |
| Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`) | Android shows the Wi-Fi network's name only to apps with location. Vibe Launcher uses it for that name alone; it never asks Android where the TV is |
| Network (`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`) | to download pictures, and show the network pill |
| Uninstall requests (`REQUEST_DELETE_PACKAGES`) | so the tile menu can open Android's own uninstall screen |
| **GitHub build only:** install packages (`REQUEST_INSTALL_PACKAGES`), close background apps (`KILL_BACKGROUND_PROCESSES`) | to install its own updates and SmartTube when you ask; Android asks you to confirm each install. The second closes the TV's Settings in the background so its install switch shows Vibe Launcher's name |

## The Home button service

Installed without a computer (from an app store, or this project's APK with a file manager), Vibe Launcher
can't turn the TV's own launcher off, so it offers an accessibility service, *Vibe Launcher Home button*, which you turn on yourself in Settings > Accessibility after a note
explaining it. It:

- hears when one of the TV's own home screens comes up (from those launchers only), and opens Vibe
  Launcher over it;
- sees the remote's key presses and keeps the Home button from the TV's own launcher: a press opens Vibe
  Launcher, a hold opens the TV's Settings. Every other key passes on unchanged. While a game controller
  is connected it stops seeing keys, and Home goes to the TV's launcher as usual;
- only if you turn on *Home at power-on* in Vibe Launcher's settings: when the screen turns on, opens Vibe
  Launcher, unless a key was pressed first (it notes only that a key was pressed, not which);
- when you pick *Restart…* in Vibe Launcher's settings, opens the TV's own power menu, where the TV asks
  before restarting;
- reads nothing on screen, stores nothing, and sends nothing anywhere.

Turning it off, in Vibe Launcher's Settings > General > Home button or in Settings > Accessibility, stops all
of it.

## What goes over the network

All of it is to show pictures and titles; none of it is linked to you. Requests go straight from the TV to
each service, over HTTPS except on your home network, and carry what any web request does (the TV's
internet address). The services' own privacy policies apply to what they receive.

| Service | Sent | When |
|---|---|---|
| The artwork servers of your apps | a request for the picture the app's card names | for each card's picture |
| Stremio Cinemeta (`v3-cinemeta.strem.io`, `images.metahub.space`) | the title of a card's film or show, to find its artwork and trailer | when there's no TMDB key |
| Streaming Catalogs, a community add-on for Stremio (`…baby-beamup.club`) | which service's popular list (Netflix, Disney+, Prime Video, Apple TV+, Max) | when that app publishes no cards, at most daily |
| TMDB (`api.themoviedb.org`, `image.tmdb.org`) | your TMDB key and a card's title | once you enter a TMDB key |
| YouTube (`googleapis.com`, `i.ytimg.com`) | your YouTube key, for the list of popular videos and their pictures | once you enter a YouTube key |
| Plex (`plex.tv`, and your own Plex server) | your Plex token, to find your server and list On Deck and recently added | once you enter a Plex token |
| HDHomeRun (your tuner, `api.hdhomerun.com`, `img.hdhomerun.com`) | your tuner's own guide code, for the guide's pictures | when an HDHomeRun tuner is on your network |
| Your phone, on your home network | the keys page, while the keys dialog is open | when you scan the QR code to type keys on a phone; it has a random address that changes each time |
| **GitHub build only:** GitHub (`api.github.com`, `github.com`) | a request for this project's latest release, and SmartTube's | daily, when the TV wakes, from the gear menu, and when you ask to install SmartTube |

The app store build doesn't contact GitHub: Google Play updates it. When Home comes back, it asks the Play Store app on the TV whether Play has a newer version, for the green dot on the gear.

## For the app store's data form

- **Data collected:** none sent to the developer. Card titles are sent to Cinemeta or TMDB only to look up
  their artwork, without anything that identifies you or the TV, and aren't kept by Vibe Launcher.
- **Data shared:** none for advertising or analytics. Keys you enter are sent only to the service they
  belong to.
- **Encrypted in transit:** yes (HTTPS), except devices on your home network.
- **Deletion:** uninstall the app or clear its data; there's nothing held elsewhere to delete.
- **Children:** not directed at children; it collects nothing from anyone.

## Contact

Questions or problems: [open an issue](https://github.com/maydings/vibe-launcher/issues).
