"""
Backend fuer die eingebettete App (Chaquopy).
Wird von MainActivity/StreamApp beim Start in einem Hintergrund-Thread aufgerufen
(start_server()). Liefert nur noch JSON-APIs + Stream-Proxy - die Oberflaeche
kommt jetzt aus Kotlin + LibVLC statt aus dem Browser.
"""
import asyncio
import os
import json
import re
import urllib.parse
import hashlib
import random
import string
import time
from datetime import datetime
import aiohttp
from aiohttp import web

PORT = 9090
CHANNELS = {}
client = None
LAST_M3U_URL = None
TELEGRAM_STATUS = {"connected": False, "error": None, "needs_login": False}
API_ID = None
API_HASH = None
# Login-Flow (Telefonnummer -> Code -> ggf. 2FA-Passwort), erzeugt session_string
# selbst in der App - der Nutzer muss ihn nie sehen oder von Hand eintragen.
LOGIN_CLIENT = None
LOGIN_PHONE_NUMBER = None
LOGIN_PHONE_CODE_HASH = None
TMDB_API_KEY = None
TMDB_CACHE = {}
STALKER_EPG_CACHE = {}  # ch_id (str) -> "Titel" oder "Titel - Beschreibung", nur erfolgreiche Treffer

# Mediathek (portiert aus media.py, hier async statt Threads/Desktop-VLC -
# Wiedergabe laeuft ueber PlayerActivity/LibVLC statt subprocess.Popen)
MEDIATHEK_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
ARD_THUMB_CACHE = {}
OG_IMAGE_CACHE = {}
# Stalker Portal (Ministra) - Live TV/VOD/Serien, EPG bewusst ausgelassen
STALKER_URL = None
STALKER_MAC = None
stalker_portal = None
STALKER_STATUS = {"connected": False, "error": None}
CONFIG_PATH = None
ARD_GATEWAY_SENDERS = ["ard", "br", "ndr", "swr", "wdr", "mdr", "hr", "rbb", "sr", "rb", "kika", "one"]


def log(category, message):
    time_str = datetime.now().strftime("%H:%M:%S")
    print(f"[{time_str}] [{category}] {message}", flush=True)


# =========================
# HELPER
# =========================
def get_filename(msg):
    if msg:
        if msg.document and msg.document.file_name:
            return msg.document.file_name
        if msg.video and msg.video.file_name:
            return msg.video.file_name
        if msg.caption:
            return msg.caption.split('\n')[0][:50]
    return f"Video_{getattr(msg, 'id', '0')}.mp4"


VIDEO_EXTENSIONS = (
    ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
    ".ts", ".m4v", ".mpg", ".mpeg", ".3gp", ".m2ts",
)


def is_video_message(msg):
    """msg.video ist eindeutig. Bei msg.document (die meisten Kanaele schicken Filme
    als 'Datei' statt als Telegram-natives Video) reicht mime_type allein nicht immer -
    manche Clients markieren Videos als application/octet-stream. Deshalb zusaetzlich
    Dateiendung als Fallback, sonst landen auch PDFs/Untertitel/Archive faelschlich
    in der Filmliste und lassen sich natuerlich nicht abspielen."""
    if msg.video:
        return True
    doc = msg.document
    if not doc:
        return False
    if doc.mime_type and doc.mime_type.startswith("video/"):
        return True
    name = (doc.file_name or "").lower()
    return name.endswith(VIDEO_EXTENSIONS)


def flatten_channels(channels_dict, parent_prefix=""):
    """
    Liefert Liste aus (Name, ChatID, TopicFilter) Tupeln.
    TopicFilter ist None (alle Topics zeigen) oder eine Liste erlaubter Topic-IDs,
    wenn der Kanal als {"id": ..., "topics": [...]} statt als reine Zahl angegeben wurde.
    """
    flat = []
    if isinstance(channels_dict, str):
        try:
            channels_dict = json.loads(channels_dict)
        except Exception:
            return flat
    if not isinstance(channels_dict, dict):
        return flat
    for key, value in channels_dict.items():
        current_name = f"{parent_prefix} > {key}" if parent_prefix else key
        if isinstance(value, dict) and "id" in value:
            # Leaf-Kanal mit optionalem Topic-Filter: {"id": -100123, "topics": [111, 222]}
            chat_id = value["id"]
            chat_id = int(chat_id) if str(chat_id).replace('-', '').isdigit() else chat_id
            topic_filter = value.get("topics")  # None = alle, Liste = nur diese IDs
            flat.append((current_name, chat_id, topic_filter))
        elif isinstance(value, dict):
            # Weitere Verschachtelung/Gruppierung wie bisher (Kanal > Unterkanal)
            flat.extend(flatten_channels(value, current_name))
        else:
            chat_id = int(value) if str(value).replace('-', '').isdigit() else value
            flat.append((current_name, chat_id, None))
    return flat


def resolve_channel(channel_name):
    """Liefert (chat_id, topic_filter) fuer einen Kanalnamen, oder (None, None)."""
    for name, chat_id, topic_filter in flatten_channels(CHANNELS):
        if name == channel_name:
            return chat_id, topic_filter
    return None, None


def parse_m3u_content(m3u_text):
    channels = []
    current_channel = {}
    for line in m3u_text.splitlines():
        line = line.strip()
        if line.startswith("#EXTINF:"):
            name = line.split(",")[-1].strip() if "," in line else "Unbekannter Sender"
            group_match = re.search(r'group-title="([^"]+)"', line)
            group = group_match.group(1) if group_match else "Allgemein"
            current_channel = {"title": name, "group": group}
        elif line and not line.startswith("#"):
            if current_channel:
                current_channel["stream_url"] = line
                channels.append(current_channel)
                current_channel = {}
    return channels


async def get_forum_topics_safe(chat_id):
    """
    Ruft channels.GetForumTopics DIREKT ueber die rohe Telegram-API auf
    (statt der High-Level-Methode client.get_forum_topics, die im offiziellen
    Pyrogram fehlt bzw. nicht zuverlaessig funktioniert). Das ist das exakte
    Pendant zu Telethons GetForumTopicsRequest.
    """
    from pyrogram import raw

    try:
        peer = await client.resolve_peer(chat_id)
        if not isinstance(peer, raw.types.InputPeerChannel):
            return []  # nur Supergroups/Kanaele koennen Foren sein

        input_channel = raw.types.InputChannel(
            channel_id=peer.channel_id,
            access_hash=peer.access_hash,
        )

        result = await client.invoke(
            raw.functions.channels.GetForumTopics(
                channel=input_channel,
                offset_date=0,
                offset_id=0,
                offset_topic=0,
                limit=100,
            )
        )
        return [t for t in result.topics if hasattr(t, "title")]
    except Exception as e:
        log("INFO", f"Keine Topics fuer Chat {chat_id}: {e}")
        return []


