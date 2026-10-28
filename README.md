# Meine Ablage

Lokale Dokumentenverwaltung mit Java 21, Spring Boot, Thymeleaf, Maven und SQLite. Keine Cloud, keine Frontend-Build-Werkzeuge, keine extern geladenen Schriftarten oder Skripte.

## Starten

Voraussetzungen: JDK 21 und Maven 3.9 oder neuer. Beim ersten Build benötigt Maven Internetzugang für die Bibliotheken.

```sh
mvn clean verify
mvn spring-boot:run
```

Im Browser **http://127.0.0.1:8080** öffnen. Beenden mit Strg+C. Alternativ nach dem Build:

```sh
java -jar target/dokumentenablage-1.0.0.jar
```

IntelliJ: `pom.xml` als Projekt öffnen, Projekt-SDK auf Java 21 setzen, Maven-Projekt laden und `de.privat.ablage.Application` starten. Unter `src/test/java` liegen Service- und HTTP-Integrationstests; `mvn verify` führt beide aus.

## Speicherort

Standardmäßig entsteht `data` im Arbeitsverzeichnis:

```text
data/
  catalog.sqlite
  originale/
    Finanzen/
      Sparkasse/
        2026-06-10_Kontoauszug.pdf
```

Alle Dateiinhalte werden unverändert kopiert. Bei Namenskollisionen mit unterschiedlichem Inhalt ergänzt die Anwendung eine zufällige Kennung vor der Dateiendung. Inhaltlich identische Dateien werden anhand ihres SHA-256-Hashes erkannt und nicht erneut gespeichert. Originale werden niemals automatisch überschrieben. Haupttag und Absender sind Pflicht und bestimmen den Ordnerpfad. Das Dokumentdatum ist optional. Mit Datum hat der gespeicherte Dateiname das Schema `JJJJ-MM-TT_Dateiname.ext`, ohne Datum lautet er nur `Dateiname.ext`. Dokumententyp ist ebenfalls Pflicht, verändert aber den Pfad nicht.

Eigenen Speicherort wählen (empfohlen: absoluter Pfad):

```sh
java -jar target/dokumentenablage-1.0.0.jar --app.data=/pfad/zur/ablage
```

Alternativ Umgebungsvariable `ABLAGE_DATA` setzen. Unter Windows beispielsweise `--app.data=C:/Dokumentenablage` nutzen. Datenbank und Originale liegen bewusst zusammen unter diesem Verzeichnis. Arbeitsverzeichnis oder Datenpfad später nicht unabsichtlich wechseln, sonst öffnet sich ein neuer leerer Katalog.

## Bedienung

Die Startseite zeigt höchstens 30 Dokumente pro Seite, standardmäßig nach Erfassungszeitpunkt absteigend (neueste zuerst). „Ältere Einträge“ und „Neuere Einträge“ blättern durch alle Treffer und behalten die gesetzten Filter bei. Eine neue Suche beginnt wieder auf Seite 1.

Der unveränderliche Erfassungszeitpunkt wird automatisch beim Anlegen des Katalogeintrags gesetzt und in der Liste sowie auf der Dokumentseite angezeigt. Er beeinflusst weder Dokumentdatum noch Dateipfad. Speicherung als UTC-Zeitpunkt, Anzeige und Kalenderfilter in `Europe/Berlin` mit Sommer-/Winterzeit. Über „Erfasst am“ kann nach Jahr, Monat oder Tag gefiltert werden, unabhängig vom Dokumentdatum; auch die Textsuche berücksichtigt den angezeigten Erfassungszeitpunkt. Bearbeiten, Umbenennen und ein erneuter Upload desselben Inhalts verändern ihn nicht.

