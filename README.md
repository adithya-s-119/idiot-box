<div align="center">

# Idiot Box

**A fast, ad-free home screen for Amazon Fire TV.**

Picks from your own apps, live TV, music, and the weather at a glance.<br>
Open source. One button to go back to Amazon's.

</div>

## What you get

- **No ads.** No sponsored rows, banners or autoplaying promos.
- **Your apps, front and center.** The streaming apps you use, on a glass shelf. Pick them and their order on the TV.
- **Picks, not promos.** Continue watching and fresh titles from what's trending, only from apps you already have.
- **Live TV.** Channels from M3U playlists, with an XMLTV guide. The remote's Live TV button goes straight to Idiot Box.
- **Music.** Home rows and search from your music app (BitChord TV, [Prism](https://github.com/Yigder/Prism) or another that shares its music), played through it.
- **The weather and the time** at the top right, always in view.
- **A screensaver to match**, and a music screensaver while something plays.
- **Set up on the TV or from your phone.** Language, city, TMDB key, playlists and guides.
- **Safe.** No root. Nothing is uninstalled. One button brings back Amazon's home screen.

## Install

You need a Fire TV (Fire OS 7 or 8: Fire TV Stick, Stick 4K, Cube or a Fire TV Edition TV) and a computer on the same Wi-Fi.

1. **On the TV**, turn on developer options: Settings → My Fire TV → About → press the device name 7 times. Then
   Settings → My Fire TV → Developer options → turn on **ADB debugging**. Write down the TV's IP address (Settings →
   My Fire TV → About → Network).
2. **Tools:** a JDK (17+), the Android SDK command-line tools (build-tools 34, platform android-34) and adb. On a Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   On Linux, get OpenJDK 17, Android's command-line tools and adb from your package manager, then run the same
   `sdkmanager` line. On Windows, use Git Bash or WSL.
3. **Build:**
   ```bash
   ./build.sh
   ```
   The first run creates `config.properties`. Set your city and your [TMDB key](#the-tmdb-key) there if you like
   (both can also be set on the TV later), then run it again.
4. **Install** (the TV asks *Allow USB debugging?* the first time: tick *Always allow* and accept):
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r IdiotBox.apk
   ```
5. **Set up the TV**, once, from the computer: Fire OS doesn't let an app do this itself.
   ```bash
   tools/firetv.sh 192.168.1.50 setup
   ```
   It turns off Amazon's ad, promo and telemetry apps (only turned off, never uninstalled), turns on Idiot Box's
   service (it opens Idiot Box whenever Amazon's home screen comes up, and closes the Alexa overlay when Alexa is
   off), makes Idiot Box the screensaver, and lets the music screensaver see what's playing. Alexa is off by default
   (it costs RAM on a 1 GB stick): `tools/firetv.sh 192.168.1.50 alexa on` turns voice search back on.
6. **On the TV**, open *Idiot Box* → *Home Screen* → *Idiot Box*.

For the Music tab, install a music app that shares its music, such as BitChord TV or [Prism](https://github.com/Yigder/Prism). Idiot Box uses whichever of those two is installed; to use another, pick it under Home Settings → Music provider.

## Going back

On the TV: *Idiot Box* → *Home Screen* → *Original*. Amazon's home screen comes back at once. If Idiot Box's home
screen ever crashes three times in five minutes, it switches back by itself.

To undo everything and remove it:
```bash
tools/firetv.sh 192.168.1.50 undo
adb uninstall io.idiotbox
```
`undo` turns Amazon's apps back on, turns the service off and gives the screensaver back to Amazon.

## Settings

The first values come from `config.properties`, created by the first build. Most of them can also be changed on the
TV, in *Idiot Box* → *Home Settings* (the city on the TV's keyboard; the TMDB key, playlists and guides from your
phone), and the TV's choice wins:

| Key | What it does | Default |
|---|---|---|
| `tmdb_key` | Your free TMDB key, for the picks | empty |
| `tmdb_region` | Your country (two letters): which titles are on which app | `US` |
| `latitude`, `longitude` | Your city, for the weather | New York |
| `city` | The name on the weather card | `Weather` |
| `hide_animation` | Leave cartoons and anime out of the picks | `false` |
| `language` | `auto` (follows the TV), or a language with a [translation](#translations) (`en`, `pt`, `es`, `de`, `hi`…) | `auto` |
| `temperature` | `C`, `F` or `auto` | `auto` |
| `hide_services` | Streaming services to leave out of the picks even when their app is on the TV (`netflix`, `prime`, `disney`…), comma separated | empty |

Changed something? Run `./build.sh` and install again. Keep `idiotbox.keystore`: updates are signed with it.

### The TMDB key

Free, and it takes two minutes: create an account at [themoviedb.org](https://www.themoviedb.org/signup), then open
[Settings → API](https://www.themoviedb.org/settings/api) and request a developer key. Either put it after `tmdb_key=`
in `config.properties`, or on the TV open *Idiot Box* → *Home Settings* → *Set up from your phone*: it shows a QR code,
and the page it opens on your phone (on the same Wi-Fi) takes the key, your city, and Live TV's M3U playlists and
XMLTV guides. It's your key: `config.properties` stays out of git.

## How it works

- **A plain Android app, no Gradle, no libraries.** [`build.sh`](build.sh) runs aapt2 → javac → d8 → zipalign →
  apksigner and makes an APK under 1 MB.
- **Over Amazon's home screen, not instead of it.** Fire OS protects its launcher and ignores other home screens, so
  an accessibility service ([`FireTvService`](app/src/io/idiotbox/FireTvService.java)) opens Idiot Box whenever
  Amazon's home comes to the front, covering it at once so it never flashes.
- **Drawn by hand on one Canvas**, at 60 fps on the stick's own chip.
- **A safety net.** A watchdog in a separate process gives the Home button back to Amazon after three crashes in five
  minutes.
- **Data** from TMDB (trending titles, new releases and which app streams what in your country), Open-Meteo
  (the weather) and, for the music screensaver, YouTube's covers and LRCLIB's timed lyrics.

| File | What it is |
|---|---|
| [`HomeView.java`](app/src/io/idiotbox/HomeView.java) | The home screen |
| [`HomeMode.java`](app/src/io/idiotbox/HomeMode.java) | Switching between Idiot Box and Original |
| [`FireTvService.java`](app/src/io/idiotbox/FireTvService.java) | Opens Idiot Box over Amazon's home screen |
| [`Recs.java`](app/src/io/idiotbox/Recs.java), [`TvPicks.java`](app/src/io/idiotbox/TvPicks.java) | The picks (TMDB, and the apps' own rows) |
| [`Live.java`](app/src/io/idiotbox/Live.java), [`LiveSource.java`](app/src/io/idiotbox/LiveSource.java) | Live TV: playlists and guides |
| [`MusicEngine.java`](app/src/io/idiotbox/MusicEngine.java) | The Music tab, through the music app (its media browser) |
| [`HomeData.java`](app/src/io/idiotbox/HomeData.java) | The weather |
| [`HomeApps.java`](app/src/io/idiotbox/HomeApps.java) | The shelf, inputs and All Apps |
| [`DreamView.java`](app/src/io/idiotbox/DreamView.java) | The screensaver |
| [`SettingsActivity.java`](app/src/io/idiotbox/SettingsActivity.java) | The settings sidebar (the app's icon) |
| [`PhoneSetup.java`](app/src/io/idiotbox/PhoneSetup.java) | The set-up-from-your-phone page |
| [`Prefs.java`](app/src/io/idiotbox/Prefs.java) | Settings changed on the TV |
| [`Watchdog.java`](app/src/io/idiotbox/Watchdog.java) | The safety net |
| [`tools/firetv.sh`](tools/firetv.sh) | The one-time setup from a computer, and its undo |

## Translations

Each language is one file, [`app/assets/i18n/<lang>.json`](app/assets/i18n), keyed by the English text in the code.
*Automatic* follows the TV's language; *Home Settings* → *Language* lists them all. Portuguese, Spanish, German,
Russian and Thai were translated by hand and are the reference; the others were machine-made.

- **Add a language:** `python3 tools/translate.py --new ca`, then fill `app/assets/i18n/ca.json` by hand, or with
  `ANTHROPIC_API_KEY` set, `python3 tools/translate.py --fill --lang ca` (only the strings go to the API).
- **After changing text in the code:** `python3 tools/translate.py` lists what each language lacks; `--prune` drops
  strings the code no longer uses; `--check` validates all catalogues.

## Privacy

No accounts, no analytics, no tracking. The app talks to TMDB, Open-Meteo (with the coordinates you set, and the city
you look up), and Live TV's playlists and guides (the built-in ones or yours). While music plays, it gets the song's
cover from YouTube (i.ytimg.com) and its timed lyrics from [LRCLIB](https://lrclib.net) (sending the title, artist and
length). Your settings stay on your computer and your TV. The set-up-from-your-phone page runs on the TV only while
that screen is open, on your Wi-Fi.

## Credits

- Idiot Box is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT), cut down to Fire TV.
- Music through BitChord TV or [Prism](https://github.com/Yigder/Prism).
- This product uses the TMDB API but is not endorsed or certified by [TMDB](https://www.themoviedb.org/).
- Weather data by [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0).
- Timed lyrics from [LRCLIB](https://lrclib.net); covers from YouTube's video thumbnails.
- Icons: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Font: [Inter](https://rsms.me/inter/) (SIL OFL 1.1).
- Not affiliated with Amazon or any streaming service. App names and logos belong to their owners.

## License

[MIT](LICENSE).