def build_upstream_headers(remote_url, range_header=None):
    parsed = urllib.parse.urlparse(remote_url)
    origin = f"{parsed.scheme}://{parsed.netloc}"
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept": "*/*",
        "Referer": origin + "/",
        "Origin": origin,
    }
    if range_header:
        headers["Range"] = range_header
    return headers


# =========================
# STREAM & API HANDLER
# =========================
def clean_title_for_tmdb(filename):
    """Leitet aus einem Telegram-Dateinamen einen brauchbaren Suchtitel + Jahr ab.
    z.B. 'Der.Pate.1972.1080p.BluRay.x264-GROUP.mkv' -> ('Der Pate', '1972')"""
    name = re.sub(r"\.\w{2,4}$", "", filename)  # Dateiendung entfernen
    name = name.replace(".", " ").replace("_", " ")

    year_match = re.search(r"\b(19\d{2}|20\d{2})\b", name)
    year = year_match.group(1) if year_match else None

    # Alles ab dem Jahr oder ab bekannten Release-Tags abschneiden
    cut_pattern = r"\b(19\d{2}|20\d{2}|1080p|720p|2160p|4k|BluRay|WEB[- ]?DL|WEBRip|HDRip|DVDRip|x264|x265|HEVC|AAC|DDP?5\.1|REMUX)\b"
    cut_match = re.search(cut_pattern, name, re.IGNORECASE)
    if cut_match:
        name = name[:cut_match.start()]

    title = re.sub(r"\s+", " ", name).strip(" -._")
    return title, year


async def fetch_tmdb_info(title, year):
    """Fragt TMDB nach Beschreibung/Poster/Bewertung, mit einfachem In-Memory-Cache."""
    if not TMDB_API_KEY or not title:
        return None

    cache_key = f"{title}|{year or ''}"
    if cache_key in TMDB_CACHE:
        return TMDB_CACHE[cache_key]

    params = {"api_key": TMDB_API_KEY, "query": title, "language": "de-DE"}
    if year:
        params["year"] = year

    result = None
    try:
        timeout = aiohttp.ClientTimeout(total=8)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get("https://api.themoviedb.org/3/search/movie", params=params) as resp:
                if resp.status == 200:
                    data = await resp.json()
                    hits = data.get("results") or []
                    if hits:
                        top = hits[0]
                        poster_path = top.get("poster_path")
                        result = {
                            "overview": top.get("overview") or None,
                            "poster_url": f"https://image.tmdb.org/t/p/w342{poster_path}" if poster_path else None,
                            "rating": top.get("vote_average"),
                            "release_date": top.get("release_date"),
                        }
    except Exception as e:
        log("INFO", f"TMDB-Abfrage fehlgeschlagen fuer '{title}': {e}")

    TMDB_CACHE[cache_key] = result  # auch Fehlschlaege cachen, um Wiederholungen zu vermeiden
    return result


async def enrich_movies_with_tmdb(movies):
    """Reichert eine Filmliste parallel (mit Limit) um TMDB-Metadaten an."""
    if not TMDB_API_KEY:
        return movies

    semaphore = asyncio.Semaphore(5)  # nicht zu viele gleichzeitige TMDB-Anfragen

    async def enrich_one(movie):
        title, year = clean_title_for_tmdb(movie["title"])
        async with semaphore:
            info = await fetch_tmdb_info(title, year)
        if info:
            movie.update(info)
        return movie

    await asyncio.gather(*(enrich_one(m) for m in movies))
    return movies


def is_real_mediathek_film(entry):
    """Filtert Trailer/Gebaerdensprache/Nachrichten etc. aus den Suchtreffern."""
    title = (entry.get("title") or "").lower()
    topic = (entry.get("topic") or "").lower()
    bad_words = [
        "trailer", "gebaerden", "gebärden", "gebaerdensprache", "gebärdensprache",
        "audiodeskription", "hoerfassung", "hörfassung", "klare sprache",
        "untertitel", "magazin", "nachrichten", "journal", "abendschau", "live",
    ]
    return not any(w in title or w in topic for w in bad_words)


async def search_mediathek_api(session, query):
    """Fragt mediathekviewweb.de (aggregiert ARD/ZDF/arte/... Mediatheken) ab."""
    url = "https://mediathekviewweb.de/api/query"
    payload = {
        "queries": [{"fields": ["title", "topic"], "query": query}],
        "sortBy": "timestamp",
        "sortOrder": "desc",
        "future": False,
        "offset": 0,
        "size": 40,
    }
    async with session.post(url, json=payload, timeout=aiohttp.ClientTimeout(total=10)) as resp:
        data = await resp.json(content_type=None)
    results = data.get("result", {}).get("results", [])
    return [e for e in results if is_real_mediathek_film(e)]


async def fetch_og_image(session, page_url):
    """Liest das og:image-Meta-Tag der Sendungsseite - funktioniert senderunabhaengig
    (ARD, ZDF, ORF, ...), da praktisch jede Video-Seite es fuer Social-Media-Previews
    mitliefert. MediathekViewWeb selbst liefert keine Bild-URLs."""
    if page_url in OG_IMAGE_CACHE:
        return OG_IMAGE_CACHE[page_url]

    result = None
    try:
        headers = {"User-Agent": MEDIATHEK_UA, "Accept-Language": "de-DE,de;q=0.9,en;q=0.8"}
        async with session.get(page_url, headers=headers, timeout=aiohttp.ClientTimeout(total=6)) as resp:
            raw = await resp.content.read(200_000)  # og:image steht im <head>, Teilstueck reicht
            html = raw.decode("utf-8", errors="ignore")
        match = re.search(
            r'<meta[^>]+(?:property|name)=["\']og:image["\'][^>]*content=["\']([^"\']+)["\']',
            html, re.IGNORECASE)
        if not match:
            match = re.search(
                r'<meta[^>]+content=["\']([^"\']+)["\'][^>]*(?:property|name)=["\']og:image["\']',
                html, re.IGNORECASE)
        if match:
            result = urllib.parse.urljoin(page_url, match.group(1))
    except Exception as e:
        log("INFO", f"og:image-Fehler fuer {page_url}: {e}")

    OG_IMAGE_CACHE[page_url] = result
    return result


def _find_ard_image_url(node):
    """Durchsucht ein verschachteltes JSON-Objekt rekursiv nach einer fertigen
    ARD-Bild-Service-URL (die Bild-ID laesst sich nicht aus der Video-URL ableiten)."""
    if isinstance(node, dict):
        for key in ("url", "src", "imageUrl"):
            val = node.get(key)
            if isinstance(val, str) and "image-service/images" in val:
                return val
        for v in node.values():
            found = _find_ard_image_url(v)
            if found:
                return found
    elif isinstance(node, list):
        for v in node:
            found = _find_ard_image_url(v)
            if found:
                return found
    return None


async def fetch_ard_thumbnail(session, crid):
    """ARDs page-gateway-API fuer die Poster-URL. Der Pfad ist teils senderspezifisch
    (z.B. /br/item/... statt /ard/item/...), daher werden bekannte Sender durchprobiert."""
    if crid in ARD_THUMB_CACHE:
        return ARD_THUMB_CACHE[crid]

    result = None
    for sender in ARD_GATEWAY_SENDERS:
        try:
            api_url = f"https://api.ardmediathek.de/page-gateway/pages/{sender}/item/{crid}"
            async with session.get(api_url, headers={"User-Agent": MEDIATHEK_UA},
                                    timeout=aiohttp.ClientTimeout(total=5)) as resp:
                if resp.status != 200:
                    continue
                data = await resp.json(content_type=None)
                image_url = _find_ard_image_url(data)
                if image_url:
                    base = image_url.split("?")[0]
                    result = f"{base}?w=640"
                    break
        except Exception:
            continue

    ARD_THUMB_CACHE[crid] = result
    return result


def extract_ard_crid(website_url):
    """Letztes URL-Segment = eigentliche Video-ID bei ardmediathek.de-Links."""
    path = urllib.parse.urlparse(website_url).path.rstrip("/")
    segments = [s for s in path.split("/") if s]
    return segments[-1] if segments else None


async def resolve_mediathek_poster(session, item):
    """1. og:image der Sendungsseite. 2. Nur fuer ardmediathek.de zusaetzlich die
    ARD-eigene API, falls og:image fehlt (z.B. reine JS-App-Seiten). 3. None."""
    website = item.get("url_website", "")
    if website:
        og_image = await fetch_og_image(session, website)
        if og_image:
            return og_image
        if "ardmediathek.de" in website:
            crid = extract_ard_crid(website)
            if crid:
                thumb = await fetch_ard_thumbnail(session, crid)
                if thumb:
                    return thumb
    return None


async def resolve_mediathek_posters(session, results, max_concurrent=8):
    """Loest die Poster fuer alle Treffer parallel auf (mit Limit), damit die
    Anfrage nicht auf viele sequenzielle Requests wartet."""
    semaphore = asyncio.Semaphore(max_concurrent)

    async def one(item):
        async with semaphore:
            item["_poster"] = await resolve_mediathek_poster(session, item)

    await asyncio.gather(*(one(item) for item in results))


async def api_get_mediathek(request):
    query = request.query.get("q", "").strip() or "Tatort"
    try:
        async with aiohttp.ClientSession(headers={"User-Agent": MEDIATHEK_UA}) as session:
            results = await search_mediathek_api(session, query)
            await resolve_mediathek_posters(session, results)
    except Exception as e:
        log("FEHLER", f"Mediathek-Suche fehlgeschlagen fuer '{query}': {e}")
        return json_response_cors({"error": str(e)}, status=500)

    items = []
    for idx, item in enumerate(results):
        video_url = item.get("url_video_hd") or item.get("url_video") or item.get("url_video_low")
        if not video_url:
            continue
        duration = item.get("duration")
        items.append({
            "id": str(idx),
            "title": item.get("title") or "Ohne Titel",
            "stream_url": video_url,
            "overview": item.get("description") or None,
            "poster_url": item.get("_poster"),
            "channel": item.get("channel") or "Mediathek",
            "topic": item.get("topic") or "Sonstiges",
            "duration_min": (duration // 60) if duration else None,
        })
    return json_response_cors(items)


STALKER_UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"


class AsyncStalkerPortal:
    """Async-Portierung von stalker.py (dort: requests + ThreadPoolExecutor, blockierend).
    Wichtig ist hier vor allem der handshake() + get_profile()-Zweischritt - ohne den
    zweiten Schritt (get_profile mit Signatur/Metrics) liefern manche Portale nur
    eingeschraenkte/leere Listen, auch wenn der Handshake selbst schon erfolgreich war."""

    # Nicht jedes Portal nutzt den klassischen /stalker_portal/server/load.php-Pfad -
    # manche (v.a. reine Xtream/Ministra-Mischformen) hoeren stattdessen auf /portal.php
    # oder /server/load.php direkt. Spieler wie SFVIP/TiviMate probieren beim Verbinden
    # mehrere Pfade durch - das hier macht dasselbe.
    CANDIDATE_API_PATHS = [
        "/stalker_portal/server/load.php",
        "/portal.php",
        "/server/load.php",
        "/stalker_portal/portal.php",
        "/c/portal.php",
    ]

    def __init__(self, portal_url, mac):
        self.portal_url = portal_url.rstrip("/")
        self.mac = mac.strip()
        parsed = urllib.parse.urlparse(self.portal_url)
        self.stream_base_url = f"{parsed.scheme}://{parsed.netloc}/vod4"
        self.serial = hashlib.md5(self.mac.encode()).hexdigest()[:13].upper()
        self.device_id = hashlib.sha256(self.mac.encode()).hexdigest().upper()
        self.token = None
        self.token_timestamp = 0.0
        self.random_value = ''.join(random.choices('0123456789abcdef', k=40))
        self.api_path = None  # wird beim ersten erfolgreichen handshake() ermittelt
        # Manche Portale setzen zusaetzlich eigene Session-Cookies (z.B. PHPSESSID) beim
        # Handshake, die bei folgenden Anfragen erwartet werden - unabhaengig vom "token".
        # Da wir fuer Hintergrund-Tasks (z.B. EPG) bewusst eine NEUE aiohttp.ClientSession
        # oeffnen (um nicht an eine einzelne Request-Handler-Lebensdauer gebunden zu sein),
        # gehen solche Cookies sonst verloren. Deshalb hier am Portal-Objekt selbst
        # gespeichert und bei jeder Anfrage manuell mitgeschickt.
        self._session_cookies = {}

    def _headers(self, include_auth=False):
        referer_dir = "/stalker_portal/c/index.html" if (self.api_path or "").startswith("/stalker_portal") else "/c/index.html"
        headers = {
            "Accept": "*/*",
            "User-Agent": STALKER_UA,
            "Referer": f"{self.portal_url}{referer_dir}",
            "X-User-Agent": "Model: MAG250; Link: WiFi",
            "X-User-MAC": self.mac,
            "Connection": "Close",
        }
        if include_auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        return headers

    def _cookies(self):
        cookies = {"mac": self.mac, "stb_lang": "en", "timezone": "Europe/Paris"}
        if self.token:
            cookies["token"] = self.token
        cookies.update(self._session_cookies)  # Portal-eigene Cookies (PHPSESSID etc.)
        return cookies

    def _epg_minimal_headers(self):
        """Absichtlich ein SEHR schlankes Header-Set, exakt nachgebaut aus einem
        bestaetigt funktionierenden curl-Test gegen ein Xtream-Codes-Portal mit
        Stalker-Emulation - solche Portale scheinen bei Extra-Headern wie
        'Authorization', 'Referer' oder 'Accept' auf dem get_epg_info/get_short_epg-
        Endpunkt in eine kaputte/leere Antwort zu laufen, obwohl die "normalen"
        Anfragen (Handshake, Kanalliste, Play) damit problemlos funktionieren."""
        return {"User-Agent": "Mozilla/5.0 (QtEmbedded; U; Linux; C)", "X-User-MAC": self.mac}

    def _epg_minimal_cookies(self):
        cookies = {"mac": self.mac}
        if self.token:
            cookies["token"] = self.token
        return cookies

    def _update_session_cookies(self, resp):
        """Merkt sich Cookies, die das Portal in der Antwort setzt (z.B. PHPSESSID) -
        muss nach JEDER Anfrage aufgerufen werden, damit spaetere Anfragen (auch in einer
        anderen aiohttp.ClientSession) als "dieselbe Portal-Sitzung" erkannt werden."""
        for name, morsel in resp.cookies.items():
            if self._session_cookies.get(name) != morsel.value:
                log("INFO", f"Portal setzt Cookie: {name}={morsel.value[:20]!r}...")
            self._session_cookies[name] = morsel.value

    async def _safe_json(self, resp):
        """Manche Portale antworten mit HTTP 200 und Body 'null' (z.B. bei abgelaufenem
        Token oder einer Parameter-Kombination, die sie nicht kennen) statt einem Fehlercode.
        json.loads('null') ergibt Python None, und ein spaeteres .get() darauf crasht mit
        'NoneType object has no attribute get' - daher hier immer ein Dict garantieren."""
        try:
            data = await resp.json(content_type=None)
        except Exception:
            data = None
        return data if isinstance(data, dict) else {}

    async def _safe_json_from_text(self, text):
        """Wie _safe_json(), aber wenn der Rohtext schon separat ausgelesen wurde (z.B. um ihn
        bei einem Fehler mitzuloggen) - resp.json() nochmal aufzurufen nachdem man resp.text()
        gelesen hat funktioniert bei aiohttp zwar meist noch, ist aber nicht garantiert."""
        try:
            data = json.loads(text)
        except Exception:
            data = None
        return data if isinstance(data, dict) else {}

    async def _try_handshake_path(self, session, path):
        """Ein einzelner Handshake-Versuch gegen einen konkreten API-Pfad. Gibt den Token
        zurueck (oder None), wirft nie - Fehler/falsche Pfade sollen die anderen Kandidaten
        nicht verhindern."""
        url = f"{self.portal_url}{path}?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        referer_dir = "/stalker_portal/c/index.html" if path.startswith("/stalker_portal") else "/c/index.html"
        headers = {
            "Accept": "*/*",
            "User-Agent": STALKER_UA,
            "Referer": f"{self.portal_url}{referer_dir}",
            "X-User-Agent": "Model: MAG250; Link: WiFi",
            "Connection": "Close",
        }
        try:
            async with session.get(url, headers=headers, cookies=self._cookies(),
                                    timeout=aiohttp.ClientTimeout(total=8)) as resp:
                self._update_session_cookies(resp)
                data = await self._safe_json(resp)
        except Exception as e:
            log("INFO", f"Stalker-Handshake auf {path} fehlgeschlagen: {e}")
            return None
        js = data.get("js") or {}
        return js.get("token"), js.get("random")

    async def handshake(self, session):
        # Nach dem ersten Erfolg immer denselben Pfad zuerst probieren (schneller bei
        # spaeteren Reconnects durch ensure_token()).
        candidates = ([self.api_path] if self.api_path else []) + [
            p for p in self.CANDIDATE_API_PATHS if p != self.api_path
        ]
        tried = []
        for path in candidates:
            tried.append(path)
            result = await self._try_handshake_path(session, path)
            if result and result[0]:
                token, random_val = result
                self.api_path = path
                self.token = token
                self.random_value = (random_val or self.random_value).lower()
                self.token_timestamp = time.time()
                return
        raise ConnectionError(
            f"Handshake ohne Token in der Antwort - {len(tried)} Pfade probiert ({', '.join(tried)}). "
            f"stalker_url pruefen (Basis-URL ohne /stalker_portal davor, z.B. http://server:port) "
            f"und ob der Server ueberhaupt erreichbar ist."
        )

    async def get_profile(self, session):
        """Zweiter Auth-Schritt - manche Portale schalten sonst nur eine leere/eingeschraenkte
        Bibliothek frei, obwohl der Handshake selbst schon 'erfolgreich' aussah."""
        url = f"{self.portal_url}{self.api_path}"
        signature = hashlib.sha256(f"{self.mac}{self.serial}{self.device_id}{self.device_id}".encode()).hexdigest().upper()
        metrics = json.dumps({"mac": self.mac, "sn": self.serial, "type": "STB", "model": "MAG250",
                               "uid": "", "random": self.random_value})
        params = {
            "type": "stb", "action": "get_profile", "hd": "1",
            "ver": "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; "
                   "PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; "
                   "Player Engine version: 0x58c",
            "num_banks": "2", "sn": self.serial, "stb_type": "MAG250", "client_type": "STB",
            "image_version": "218", "video_out": "hdmi", "device_id": self.device_id,
            "device_id2": self.device_id, "signature": signature, "auth_second_step": "1",
            "hw_version": "1.7-BD-00", "not_valid_token": "0", "metrics": metrics,
            "hw_version_2": hashlib.sha1(self.mac.encode()).hexdigest(),
            "timestamp": int(time.time()), "api_signature": "262", "prehash": "",
            "JsHttpRequest": "1-xml",
        }
        async with session.get(url, params=params, headers=self._headers(include_auth=True),
                                cookies=self._cookies(), timeout=aiohttp.ClientTimeout(total=10)) as resp:
            self._update_session_cookies(resp)
            data = await self._safe_json(resp)
        js = data.get("js") or {}
        if js.get("token"):
            self.token = js["token"]
            self.token_timestamp = time.time()

    async def ensure_token(self, session):
        if not self.token or (time.time() - self.token_timestamp) > 3600:
            await self.handshake(session)
            await self.get_profile(session)

    async def get_bulk_epg(self, session, period_hours=24):
        """Manche Portale (Xtream/Ministra-Hybride) unterstuetzen zusaetzlich zum
        "offiziellen" Ministra-Standard (get_short_epg, ein Aufruf pro Kanal) einen
        Bulk-Endpunkt, der das EPG fuer ALLE Kanaele auf einmal liefert - deutlich
        schneller. Format: {"js": {"data": {"<ch_id>": [{...}, ...]}}}.
        Liefert {} falls das Portal diesen Endpunkt nicht unterstuetzt (kein Fehler,
        einfach stiller Fallback auf get_short_epg)."""
        await self.ensure_token(session)
        url = f"{self.portal_url}{self.api_path}"
        params = {"type": "itv", "action": "get_epg_info", "period": str(period_hours)}
        try:
            async with session.get(url, params=params, headers=self._epg_minimal_headers(),
                                    cookies=self._epg_minimal_cookies(), timeout=aiohttp.ClientTimeout(total=25)) as resp:
                self._update_session_cookies(resp)
                raw_text = await resp.text()
                data = await self._safe_json_from_text(raw_text)
        except Exception as e:
            log("INFO", f"get_bulk_epg fehlgeschlagen: {e}")
            return {}
        js = data.get("js") or {}
        epg_data = js.get("data")
        if not isinstance(epg_data, dict) or not epg_data:
            log("INFO", f"get_bulk_epg leere/unerwartete Antwort, Rohtext: {raw_text[:300]!r}")
            return {}
        return epg_data

    async def get_short_epg(self, session, ch_id, size=1):
        """Kurz-EPG (aktuell laufende Sendung) fuer einen Live-Kanal - Ministra-Standard-
        Endpunkt. Liefert (titel, beschreibung, blocked). titel/beschreibung sind ggf. None.
        blocked=True heisst: das Portal liefert einen komplett leeren Antwort-Body (nicht
        einmal gueltiges "leeres" JSON wie {"js":[]}) - das ist KEIN normales "kein EPG
        fuer diesen Kanal", sondern ein Zeichen, dass das Skript auf Serverseite bei
        wiederholten Aufrufen abstuerzt/blockt (typisch bei Xtream-Codes-Portalen, die
        Stalker nur unvollstaendig emulieren). Wirft absichtlich nie - EPG ist ein
        "nice to have", ein einzelner fehlgeschlagener Kanal soll nicht die ganze
        Kanalliste kaputt machen."""
        await self.ensure_token(session)
        url = f"{self.portal_url}{self.api_path}"
        params = {"type": "itv", "action": "get_short_epg", "ch_id": str(ch_id), "size": str(size)}
        try:
            async with session.get(url, params=params, headers=self._epg_minimal_headers(),
                                    cookies=self._epg_minimal_cookies(), timeout=aiohttp.ClientTimeout(total=8)) as resp:
                self._update_session_cookies(resp)
                raw_text = await resp.text()
        except Exception as e:
            log("INFO", f"get_short_epg({ch_id}) Anfrage fehlgeschlagen: {e}")
            return None, None, False

        if not raw_text.strip():
            # Komplett leerer Body - kein Parsing-Versuch noetig, das ist eindeutig
            # "Portal antwortet nicht mehr sinnvoll", nicht "kein EPG fuer diesen Kanal".
            return None, None, True

        data = await self._safe_json_from_text(raw_text)
        js = data.get("js")
        entries = []
        if isinstance(js, list):
            entries = js
        elif isinstance(js, dict):
            by_channel = js.get(str(ch_id))
            if isinstance(by_channel, list):
                entries = by_channel
            elif js:
                first_val = next(iter(js.values()), None)
                entries = first_val if isinstance(first_val, list) else []

        if not entries or not isinstance(entries[0], dict):
            # {"js":[]} (oder aehnlich) ist KEIN Fehler, sondern ehrliches "kein EPG
            # fuer diesen Kanal gerade" - dafuer nicht "blocked" melden.
            log("INFO", f"get_short_epg({ch_id}) leere/unerwartete Antwort, Rohtext: {raw_text[:200]!r}")
            return None, None, False
        entry = entries[0]
        name = entry.get("name") or entry.get("title")
        descr = entry.get("descr") or entry.get("description")
        return name, descr, False

    async def get_categories(self, session, cat_type):
        await self.ensure_token(session)
        if cat_type == "itv":
            url = f"{self.portal_url}{self.api_path}?type=itv&action=get_genres&JsHttpRequest=1-xml"
        else:
            url = f"{self.portal_url}{self.api_path}?type=vod&action=get_categories&JsHttpRequest=1-xml"
        async with session.get(url, headers=self._headers(include_auth=True), cookies=self._cookies(),
                                timeout=aiohttp.ClientTimeout(total=10)) as resp:
            self._update_session_cookies(resp)
            data = await self._safe_json(resp)
        raw = data.get("js") or []
        if isinstance(raw, dict):
            raw = [raw]
        result = []
        for c in raw:
            name = c.get("title") or c.get("name")
            cid = c.get("id")
            if not (name and cid):
                continue
            # vod-Kategorien enthalten bei den meisten Portalen sowohl Filme als auch
            # Serien in derselben Liste - grobe Trennung wie im Desktop-Skript ueber
            # Namens-Schluesselwoerter (echte is_series-Flags stehen erst auf Item-Ebene).
            is_series_like = any(w in name.lower() for w in ("serie", "show", " tv", "tv-"))
            if cat_type == "vod" and is_series_like:
                continue
            if cat_type == "series" and not is_series_like:
                continue
            result.append({"id": str(cid), "name": name})
        result.sort(key=lambda x: x["name"])
        return result

    async def _get_ordered_list_page(self, session, type_param, extra_params, page):
        url = f"{self.portal_url}{self.api_path}"
        params = {"type": type_param, "action": "get_ordered_list", "JsHttpRequest": "1-xml", "p": page}
        params.update(extra_params)
        async with session.get(url, params=params, headers=self._headers(include_auth=True), cookies=self._cookies(),
                                timeout=aiohttp.ClientTimeout(total=15)) as resp:
            self._update_session_cookies(resp)
            data = await self._safe_json(resp)
        js = data.get("js") or {}
        items = js.get("data") or []
        try:
            total_items = int(js.get("total_items", 0))
        except (TypeError, ValueError):
            total_items = len(items)
        return items, total_items

    async def get_items(self, session, cat_type, cat_id, max_pages=15):
        """Live TV: type=itv&genre=<id>. Filme/Serien: type=vod&category=<id> (beide teilen
        sich denselben Pool, Trennung erfolgt ueber is_series je Item)."""
        await self.ensure_token(session)
        type_param = "itv" if cat_type == "itv" else "vod"
        param_key = "genre" if cat_type == "itv" else "category"
        extra = {param_key: cat_id}

        first_items, total_items = await self._get_ordered_list_page(session, type_param, extra, 1)
        items = list(first_items)
        per_page = len(first_items) or 1
        total_pages = min(max_pages, max(1, (total_items + per_page - 1) // per_page))

        if total_pages > 1:
            sem = asyncio.Semaphore(5)

            async def one(p):
                async with sem:
                    try:
                        page_items, _ = await self._get_ordered_list_page(session, type_param, extra, p)
                        return page_items
                    except Exception:
                        return []

            pages = await asyncio.gather(*(one(p) for p in range(2, total_pages + 1)))
            for page_items in pages:
                items.extend(page_items)

        if cat_type == "vod":
            items = [i for i in items if str(i.get("is_series", "0")) != "1"]
        elif cat_type == "series":
            items = [i for i in items if str(i.get("is_series", "0")) == "1"]
        return items

    async def get_seasons(self, session, movie_id):
        await self.ensure_token(session)
        items, total = await self._get_ordered_list_page(
            session, "vod", {"movie_id": movie_id, "season_id": "0", "episode_id": "0"}, 1)
        return [i for i in items if str(i.get("is_season", "0")) in ("1", "true", "True")]

    async def get_episodes(self, session, movie_id, season_id):
        await self.ensure_token(session)
        items, total = await self._get_ordered_list_page(
            session, "vod", {"movie_id": movie_id, "season_id": season_id, "episode_id": "0"}, 1)
        return items

    def _clean_stream_cmd(self, cmd_value):
        cmd_value = (cmd_value or "").strip()
        cmd_value = re.sub(r"(?i)^ffmpeg\s*", "", cmd_value).strip()
        if not re.match(r"^https?://", cmd_value, re.IGNORECASE):
            cmd_value = f"{self.stream_base_url}/{cmd_value.lstrip('/')}"
        return self._sanitize_url(cmd_value)

    def _sanitize_url(self, url):
        """Bereinigt doppelte Slashes/leere Pfadsegmente ('/./'), die manche Portale in
        ihren create_link-Antworten zurueckgeben. Reine Vorsichtsmassnahme - falls das
        Token an den exakten Pfad gebunden ist, aendert das nichts (dann hilft nur der
        alternative Aufloesungsweg in get_vod_link_candidates/get_episode_link_candidates)."""
        if not url:
            return url
        try:
            parsed = urllib.parse.urlparse(url)
            path_parts = [p for p in parsed.path.split('/') if p and p != '.']
            clean_path = '/' + '/'.join(path_parts) if path_parts else '/'
            clean_url = urllib.parse.urlunparse((
                parsed.scheme, parsed.netloc, clean_path,
                parsed.params, parsed.query, parsed.fragment
            ))
            if clean_url != url:
                log("INFO", f"URL bereinigt: {url[:100]} -> {clean_url[:100]}")
            return clean_url
        except Exception:
            return url

    async def _create_link(self, session, stream_type, cmd):
        await self.ensure_token(session)
        url = f"{self.portal_url}{self.api_path}"
        params = {"action": "create_link", "type": stream_type, "cmd": cmd, "JsHttpRequest": "1-xml"}
        async with session.get(url, params=params, headers=self._headers(include_auth=True), cookies=self._cookies(),
                                timeout=aiohttp.ClientTimeout(total=10)) as resp:
            self._update_session_cookies(resp)
            status = resp.status
            raw_text = await resp.text()
            data = await self._safe_json_from_text(raw_text)
        js = data.get("js") or {}
        stream_url = js.get("url") or js.get("cmd")
        if not stream_url:
            # Rohantwort mitloggen - "Keine Stream-URL" allein sagt nicht, ob das Portal einen
            # Fehler zurückgegeben hat, eine leere Antwort, oder ein anderes JSON-Format nutzt.
            log("INFO", f"create_link Rohantwort (status={status}, cmd={cmd}): {raw_text[:500]}")
            raise ConnectionError(
                f"Keine Stream-URL in der create_link-Antwort (HTTP {status}). "
                f"Rohantwort im log.txt: {raw_text[:200]}"
            )
        cleaned = self._clean_stream_cmd(stream_url)
        log("OK", f"Stalker Stream aufgeloest ({stream_type}): {cleaned[:150]}")
        return cleaned

    async def get_vod_link_candidates(self, session, movie_id):
        """Liefert (Label, Typ, cmd)-Kandidaten fuer create_link, in Prioritaetsreihenfolge.
        Es gibt zwei in freier Wildbahn vorkommende Konventionen dafuer, wie ein VOD-Stream
        aufgeloest wird - manche Portale wollen den klassischen Datei-ID-Pfad, andere direkt
        das rohe cmd-Feld des Ordered-List-Eintrags. api_stalker_play probiert beide durch
        und nimmt automatisch die, die tatsaechlich einen abrufbaren Stream liefert."""
        items, _ = await self._get_ordered_list_page(session, "vod", {"movie_id": movie_id}, 1)
        log("INFO", f"Stalker VOD ordered_list fuer movie_id={movie_id}: {len(items)} Eintraege")
        if not items:
            raise ConnectionError("Kein Stream-Eintrag fuer diesen Film gefunden.")
        item = items[0]
        stream_id = item.get("id")
        raw_cmd = item.get("cmd")
        candidates = []
        if stream_id:
            candidates.append(("Datei-ID", "vod", f"/media/file_{stream_id}.mpg"))
        if raw_cmd:
            candidates.append(("Item-cmd", "vod", raw_cmd))
        if not candidates:
            raise ConnectionError("Weder Datei-ID noch cmd fuer diesen Film verfuegbar.")
        return candidates

    async def get_episode_link_candidates(self, session, movie_id, season_id, episode_id):
        items, _ = await self._get_ordered_list_page(
            session, "vod", {"movie_id": movie_id, "season_id": season_id, "episode_id": episode_id}, 1)
        if not items:
            raise ConnectionError("Kein Stream-Eintrag fuer diese Episode gefunden.")
        item = items[0]
        stream_id = item.get("id")
        raw_cmd = item.get("cmd")
        candidates = []
        if stream_id:
            candidates.append(("Datei-ID", "vod", f"/media/file_{stream_id}.mpg"))
        if raw_cmd:
            candidates.append(("Item-cmd", "vod", raw_cmd))
        if not candidates:
            raise ConnectionError("Weder Datei-ID noch cmd fuer diese Episode verfuegbar.")
        return candidates


def _stalker_poster(item):
    for key in ("screenshot_uri", "pic", "cover", "poster", "logo"):
        val = item.get(key)
        if val:
            return val
    return None


def _stalker_node(id_=None, title=None, poster_url=None, stream_url=None,
                   category_id=None, movie_id=None, season_id=None, overview=None):
    return {
        "id": str(id_) if id_ is not None else None,
        "title": title or "Ohne Titel",
        "poster_url": poster_url,
        "stream_url": stream_url,
        "category_id": str(category_id) if category_id is not None else None,
        "movie_id": str(movie_id) if movie_id is not None else None,
        "season_id": str(season_id) if season_id is not None else None,
        "overview": overview,
    }


STALKER_PROFILE_SLOTS = 3


def _normalize_stalker_profiles(raw):
    """Sorgt dafuer, dass immer genau STALKER_PROFILE_SLOTS Eintraege existieren,
    egal was (oder ob ueberhaupt etwas) bisher in der config.json stand."""
    profiles = list(raw) if isinstance(raw, list) else []
    normalized = []
    for i in range(STALKER_PROFILE_SLOTS):
        entry = profiles[i] if i < len(profiles) and isinstance(profiles[i], dict) else {}
        normalized.append({
            "name": entry.get("name") or f"Portal {i + 1}",
            "url": entry.get("url") or "",
            "mac": entry.get("mac") or "",
        })
    return normalized


def _load_stalker_profiles():
    if not CONFIG_PATH:
        return _normalize_stalker_profiles([])
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        return _normalize_stalker_profiles(config.get("stalker_profiles"))
    except Exception as e:
        log("WARNUNG", f"Konnte stalker_profiles nicht laden: {e}")
        return _normalize_stalker_profiles([])


def _persist_stalker_config(url, mac, slot=None, name=None):
    """Schreibt stalker_url/stalker_mac (die 'aktive' Verbindung, fuer Auto-Connect beim
    naechsten App-Start) dauerhaft in die config.json. Ist ein slot angegeben, wird
    zusaetzlich das entsprechende der STALKER_PROFILE_SLOTS Profile ueberschrieben -
    das ist die "Portal 1/2/3"-Ablage zum schnellen Umschalten ohne erneute Eingabe."""
    if not CONFIG_PATH:
        return
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        config["stalker_url"] = url
        config["stalker_mac"] = mac
        if slot is not None and 0 <= slot < STALKER_PROFILE_SLOTS:
            profiles = _normalize_stalker_profiles(config.get("stalker_profiles"))
            profiles[slot]["url"] = url
            profiles[slot]["mac"] = mac
            if name:
                profiles[slot]["name"] = name
            config["stalker_profiles"] = profiles
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
    except Exception as e:
        log("WARNUNG", f"Konnte stalker_url/mac nicht in config.json speichern: {e}")


async def _connect_and_activate(url, mac):
    """Gemeinsame Handshake+get_profile-Logik fuer beide Connect-Endpunkte (neue Eingabe
    und Umschalten auf ein gespeichertes Profil). Aktualisiert bei Erfolg die globalen
    stalker_portal/STALKER_STATUS - schreibt aber NICHT in die config.json, das macht
    der jeweilige Aufrufer (unterschiedliche Slot-Handhabung je nach Fall)."""
    global stalker_portal, STALKER_URL, STALKER_MAC
    portal = AsyncStalkerPortal(url, mac)
    try:
        async with aiohttp.ClientSession() as session:
            await portal.handshake(session)
            await portal.get_profile(session)
    except Exception as e:
        STALKER_STATUS["connected"] = False
        STALKER_STATUS["error"] = f"{type(e).__name__}: {e}"
        log("FEHLER", f"Stalker-Connect fehlgeschlagen: {STALKER_STATUS['error']}")
        return False, STALKER_STATUS["error"]

    stalker_portal = portal
    STALKER_URL, STALKER_MAC = url, mac
    STALKER_STATUS["connected"] = True
    STALKER_STATUS["error"] = None
    log("OK", f"Stalker-Portal verbunden (in-app): {portal.portal_url}")
    return True, None


async def api_stalker_connect(request):
    """Verbindet zur Laufzeit mit einer neu eingegebenen URL/MAC (z.B. aus dem
    In-App-Eingabefeld) - Alternative zum bisherigen Weg ueber config.json + ADB.
    Optionaler slot-Parameter (0/1/2) legt das Ergebnis zusaetzlich in einem der drei
    "Portal"-Slots ab, damit man spaeter ohne erneute Eingabe dorthin zurueckwechseln kann."""
    url = (request.query.get("url") or "").strip()
    mac = (request.query.get("mac") or "").strip()
    slot_raw = request.query.get("slot")
    name = (request.query.get("name") or "").strip() or None
    if not url or not mac:
        return json_response_cors({"error": "url und mac werden benoetigt"}, status=400)

    slot = None
    if slot_raw is not None:
        try:
            slot = int(slot_raw)
        except ValueError:
            slot = None

    ok, error = await _connect_and_activate(url, mac)
    if not ok:
        return json_response_cors({"error": error}, status=500)

    _persist_stalker_config(url, mac, slot=slot, name=name)
    return json_response_cors({"status": "ok"})


async def api_stalker_connect_slot(request):
    """Schnelles Umschalten auf ein bereits gespeichertes Portal-Profil (0/1/2) - ohne
    URL/MAC erneut eintippen zu muessen."""
    slot_raw = request.query.get("slot")
    try:
        slot = int(slot_raw)
    except (TypeError, ValueError):
        return json_response_cors({"error": "slot fehlt oder ungueltig"}, status=400)
    if not (0 <= slot < STALKER_PROFILE_SLOTS):
        return json_response_cors({"error": f"slot muss zwischen 0 und {STALKER_PROFILE_SLOTS - 1} liegen"}, status=400)

    profiles = _load_stalker_profiles()
    profile = profiles[slot]
    if not profile["url"] or not profile["mac"]:
        return json_response_cors({"error": f"Slot {slot + 1} ist noch leer"}, status=400)

    ok, error = await _connect_and_activate(profile["url"], profile["mac"])
    if not ok:
        return json_response_cors({"error": error}, status=500)

    _persist_stalker_config(profile["url"], profile["mac"])
    return json_response_cors({"status": "ok", "name": profile["name"]})


async def api_stalker_profiles(request):
    return json_response_cors(_load_stalker_profiles())


async def api_stalker_status(request):
    # portal_url mit ausliefern, damit die Kotlin-Seite Favoriten pro Portal trennen kann
    # (Portal wechseln soll nicht ploetzlich Favoriten eines anderen Anbieters zeigen).
    payload = dict(STALKER_STATUS)
    payload["portal_url"] = stalker_portal.portal_url if stalker_portal else None
    return json_response_cors(payload)


async def api_stalker_categories(request):
    if not stalker_portal or not STALKER_STATUS["connected"]:
        return json_response_cors({"error": STALKER_STATUS["error"] or "Stalker nicht verbunden"}, status=503)
    cat_type = request.query.get("type", "itv")
    try:
        async with aiohttp.ClientSession() as session:
            cats = await stalker_portal.get_categories(session, cat_type)
    except Exception as e:
        log("FEHLER", f"Stalker-Kategorien fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)
    nodes = [_stalker_node(id_=c["id"], title=c["name"], category_id=c["id"]) for c in cats]
    return json_response_cors(nodes)


async def _enrich_itv_nodes_with_epg(chid_list):
    """Fuellt STALKER_EPG_CACHE mit der aktuell laufenden Sendung pro Kanal - laeuft als
    Hintergrund-Task, damit die Kanalliste selbst nicht darauf warten muss.
    Probiert zuerst den Bulk-Endpunkt (ein Aufruf fuer ALLE Kanaele, siehe get_bulk_epg) -
    unterstuetzt nicht jedes Portal, dafuer wenn vorhanden viel schneller. Fuer alles, was
    davon nicht abgedeckt wird, klassisch einzeln nachfragen (streng nacheinander - manche
    Portale vertragen keine parallelen Anfragen pro MAC-Adresse)."""
    if not stalker_portal:
        return
    async with aiohttp.ClientSession() as session:
        try:
            bulk = await stalker_portal.get_bulk_epg(session)
        except Exception as e:
            log("INFO", f"Bulk-EPG fehlgeschlagen: {e}")
            bulk = {}

        if bulk:
            log("INFO", f"Bulk-EPG erfolgreich: {len(bulk)} Kanaele geliefert")
            for ch_id_str, entries in bulk.items():
                if ch_id_str in STALKER_EPG_CACHE or not entries:
                    continue
                entry = entries[0] if isinstance(entries, list) else None
                if not isinstance(entry, dict):
                    continue
                name = entry.get("name") or entry.get("title")
                descr = entry.get("descr") or entry.get("description")
                if name:
                    STALKER_EPG_CACHE[ch_id_str] = f"{name} \u2013 {descr}" if descr else name
        else:
            log("INFO", "Bulk-EPG leer/nicht unterstuetzt - falle zurueck auf get_short_epg pro Kanal")

        missing = [c for c in chid_list if str(c) not in STALKER_EPG_CACHE]
        if not missing:
            return
        log("INFO", f"Frage EPG einzeln ab fuer {len(missing)} Kanaele...")

        for ch_id in missing:
            try:
                name, descr, blocked = await stalker_portal.get_short_epg(session, ch_id)
            except Exception as e:
                log("INFO", f"EPG-Anreicherung fuer Kanal {ch_id} fehlgeschlagen: {e}")
                name, descr, blocked = None, None, False

            if blocked:
                # Portal antwortet nur noch mit komplett leerem Body (kein gueltiges JSON
                # mehr) - typisch fuer Portale, deren Stalker-Emulation bei wiederholten
                # EPG-Aufrufen abstuerzt (z.B. manche Xtream-Codes-Panels). Weitermachen
                # wuerde nur sinnlos Zeit verschwenden und Log vollmuellen.
                log("INFO", "EPG: Portal liefert nur noch leere Antworten (EPG-Endpunkt "
                             "vermutlich instabil/nicht unterstuetzt) - breche fuer diese "
                             "Kategorie ab.")
                break

            if name:
                STALKER_EPG_CACHE[str(ch_id)] = f"{name} \u2013 {descr}" if descr else name
            await asyncio.sleep(0.3)


async def api_stalker_epg(request):
    """Liefert die bereits im Hintergrund geladenen EPG-Texte fuer die angefragten
    Kanal-IDs zurueck (nur was schon fertig ist) - die App fragt das kurz nach dem
    Laden der Kanalliste einmal ab, um die Beschreibungen nachzutragen."""
    ids_param = request.query.get("ids", "")
    ids = [i for i in ids_param.split(",") if i]
    result = {i: STALKER_EPG_CACHE[i] for i in ids if i in STALKER_EPG_CACHE}
    return json_response_cors(result)


async def api_stalker_items(request):
    if not stalker_portal or not STALKER_STATUS["connected"]:
        return json_response_cors({"error": STALKER_STATUS["error"] or "Stalker nicht verbunden"}, status=503)
    cat_type = request.query.get("type", "itv")
    cat_id = request.query.get("category_id")
    if not cat_id:
        return json_response_cors({"error": "category_id fehlt"}, status=400)
    try:
        async with aiohttp.ClientSession() as session:
            items = await stalker_portal.get_items(session, cat_type, cat_id)
    except Exception as e:
        log("FEHLER", f"Stalker-Items fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)

    nodes = []
    itv_chids = []  # fuer den Hintergrund-EPG-Abruf danach
    for it in items:
        title = it.get("name") or "Ohne Titel"
        poster = _stalker_poster(it)
        if cat_type == "itv":
            cmd = it.get("cmd")
            ch_id = it.get("id")
            if not cmd:
                continue
            stream_url = f"http://127.0.0.1:{PORT}/api/stalker/play?kind=itv&cmd=" + urllib.parse.quote(cmd, safe="")
            # Schon gecachte EPG-Texte (von einem frueheren Aufruf) sofort mitschicken -
            # sonst blinkt die Beschreibung bei jedem erneuten Oeffnen der Liste kurz weg.
            overview = STALKER_EPG_CACHE.get(str(ch_id)) if ch_id else None
            nodes.append(_stalker_node(id_=ch_id, title=title, poster_url=poster, stream_url=stream_url, overview=overview))
            if ch_id and str(ch_id) not in STALKER_EPG_CACHE:
                itv_chids.append(ch_id)
        elif cat_type == "vod":
            movie_id = it.get("id")
            if not movie_id:
                continue
            stream_url = f"http://127.0.0.1:{PORT}/api/stalker/play?kind=vod&movie_id=" + urllib.parse.quote(str(movie_id))
            nodes.append(_stalker_node(id_=movie_id, title=title, poster_url=poster, stream_url=stream_url))
        else:  # series -> navigiert erst zu Staffeln, kein direkter stream_url
            movie_id = it.get("id")
            if not movie_id:
                continue
            nodes.append(_stalker_node(id_=movie_id, title=title, poster_url=poster, movie_id=movie_id))

    if cat_type == "itv" and itv_chids:
        # Bewusst NICHT awaiten - die Kanalliste soll sofort zurueckgehen, EPG kommt
        # per separatem Abruf (api_stalker_epg) kurz danach nach.
        asyncio.create_task(_enrich_itv_nodes_with_epg(itv_chids))

    return json_response_cors(nodes)


async def api_stalker_seasons(request):
    if not stalker_portal or not STALKER_STATUS["connected"]:
        return json_response_cors({"error": STALKER_STATUS["error"] or "Stalker nicht verbunden"}, status=503)
    movie_id = request.query.get("movie_id")
    if not movie_id:
        return json_response_cors({"error": "movie_id fehlt"}, status=400)
    try:
        async with aiohttp.ClientSession() as session:
            seasons = await stalker_portal.get_seasons(session, movie_id)
    except Exception as e:
        log("FEHLER", f"Stalker-Staffeln fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)
    nodes = []
    for s in seasons:
        season_id = s.get("id")
        if not season_id:
            continue
        title = s.get("name") or f"Staffel {season_id}"
        nodes.append(_stalker_node(id_=season_id, title=title, poster_url=_stalker_poster(s),
                                    movie_id=movie_id, season_id=season_id))
    return json_response_cors(nodes)


async def api_stalker_episodes(request):
    if not stalker_portal or not STALKER_STATUS["connected"]:
        return json_response_cors({"error": STALKER_STATUS["error"] or "Stalker nicht verbunden"}, status=503)
    movie_id = request.query.get("movie_id")
    season_id = request.query.get("season_id")
    if not (movie_id and season_id):
        return json_response_cors({"error": "movie_id/season_id fehlt"}, status=400)
    try:
        async with aiohttp.ClientSession() as session:
            episodes = await stalker_portal.get_episodes(session, movie_id, season_id)
    except Exception as e:
        log("FEHLER", f"Stalker-Episoden fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)
    nodes = []
    for e in episodes:
        episode_id = e.get("id")
        if not episode_id:
            continue
        title = e.get("name") or f"Episode {episode_id}"
        stream_url = (
            f"http://127.0.0.1:{PORT}/api/stalker/play?kind=episode&movie_id={urllib.parse.quote(str(movie_id))}"
            f"&season_id={urllib.parse.quote(str(season_id))}&episode_id={urllib.parse.quote(str(episode_id))}"
        )
        nodes.append(_stalker_node(id_=episode_id, title=title, poster_url=_stalker_poster(e), stream_url=stream_url))
    return json_response_cors(nodes)


async def api_stalker_play(request):
    """Loest Stalker-cmd/movie_id/episode-Angaben erst beim Abspielen zu einer echten
    URL auf (statt bei jedem Listing) und reicht sie an LibVLC weiter - als m3u8 ueber
    den bestehenden HLS-Rewrite-Proxy, sonst direkt durchgestreamt (wie proxy_segment).

    Fuer VOD/Episoden gibt es zwei in freier Wildbahn vorkommende Konventionen, wie
    create_link aufgeloest wird (Datei-ID vs. rohes Item-cmd) - hier werden beide
    Kandidaten durchprobiert (echter Abruf, nicht nur create_link) und die erste
    Antwort verwendet, die tatsaechlich mit 200/206 kommt."""
    if not stalker_portal or not STALKER_STATUS["connected"]:
        return web.Response(text="Stalker nicht verbunden", status=503)

    kind = request.query.get("kind")
    log("INFO", f"Stalker Play-Anfrage: kind={kind} query={dict(request.query)}")

    api_session = aiohttp.ClientSession()
    try:
        try:
            if kind == "itv":
                cmd = request.query.get("cmd")
                if not cmd:
                    return web.Response(text="cmd fehlt", status=400)
                candidates = [("cmd", "itv", cmd)]
            elif kind == "vod":
                movie_id = request.query.get("movie_id")
                if not movie_id:
                    return web.Response(text="movie_id fehlt", status=400)
                candidates = await stalker_portal.get_vod_link_candidates(api_session, movie_id)
            elif kind == "episode":
                movie_id = request.query.get("movie_id")
                season_id = request.query.get("season_id")
                episode_id = request.query.get("episode_id")
                if not (movie_id and season_id and episode_id):
                    return web.Response(text="movie_id/season_id/episode_id fehlt", status=400)
                candidates = await stalker_portal.get_episode_link_candidates(
                    api_session, movie_id, season_id, episode_id)
            else:
                return web.Response(text="Unbekannte kind", status=400)
        except Exception as e:
            log("FEHLER", f"Stalker-Stream-Aufloesung fehlgeschlagen: {e}")
            return web.Response(text=f"Stream-Aufloesung fehlgeschlagen: {e}", status=500)

        resp = None
        media_session = None
        last_error = None
        for label, stream_type, cmd_value in candidates:
            try:
                candidate_url = await stalker_portal._create_link(api_session, stream_type, cmd_value)
            except Exception as e:
                log("INFO", f"Stalker Play: Kandidat '{label}' create_link fehlgeschlagen: {e}")
                last_error = str(e)
                continue

            if ".m3u8" in candidate_url.lower():
                log("INFO", f"Stalker Play: Kandidat '{label}' ist m3u8, leite weiter: {candidate_url[:150]}")
                raise web.HTTPFound(location="/proxy/m3u8?url=" + urllib.parse.quote(candidate_url, safe=""))

            req_headers = build_upstream_headers(candidate_url, request.headers.get("Range"))
            # WICHTIG: total=30 wuerde die GESAMTE Verbindung nach 30 Sekunden abbrechen -
            # unabhaengig davon, ob Daten fliessen oder der Player pausiert ist (total ist ein
            # absolutes Zeitlimit fuer die komplette Anfrage, nicht nur bis zu den Headern).
            # Bei einem langlebigen Stream (ganzer Film am Stueck) wuerde das JEDE Wiedergabe
            # nach 30s abwuergen. sock_read greift stattdessen nur, wenn wirklich mal 30s lang
            # keine neuen Daten vom Server kommen (= echt tote Verbindung) - beim Pausieren
            # blockieren wir auf dem Schreiben zum Player, nicht auf dem Lesen vom Server,
            # sock_read startet also gar nicht erst neu waehrend pausiert ist.
            trial_session = aiohttp.ClientSession(
                timeout=aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=30)
            )
            try:
                trial_resp = await trial_session.get(candidate_url, headers=req_headers)
            except Exception as e:
                await trial_session.close()
                log("INFO", f"Stalker Play: Kandidat '{label}' Verbindung fehlgeschlagen: {e}")
                last_error = str(e)
                continue

            log("INFO", f"Stalker Play: Kandidat '{label}' -> status={trial_resp.status} "
                        f"content-type={trial_resp.headers.get('Content-Type')} url={candidate_url[:150]}")

            if trial_resp.status in (200, 206):
                resp = trial_resp
                media_session = trial_session
                log("OK", f"Stalker Play: Kandidat '{label}' erfolgreich, wird verwendet.")
                break

            body_preview = await trial_resp.text(errors="ignore")
            log("INFO", f"Stalker Play: Kandidat '{label}' HTTP {trial_resp.status}: {body_preview[:200]}")
            last_error = f"HTTP {trial_resp.status}"
            await trial_resp.release()
            await trial_session.close()

        if resp is None:
            log("FEHLER", f"Stalker Play: kein Kandidat lieferte einen abspielbaren Stream ({last_error})")
            return web.Response(
                text=f"Kein funktionierender Stream gefunden (letzter Fehler: {last_error})", status=502)
    finally:
        await api_session.close()

    status = resp.status
    out_headers = {
        "Access-Control-Allow-Origin": "*",
        "Content-Type": resp.headers.get("Content-Type", "video/mp4"),
    }
    for h in ("Content-Length", "Content-Range", "Accept-Ranges"):
        if h in resp.headers:
            out_headers[h] = resp.headers[h]

    response = web.StreamResponse(status=status, headers=out_headers)
    await response.prepare(request)
    try:
        async for chunk in resp.content.iter_chunked(64 * 1024):
            await response.write(chunk)
    except Exception:
        pass
    finally:
        resp.close()
        await media_session.close()
    return response


async def stream_handler_path(request):
    try:
        channel_param = request.match_info["channel"]
        msg_id = int(request.match_info["msg"])
        channel_decoded = urllib.parse.unquote(channel_param)
    except (KeyError, ValueError):
        return web.Response(text="Ungueltiger Pfad", status=400)

    CHAT_ID, _ = resolve_channel(channel_decoded)
    if not CHAT_ID:
        return web.Response(text="Kanal nicht gefunden", status=404)

    try:
        msg = await client.get_messages(CHAT_ID, message_ids=msg_id)
        if not msg or not is_video_message(msg):
            return web.Response(text="Keine Mediendatei gefunden", status=404)

        media = msg.document or msg.video
        f_size = media.file_size
        base_headers = {
            "Content-Type": "video/mp4",
            "Accept-Ranges": "bytes",
            "Access-Control-Allow-Origin": "*",
        }

        if request.method == "HEAD":
            headers = dict(base_headers)
            if f_size:
                headers["Content-Length"] = str(f_size)
            return web.Response(status=200, headers=headers)

        range_header = request.headers.get("Range")
        start_byte = int(range_header.replace("bytes=", "").split("-")[0]) if range_header else 0
        status = 206 if range_header else 200

        # WICHTIG: pyrogram teilt Dateien bei stream_media()/get_file() intern immer in
        # 1-MiB-Bloecke auf (chunk_size = 1024*1024 in Client.get_file(), fest verdrahtet -
        # NICHT konfigurierbar). offset ist die Anzahl zu ueberspringender 1-MiB-Bloecke.
        # Mit einer falschen (kleineren) Blockgroesse hier landet man beim Umrechnen an der
        # falschen Stelle in der Datei - faellt bei Range-Requests ab Byte 0 nicht auf, aber
        # viele Videos (v.a. ohne "faststart"/moov-Atom am Dateianfang) brauchen als allererste
        # Anfrage einen Sprung ans Dateiende, um die Metadaten zu lesen - genau da griff der
        # falsche Offset und die Wiedergabe schlug fehl, obwohl andere Dateien liefen.
        chunk_alignment = 1024 * 1024
        aligned_offset = (start_byte // chunk_alignment) * chunk_alignment
        skip = start_byte - aligned_offset

        headers = dict(base_headers)
        if f_size:
            headers["Content-Length"] = str(f_size - start_byte)
            if status == 206:
                headers["Content-Range"] = f"bytes {start_byte}-{f_size - 1}/{f_size}"

        response = web.StreamResponse(status=status, headers=headers)
        await response.prepare(request)
        offset_chunks = aligned_offset // chunk_alignment

        try:
            async for chunk in client.stream_media(media, offset=offset_chunks):
                if skip > 0:
                    if skip >= len(chunk):
                        skip -= len(chunk)
                        continue
                    else:
                        chunk = chunk[skip:]
                        skip = 0
                await response.write(chunk)
        except Exception:
            pass

        return response
    except Exception as e:
        return web.Response(text=f"Stream Fehler: {e}", status=500)


def json_response_cors(data, status=200):
    headers = {"Access-Control-Allow-Origin": "*", "Cache-Control": "no-cache"}
    return web.json_response(data, status=status, headers=headers)


async def api_get_channels(request):
    if not TELEGRAM_STATUS["connected"]:
        return json_response_cors({"error": TELEGRAM_STATUS["error"] or "Telegram-Login laeuft noch..."}, status=503)
    flat_list = flatten_channels(CHANNELS)
    return json_response_cors([item[0] for item in flat_list])

async def api_status(request):
    return json_response_cors(TELEGRAM_STATUS)


async def api_get_topics(request):
    channel_name = request.query.get("channel")
    if not channel_name:
        return json_response_cors([], status=400)
    chat_id, topic_filter = resolve_channel(channel_name)
    if chat_id is None:
        return json_response_cors([], status=404)

    topics = await get_forum_topics_safe(chat_id)
    by_id = {}
    for t in topics:
        topic_id = getattr(t, "id", None)
        if topic_id is None:
            continue
        title = getattr(t, "title", None) or f"Thema {topic_id}"
        by_id[topic_id] = {"id": topic_id, "title": title}

    if topic_filter:
        # Nur die konfigurierten IDs, in der Reihenfolge aus der config.json
        result = [by_id[tid] for tid in topic_filter if tid in by_id]
    else:
        result = list(by_id.values())

    return json_response_cors(result)


async def fetch_topic_messages_raw(chat_id, topic_id, limit=300):
    """
    Laedt die Nachrichten eines Forum-Topics per Roh-API. Fuer echte Foren-Themen
    ist messages.Search mit top_msg_id die richtige Methode (nicht GetReplies,
    das ist fuer Kommentare unter Kanal-Posts gedacht und liefert TOPIC_ID_INVALID).
    """
    from pyrogram import raw
    from datetime import datetime as dt

    results = []
    try:
        peer = await client.resolve_peer(chat_id)
        response = await client.invoke(
            raw.functions.messages.Search(
                peer=peer,
                q="",
                filter=raw.types.InputMessagesFilterEmpty(),
                min_date=0,
                max_date=0,
                offset_id=0,
                add_offset=0,
                limit=limit,
                max_id=0,
                min_id=0,
                hash=0,
                top_msg_id=int(topic_id),
            )
        )
        for m in getattr(response, "messages", []):
            media = getattr(m, "media", None)
            document = getattr(media, "document", None) if media else None
            if document is None or not hasattr(document, "id"):
                continue  # Textnachricht ohne Datei - fuer uns nicht relevant

            filename = None
            is_media = bool(document.mime_type and document.mime_type.startswith("video/"))
            for attr in getattr(document, "attributes", []) or []:
                cls_name = attr.__class__.__name__
                if cls_name == "DocumentAttributeFilename":
                    filename = attr.file_name
                elif cls_name == "DocumentAttributeVideo":
                    is_media = True

            if not is_media and filename:
                # mime_type ist manchmal generisch (application/octet-stream) -
                # Dateiendung als Fallback, sonst faellt is_media faelschlich negativ aus.
                is_media = filename.lower().endswith(VIDEO_EXTENSIONS)

            if not is_media:
                continue  # PDF/Untertitel/Archiv/... - keine abspielbare Videodatei
            if not filename:
                filename = f"Video_{m.id}.mp4"

            date_str = ""
            try:
                date_str = dt.fromtimestamp(m.date).strftime("%d.%m.%Y %H:%M")
            except Exception:
                pass

            results.append({"id": m.id, "title": filename, "date": date_str})
    except Exception as e:
        log("FEHLER", f"Fehler beim Laden der Topic-Nachrichten ({type(e).__name__}): {e}")

    return results


async def api_get_movies(request):
    channel_name = request.query.get("channel")
    topic_id = request.query.get("topic")
    if not channel_name:
        return json_response_cors([], status=400)

    chat_id, _ = resolve_channel(channel_name)
    if chat_id is None:
        return json_response_cors([], status=404)

    movies = []
    safe_channel = urllib.parse.quote(channel_name, safe="")
    base_url = f"http://127.0.0.1:{PORT}"

    if topic_id is not None:
        for m in await fetch_topic_messages_raw(chat_id, topic_id):
            movies.append({
                "id": m["id"],
                "title": m["title"],
                "stream_url": f"{base_url}/stream/{safe_channel}/{m['id']}.mp4",
                "date": m["date"],
            })
    else:
        try:
            async for msg in client.get_chat_history(chat_id, limit=100):
                if is_video_message(msg):
                    filename = get_filename(msg)
                    movies.append({
                        "id": msg.id,
                        "title": filename,
                        "stream_url": f"{base_url}/stream/{safe_channel}/{msg.id}.mp4",
                        "date": msg.date.strftime("%d.%m.%Y %H:%M") if msg.date else ""
                    })
        except Exception as e:
            log("FEHLER", f"Fehler beim Laden der Filme: {e}")

    movies = await enrich_movies_with_tmdb(movies)

    return json_response_cors(movies)


async def api_load_m3u(request):
    global LAST_M3U_URL
    m3u_url = request.query.get("url")
    if not m3u_url:
        return json_response_cors({"error": "Keine URL"}, status=400)
    try:
        timeout = aiohttp.ClientTimeout(total=15)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(m3u_url, headers=build_upstream_headers(m3u_url)) as resp:
                if resp.status != 200:
                    return json_response_cors({"error": f"Status {resp.status}"}, status=400)
                text = await resp.text(errors="ignore")
                LAST_M3U_URL = m3u_url
                return json_response_cors(parse_m3u_content(text))
    except Exception as e:
        return json_response_cors({"error": str(e)}, status=500)


async def proxy_m3u8(request):
    remote_url = request.query.get("url")
    if not remote_url:
        return web.Response(text="Keine URL", status=400)
    try:
        timeout = aiohttp.ClientTimeout(total=15)
        headers = build_upstream_headers(remote_url)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(remote_url, headers=headers) as resp:
                if resp.status != 200:
                    return web.Response(text=f"Anbieter antwortete mit Status {resp.status}", status=400)
                text = await resp.text(errors="ignore")
    except Exception as e:
        return web.Response(text=f"Fehler: {e}", status=500)

    base = remote_url
    out_lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#EXT-X-KEY") and "URI=" in stripped:
            def repl_key(m):
                key_url = urllib.parse.urljoin(base, m.group(1))
                proxied = "/proxy/segment?url=" + urllib.parse.quote(key_url, safe="")
                return f'URI="{proxied}"'
            stripped = re.sub(r'URI="([^"]+)"', repl_key, stripped)
            out_lines.append(stripped)
        elif stripped.startswith("#") or stripped == "":
            out_lines.append(line)
        else:
            abs_url = urllib.parse.urljoin(base, stripped)
            if ".m3u8" in abs_url.lower():
                proxied = "/proxy/m3u8?url=" + urllib.parse.quote(abs_url, safe="")
            else:
                proxied = "/proxy/segment?url=" + urllib.parse.quote(abs_url, safe="")
            out_lines.append(proxied)

    body = "\n".join(out_lines)
    headers = {
        "Content-Type": "application/vnd.apple.mpegurl",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-cache",
    }
    return web.Response(text=body, headers=headers)


async def proxy_segment(request):
    remote_url = request.query.get("url")
    if not remote_url:
        return web.Response(text="Keine URL", status=400)

    req_headers = build_upstream_headers(remote_url, request.headers.get("Range"))
    session = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=30))
    try:
        resp = await session.get(remote_url, headers=req_headers)
    except Exception as e:
        await session.close()
        return web.Response(text=f"Fehler: {e}", status=500)

    status = resp.status if resp.status in (200, 206) else 200
    out_headers = {
        "Access-Control-Allow-Origin": "*",
        "Content-Type": resp.headers.get("Content-Type", "video/mp2t"),
    }
    for h in ("Content-Length", "Content-Range", "Accept-Ranges"):
        if h in resp.headers:
            out_headers[h] = resp.headers[h]

    response = web.StreamResponse(status=status, headers=out_headers)
    await response.prepare(request)
    try:
        async for chunk in resp.content.iter_chunked(64 * 1024):
            await response.write(chunk)
    except Exception:
        pass
    finally:
        resp.close()
        await session.close()
    return response


async def api_playlist_m3u(request):
    """Liefert die zuletzt geladene LiveTV-Liste nochmal als M3U (fuer Debug/Export)."""
    if not LAST_M3U_URL:
        return web.Response(text="#EXTM3U\n", content_type="audio/x-mpegurl")
    try:
        timeout = aiohttp.ClientTimeout(total=15)
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(LAST_M3U_URL, headers=build_upstream_headers(LAST_M3U_URL)) as resp:
                text = await resp.text(errors="ignore")
    except Exception as e:
        return web.Response(text=f"#EXTM3U\n# Fehler: {e}\n", content_type="audio/x-mpegurl")
    return web.Response(text=text, content_type="audio/x-mpegurl")


# =========================
# TELEGRAM LOGIN (in-app, ohne dass der Nutzer je einen session_string sieht)
# =========================
def _persist_session_string(session_string):
    """Schreibt den frisch erzeugten session_string dauerhaft in die config.json,
    damit der Login-Flow beim naechsten App-Start nicht erneut durchlaufen werden muss."""
    if not CONFIG_PATH:
        return
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        config["session_string"] = session_string
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        log("OK", "session_string in config.json gespeichert")
    except Exception as e:
        log("WARNUNG", f"Konnte session_string nicht speichern: {e}")


async def _bring_telegram_client_online(new_client):
    """Startet einen fertig konfigurierten (aber noch nicht gestarteten) Pyrogram-Client,
    baut den Peer-Cache auf und aktiviert ihn als globalen 'client'. Wird sowohl beim
    normalen Start (session_string schon in config.json) als auch nach einem frischen
    In-App-Login (Telefonnummer/Code) verwendet."""
    global client, TELEGRAM_STATUS
    try:
        await new_client.start()
        client = new_client
        me = await client.get_me()
        TELEGRAM_STATUS["connected"] = True
        TELEGRAM_STATUS["error"] = None
        TELEGRAM_STATUS["needs_login"] = False
        log("OK", f"Telegram: eingeloggt als {me.first_name}")

        # WICHTIG bei no_updates=True: Pyrogram kennt den access_hash eines Chats nur,
        # wenn es ihn schon einmal in einer Dialogliste gesehen hat - sonst schlaegt
        # selbst get_chat(id) mit "Peer id invalid" fehl. Deshalb hier einmal alle
        # Dialoge durchgehen, das baut den Peer-Cache fuer jeden Kanal auf.
        try:
            dialog_count = 0
            async for _ in client.get_dialogs():
                dialog_count += 1
            log("OK", f"{dialog_count} Dialoge geladen (Peer-Cache aufgebaut)")
        except Exception as e:
            log("WARNUNG", f"Dialoge konnten nicht vollstaendig geladen werden: {e}")

        for name, chat_id, _ in flatten_channels(CHANNELS):
            try:
                await client.get_chat(chat_id)
                log("OK", f"Kanal aufgeloest: {name}")
            except Exception as e:
                log("WARNUNG", f"Kanal '{name}' (ID {chat_id}) konnte nicht aufgeloest werden: {e}")
    except Exception as e:
        TELEGRAM_STATUS["connected"] = False
        TELEGRAM_STATUS["error"] = f"{type(e).__name__}: {e}"
        log("FEHLER", f"Telegram-Login fehlgeschlagen: {TELEGRAM_STATUS['error']}")


async def _finalize_login():
    """Nach erfolgreichem sign_in()/check_password(): session_string aus dem
    Login-Client ziehen, dauerhaft speichern und den echten Client damit hochfahren."""
    global client, LOGIN_CLIENT, LOGIN_PHONE_NUMBER, LOGIN_PHONE_CODE_HASH
    session_string = await LOGIN_CLIENT.export_session_string()
    try:
        await LOGIN_CLIENT.disconnect()
    except Exception:
        pass
    LOGIN_CLIENT = None
    LOGIN_PHONE_NUMBER = None
    LOGIN_PHONE_CODE_HASH = None
    _persist_session_string(session_string)

    from pyrogram import Client
    new_client = Client(
        name="pyrogram_session", api_id=API_ID, api_hash=API_HASH,
        session_string=session_string, no_updates=True,
        workdir=os.path.dirname(CONFIG_PATH),
    )
    await _bring_telegram_client_online(new_client)


async def api_telegram_login_start(request):
    """Schritt 1: Telefonnummer -> Telegram schickt einen Code per SMS/App."""
    global LOGIN_CLIENT, LOGIN_PHONE_NUMBER, LOGIN_PHONE_CODE_HASH
    phone = (request.query.get("phone") or "").strip()
    if not phone:
        return json_response_cors({"error": "phone fehlt"}, status=400)
    if not API_ID or not API_HASH:
        return json_response_cors({"error": "api_id/api_hash fehlen in der config.json"}, status=400)

    from pyrogram import Client
    try:
        if LOGIN_CLIENT:
            try:
                await LOGIN_CLIENT.disconnect()
            except Exception:
                pass
        LOGIN_CLIENT = Client(
            name="pyrogram_login", api_id=API_ID, api_hash=API_HASH,
            in_memory=True, workdir=os.path.dirname(CONFIG_PATH),
        )
        await LOGIN_CLIENT.connect()
        sent = await LOGIN_CLIENT.send_code(phone)
        LOGIN_PHONE_NUMBER = phone
        LOGIN_PHONE_CODE_HASH = sent.phone_code_hash
        return json_response_cors({"status": "code_sent"})
    except Exception as e:
        log("FEHLER", f"Telegram-Login (send_code) fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)


async def api_telegram_login_code(request):
    """Schritt 2: den per SMS/App erhaltenen Code bestaetigen. Falls das Konto 2FA
    aktiviert hat, antwortet Telegram mit SessionPasswordNeeded - dann muss zusaetzlich
    noch api_telegram_login_password aufgerufen werden."""
    from pyrogram.errors import SessionPasswordNeeded, PhoneCodeInvalid, PhoneCodeExpired

    if not LOGIN_CLIENT or not LOGIN_PHONE_CODE_HASH:
        return json_response_cors({"error": "Kein Login gestartet - zuerst Telefonnummer senden"}, status=400)

    code = (request.query.get("code") or "").strip()
    if not code:
        return json_response_cors({"error": "code fehlt"}, status=400)

    try:
        await LOGIN_CLIENT.sign_in(LOGIN_PHONE_NUMBER, LOGIN_PHONE_CODE_HASH, code)
    except SessionPasswordNeeded:
        return json_response_cors({"status": "need_password"})
    except (PhoneCodeInvalid, PhoneCodeExpired) as e:
        return json_response_cors({"error": f"Code ungueltig/abgelaufen: {e}"}, status=400)
    except Exception as e:
        log("FEHLER", f"Telegram-Login (sign_in) fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=500)

    await _finalize_login()
    if TELEGRAM_STATUS["connected"]:
        return json_response_cors({"status": "ok"})
    return json_response_cors({"error": TELEGRAM_STATUS["error"] or "Aktivierung fehlgeschlagen"}, status=500)


async def api_telegram_login_password(request):
    """Schritt 3 (nur bei aktivierter 2FA): Cloud-Passwort bestaetigen."""
    if not LOGIN_CLIENT:
        return json_response_cors({"error": "Kein Login gestartet"}, status=400)

    password = request.query.get("password") or ""
    if not password:
        return json_response_cors({"error": "password fehlt"}, status=400)

    try:
        await LOGIN_CLIENT.check_password(password)
    except Exception as e:
        log("FEHLER", f"Telegram-Login (2FA) fehlgeschlagen: {e}")
        return json_response_cors({"error": str(e)}, status=400)

    await _finalize_login()
    if TELEGRAM_STATUS["connected"]:
        return json_response_cors({"status": "ok"})
    return json_response_cors({"error": TELEGRAM_STATUS["error"] or "Aktivierung fehlgeschlagen"}, status=500)


async def api_telegram_dialogs(request):
    """Listet alle Kanaele/Supergruppen des gerade eingeloggten Accounts auf, damit der
    Nutzer sie in der App antippen statt IDs von Hand raussuchen/eintragen zu muessen."""
    if client is None:
        return json_response_cors({"error": "Noch nicht eingeloggt"}, status=400)
    try:
        results = []
        async for dialog in client.get_dialogs(limit=300):
            chat = dialog.chat
            if chat.type.name in ("CHANNEL", "SUPERGROUP", "GROUP"):
                results.append({"name": chat.title or str(chat.id), "id": chat.id})
        results.sort(key=lambda c: c["name"].lower())
        return json_response_cors({"dialogs": results})
    except Exception as e:
        log("FEHLER", f"Dialogliste konnte nicht geladen werden: {e}")
        return json_response_cors({"error": str(e)}, status=500)


async def api_telegram_save_channels(request):
    """Speichert die vom Nutzer angetippte Kanalauswahl (Name -> ID) dauerhaft in der
    config.json, genau wie der session_string nach dem Login automatisch eingetragen wird."""
    global CHANNELS
    raw = request.query.get("selected") or ""
    try:
        selected = json.loads(raw)
        if not isinstance(selected, dict) or not selected:
            raise ValueError("Auswahl ist leer oder kein Objekt")
        selected = {str(name): int(chat_id) for name, chat_id in selected.items()}
    except Exception as e:
        return json_response_cors({"error": f"Ungueltige Auswahl: {e}"}, status=400)

    CHANNELS = selected
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        config["channels"] = selected
        with open(CONFIG_PATH, "w", encoding="utf-8") as f:
            json.dump(config, f, ensure_ascii=False, indent=2)
        log("OK", f"{len(selected)} Kanaele in config.json gespeichert")
    except Exception as e:
        log("WARNUNG", f"Konnte Kanalauswahl nicht speichern: {e}")
        return json_response_cors({"error": str(e)}, status=500)

    if client is not None:
        for name, chat_id in selected.items():
            try:
                await client.get_chat(chat_id)
            except Exception as e:
                log("WARNUNG", f"Kanal '{name}' (ID {chat_id}) konnte nicht aufgeloest werden: {e}")

    return json_response_cors({"status": "ok"})


# =========================
# STARTUP (wird aus Kotlin per Chaquopy aufgerufen)
# =========================
def start_server(config_path):
    """Blockierender Einstiegspunkt - MUSS in einem eigenen Hintergrund-Thread aufgerufen werden."""
    global CHANNELS, client, TMDB_API_KEY, STALKER_URL, STALKER_MAC, stalker_portal, CONFIG_PATH
    global API_ID, API_HASH
    CONFIG_PATH = config_path

    # Pyrogram schreibt bei unbekannten Fehlercodes intern Debug-Infos in eine
    # relative Datei ("unknown_errors.txt") im aktuellen Arbeitsverzeichnis.
    # Ohne das hier scheitert dieser Schreibversuch auf Android (read-only cwd)
    # und VERDECKT den eigentlichen Fehler dahinter.
    os.chdir(os.path.dirname(config_path))

    # Chaquopy/Android liefert kein Standard-CA-Zertifikatsbuendel fuer Pythons
    # ssl-Modul mit - ohne das hier scheitert JEDE TLS-Verbindung (Telegram, aiohttp).
    try:
        import certifi
        os.environ["SSL_CERT_FILE"] = certifi.where()
        os.environ["REQUESTS_CA_BUNDLE"] = certifi.where()
        log("OK", f"CA-Zertifikate gesetzt: {certifi.where()}")
    except Exception as e:
        log("WARNUNG", f"certifi nicht verfuegbar: {e}")

    with open(config_path, "r", encoding="utf-8") as f:
        config = json.load(f)
    CHANNELS = config.get("channels") or {}
    API_ID = int(config["api_id"])
    API_HASH = config["api_hash"]
    TMDB_API_KEY = config.get("tmdb_api_key") or None
    STALKER_URL = config.get("stalker_url") or None
    STALKER_MAC = config.get("stalker_mac") or None
    if STALKER_URL and STALKER_MAC:
        stalker_portal = AsyncStalkerPortal(STALKER_URL, STALKER_MAC)

    # session_string ist jetzt OPTIONAL in der config.json - fehlt er, wartet der Server
    # auf den In-App-Login (Telefonnummer/Code, siehe api_telegram_login_*) und traegt
    # den erzeugten session_string danach selbst dauerhaft in die config.json ein.
    existing_session_string = config.get("session_string") or None
    TELEGRAM_STATUS["needs_login"] = existing_session_string is None

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    # WICHTIG: pyrogram erst NACH dem Setzen der Event-Loop importieren.
    # pyrogram.sync ruft beim Import asyncio.get_event_loop() auf - in einem
    # frischen Hintergrund-Thread (wie diesem) existiert vorher noch keine,
    # was sofort mit "There is no current event loop in thread" abstuerzt.
    from pyrogram import Client

    async def _connect_telegram_existing():
        """Normalfall: session_string steht schon in der config.json (Login bereits
        beim letzten Start durchgefuehrt) - direkt verbinden, kein Login-Flow noetig."""
        existing_client = Client(
            name="pyrogram_session", api_id=API_ID, api_hash=API_HASH,
            session_string=existing_session_string, no_updates=True,
            workdir=os.path.dirname(config_path),
        )
        await _bring_telegram_client_online(existing_client)

    async def _connect_stalker():
        if not stalker_portal:
            STALKER_STATUS["connected"] = False
            STALKER_STATUS["error"] = "Kein stalker_url/stalker_mac in config.json"
            return
        try:
            async with aiohttp.ClientSession() as session:
                await stalker_portal.handshake(session)
                await stalker_portal.get_profile(session)
            STALKER_STATUS["connected"] = True
            STALKER_STATUS["error"] = None
            log("OK", f"Stalker-Portal verbunden: {stalker_portal.portal_url}")
        except Exception as e:
            STALKER_STATUS["connected"] = False
            STALKER_STATUS["error"] = f"{type(e).__name__}: {e}"
            log("FEHLER", f"Stalker-Verbindung fehlgeschlagen: {STALKER_STATUS['error']}")

    async def _main():
        # Webserver zuerst starten - LiveTV (und die Diagnose ueber /api/status)
        # funktionieren so auch, wenn der Telegram-Login scheitert/haengt.
        app = web.Application()
        app.router.add_get("/api/status", api_status)
        app.router.add_get("/api/telegram/login/start", api_telegram_login_start)
        app.router.add_get("/api/telegram/login/code", api_telegram_login_code)
        app.router.add_get("/api/telegram/login/password", api_telegram_login_password)
        app.router.add_get("/api/telegram/login/dialogs", api_telegram_dialogs)
        app.router.add_get("/api/telegram/login/channels/save", api_telegram_save_channels)
        app.router.add_get("/api/channels", api_get_channels)
        app.router.add_get("/api/topics", api_get_topics)
        app.router.add_get("/api/movies", api_get_movies)
        app.router.add_get("/api/mediathek", api_get_mediathek)
        app.router.add_get("/api/stalker/status", api_stalker_status)
        app.router.add_get("/api/stalker/connect", api_stalker_connect)
        app.router.add_get("/api/stalker/connect_slot", api_stalker_connect_slot)
        app.router.add_get("/api/stalker/profiles", api_stalker_profiles)
        app.router.add_get("/api/stalker/categories", api_stalker_categories)
        app.router.add_get("/api/stalker/items", api_stalker_items)
        app.router.add_get("/api/stalker/epg", api_stalker_epg)
        app.router.add_get("/api/stalker/seasons", api_stalker_seasons)
        app.router.add_get("/api/stalker/episodes", api_stalker_episodes)
        app.router.add_get("/api/stalker/play", api_stalker_play)
        app.router.add_get("/api/m3u", api_load_m3u)
        app.router.add_get("/playlist.m3u", api_playlist_m3u)
        app.router.add_get("/proxy/m3u8", proxy_m3u8)
        app.router.add_get("/proxy/segment", proxy_segment)
        app.router.add_get("/stream/{channel}/{msg}.mp4", stream_handler_path)

        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", PORT)
        await site.start()
        log("BEREIT", f"Backend laeuft auf http://127.0.0.1:{PORT}")

        # Telegram-Login parallel im Hintergrund, blockiert den Server nicht mehr.
        # Ohne session_string in der config.json wird hier NICHT automatisch verbunden -
        # das passiert erst, wenn der Nutzer den In-App-Login (Telefonnummer/Code)
        # ueber /api/telegram/login/* durchlaeuft.
        if existing_session_string:
            asyncio.create_task(_connect_telegram_existing())
        else:
            log("INFO", "Kein session_string in config.json - warte auf In-App-Login")
        asyncio.create_task(_connect_stalker())

        await asyncio.Event().wait()

    loop.run_until_complete(_main())
