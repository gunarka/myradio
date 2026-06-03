# RadioWidget

Ein Android-Homescreen-Widget zum Streamen von Internetradio. Stationen werden über die öffentliche [radio-browser.info](https://www.radio-browser.info)-API gesucht und lokal verwaltet.

---

## Features

- **Homescreen-Widget** (4 × 2 Zellen, skalierbar) mit bis zu 5 Sender-Schnellwahl-Slots pro Seite, blätterbar mit `‹` / `›`
- **Hintergrund-Streaming** über einen Foreground-Service mit Wake- und WiFi-Lock; läuft weiter, wenn die App aus dem Recents-Menü entfernt wird (`stopWithTask=false`)
- **Sendersuche** via radio-browser.info (round-robin DNS, nach Name)
- **Senderverwaltung** mit Drag-and-Drop-Sortierung und Löschfunktion; benutzerdefinierte Sender werden mit ★ markiert
- **Benachrichtigungssteuerung** (Zurück / Stop / Weiter) im Sperrbildschirm und in der Notification-Shade
- **Audio-Fokus-Handling** – automatische Pause bei eingehenden Anrufen, automatische Fortsetzung nach Freigabe
- **Watchdog** – erkennt stumm gestorbene Streams (bekanntes Samsung-Problem) und startet neu
- **Automatischer Neustart** nach Service-Beendigung durch das System (bis zu 10 Versuche, 5 s Abstand)
- **Batterieoptimierungs-Dialog** – fragt beim ersten Start einmalig nach der Ausnahme vom Batterie-Optimierer
- **Robuste Fehlerbehandlung** – korrupte SharedPreferences-Daten führen zu Fallback auf Standardsender statt Absturz

---

## Voraussetzungen

| Komponente | Version |
|---|---|
| Android SDK (Compile/Target) | API 36 |
| Minimale Android-Version | API 31 (Android 12) |
| Android Gradle Plugin | 9.1.1 |
| Java | 17 |

---

## Build & Installation

```bash
# Repository klonen
git clone https://github.com/gunarka/myradio.git
cd myradio/RadioWidget

# Debug-APK bauen
./gradlew assembleDebug

# Direkt auf verbundenem Gerät installieren
./gradlew installDebug
```

Die fertige APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

> **Hinweis:** Das Projekt verwendet AGP 9.1.1. Es wird empfohlen, es mit Android Studio Meerkat (2024.3.2) oder neuer zu öffnen.

---

## Projektstruktur

```
RadioWidget/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/de/radiowidget/
│       │   ├── BatteryOptimizationActivity.kt  # Einmaliger Batterie-Dialog (transparent)
│       │   ├── RadioBrowserApi.kt              # HTTP-Client für radio-browser.info
│       │   ├── RadioService.kt                 # Foreground-Service (Streaming)
│       │   ├── RadioWidgetProvider.kt          # AppWidgetProvider (Widget-UI)
│       │   ├── StationManagerActivity.kt       # Senderverwaltung + Suche (UI)
│       │   └── StationRepository.kt            # Persistenz (SharedPreferences / JSON)
│       └── res/
│           ├── drawable/                       # Widget-Hintergrund, Button-Styles
│           ├── layout/
│           │   ├── activity_station_manager.xml
│           │   ├── item_station.xml
│           │   └── widget_radio.xml
│           ├── values/
│           │   ├── strings.xml
│           │   └── themes.xml
│           └── xml/
│               └── radio_widget_info.xml
├── gradle/
│   └── libs.versions.toml                      # Version Catalog
├── gradle.properties
└── settings.gradle.kts
```

---

## Abhängigkeiten

| Bibliothek | Version | Zweck |
|---|---|---|
| `androidx.core:core-ktx` | 1.16.0 | Kotlin-Extensions |
| `androidx.appcompat:appcompat` | 1.7.0 | AppCompatActivity / AlertDialog |
| `androidx.media:media` | 1.7.0 | MediaStyle-Benachrichtigung |
| `androidx.recyclerview:recyclerview` | 1.3.2 | Senderliste in der Verwaltung |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.7 | `lifecycleScope` für Coroutinen |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.9.0 | Coroutinen |

---

## Berechtigungen

| Berechtigung | Grund |
|---|---|
| `INTERNET` | Streaming + API-Suche |
| `FOREGROUND_SERVICE` | Dauerhafter Streaming-Service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Service-Typ für Medienwiedergabe |
| `POST_NOTIFICATIONS` | Benachrichtigung mit Mediensteuerung |
| `WAKE_LOCK` | CPU wach halten während des Streamings |
| `ACCESS_WIFI_STATE` / `CHANGE_WIFI_STATE` | WiFi-Lock für stabile Verbindung |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Einmaliger Dialog für Batterieausnahme |

---

## Widget einrichten

1. App installieren
2. Homescreen lange gedrückt halten → **Widget hinzufügen**
3. *Radio Widget* auswählen und auf mindestens 4 × 2 Zellen platzieren
4. Beim ersten Abspielen erscheint ein **Batterieoptimierungs-Dialog** — „Nicht optimieren" wählen, um Stream-Unterbrechungen zu vermeiden
5. Über den ⚙-Button im Widget den **Sendermanager** öffnen
6. Im Tab **Suche** Sender finden und zur eigenen Liste hinzufügen
7. Die Sender erscheinen als Schnellwahl-Slots (je 5 pro Seite, mit `‹` / `›` blättern)

### Vorinstallierte Sender

| Name | Abk. | Genre |
|---|---|---|
| WDR 1Live | 1L | Pop / Rock |
| WDR 2 | W2 | Pop |
| WDR 3 | W3 | Klassik |
| Deutschlandfunk | DLF | Info |
| SWR3 | SWR | Pop |
| Energy NRW | ENY | Dance / EDM |
| Antenne 1 | A1 | Pop / Hits |
| NDR Info | NDR | News |

---

## Bekannte Einschränkungen

- Nur HTTP/HTTPS-Streams werden unterstützt (kein HLS, kein RTSP)
- Auf manchen Samsung-Geräten kann der Service trotz Batterieausnahme eingeschränkt werden → zusätzlich in den App-Einstellungen unter **Akku → „Nicht eingeschränkt"** setzen (der Dialog weist darauf hin)
- Kein EQ oder Lautstärkeregler – Systemlautstärke wird verwendet

---

## Design

Das Widget und der Sendermanager verwenden ein dunkles Theme mit Neon-Gelb-Grün als Akzentfarbe:

- Hintergrund: `#1A1A1A` / `#0F0F0F`
- Akzentfarbe: `#E8FF5A`

---

## Mitwirken

Pull Requests sind willkommen. Bitte vor größeren Änderungen zuerst ein Issue öffnen.

```bash
git checkout -b feature/mein-feature
git commit -m "feat: kurze Beschreibung"
git push origin feature/mein-feature
```

Code-Stil: offizielle Kotlin Coding Conventions; kein `!!`-Operator ohne Kommentar.

---

## Lizenz

Dieses Projekt steht unter der [MIT-Lizenz](LICENSE).
