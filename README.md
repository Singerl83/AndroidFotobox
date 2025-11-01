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

Dieses Repository enthält keinen Gradle Wrapper. Verwende eine lokale Gradle-Installation (z. B. `gradle 8.5`) und führe im Projektverzeichnis aus:

```bash
gradle assembleDebug
```

Zum Starten in Android Studio das Projekt als bestehendes Android-Projekt importieren.