- **Hinzufügen:** Das Datum kann leer bleiben und wird nicht automatisch mit dem heutigen Tag vorbelegt. Eine oder mehrere Dateien per Drag-and-drop oder Dateiauswahl. Metadaten gelten für alle Dateien eines Uploads. Bei unterschiedlichen Metadaten getrennt hochladen. Teilweise erfolgreiche Mehrfachuploads werden mit Anzahl und Fehlern je Datei angezeigt; erfolgreiche Dateien nicht erneut hochladen.
- **Dublettenprüfung:** Für jedes neu hochgeladene Dokument wird der SHA-256-Hash des vollständigen Dateiinhalts in `documents.sha256` gespeichert. Existiert dieser Hash bereits im Katalog, erscheint nur eine Meldung mit Link zum vorhandenen Dokument. Name, Datum, Absender und Tags spielen bei dieser Prüfung keine Rolle und werden am vorhandenen Dokument nicht verändert. Bei gemischten Mehrfach-Uploads werden neue Dateien gespeichert und Dubletten einzeln verlinkt. Die Prüfung erkennt identische Bytes, keine nur optisch gleichen PDFs/Bilder mit unterschiedlichen Metadaten oder Dateiinhalten. Nach dem Entfernen eines Katalogeintrags ist der Inhalt wieder hochladbar; unregistrierte Dateien werden nicht für diese Prüfung gescannt.
- **Kommentare:** Beim Hinzufügen und Bearbeiten steht ein mehrzeiliges Kommentarfeld mit maximal 10000 Zeichen bereit. Zeilenumbrüche bleiben erhalten. Die normale Textsuche durchsucht Kommentare ohne Beachtung der Groß-/Kleinschreibung und lässt sich mit allen Filtern kombinieren. Kommentaränderungen beeinflussen weder Dateinamen, Ablagepfad, Hash noch Erfassungszeitpunkt und benötigen keine Dateisystembestätigung. Ein leeres Feld entfernt den Kommentar. Bei Mehrfach-Uploads gilt der Kommentar für alle neuen Dokumente; erkannte Dubletten behalten ihren bisherigen Kommentar.
- **Tags:** Genau ein Haupttag und Dokumententyp, beliebig viele optionale Tags. Eingabefelder schlagen vorhandene Werte vor; neue Werte können direkt eingegeben werden. Optionale Tags mit Semikolon trennen oder vorhandene Tags anklicken. Unter „Tags verwalten“ lassen sich Begriffe vorab anlegen und auflisten. Umbenennen/Zusammenführen/Löschen von Begriffen ist in dieser Version nicht enthalten; die Zuordnung pro Dokument lässt sich bearbeiten.
- **Suche:** Freitext über Dateiname, Absender und Tags. Zeitraum `2025`, `2025-06` oder `2025-06-10`. Mit „Nur Einträge ohne Datum“ werden ausschließlich undatierte Dokumente gefunden; der Zeitraumfilter wird dann deaktiviert bzw. ignoriert. Ohne diese Auswahl und ohne Zeitraum werden Dokumente mit und ohne Datum angezeigt. Ein Zeitraum berücksichtigt ausschließlich datierte Dokumente. Alle weiteren Filter und optionalen Tags werden mit UND kombiniert. Beispiel: Zeitraum `2025`, Dokumententyp `Rechnung`, zusätzlicher Tag `Relevant für Steuererklärung`.
- **Bearbeiten / Umbenennen:** Im Feld „Dateiname“ den gewünschten Namen mit Dateiendung, aber ohne Datumsprefix eingeben. Bei vorhandenem Datum wird der Datumsprefix automatisch ergänzt, z. B. `2025-10-27_Rechnung.pdf`. Das Datum kann auch später ergänzt oder entfernt werden; entsprechend wird der Prefix nach Bestätigung hinzugefügt oder entfernt. Dateiname, Datum, Absender und Haupttag sind pfadwirksam. Pfadwirksame Änderungen werden vor dem Absenden bestätigt. Abbrechen lässt das Formular unverändert offen. Ohne JavaScript blockiert das Backend eine unbestätigte Pfadänderung. Bei einem belegten Zielpfad bleibt die vorhandene Datei erhalten und es erscheint ein Fehler.
- **Löschen:** „Löschen“ öffnet zuerst eine Bestätigungsseite. Wähle, ob nur der Katalogeintrag oder auch die Originaldatei entfernt werden soll. „Originaldatei behalten“ ist vorausgewählt. Zusätzlich muss die Bestätigung angehakt werden. Es gibt keinen Papierkorb. Behaltene Originale erscheinen im Dateiabgleich als nicht katalogisiert; fehlende Originale verhindern das Entfernen eines Eintrags nicht. Entfernt werden nur das gewählte Dokument und seine Tag-Zuordnungen, nicht die gemeinsamen Tag-Begriffe oder andere Dokumente.
- **Bearbeitungsansicht:** Auf Desktop-Bildschirmen stehen Vorschau und Formular nebeneinander. Die Vorschau bleibt beim Scrollen sichtbar. Bis 1000 Pixel Fensterbreite werden beide untereinander angezeigt.
- **Original / Vorschau:** Die Dokumentseite enthält einen Downloadlink und zeigt PNG-, JPEG-, GIF-, WebP- und BMP-Bilder sowie PDFs direkt an. PDFs verwenden den eingebauten Browser-Viewer; falls dieser nicht verfügbar ist, stehen „In neuem Tab öffnen“ und der Download bereit. ZIP und andere Formate bleiben als Download verfügbar. Vorschauen werden anhand der Dateisignatur erkannt, nicht nur anhand der Dateiendung; HTML und SVG werden nicht inline angezeigt. Es werden keine externen Viewer oder zusätzlichen Bibliotheken verwendet. Der Download liefert weiterhin die unveränderte Originaldatei. Beliebige Dateitypen sind erlaubt; es gibt weder ZIP-Entpacken noch Ausführung hochgeladener Inhalte. Grenze: 100 MB pro Datei und 500 MB je Anfrage.
- **Dateiabgleich:** Meldet fehlende Originale und unbekannte Dateien, ohne etwas zu verändern. Unbekannte Dateien können per Upload als Kopie aufgenommen werden; die alte unbekannte Kopie bleibt zur manuellen Prüfung erhalten. Kein automatischer Ordnerimport. Symlinks werden nicht als Dokumentpfade akzeptiert.

