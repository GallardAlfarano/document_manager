package de.privat.ablage;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.*;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.text.Normalizer;
import java.util.*;

@Service
public class Catalog {
    public record Doc(String id, String date, String sender, String main, String type,
                      String filename, String path, List<String> tags, String sha256, Instant createdAt, String comment, String subcategory) {
        public String createdAtLabel() {
            return createdAt == null ? "Unbekannt (Bestand)" : CAPTURE_LABEL.format(createdAt);
        }
    }

    public record SearchPage(List<Doc> docs, int total, int page, int pageCount) {
    }

    public static final int PAGE_SIZE = 30;
    private static final ZoneId CAPTURE_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter CAPTURE_FORMAT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final DateTimeFormatter CAPTURE_LABEL = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z", Locale.GERMANY).withZone(CAPTURE_ZONE);

    public record Metadata(String date, String sender, String main, String type, List<String> tags, String comment, String subcategory) {
        public Metadata(String date, String sender, String main, String type, List<String> tags) {
            this(date, sender, main, type, tags, null, null);
        }
        public Metadata(String date, String sender, String main, String type, List<String> tags, String comment) {
            this(date, sender, main, type, tags, comment, null);
        }
    }

    public record Subcategory(String main, String name) {
    }

    public record Check(List<String> missing, List<String> uncatalogued) {
    }

    public record DeleteResult(String retainedBackup) {
    }

    public static class DuplicateDocumentException extends IllegalArgumentException {
        private final Doc existing;

        public DuplicateDocumentException(Doc existing) {
            super("Die Datei existiert bereits: " + existing.filename());
            this.existing = existing;
        }

        public Doc existing() {
            return existing;
        }
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(Catalog.class);
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Path root;

    public Catalog(JdbcTemplate db, PlatformTransactionManager manager, @Value("${app.data}") String data) throws IOException {
        this.db = db;
        tx = new TransactionTemplate(manager);
        root = Path.of(data).toAbsolutePath().normalize().resolve("originale");
        Files.createDirectories(root);
        db.execute("PRAGMA foreign_keys=ON");
        db.execute("CREATE TABLE IF NOT EXISTS labels(kind TEXT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY(kind,key))");
        db.execute("CREATE TABLE IF NOT EXISTS documents(id TEXT PRIMARY KEY, date TEXT NOT NULL, sender TEXT NOT NULL, main TEXT NOT NULL, type TEXT NOT NULL, filename TEXT NOT NULL, path TEXT UNIQUE NOT NULL, sha256 TEXT)");
        db.execute("CREATE TABLE IF NOT EXISTS document_tags(document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, tag TEXT NOT NULL, PRIMARY KEY(document_id,tag))");
        migrateHashes();
        migrateCreatedAt();
        migrateComments();
        migrateSubcategories();
        backfillHashes();
        for (String s : List.of("Finanzen", "Wohnen", "Versicherungen", "Gesundheit")) label("main", s);
        for (String s : List.of("Rechnung", "Info", "Newsletter")) label("type", s);
        for (String s : List.of("Relevant für Steuererklärung", "Todo")) label("extra", s);
        label("sender", "Unbekannt");
    }

    private void migrateHashes() {
        tx.executeWithoutResult(status -> {
            boolean present = db.queryForList("PRAGMA table_info(documents)").stream().anyMatch(c -> "sha256".equals(c.get("name")));
            if (!present) db.execute("ALTER TABLE documents ADD COLUMN sha256 TEXT");
            // Nicht UNIQUE: Bereits früher angelegte Dubletten bleiben erhalten.
            db.execute("CREATE INDEX IF NOT EXISTS documents_sha256 ON documents(sha256)");
            // Neue Dubletten werden auch bei konkurrierenden INSERTs atomar verhindert.
            db.execute("CREATE TRIGGER IF NOT EXISTS documents_reject_duplicate_hash BEFORE INSERT ON documents WHEN NEW.sha256 IS NOT NULL AND EXISTS(SELECT 1 FROM documents WHERE sha256=NEW.sha256) BEGIN SELECT RAISE(ABORT, 'duplicate_sha256'); END");
            if (db.queryForObject("PRAGMA user_version", Integer.class) < 1) db.execute("PRAGMA user_version=1");
        });
    }

