# Idiot Box: notes for Claude

A home screen for Amazon Fire TV (Fire OS 7 = Android 9, Fire OS 8 = Android 11), with picks, Live TV, Music and a
settings sidebar. A plain Android app in Java, built without Gradle by `build.sh`. It only targets Fire TV.
Before you push, run `tools/check.sh`: the build and `translate.py --check`. Every pull request runs it too
(`.github/workflows/check.yml`).

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say. ADB debugging must be on (Settings → My Fire
   TV → Developer options; to show that menu, press the device name in About 7 times).
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: Git Bash or WSL (`build.sh` handles Git Bash), then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude`, `longitude` and `city`,
   `tmdb_region` (two-letter country) and `temperature`.
4. **The TMDB key is theirs and secret.** Ask them to create one (free: themoviedb.org → Settings → API) and paste it
   after `tmdb_key=` in `config.properties` themselves, or add it later from their phone (Home Settings → Set up from
   your phone). Don't ask for it in the chat, never print it, never commit it. If they paste it in the chat anyway,
   put it only in `config.properties`. Without a key everything works except the TMDB picks.
5. **Build:** `./build.sh` → `IdiotBox.apk`. Keep `idiotbox.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555` (the TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*), then `adb install -r IdiotBox.apk`.
   On a Mac, `adb connect` can fail with `No route to host` while the TV answers (`nc -z <IP> 5555` connects):
   macOS's Local Network privacy blocks the adb server that adb starts in the background. Start it from the same
   terminal instead (`adb kill-server; adb nodaemon server &`), or allow the terminal app under System Settings →
   Privacy & Security → Local Network.
7. **Tell them what setup does** before running it: Amazon's ad, promo and telemetry apps are turned off (never
   uninstalled), Idiot Box's accessibility service goes on (it opens Idiot Box over Amazon's home screen and closes the
   Alexa overlay), Idiot Box becomes the screensaver, and the music screensaver may read what's playing. `undo` brings
   it all back. With their OK: `tools/firetv.sh <IP> setup`. Apps someone may use (Prime Video, which takes about
   140 MB in the background) go off only when they ask: `tools/firetv.sh <IP> off <package>`; `undo` brings them back too.
8. **They switch on the TV:** *Idiot Box* → *Home Screen* → *Idiot Box*. Don't switch modes from the computer.
9. **Music** needs a music app that shares its music, such as BitChord TV (com.music.bitchord.tv) or Prism
   (com.prism.music): the Music tab browses and plays through it. With none picked under Home Settings → Music provider,
   it uses whichever of those two is installed. The music screensaver fetches covers from YouTube and timed lyrics
   from LRCLIB (lrclib.net).
10. **Check:** `adb logcat -s IdiotBox` shows what the app did.

**Removing it:** *Original* on the TV, then `tools/firetv.sh <IP> undo`, then `adb uninstall io.idiotbox`. Uninstalled
without `undo`? Install again and run `undo`: the Amazon apps stay off and the screensaver points at nothing until then.

## The code

- Java 8 language level, minSdk 26, targetSdk 33. No Gradle, no libraries: only the Android SDK.
  `Ui.LEGACY` is true on both Fire OS 7 (Android 9) and 8 (Android 11), so the home screen skips the GPU layers, draws
  blurred shadows from cached bitmaps (`Shadows`) and rounds images with a shader instead of clipPath. No AGSL:
  `HomeView` draws the background and glass flat.
  Fire OS 8 (Android 11) runs the screensaver as `ScreensaverAlone`, in the main process (its DreamActivity needs the
  dream in the app's default process); `res/values-v30/bools.xml` picks it, Fire OS 7 keeps `Screensaver` in `:home`.
  Keep both in step.
- **How it takes over:** Fire OS protects its launcher (pm refuses to disable it) and ignores other home activities.
  `FireTvService` (an accessibility service) watches which window is in front: when Amazon's home comes up in Idiot Box
  mode it covers it with a black overlay and opens `HomeActivity` (Android holds activity starts back 5 s after Home;
  overlays aren't). `LiveTvActivity` takes the remote's Live TV button links (Amazon's Live TV app is off). Fire OS's
  adbd never answers the device itself, so the app has no ADB: `HomeMode` only records the mode, which the service
  follows, and `tools/firetv.sh` does the rest from a computer.
- **Daily wallpaper:** Home Settings → Wallpaper picks Off, Bing's photo of the day, NASA APOD (the picture on the
  APOD page; its free API is the fallback, and video days show the video's thumbnail) or Lorem Picsum (a random photo,
  seeded by the date). `Wallpaper` caches
  one photo a day per source, at the backdrop size, and the home screen cross-fades to it on every row (the pick's
  title, label and details fade out with it); `Prefs.wallpaperSource` holds the choice. A failed fetch keeps
  yesterday's photo and retries after 15 minutes.
- The home screen is drawn by hand on one Canvas (`HomeView`). Keep it at 60 fps: no allocations in `onDraw`, no work on
  the UI thread (HTTP goes through `Net`, on its background queues). The stick has about 1 GB of RAM: every MB counts.
- `HomeMode` runs the switch as a background job (`ModeJob`) in the main process; the UI is in the `:home` process.
  `Watchdog` gives Home back to Amazon after 3 crashes of the `:home` process in 5 minutes (`homeReverted`, which
  `FireTvService` reads).
- Settings (`SettingsActivity`) is a translucent activity: a sidebar that slides in over the home screen, on black
  that fades out to the left.
- On-screen text: `Ui.tr("English")`, translated from `app/assets/i18n/<lang>.json` (keyed by the English; one
  HashMap lookup, English when a language lacks it). Text built at runtime is a pattern: `Ui.trf("{n} min ago", "n", 12)`,
  never concatenated words. A key `"context|Text"` is the same English where a language may need another word
  (`"format|h:mm"` holds a SimpleDateFormat pattern). A count with a word is
  `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: each catalogue gives one form per CLDR plural category of
  its language. Portuguese, Spanish, German, Russian and Thai are hand translations: the reference, edited by hand, never
  replaced with machine ones. Dates and numbers use `Ui.locale()`. Text drawn every frame is cached and made again
  when `Ui.generation` changes. After changing text, run `python3 tools/translate.py`: it rebuilds `en.json` from the
  code and lists what each language lacks; translate those in every catalogue (or `--fill` with `ANTHROPIC_API_KEY`,
  which sends only the strings), keep placeholders as they are, and `--prune` what the code no longer uses.
  `python3 tools/translate.py --check` validates all catalogues. No right-to-left languages yet; CJK and Devanagari
  use the system font. Comments in English, short, only where the why isn't obvious.
- `assets/fonts/InterVariable.ttf` is Inter 4 trimmed with fontTools (`pyftsubset`): Latin with Vietnamese, Greek,
  Cyrillic and common punctuation and symbols, the features `calt,ccmp,locl,kern,mark,mkmk,tnum,case`, both axes whole.
  A character it lacks comes from the system font.
- Settings come from `config.properties` through the generated `out/gen/io/idiotbox/Config.java` (never edit that).
  What the person changes on the TV (`Prefs`: Home Settings, the phone page, Shelf) wins over those first values.
- After any change, `./build.sh` must end with `Built IdiotBox.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `tools/firetv.sh`, so
  `undo` can bring them back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: *Original*, the watchdog, and `tools/firetv.sh undo`.