Namen sind auf 120 Zeichen begrenzt und dürfen keine Windows-/Unix-Pfadsonderzeichen enthalten. Groß-/Kleinschreibung und Unicode-Normalisierung werden bei der Wiederverwendung von Tags berücksichtigt. Inhaltssuche/OCR, Benutzerkonten und Heimnetzbetrieb sind nicht Bestandteil dieser Version.

## Sicherung und Konsistenz

Anwendung beenden und den gesamten Datenordner sichern: **SQLite-Datenbank und `originale` gemeinsam**. Eine reine Datenbanksicherung enthält keine Originaldateien. Die Anwendung kompensiert Datenbankfehler bei Upload und Umbenennung durch Entfernen der neu kopierten Datei bzw. Rückverschieben des Originals. Beim Löschen wird die Originaldatei vorübergehend gesichert, um sie bei fehlgeschlagenen Datei-/Datenbankoperationen wiederherstellen zu können. Nach erfolgreichem Löschen wird diese temporäre Kopie entfernt; schlägt die Bereinigung fehl, meldet die Oberfläche ihren Pfad. Nach einem Abbruch können `.delete-backup-…tmp`-Dateien verbleiben, die der Dateiabgleich anzeigt. Dateisystem und SQLite besitzen jedoch keine gemeinsame atomare Transaktion: nach Stromausfall oder Prozessabbruch den Dateiabgleich ausführen. Dateien während des Betriebs möglichst nicht mit externen Programmen verschieben. Der Abgleich prüft Vorhandensein, keine Prüfsummen oder inhaltliche Änderungen.

Nur eine laufende Anwendungsinstanz pro Datenordner verwenden. Der Server bindet ausschließlich `127.0.0.1`; es gibt keinen Login. CSRF-Schutz bleibt aktiv. Für eine spätere Öffnung ins Heimnetz sind Authentifizierung, HTTPS und eine Überprüfung der Betriebs-/Sicherheitskonfiguration erforderlich.

## Projektaufbau

- `Application.java`: Start, Datenquelle, lokale Web-Sicherheit
- `Catalog.java`: SQLite-Katalog, Tag-Normalisierung, Suche, Dateioperationen und Abgleich
- `Web.java`: Browser-Routen und Formulare
- `src/main/resources/templates`: serverseitige HTML-Oberfläche
- `src/main/resources/static`: CSS und kleines JavaScript für Drag-and-drop/Bestätigung
- `src/test/java`: Dateiintegrität, Kollisionen, Rückabwicklung, Suche, CSRF und HTTP-Abläufe

