# Publishing the store build on Google Play

Everything Play Console asks for, ready to paste. The app is the **store** flavor
(`./gradlew bundleStoreRelease` → `app/build/outputs/bundle/storeRelease/app-store-release.aab`).

## 1. Signing: keep our own key

So the Play install and the GitHub APKs stay signed alike (either can replace the other without losing
settings), Play signs with **our existing key** instead of one it makes. This is chosen once, when the app
is created, and can't be undone.

1. Create the app in Play Console. At **Setup → App signing** (or on the first release, under *App
   integrity*), choose **Use a different key / Export and upload a key from Java keystore**.
2. Download **pepk.jar** and the **encryption public key** that page offers.
3. Export the key (the passwords are the ones in `local.properties`; the tool asks for them):

   ```bash
   java -jar pepk.jar --keystore=$HOME/.vibe-launcher-release.jks --alias=vibe-launcher \
     --output=vibe-launcher-private-key.zip --include-cert --rsa-aes-encryption \
     --encryption-key-path=encryption_public_key.pem
   ```

4. Upload `vibe-launcher-private-key.zip` there, then delete it: it's the key, only encrypted for Google.
5. Upload key: Play also asks which key signs your uploads. The same key works (the AAB from
   `bundleStoreRelease` is already signed with it). A separate upload key is optional; if one ever leaks,
   Google can replace it, which it can't do for the app signing key.

Keep `~/.vibe-launcher-release.jks` backed up off this Mac either way.

## 2. Testing before production

