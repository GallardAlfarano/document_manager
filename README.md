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
        2026-06-10 Kontoauszug.pdf
```

Alle Dateiinhalte werden unverändert kopiert. Bei Namenskollisionen ergänzt die Anwendung eine zufällige Kennung vor der Dateiendung. Originale werden niemals automatisch überschrieben. Datum, Haupttag und Absender sind Pflicht und bestimmen den Pfad. Dokumententyp ist ebenfalls Pflicht, verändert aber den Pfad nicht.

Eigenen Speicherort wählen (empfohlen: absoluter Pfad):

```sh
java -jar target/dokumentenablage-1.0.0.jar --app.data=/pfad/zur/ablage
```

Alternativ Umgebungsvariable `ABLAGE_DATA` setzen. Unter Windows beispielsweise `--app.data=C:/Dokumentenablage` nutzen. Datenbank und Originale liegen bewusst zusammen unter diesem Verzeichnis. Arbeitsverzeichnis oder Datenpfad später nicht unabsichtlich wechseln, sonst öffnet sich ein neuer leerer Katalog.

## Bedienung

- **Hinzufügen:** Eine oder mehrere Dateien per Drag-and-drop oder Dateiauswahl. Metadaten gelten für alle Dateien eines Uploads. Bei unterschiedlichen Metadaten getrennt hochladen. Teilweise erfolgreiche Mehrfachuploads werden mit Anzahl und Fehlern je Datei angezeigt; erfolgreiche Dateien nicht erneut hochladen.
- **Tags:** Genau ein Haupttag und Dokumententyp, beliebig viele optionale Tags. Eingabefelder schlagen vorhandene Werte vor; neue Werte können direkt eingegeben werden. Optionale Tags mit Semikolon trennen oder vorhandene Tags anklicken. Unter „Tags verwalten“ lassen sich Begriffe vorab anlegen und auflisten. Umbenennen/Zusammenführen/Löschen von Begriffen ist in dieser Version nicht enthalten; die Zuordnung pro Dokument lässt sich bearbeiten.
- **Suche:** Freitext über Dateiname, Absender und Tags. Zeitraum `2025`, `2025-06` oder `2025-06-10`. Alle gesetzten Filter und optionalen Tags werden mit UND kombiniert. Beispiel: Zeitraum `2025`, Dokumententyp `Rechnung`, zusätzlicher Tag `Relevant für Steuererklärung`.
- **Bearbeiten:** Pfadwirksame Änderungen werden vor dem Absenden bestätigt. Abbrechen lässt das Formular unverändert offen. Ohne JavaScript blockiert das Backend eine unbestätigte Pfadänderung. Bei einem belegten Zielpfad bleibt die vorhandene Datei erhalten und es erscheint ein Fehler.
- **Original:** Lädt die Originaldatei herunter. Beliebige Dateitypen sind erlaubt; es gibt weder ZIP-Entpacken noch Ausführung hochgeladener Inhalte. Grenze: 100 MB pro Datei und 500 MB je Anfrage.
- **Dateiabgleich:** Meldet fehlende Originale und unbekannte Dateien, ohne etwas zu verändern. Unbekannte Dateien können per Upload als Kopie aufgenommen werden; die alte unbekannte Kopie bleibt zur manuellen Prüfung erhalten. Kein automatischer Ordnerimport. Symlinks werden nicht als Dokumentpfade akzeptiert.

Namen sind auf 120 Zeichen begrenzt und dürfen keine Windows-/Unix-Pfadsonderzeichen enthalten. Groß-/Kleinschreibung und Unicode-Normalisierung werden bei der Wiederverwendung von Tags berücksichtigt. Inhaltssuche/OCR, Benutzerkonten, Löschfunktionen und Heimnetzbetrieb sind nicht Bestandteil dieser Version.

## Sicherung und Konsistenz

Anwendung beenden und den gesamten Datenordner sichern: **SQLite-Datenbank und `originale` gemeinsam**. Eine reine Datenbanksicherung enthält keine Originaldateien. Die Anwendung kompensiert Datenbankfehler bei Upload und Umbenennung durch Entfernen der neu kopierten Datei bzw. Rückverschieben des Originals. Dateisystem und SQLite besitzen jedoch keine gemeinsame atomare Transaktion: nach Stromausfall oder Prozessabbruch den Dateiabgleich ausführen. Dateien während des Betriebs möglichst nicht mit externen Programmen verschieben. Der Abgleich prüft Vorhandensein, keine Prüfsummen oder inhaltliche Änderungen.

Nur eine laufende Anwendungsinstanz pro Datenordner verwenden. Der Server bindet ausschließlich `127.0.0.1`; es gibt keinen Login. CSRF-Schutz bleibt aktiv. Für eine spätere Öffnung ins Heimnetz sind Authentifizierung, HTTPS und eine Überprüfung der Betriebs-/Sicherheitskonfiguration erforderlich.

## Projektaufbau

- `Application.java`: Start, Datenquelle, lokale Web-Sicherheit
- `Catalog.java`: SQLite-Katalog, Tag-Normalisierung, Suche, Dateioperationen und Abgleich
- `Web.java`: Browser-Routen und Formulare
- `src/main/resources/templates`: serverseitige HTML-Oberfläche
- `src/main/resources/static`: CSS und kleines JavaScript für Drag-and-drop/Bestätigung
- `src/test/java`: Dateiintegrität, Kollisionen, Rückabwicklung, Suche, CSRF und HTTP-Abläufe

Die Datenbanktabellen werden beim ersten Start angelegt. Für spätere Schemaänderungen sollte eine versionierte Migration ergänzt werden.

## Validierungsstand dieser Übergabe

Java-21-Syntax aller fünf Java-Dateien, JavaScript-Syntax, Maven-XML und einfache HTML-Strukturprüfungen wurden geprüft. Die 18 JUnit-Testfälle sind vorhanden, konnten hier aber nicht ausgeführt werden: Maven fehlt in der bereitgestellten Umgebung und der konfigurierte Netzwerk-Proxy ist nicht erreichbar, sodass die Bibliotheken nicht geladen werden können. Ein vollständiger Build, die Typprüfung mit Spring-Abhängigkeiten sowie Start und Browserablauf sind daher noch nicht verifiziert. Bitte zuerst `mvn clean verify` ausführen.