Die Datenbanktabellen werden beim ersten Start angelegt. Die Hash-Migration ergänzt die Spalte `documents.sha256`, einen Suchindex und einen Trigger gegen neue Hash-Dubletten; sie ist wiederholbar und setzt SQLite `user_version` auf mindestens 1.

## Bestehende Ablagen aktualisieren

Die Kommentar-Migration ergänzt automatisch `comment TEXT NOT NULL DEFAULT ''` (Schema-Version mindestens 3). Bestehende Dokumente starten mit leerem Kommentar; Originaldateien und vorhandene Metadaten bleiben erhalten.

Die Timestamp-Migration ergänzt automatisch `created_at` sowie einen Index und einen Datenbanktrigger gegen nachträgliche Änderungen (Schema-Version mindestens 2). Bereits vorhandene Einträge behalten `NULL` als Erfassungszeitpunkt und werden als „Unbekannt (Bestand)“ angezeigt: ihre ursprüngliche Erfassungszeit lässt sich nicht zuverlässig rekonstruieren. Sie erscheinen hinter den neuen Einträgen, innerhalb des Bestands nach bisheriger Einfügereihenfolge absteigend. Ein Filter auf „Erfasst am“ berücksichtigt nur bekannte Erfassungszeitpunkte; über die Textsuche „Unbekannt (Bestand)“ sind alte Einträge gezielt auffindbar. Es wird kein Migrationsdatum als vermeintlicher Erfassungszeitpunkt eingetragen.

Anwendung beenden, Datenordner sichern, Projekt aktualisieren und neu starten. Ein fehlendes Dokumentdatum wird als leere Zeichenfolge im vorhandenen `date`-Feld gespeichert; für optionale Datumsangaben ist daher keine Tabellenänderung oder Umwandlung vorhandener Datumswerte nötig. Die Datenbank wird bei älteren Versionen automatisch um das Hash-Feld erweitert. Vorhandene, lesbare Originaldateien werden beim Start einmal vollständig gelesen; bei großen Ablagen kann dieser erste Start länger dauern. Bereits vorhandene Dubletten und ihre Einträge bleiben erhalten. Fehlende oder nicht lesbare Originaldateien bleiben zunächst ohne Hash und werden im Anwendungslog gemeldet; sie verhindern den Start nicht. Sobald solche Dateien wieder lesbar sind, wird ihr Hash beim nächsten Start oder vor dem nächsten Upload nachgetragen. Bereits gespeicherte Hashes bleiben auch bei fehlenden Originalen wirksam. Dateien nach dem Erfassen nicht extern inhaltlich verändern: der gespeicherte Hash wird nicht fortlaufend neu berechnet. Bestehende Dateien mit Leerzeichen nach dem Datum bleiben unverändert nutzbar. Eine reine Änderung der Tags oder des Dokumententyps ändert ihren Pfad nicht. Bei einer Umbenennung oder Änderung von Datum, Haupttag oder Absender wird das neue Schema mit Unterstrich verwendet, nach ausdrücklicher Bestätigung.

## Validierungsstand dieser Übergabe

Java-21-Syntax aller fünf Java-Dateien, JavaScript-Syntax und einfache HTML-Strukturprüfungen wurden lokal geprüft. Die erweiterten Tests decken unter anderem Umbenennen, alte Dateipfade, Namenskollisionen, beide Löschvarianten, fehlende Originale, explizite Bestätigung, CSRF und Rückabwicklung von Datenbankfehlern, SHA-256-Speicherung, Migration bestehender Ablagen und Dublettenmeldungen mit Links ab.

Ein vollständiger Maven-Build ist in der bereitgestellten Entwicklungsumgebung weiterhin nicht möglich, weil Maven fehlt und der konfigurierte Netzwerk-Proxy nicht erreichbar ist. Der Workflow `.github/workflows/verify.yml` führt `mvn clean verify` mit Java 21 unter Linux und Windows aus. Den tatsächlichen Laufstatus findest du im GitHub-Reiter **Actions**. Lokal kannst du die Tests über das Maven-Fenster in IntelliJ ausführen.
