# Stream Center TV

Native Android/Fire-TV-App: Kotlin-UI (2 Tabs: Telegram, LiveTV) + eingebettetes
Python-Backend (Chaquopy, dein bisheriges `aiohttp`/`pyrogram`-Skript ohne
HTML-Frontend) + LibVLC (`MediaListPlayer`) fürs Zappen per D-Pad.

## Bauen

**Option A: Android Studio**
1. Projektordner öffnen (Android Studio erkennt Gradle-Projekt automatisch, generiert
   `gradlew`/Wrapper-Dateien beim ersten Sync selbst).
2. Gradle-Sync abwarten (lädt Chaquopy- und LibVLC-Abhängigkeiten herunter).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

**Option B: GitHub Actions**
1. Repo auf GitHub pushen.
2. Tab "Actions" → Workflow "Build APK" manuell starten (`workflow_dispatch`) oder push auf `main`.
3. Fertige APK unter "Releases" (nicht "Artifacts") herunterladen - landet dort automatisch
   nach jedem erfolgreichen Build (verbraucht keinen Artifact-Speicherplatz).

## Einrichtung auf dem Gerät

Beim ersten Start fragt die App nach einer minimalen `config.json` - nur noch
`api_id` und `api_hash`:
```json
{
  "api_id": "12345678",
  "api_hash": "dein32stelligerhash"
}
```
`api_id`/`api_hash` holst du dir einmalig unter https://my.telegram.org/apps.
Alles Weitere passiert danach direkt in der App:
1. **Login**: Telefonnummer eingeben, per SMS/App erhaltenen Code eintippen, bei
   aktivierter 2FA zusätzlich das Cloud-Passwort. Der `session_string` wird dabei
   intern erzeugt und automatisch in die `config.json` nachgetragen - du siehst
   ihn nie und musst ihn nie kopieren.
2. **Kanäle auswählen**: die App zeigt eine Liste aller Kanäle/Gruppen des Accounts;
   einfach antippen, welche in der App erscheinen sollen. Die Auswahl wird ebenfalls
   automatisch in die `config.json` geschrieben - kein manuelles Heraussuchen von
   Kanal-IDs mehr nötig.

Das externe `telegram_info.py`-Skript (Telethon) wird dafür nicht mehr benötigt.

Die `config.json` selbst kommt wie bisher auf zwei Wegen aufs Gerät:
- **Datei auswählen**: falls sie auf dem Gerät/USB-Stick liegt.
- **Text einfügen**: Inhalt in das Feld einfügen (z.B. per Fire-TV-Fernbedienung
  mühsam, aber machbar - alternativ Bluetooth-Tastatur nutzen).

## Zappen

Im Player: **D-Pad hoch/runter** (oder die Kanal-Tasten der Fernbedienung) wechseln
zum nächsten/vorherigen Eintrag der jeweils aktuell geöffneten Liste (alle Filme
eines Telegram-Themas bzw. alle Kanäle der aktuellen Genre-Auswahl) - ganz ohne
App-Wechsel. **OK/Enter** pausiert/spielt fort, **links/rechts** spult bei VOD-Inhalten.

## TMDB-Filmbeschreibungen (optional)

Trag in deiner `config.json` optional ein `tmdb_api_key`-Feld ein:
```json
{
  "api_id": "...",
  "api_hash": "...",
  "session_string": "...",
  "channels": { ... },
  "tmdb_api_key": "DEIN_TMDB_API_KEY"
}
```
Kostenlosen Key gibt's unter https://www.themoviedb.org/settings/api. Ohne das
Feld funktioniert alles wie bisher, nur ohne Beschreibungen/Bewertungen.

Der Server leitet aus dem Telegram-Dateinamen automatisch Titel + Jahr ab
(z.B. `Der.Pate.1972.1080p.BluRay.x264.mkv` -> "Der Pate", 1972) und fragt
TMDB danach. Bei unsauberen Dateinamen kann die Zuordnung daneben liegen -
das ist rein heuristisch.

## Bekannte Risiken / offene Punkte

- **`aiohttp` unter Chaquopy**: Chaquopy pflegt eine eigene Liste vorkompilierter
  Wheels für Android. Ob `aiohttp` (nutzt teils C-Erweiterungen) dort verfügbar
  ist, muss der erste Gradle-Sync zeigen. Falls der `pip install`-Schritt in
  `app/build.gradle` fehlschlägt: mir Bescheid geben, dann bauen wir das Backend
  probehalber auf `http.server`/reinem `asyncio`-Networking ohne `aiohttp` um.
- **`tgcrypto`**: bewusst NICHT installiert (keine Android-Wheels), Pyrogram nutzt
  automatisch die reine Python-Implementierung (`pyaes`) - etwas langsamer beim
  Ver-/Entschlüsseln, für normalen Gebrauch aber unproblematisch.
- **Icon/Banner**: `ic_launcher.xml` und `tv_banner.xml` sind nur einfarbige
  Platzhalter. Für den Play Store/Fire-TV-Store bitte durch echte Grafiken
  ersetzen (Banner: 320x180dp, Pflichtformat für den Fire-TV-Store-Eintrag).
- **Ich konnte dieses Projekt in meiner Umgebung nicht kompilieren/testen**
  (kein Zugriff auf Googles Maven-Server hier) - es ist ein sorgfältig
  geschriebenes Grundgerüst, aber der erste echte Build-Durchlauf bei dir kann
  noch kleinere Anpassungen brauchen (z.B. exakte Versionsnummern der
  Bibliotheken). Schick mir Fehlermeldungen aus Android Studio/den Actions-Logs,
  dann beheben wir das gezielt.

## Projektstruktur

```
app/src/main/python/backend.py     -> Python-Backend (APIs, Telegram, Proxy)
app/src/main/java/.../StreamApp.kt -> startet Backend beim App-Start
app/src/main/java/.../MainActivity.kt      -> 2 Tabs
app/src/main/java/.../TelegramFragment.kt  -> Kanal > Thema > Filme
app/src/main/java/.../LiveTvFragment.kt    -> M3U laden, Genre-Filter
app/src/main/java/.../PlayerActivity.kt    -> LibVLC MediaListPlayer + Zapping
app/src/main/java/.../SetupActivity.kt     -> config.json hinterlegen
```
