# Contributing to Vibe Launcher

The launcher is deliberately small: plain Java, AndroidX RecyclerView and DynamicAnimation, ZXing
for the QR code, no Kotlin, no Compose. It has to stay smooth on TVs with 1 GB of RAM, so:

- No background services, no polling while the screen is idle. Video only when the user adds a video wallpaper.
- Network only for backdrop art and the keyed sources a user has set up; nothing else, no analytics.
- Anything that depends on a specific TV goes in `app/src/main/res/values/config.xml`, not in code.
- Keep D-pad navigation deterministic. Test Up/Down/Left/Right from every edge and in every menu.
- Never commit keys, tokens or a signing key. Keys belong in Vibe Launcher settings on the device;
  the release key in `local.properties`.

Verify on a real TV, not only the emulator. Key injection plus a screenshot is the quickest loop:

```bash
adb shell input keyevent KEYCODE_DPAD_DOWN
adb exec-out screencap -p > s.png
```

Include a screenshot in your pull request and say which TV you tested on.

Ideas that fit: more backdrop sources (Jellyfin and Emby servers), editing the dock from settings,
more languages. Ideas that don't: recommendation rows, content feeds, ads,
telemetry.

## Pull requests

- One topic per PR. Small is fine.
- No new dependencies without discussing it in an issue first.
