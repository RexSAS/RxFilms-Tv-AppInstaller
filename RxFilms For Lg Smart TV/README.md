# RxFilms for LG Smart TV

The Fire TV app's RxFilms experience as a webOS app: banner, Continue watching, Next up, Top 10 and Downloadable rows
from Seerr, Because you watched, Recently added, Franchises, Genres, library grids, details, search with Seerr requests
and a player with audio/subtitle selection. Plain JavaScript, no build step; runs on webOS 4 (2018) and newer.

- `app/` — the web app (`index.html`, `js/`, `css/`, `appinfo.json`)
- `service/` — a small on-TV service that makes the Seerr calls, since Seerr sends no CORS headers
- `test/` — `node --test test/*.test.js`

## Build

```sh
./build.sh
```

Writes `app/config.js` from `rxfilms.seerr.url` / `rxfilms.seerr.key` in `~/.gradle/gradle.properties` (same keys as
the Fire TV build; the file is gitignored), runs the tests and creates `dist/org.rxfilms.tv_<version>_all.ipk`.

## Install on the TV

1. Install **Developer Mode** from the LG Content Store, sign in with an LG developer account, turn Dev Mode on and
   restart the TV. Dev Mode lasts 50 hours and must be renewed from that app.
2. Register the TV once: `npx -p @webos-tools/cli ares-setup-device` (name `tv`, the TV's IP, port 9922, the
   passphrase shown in the Developer Mode app), then `npx -p @webos-tools/cli ares-novacom --device tv --getkey`.
3. `npx -p @webos-tools/cli ares-install --device tv dist/org.rxfilms.tv_*_all.ipk`
4. Logs: `npx -p @webos-tools/cli ares-inspect --device tv --app org.rxfilms.tv` opens the web inspector.

Desktop preview: open `app/index.html` in Chrome at 1920×1080; arrows, Enter and Esc act as the remote.
Seerr rows only load on the TV (or in a browser started with web security off).



# Things that needs to be fixed or added

- [x] Next up row bugged: when you go from the top 10 up yall focus on the top bar and not on the next up row
- [x] Add a back/home button in pages
- [x] In playback add title in top left
- [x] Playback cant be stopped with the center button of the remote
- [x] Users password has to be remembered and when you launch the app it has to ask who is watchin every time
- [x] Remove the add user button
- [x] Add SyncPlay
- [x] add video view option (full screen, fill, ecc)
- [x] In next up show Season Banner not an episode frame
- [x] add scroll with remote 
- [x] Boost optimization to make it more fluid