    private void migrateCreatedAt() {
        tx.executeWithoutResult(status -> {
            boolean present = db.queryForList("PRAGMA table_info(documents)").stream()
                    .anyMatch(c -> "created_at".equals(c.get("name")));
            // Keine erfundenen Erfassungszeiten für bereits vorhandene Einträge.
            if (!present) db.execute("ALTER TABLE documents ADD COLUMN created_at TEXT");
            db.execute("CREATE INDEX IF NOT EXISTS documents_created_at ON documents(created_at DESC)");
            db.execute("CREATE TRIGGER IF NOT EXISTS documents_created_at_immutable BEFORE UPDATE OF created_at ON documents WHEN NEW.created_at IS NOT OLD.created_at BEGIN SELECT RAISE(ABORT, 'created_at_immutable'); END");
            if (db.queryForObject("PRAGMA user_version", Integer.class) < 2) db.execute("PRAGMA user_version=2");
        });
    }

    private void migrateComments() {
        tx.executeWithoutResult(status -> {
            boolean present = db.queryForList("PRAGMA table_info(documents)").stream()
                    .anyMatch(c -> "comment".equals(c.get("name")));
            if (!present) db.execute("ALTER TABLE documents ADD COLUMN comment TEXT NOT NULL DEFAULT ''");
            if (db.queryForObject("PRAGMA user_version", Integer.class) < 3) db.execute("PRAGMA user_version=3");
        });
    }

    private void migrateSubcategories() {
        tx.executeWithoutResult(status -> {
            boolean present = db.queryForList("PRAGMA table_info(documents)").stream()
                    .anyMatch(c -> "subcategory".equals(c.get("name")));
            if (!present) db.execute("ALTER TABLE documents ADD COLUMN subcategory TEXT NOT NULL DEFAULT ''");
            db.execute("CREATE TABLE IF NOT EXISTS subcategories(main_key TEXT NOT NULL, main TEXT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY(main_key,key))");
            if (db.queryForObject("PRAGMA user_version", Integer.class) < 4) db.execute("PRAGMA user_version=4");
        });
    }

    public synchronized String subcategory(String main, String name) {
        String child = component(name);
        String parent = label("main", main);
        db.update("INSERT OR IGNORE INTO subcategories(main_key,main,name,key) VALUES(?,?,?,?)", key(parent), parent, child, key(child));
        return db.queryForObject("SELECT name FROM subcategories WHERE main_key=? AND key=?", String.class, key(parent), key(child));
    }

    public List<Subcategory> subcategories() {
        return db.query("SELECT main,name FROM subcategories ORDER BY main,name", (r,n) -> new Subcategory(r.getString("main"),r.getString("name")));
    }

