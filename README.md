# AndroidFotobox
Eine Android-App, die ein Smartphone oder Tablet in eine Fotobox verwandelt.

## Funktionen

* Live-Vorschau mit CameraX und Unterstützung für Front- und Rückkamera.
* Auslösen per Bluetooth-Fernauslöser (interpretiert Lautstärketasten), Fingertipp mit konfigurierbarem Countdown oder Sprachsteuerung ("Cheese", "Foto", "Photo").
* Einstellbare Fotoauflösung (nativ oder vordefinierte Megapixel-Werte).
* Speichern der Aufnahmen im öffentlichen *Pictures/Fotobox*-Verzeichnis.
* Steuerung einer Canon EOS 1200D über USB inkl. Auslösung direkt aus der App.

## Anforderungen

* Android Studio Iguana (oder neuer) bzw. Gradle 8.5+.
* Android-Gerät mit Android 8.0 (API 26) oder höher.
* Kamera- und Mikrofon-Berechtigungen.
* USB-Host-Unterstützung (OTG) und USB-Verbindung zur Canon EOS 1200D.

## Projekt bauen

Das Projekt bringt den Gradle Wrapper mit. Du kannst Builds direkt mit folgendem Befehl starten:

```bash
./gradlew assembleDebug
```

Alternativ lässt sich das Projekt wie gewohnt in Android Studio importieren und über die Oberfläche starten.

## Projekt archivieren

Für einen direkten Download ohne Upload nach GitHub kannst du jederzeit ein aktuelles Archiv lokal erzeugen. Das Repository bringt dafür ein Skript mit, das ein ZIP in den Ordner `dist/` schreibt:

```bash
./scripts/package_project.sh
```

Im Anschluss findest du z. B. `dist/AndroidFotobox-20240523-153000.zip`, das du ohne weitere Nachbearbeitung weitergeben oder herunterladen kannst. Das Archiv enthält sämtliche Projektdaten (inklusive Gradle-Wrapper) und lässt sich direkt in Android Studio importieren.