Personal developer accounts created after 13 November 2023 must run a **closed test with at least 12
testers, opted in for 14 days in a row**, before they can apply for production
([Play Console Help](https://support.google.com/googleplay/android-developer/answer/14151465)). Organisation
accounts don't. Testers need a Google account and a Google TV or Android TV device to install it on.

## 3. Store listing

**App name** (30 characters max): `Vibe Launcher for TV`
Plain "Vibe Launcher" is free, but two unrelated apps on Play are close to it ("Takan - Vibe Launcher",
"Vibe Music & Podcast Launcher"); "for TV" sets this one apart in search.

**Short description** (80 max):
`A calm home screen for your TV: big pictures, your apps, no ads or tracking.`

**Full description** (4000 max):

```
Vibe Launcher is a home screen for Android TV and Google TV: a dock of your favourite apps under a full-screen picture of what's in them, and everything else in a grid below. No ads, no tracking, no account.

PICTURES FROM YOUR APPS
• The focused app fills the screen with artwork from its own home-screen cards: what you're watching, what's new, what it recommends.
• Started something? It comes first, with the time left.
• Hold OK on the picture to open that title in its app.
• Press Up for the picture alone; Left and Right step through more.
• After a minute without a press, a slideshow of every app's pictures.
• Optional: your own Plex server's art and Continue watching, TMDB artwork and logos, popular YouTube videos.

A HOME SCREEN THAT STAYS OUT OF THE WAY
• At rest, just your dock: from one app to a full row, as you choose. Press Down and the rest of your apps slide up in a grid.
• 4, 5 or 6 tiles per row, picked for your screen size.
• Move, hide or uninstall apps from the tile menu; hidden ones come back from it too.
• The clock, your network and your TV's inputs (HDMI, with the devices on them) in a frosted bar, or hidden until you need it.
• Make it yours: a highlight color for what's focused, and faint arrows that show where the remote goes (or not).
• Home at power-on: for TVs that wake up on their last input, Home comes up instead (optional).
• Full-screen settings that apply as you change them, in 50 languages.
• Light: built for TVs with 1 to 2 GB of memory.

THE HOME BUTTON
Android TV has no setting for choosing the home screen, so Vibe Launcher includes an optional accessibility service, "Vibe Launcher Home button". With it on, pressing Home opens Vibe Launcher and holding Home opens your TV's Settings. It hears only when the TV's own home screen comes up, and the remote's keys: it keeps Home and passes every other key on. It reads nothing on screen, stores nothing and sends nothing anywhere. Vibe Launcher explains this before you turn it on, and you can turn it off at any time in Settings > Accessibility.

PRIVACY
No ads, no analytics, no accounts. Vibe Launcher goes online only for pictures: from your apps, and from the services you give it a key for. The full list is in the privacy policy.

Free and open source (MIT): github.com/maydings/vibe-launcher
```

**Category:** Personalization (Play has no launcher category).
**Tags:** Launcher, Home screen.
**Contact email:** required and shown publicly; a separate address is a good idea.
**Website:** `https://github.com/maydings/vibe-launcher`
**Privacy policy:** `https://github.com/maydings/vibe-launcher/blob/main/vibe-launcher/docs/PRIVACY.md`

### Graphics (`store/graphics/`, drawn by `make_graphics.py`)

| File | Play Console field |
|---|---|
| `icon-512.png` | App icon, 512 x 512 |
| `feature-graphic-1024x500.png` | Feature graphic |
| `tv-banner-1280x720.png` | Android TV banner |
| *(to take)* | Android TV screenshots, 1920 x 1080 (at least 1, up to 8) |

Screenshots: film and show art is someone else's copyright, and other companies' logos mustn't look like
an endorsement. Take them with neutral pictures (your own Plex media, or apps without cards, which show
their colour glow), not the README's ones, whose backdrops are Netflix and Apple artwork.

## 4. App content (Policy → App content)

**Privacy policy:** the URL above.

**Ads:** No, the app contains no ads.

**App access:** All functionality is available without special access (no login).

**Content rating** (IARC questionnaire): category **All Other App Types**. Every content question
(violence, sexuality, language, controlled substances, gambling, user interaction, sharing location)
is **No**. Expected rating: Everyone / PEGI 3.

**Target audience:** 13 and over (choose 13–15, 16–17, 18+). It isn't designed for children, and
choosing under-13 ages brings the Families policy.

**News app:** No. **COVID-19 tracing:** No. **Government app:** No. **Financial features:** none.
**Health:** none.

**Data safety** (from `docs/PRIVACY.md`):
- **Does your app collect or share any of the required user data types?** No. Nothing goes to the
  developer. The titles of the films and shows on cards are looked up on Cinemeta or TMDB for their
  pictures without anything that identifies the user or the TV, and processed ephemerally, which Play's
  form exempts. Keys the user enters go only to their own service, at the user's request.
- **Is all of the user data collected by your app encrypted in transit?** Yes (HTTPS; devices on the home
  network excepted).
- **Do you provide a way for users to request that their data is deleted?** There's nothing held
  anywhere; uninstalling or clearing the app's data removes everything. Say so in the free-text box.
- If a reviewer asks for the title lookups to be declared after all: **App activity → Other actions**,
  collected, not shared, processed ephemerally, required, purpose **App functionality**.

**Permissions declarations** Play may ask about:
- `REQUEST_DELETE_PACKAGES`: "The tile menu's Uninstall opens Android's own uninstall confirmation for the
  app the user picked." (Not a restricted permission; Play may not ask.)
- Location: foreground only, used to read the connected Wi-Fi network's name for the network pill; never
  sent anywhere. No background location.

### Accessibility API declaration

**Is your app an accessibility tool?** No.

**Describe the feature that uses the AccessibilityService API** (paste):

```
Vibe Launcher is a home screen (launcher) for Android TV and Google TV. These TVs have no setting for choosing the home screen app, and their own launcher can't be turned off without a computer, so the remote's Home button always opens the TV's built-in home screen.

The optional "Vibe Launcher Home button" service makes Home open Vibe Launcher instead:
- It filters key events and keeps only KEYCODE_HOME: a short press opens Vibe Launcher, a long press opens the TV's Settings. Every other key is passed on unchanged. While a game controller is connected, key filtering is switched off.
- It receives TYPE_WINDOW_STATE_CHANGED events, limited at runtime to the packages of the TV's own launchers (packageNames), so that when the built-in home screen comes up anyway (for example after Back from an app), Vibe Launcher opens over it.
- Only if the user turns on "Home at power-on" in Vibe Launcher's settings: when the screen turns on, it opens Vibe Launcher, unless a key was pressed first (it notes only that a key was pressed, not which).
- Settings > Restart… calls performGlobalAction(GLOBAL_ACTION_POWER_DIALOG) when the user picks it, to open the TV's own power menu; an app can't restart the TV itself, and the TV asks before restarting.
- It does not retrieve window content (canRetrieveWindowContent is false), stores nothing and sends nothing off the device.

Before the user is sent to Accessibility settings, the app shows a disclosure explaining what the service sees and does, with Agree and Cancel. Nothing is enabled without the user turning it on in Settings > Accessibility.
```

**Video** (YouTube, unlisted; about a minute). Play asks it to show
([guidance](https://support.google.com/googleplay/android-developer/answer/10964491)): the app opening on
the device, the way to the disclosure and the whole of it, what happens when the user agrees (including
turning the service on), what happens when they don't and how they get the disclosure back, and a core
feature that uses the service, with captions where it isn't obvious. Any Android TV or Google TV will do,
as long as the disclosure comes up there (on TVs where Android's own default-Home dialog wins, that dialog
comes first; Cancel on it brings the disclosure). Install the store build fresh, then:

1. Open Vibe Launcher from the TV's apps row. Answer the permissions note (that's a separate one).
2. The Home button disclosure appears. Hold on it long enough to read every line.
3. Choose *Cancel*: the note closes and Vibe Launcher goes on working, with Home still going to the TV's
   own home screen (press it to show that, then open Vibe Launcher again).
4. Top bar > Settings menu > *Make Vibe Launcher your Home* (or Vibe Launcher settings > General >
   *Home button*): the same disclosure comes back.
5. Choose *Agree*: Android's Settings opens on Accessibility. Choose *Vibe Launcher Home button*, turn it
   on, accept Android's confirmation.
6. Open any app, press Home: Vibe Launcher opens. Caption: "Home button service: Home opens Vibe Launcher".
7. Hold Home: the TV's Settings open. Caption: "Holding Home opens the TV's Settings".
8. In Vibe Launcher settings > General, set *Home button* to Off: Home goes back to the TV's own home
   screen.

A phone pointed at the TV is fine; a capture card is clearer. Add the captions in YouTube's editor or
any video editor.

## 5. Before each upload

- `versionCode` higher than the last upload (`app/build.gradle`).
- `./gradlew bundleStoreRelease`, upload the AAB, attach `mapping.txt` from
  `app/build/outputs/mapping/storeRelease/` for readable crash reports.
- The target SDK (34) meets Play's floor for Android TV apps, including from 31 August 2026; the 16 KB page
  size rule doesn't apply (no native code).
