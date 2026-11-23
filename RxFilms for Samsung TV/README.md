# RxFilms for Samsung TV

The Fire TV app's RxFilms experience as a Samsung Tizen web app, ported from the LG webOS app: banner, Continue watching,
Next up, Top 10 and Downloadable rows from Seerr, Because you watched, Recently added, Franchises, Genres, library grids,
details, search with Seerr requests, SyncPlay and a player with audio/subtitle/picture selection. Plain JavaScript, no
build step; runs on Tizen 4 (2018) and newer.

- `app/` — the web app (`index.html`, `js/`, `css/`, `config.xml`); `js/tizen.js` holds the Samsung-only bits
  (Return key, media keys, exit)
- `test/` — `node --test test/*.test.js`

## Build

```sh
./build.sh
```

Writes `app/config.js` from `rxfilms.seerr.url` / `rxfilms.seerr.key` in `~/.gradle/gradle.properties` (same keys as
the Fire TV build; the file is gitignored), runs the tests and creates `dist/RxFilms-<version>.wgt`.

The `.wgt` is unsigned. Samsung TVs only install packages signed with a Samsung certificate made for that TV, so it is
signed once on your machine:

## Install on the TV

1. On the TV: Apps → type `12345` with the remote → Developer mode **On**, Host PC IP = your computer's IP, restart the TV.
2. Install [Tizen Studio](https://developer.tizen.org/development/tizen-studio/download) (CLI is enough) with the
   **Samsung Certificate Extension** and **TV Extensions** from the Package Manager.
3. `sdb connect <TV IP>` then, in Tizen Studio's Certificate Manager, create a **Samsung** certificate profile for
   the TV (name it e.g. `rxfilms`; it reads the TV's DUID over `sdb`).
4. Sign and install the release (or your own build):
   ```sh
   mkdir wgt && cd wgt && unzip ../RxFilms-1.0.1.wgt
   tizen package -t wgt -s rxfilms -- .
   tizen install -n RxFilms.wgt -t "$(sdb devices | sed -n 2p | awk '{print $3}')"
   ```
5. Logs: `tizen run -p RxFilmsTV0.RxFilms -t <device>` and Chrome DevTools via `sdb` debug mode.

Desktop preview: open `app/index.html` in Chrome at 1920×1080; arrows, Enter and Esc act as the remote.
Seerr rows only load on the TV (or in a browser started with web security off).
