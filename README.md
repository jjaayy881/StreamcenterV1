# Stream Center TV

Native Android/Fire-TV-App für Video-Streaming aus mehreren Quellen: eigene
Telegram-Kanäle, IPTV/VOD über Stalker(Ministra)-Portale, M3U-Playlists und die
ARD/ZDF-Mediathek. Ein eingebettetes Python-Backend (Chaquopy) übernimmt die
gesamte Server-Logik, die Kotlin-Oberfläche ist auf Fernbedienungs-Bedienung
(D-Pad) ausgelegt.

## Inhalt

- [Features](#features)
- [Bauen](#bauen)
- [Einrichtung auf dem Gerät](#einrichtung-auf-dem-gerät)
- [Zappen](#zappen)
- [TMDB-Filmbeschreibungen (optional)](#tmdb-filmbeschreibungen-optional)
- [Bekannte Risiken / offene Punkte](#bekannte-risiken--offene-punkte)
- [Projektstruktur](#projektstruktur)

## Features

- **Telegram**: eigene Kanäle (inkl. Foren/Topics) als Filmbibliothek, mit
  automatischer TMDB-Anreicherung (Poster, Beschreibung, Bewertung)
- **Stalker/Ministra-Portale**: Live-TV, VOD, Serien - inkl. Poster und
  EPG ("gerade läuft") für Live-Kanäle, bis zu 3 Portale parallel hinterlegbar
- **M3U-Playlists**: mit Genre-Filter
- **Mediathek**: ARD/ZDF-Suche direkt in der App
- **Favoriten**, Zapping per D-Pad, LibVLC-Player mit breiter Codec-Unterstützung
- **Einrichtung ohne Bastelei**: nur `api_id`/`api_hash` nötig, Telegram-Login
  (Telefonnummer + Code) und Kanalauswahl laufen direkt in der App

## Bauen

**Option A: Android Studio**
1. Projektordner öffnen (Android Studio erkennt Gradle-Projekt automatisch, generiert
   `gradlew`/Wrapper-Dateien beim ersten Sync selbst).
2. Gradle-Sync abwarten (lädt Chaquopy-, LibVLC- und Glide-Abhängigkeiten herunter).
3. `Build > Build Bundle(s) / APK(s) > Build APK(s)`.

**Option B: GitHub Actions**
1. Repo auf GitHub pushen.
2. Tab "Actions" → Workflow "Build APK" manuell starten (`workflow_dispatch`) oder
   push auf `main`.
3. Fertige APK unter "Releases" (nicht "Artifacts") herunterladen - landet dort
   automatisch nach jedem erfolgreichen Build (verbraucht keinen
   Artifact-Speicherplatz und bleibt dauerhaft erhalten).

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

Die `config.json` selbst kommt auf zwei Wegen aufs Gerät:
- **Datei auswählen**: falls sie auf dem Gerät/USB-Stick liegt.
- **Text einfügen**: Inhalt in das Feld einfügen (z.B. per Fire-TV-Fernbedienung
  mühsam, aber machbar - alternativ Bluetooth-Tastatur nutzen).

Stalker-Portale (bis zu 3) und M3U-Playlisten werden direkt im jeweiligen Tab
der App hinterlegt, nicht über die `config.json`.

## Zappen

Im Player: **D-Pad hoch/runter** (oder die Kanal-Tasten der Fernbedienung) wechseln
zum nächsten/vorherigen Eintrag der jeweils aktuell geöffneten Liste (alle Filme
eines Telegram-Themas, alle Kanäle der aktuellen Genre-Auswahl, o.ä.) - ganz ohne
App-Wechsel. **OK/Enter** pausiert/spielt fort, **links/rechts** spult bei
VOD-Inhalten.

## TMDB-Filmbeschreibungen (optional)

Trag in deiner `config.json` optional ein `tmdb_api_key`-Feld ein:
```json
{
  "api_id": "...",
  "api_hash": "...",
  "tmdb_api_key": "DEIN_TMDB_API_KEY"
}
```
Kostenlosen Key gibt's unter https://www.themoviedb.org/settings/api. Ohne das
Feld funktionieren Telegram-Filme weiterhin, nur ohne Poster/Beschreibung/Bewertung
(Stalker-Poster sind davon unabhängig, die kommen direkt vom Portal).

Der Server leitet aus dem Telegram-Dateinamen automatisch Titel + Jahr ab
(z.B. `Der.Pate.1972.1080p.BluRay.x264.mkv` -> "Der Pate", 1972) und fragt
TMDB danach. Bei unsauberen Dateinamen kann die Zuordnung daneben liegen -
das ist rein heuristisch.

## Bekannte Risiken / offene Punkte

- **`tgcrypto`**: wird installiert und beschleunigt Pyrogrums Ver-/Entschlüsselung;
  falls der Build auf einer Architektur fehlschlägt, für die kein vorkompiliertes
  Wheel existiert, fällt Pyrogram automatisch auf die reine Python-Implementierung
  zurück (etwas langsamer, aber funktionsfähig).
- **Stalker-EPG**: der "gerade läuft"-Text für Live-Kanäle wird gedrosselt im
  Hintergrund nachgeladen (manche Portale reagieren empfindlich auf zu viele
  parallele Anfragen pro MAC-Adresse) - kann bei sehr großen Kanallisten ein
  bis zwei Minuten dauern, bis alle Kanäle eine Beschreibung haben.
- **Icon/Banner**: `ic_launcher.xml` und `tv_banner.xml` sind nur einfarbige
  Platzhalter. Für den Play Store/Fire-TV-Store bitte durch echte Grafiken
  ersetzen (Banner: 320x180dp, Pflichtformat für den Fire-TV-Store-Eintrag).

## Projektstruktur

```
app/src/main/python/backend.py               Python-Backend (aiohttp): Telegram (Pyrogram),
                                               Stalker/Ministra, M3U, Mediathek, TMDB, Proxy

app/src/main/java/.../StreamApp.kt            startet das Backend beim App-Start
app/src/main/java/.../MainActivity.kt         Tab-Host (Telegram/LiveTV/Mediathek/Stalker)
app/src/main/java/.../TelegramFragment.kt     Kanal -> Thema -> Filme
app/src/main/java/.../StalkerFragment.kt      Portal-Verwaltung, Kategorien -> Kanäle/VOD/Serien
app/src/main/java/.../LiveTvFragment.kt       M3U laden, Genre-Filter
app/src/main/java/.../MediathekFragment.kt    ARD/ZDF-Suche
app/src/main/java/.../PlayerActivity.kt       LibVLC-Player + Zapping
app/src/main/java/.../SetupActivity.kt        config.json hinterlegen
app/src/main/java/.../TelegramLoginActivity.kt   Telefonnummer/Code/2FA-Login
app/src/main/java/.../ChannelSelectionActivity.kt Kanalauswahl nach dem Login
app/src/main/java/.../MovieAdapter.kt         Kachel-Liste (Poster + Titel + Beschreibung)
app/src/main/java/.../FavoritesManager.kt     lokale Favoriten
```