    private void backfillHashes() {
        var rows = db.queryForList("SELECT id,path FROM documents WHERE sha256 IS NULL");
        for (var row : rows) {
            String hash;
            try {
                hash = sha256(file((String) row.get("path")));
            } catch (IOException e) {
                // Fehlende/gesperrte Originale dürfen den Start nicht verhindern.
                log.warn("Hash für Dokument {} nicht berechnet; Original fehlt oder ist nicht lesbar: {}", row.get("id"), e.getMessage());
                continue;
            }
            db.update("UPDATE documents SET sha256=? WHERE id=? AND sha256 IS NULL", hash, row.get("id"));
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256(Path file) throws IOException {
        var digest = digest();
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private Optional<Doc> findHash(String hash) {
        return db.queryForList("SELECT id FROM documents WHERE sha256=? ORDER BY date,id LIMIT 1", String.class, hash)
                .stream().findFirst().map(this::get);
    }

    public String root() {
        return root.toString();
    }

    private static String key(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    static String component(String value) {
        if (value == null) throw new IllegalArgumentException("Pflichtfeld fehlt.");
        String s = Normalizer.normalize(value.trim(), Normalizer.Form.NFC);
        if (s.isBlank() || s.length() > 120 || s.matches("(?s).*[\\\\/:*?\"<>|\\p{Cntrl}].*") || s.endsWith(".") || s.equals("..") || s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
            throw new IllegalArgumentException("Ungültiger Name: keine Pfadzeichen, reservierten Namen oder mehr als 120 Zeichen verwenden.");
        return s;
    }

    public synchronized String label(String kind, String name) {
        if (!List.of("main", "type", "extra", "sender").contains(kind))
            throw new IllegalArgumentException("Unbekannte Tag-Art.");
        String value = component(name);
        if (kind.equals("extra") && value.contains(";"))
            throw new IllegalArgumentException("Zusätzliche Tags dürfen kein Semikolon enthalten.");
        db.update("INSERT OR IGNORE INTO labels(kind,name,key) VALUES(?,?,?)", kind, value, key(value));
        return db.queryForObject("SELECT name FROM labels WHERE kind=? AND key=?", String.class, kind, key(value));
    }

    public List<String> labels(String kind) {
        return db.queryForList("SELECT name FROM labels WHERE kind=? ORDER BY name", String.class, kind);
    }

    private Metadata validate(Metadata m) {
        // Ein leeres Datum bleibt mit vorhandenen NOT-NULL-Schemata kompatibel.
        String date = m.date() == null || m.date().isBlank() ? "" : LocalDate.parse(m.date().trim()).toString();
        String comment = m.comment() == null ? "" : m.comment().replace("\r\n", "\n").replace('\r', '\n');
        if (comment.length() > 10000) throw new IllegalArgumentException("Der Kommentar darf höchstens 10000 Zeichen enthalten.");
        // Validate everything before storing labels.
        component(m.sender());
        component(m.main());
        component(m.type());
        String child = m.subcategory() == null || m.subcategory().isBlank() ? "" : component(m.subcategory());
        List<String> extras = m.tags() == null ? List.of() : m.tags().stream().filter(s -> !s.isBlank()).map(Catalog::component).toList();
        String main = label("main", m.main());
        return new Metadata(date, label("sender", m.sender()), main, label("type", m.type()),
                extras.stream().map(s -> label("extra", s)).distinct().toList(), comment,
                child.isEmpty() ? "" : subcategory(main, child));
    }

    public Doc get(String id) {
        return db.query("SELECT * FROM documents WHERE id=?", (r, n) -> new Doc(r.getString("id"), r.getString("date"), r.getString("sender"), r.getString("main"), r.getString("type"), r.getString("filename"), r.getString("path"), db.queryForList("SELECT tag FROM document_tags WHERE document_id=? ORDER BY tag", String.class, id), r.getString("sha256"), r.getString("created_at") == null ? null : Instant.parse(r.getString("created_at")), r.getString("comment"), r.getString("subcategory")), id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Dokument nicht gefunden."));
    }

    public List<Doc> search(String query, String period, String main, String type, List<String> tags) {
        return search(query, period, main, type, tags, false);
    }

    public List<Doc> search(String query, String period, String main, String type, List<String> tags, boolean undated) {
        return search(query, period, main, type, tags, undated, "");
    }

    public List<Doc> search(String query, String period, String main, String type, List<String> tags, boolean undated, String createdPeriod) {
        return search(query, period, main, type, tags, undated, createdPeriod, "");
    }

    public List<Doc> search(String query, String period, String main, String type, List<String> tags, boolean undated, String createdPeriod, String subcategory) {
        Instant[] captured = captureBounds(createdPeriod);
        String selectedPeriod = period == null ? "" : period.trim();
        // "Ohne Datum" und ein Zeitraum sind alternativ, auch ohne JavaScript.
        if (!undated && !selectedPeriod.isEmpty()) {
            if (!selectedPeriod.matches("\\d{4}(-\\d{2})?(-\\d{2})?"))
                throw new IllegalArgumentException("Datum als JJJJ, JJJJ-MM oder JJJJ-MM-TT eingeben.");
            if (selectedPeriod.length() == 10) LocalDate.parse(selectedPeriod);
            else if (selectedPeriod.length() == 7) java.time.YearMonth.parse(selectedPeriod);
            else java.time.Year.parse(selectedPeriod);
        }
        String q = key(query == null ? "" : query);
        return db.queryForList("SELECT id FROM documents ORDER BY created_at DESC,rowid DESC", String.class).stream().map(this::get)
                .filter(d -> q.isEmpty() || key(d.filename() + " " + d.sender() + " " + d.main() + " " + d.subcategory() + " " + d.type() + " " + String.join(" ", d.tags()) + " " + d.comment() + " " + d.createdAtLabel() + " " + (d.createdAt() == null ? "" : d.createdAt())).contains(q))
                .filter(d -> captured == null || (d.createdAt() != null && !d.createdAt().isBefore(captured[0]) && d.createdAt().isBefore(captured[1])))
                .filter(d -> undated ? d.date().isEmpty() : (selectedPeriod.isEmpty() || d.date().startsWith(selectedPeriod)))
                .filter(d -> main == null || main.isBlank() || key(d.main()).equals(key(main)))
                .filter(d -> subcategory == null || subcategory.isBlank() || key(d.subcategory()).equals(key(subcategory)))
                .filter(d -> type == null || type.isBlank() || key(d.type()).equals(key(type)))
                .filter(d -> tags == null || tags.stream().allMatch(t -> d.tags().stream().anyMatch(s -> key(s).equals(key(t))))).toList();
    }

    public SearchPage page(String query, String period, String main, String type, List<String> tags,
                           boolean undated, String createdPeriod, int requestedPage) {
        return page(query, period, main, type, tags, undated, createdPeriod, "", requestedPage);
    }

    public SearchPage page(String query, String period, String main, String type, List<String> tags,
                           boolean undated, String createdPeriod, String subcategory, int requestedPage) {
        return page(query, period, main, type, tags, undated, createdPeriod, subcategory, requestedPage, "createdAt", "desc");
    }

    public record Sorting(String field, String direction) {
        public Sorting {
            if (!List.of("filename", "sender", "date", "createdAt", "main", "subcategory", "type", "tags").contains(field)
                    || !List.of("asc", "desc").contains(direction))
                throw new IllegalArgumentException("Ungültige Sortierung.");
        }

        private Comparator<Doc> comparator() {
            Comparator<String> text = direction.equals("desc") ? Comparator.reverseOrder() : Comparator.naturalOrder();
            if (field.equals("createdAt")) {
                Comparator<Instant> time = direction.equals("desc") ? Comparator.reverseOrder() : Comparator.naturalOrder();
                return Comparator.comparing(Doc::createdAt, Comparator.nullsLast(time));
            }
            return Comparator.comparing((Doc d) -> switch (field) {
                case "filename" -> key(d.filename());
                case "sender" -> key(d.sender());
                case "date" -> d.date().isEmpty() ? null : d.date();
                case "createdAt" -> d.createdAt() == null ? null : d.createdAt().toString();
                case "main" -> key(d.main());
                case "subcategory" -> d.subcategory().isEmpty() ? null : key(d.subcategory());
                case "type" -> key(d.type());
                case "tags" -> d.tags().isEmpty() ? null : d.tags().stream().map(Catalog::key).sorted().collect(java.util.stream.Collectors.joining("; "));
                default -> throw new IllegalStateException();
            }, Comparator.nullsLast(text));
        }
    }

    public SearchPage page(String query, String period, String main, String type, List<String> tags,
                           boolean undated, String createdPeriod, String subcategory, int requestedPage,
                           String sort, String direction) {
        var sorting = new Sorting(sort, direction);
        // Die normale Startseite lädt nur die benötigten 30 Datensätze.
        if (sort.equals("createdAt") && direction.equals("desc")
                && (query == null || query.isBlank()) && (period == null || period.isBlank())
                && (main == null || main.isBlank()) && (type == null || type.isBlank())
                && (tags == null || tags.isEmpty()) && !undated && (createdPeriod == null || createdPeriod.isBlank())
                && (subcategory == null || subcategory.isBlank())) {
            int total = db.queryForObject("SELECT count(*) FROM documents", Integer.class);
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            int page = Math.max(1, Math.min(requestedPage, pages));
            List<Doc> docs = db.queryForList("SELECT id FROM documents ORDER BY created_at DESC,rowid DESC LIMIT ? OFFSET ?",
                    String.class, PAGE_SIZE, (page - 1) * PAGE_SIZE).stream().map(this::get).toList();
            return new SearchPage(docs, total, page, pages);
        }
        List<Doc> matches = search(query, period, main, type, tags, undated, createdPeriod, subcategory)
                .stream().sorted(sorting.comparator()).toList();
        int pages = Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(1, Math.min(requestedPage, pages));
        int start = (page - 1) * PAGE_SIZE;
        return new SearchPage(List.copyOf(matches.subList(start, Math.min(start + PAGE_SIZE, matches.size()))), matches.size(), page, pages);
    }

    private static Instant[] captureBounds(String period) {
        if (period == null || period.isBlank()) return null;
        String value = period.trim();
        if (!value.matches("\\d{4}(-\\d{2})?(-\\d{2})?"))
            throw new IllegalArgumentException("Erfasst am als JJJJ, JJJJ-MM oder JJJJ-MM-TT eingeben.");
        LocalDate start;
        LocalDate end;
        if (value.length() == 4) {
            start = Year.parse(value).atDay(1);
            end = start.plusYears(1);
        } else if (value.length() == 7) {
            start = YearMonth.parse(value).atDay(1);
            end = start.plusMonths(1);
        } else {
            start = LocalDate.parse(value);
            end = start.plusDays(1);
        }
        return new Instant[]{start.atStartOfDay(CAPTURE_ZONE).toInstant(), end.atStartOfDay(CAPTURE_ZONE).toInstant()};
    }

    static String baseFilename(String filename, String documentDate) {
        if (documentDate == null || documentDate.isBlank()) return filename;
        String name = filename;
        while (true) {
            var match = java.util.regex.Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})_(.+)$").matcher(name);
            if (!match.matches()) break;
            try {
                LocalDate.parse(match.group(1));
            } catch (java.time.DateTimeException e) {
                break;
            }
            String rest = match.group(2).trim();
            int dot = rest.lastIndexOf('.');
            String stem = dot < 0 ? rest : rest.substring(0, dot);
            // Datum.pdf ist ein echter Basisname; _ .pdf enthält dagegen keinen.
            if (stem.isBlank()) break;
            name = rest;
        }
        return name;
    }

    private String relative(Metadata m, String filename) {
        return m.main() + "/" + (m.subcategory().isEmpty() ? "" : m.subcategory() + "/")
                + (key(m.sender()).equals("unbekannt") ? "" : m.sender() + "/")
                + (m.date().isEmpty() ? "" : m.date() + "_") + filename;
    }

    public Path file(String relative) throws IOException {
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new IOException("Ungültiger Dateipfad.");
        Path current = root;
        if (Files.isSymbolicLink(root)) throw new IOException("Symbolische Links sind nicht erlaubt.");
        for (Path c : root.relativize(p)) {
            current = current.resolve(c);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolische Links sind nicht erlaubt.");
        }
        return p;
    }

    private void tags(String id, List<String> tags) {
        db.update("DELETE FROM document_tags WHERE document_id=?", id);
        for (String tag : tags) db.update("INSERT INTO document_tags VALUES(?,?)", id, tag);
    }

    public synchronized Doc upload(MultipartFile upload, Metadata raw) throws IOException {
        if (upload.isEmpty()) throw new IllegalArgumentException("Die Datei ist leer.");
        Path staged = Files.createTempFile(root, ".upload-", ".tmp");
        try {
            var digest = digest();
            try (var in = new DigestInputStream(upload.getInputStream(), digest)) {
                Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            String hash = HexFormat.of().formatHex(digest.digest());
            // Auch inzwischen wiederhergestellte Originale ohne bisherigen Hash berücksichtigen.
            backfillHashes();
            var existing = findHash(hash);
            if (existing.isPresent()) throw new DuplicateDocumentException(existing.get());
            // Dubletten ändern weder Metadaten noch Tag-Begriffe.
            String filename = component(upload.getOriginalFilename());
            Metadata m = validate(raw);
            filename = component(baseFilename(filename, m.date()));
            String rel = relative(m, filename);
            Path destination = file(rel);
            // Gleicher Dateiname mit anderem Inhalt: keine vorhandene Datei überschreiben.
            if (Files.exists(destination) || db.queryForObject("SELECT count(*) FROM documents WHERE path=?", Integer.class, rel) > 0) {
                int dot = filename.lastIndexOf('.');
                String suffix = " (" + UUID.randomUUID().toString().substring(0, 8) + ")";
                filename = dot > 0 ? filename.substring(0, dot) + suffix + filename.substring(dot) : filename + suffix;
                rel = relative(m, filename);
                destination = file(rel);
            }
            String id = UUID.randomUUID().toString(), finalName = filename, finalRel = rel;
            Files.createDirectories(destination.getParent());
            Files.move(staged, destination);
            try {
                tx.executeWithoutResult(status -> {
                    db.update("INSERT INTO documents(id,date,sender,main,type,filename,path,sha256,created_at,comment,subcategory) VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, m.date(), m.sender(), m.main(), m.type(), finalName, finalRel, hash, CAPTURE_FORMAT.format(Instant.now()), m.comment(), m.subcategory());
                    tags(id, m.tags());
                });
            } catch (RuntimeException e) {
                Files.delete(destination);
                // Schutz durch den DB-Trigger: gleicher Hinweis bei einem konkurrierenden Upload.
                var duplicate = findHash(hash);
                if (duplicate.isPresent()) throw new DuplicateDocumentException(duplicate.get());
                throw e;
            }
            return get(id);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public synchronized Doc update(String id, Metadata raw, boolean confirmed) throws IOException {
        return update(id, raw, get(id).filename(), confirmed);
    }

    public synchronized Doc update(String id, Metadata raw, String filename, boolean confirmed) throws IOException {
        Doc old = get(id);
        String inputName = component(filename);
        // Ältere Aufrufer ohne Kommentarparameter behalten vorhandene Notizen.
        String child = raw.subcategory() == null
                ? (raw.main() != null && key(raw.main()).equals(key(old.main())) ? old.subcategory() : "") : raw.subcategory();
        Metadata m = validate(new Metadata(raw.date(), raw.sender(), raw.main(), raw.type(), raw.tags(),
                raw.comment() == null ? old.comment() : raw.comment(), child));
        boolean pathRequested = !old.filename().equals(inputName) || !old.date().equals(m.date())
                || !old.sender().equals(m.sender()) || !old.main().equals(m.main()) || !old.subcategory().equals(m.subcategory());
        // Reine Kommentar-/Tagänderungen bereinigen alte Dateinamen nicht nebenbei.
        String name = pathRequested ? component(baseFilename(inputName, m.date())) : inputName;
        boolean pathChanged = !old.filename().equals(name) || !old.date().equals(m.date())
                || !old.sender().equals(m.sender()) || !old.main().equals(m.main()) || !old.subcategory().equals(m.subcategory());
        // Bestehende Pfade im alten Format bleiben bei reinen Tag-/Typänderungen erhalten.
        String rel = pathChanged ? relative(m, name) : old.path();
        boolean moving = !old.path().equals(rel);
        if (moving && !confirmed)
            throw new IllegalArgumentException("Achtung, du veränderst etwas, was sich aufs Dateisystem auswirkt. Bitte bestätigen.");
        Path source = file(old.path()), destination = file(rel);
        if (moving) {
            if (db.queryForObject("SELECT count(*) FROM documents WHERE path=? AND id<>?", Integer.class, rel, id) > 0)
                throw new IllegalArgumentException("Der Zielpfad ist bereits katalogisiert. Bitte einen anderen Dateinamen wählen.");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                throw new FileAlreadyExistsException(destination.toString());
            Files.createDirectories(destination.getParent());
            // Kein REPLACE_EXISTING und kein ATOMIC_MOVE: ein belegtes Ziel wird nicht ersetzt.
            Files.move(source, destination);
        }
        try {
            tx.executeWithoutResult(status -> {
                db.update("UPDATE documents SET date=?,sender=?,main=?,type=?,filename=?,path=?,comment=?,subcategory=? WHERE id=?", m.date(), m.sender(), m.main(), m.type(), name, rel, m.comment(), m.subcategory(), id);
                tags(id, m.tags());
            });
        } catch (RuntimeException e) {
            if (moving) Files.move(destination, source);
            throw e;
        }
        return get(id);
    }

    public synchronized DeleteResult delete(String id, boolean deleteOriginal, boolean confirmed) throws IOException {
        if (!confirmed) throw new IllegalArgumentException("Bitte das Löschen ausdrücklich bestätigen.");
        Doc old = get(id);
        Path source = null, backup = null;
        if (deleteOriginal) {
            source = file(old.path());
            boolean present;
            try {
                var attributes = Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) throw new IOException("Die Originaldatei ist keine reguläre Datei.");
                present = true;
            } catch (NoSuchFileException e) {
                present = false;
            }
            if (present) {
                // Die Kopie ermöglicht Wiederherstellung auch bei einem fehlgeschlagenen DB-Commit.
                backup = Files.createTempFile(root, ".delete-backup-", ".tmp");
                try {
                    Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(backup);
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
            }
        }
        final Path original = source, recovery = backup;
        try {
            tx.executeWithoutResult(status -> {
                db.update("DELETE FROM document_tags WHERE document_id=?", id);
                db.update("DELETE FROM documents WHERE id=?", id);
                if (recovery != null) {
                    try {
                        Files.delete(original);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
        } catch (RuntimeException e) {
            if (recovery != null) {
                try {
                    // Niemals eine zwischenzeitlich neu angelegte Datei überschreiben.
                    if (!Files.exists(original, LinkOption.NOFOLLOW_LINKS)) Files.move(recovery, original);
                    else Files.delete(recovery);
                } catch (IOException restore) {
                    e.addSuppressed(restore);
                }
            }
            if (e instanceof UncheckedIOException io) throw io.getCause();
            throw e;
        }
        if (recovery != null) {
            try {
                Files.delete(recovery);
            } catch (IOException e) {
                return new DeleteResult(root.relativize(recovery).toString());
            }
        }
        return new DeleteResult(null);
    }

    public synchronized EmptyDirectoryCleaner.Result cleanEmptyDirectories() throws IOException {
        // Gleiche Sperre wie Upload/Verschieben/Löschen; keinerlei Katalogabfragen nötig.
        return EmptyDirectoryCleaner.clean(root);
    }

    public synchronized Check check() throws IOException {
        Set<String> known = new HashSet<>(db.queryForList("SELECT path FROM documents", String.class));
        List<String> missing = new ArrayList<>();
        for (String p : known) {
            try {
                if (!Files.isRegularFile(file(p))) missing.add(p);
            } catch (IOException e) {
                missing.add(p + " (" + e.getMessage() + ")");
            }
        }
        List<String> unknown;
        try (var paths = Files.walk(root)) {
            unknown = paths.filter(p -> !p.equals(root)).filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)).map(root::relativize).map(p -> p.toString().replace(java.io.File.separatorChar, '/')).filter(p -> !known.contains(p)).sorted().toList();
        }
        Collections.sort(missing);
        return new Check(missing, unknown);
    }
}
