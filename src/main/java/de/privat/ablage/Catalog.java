package de.privat.ablage;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.multipart.MultipartFile;
import java.nio.file.*;
import java.io.*;
import java.time.LocalDate;
import java.text.Normalizer;
import java.util.*;

@Service
public class Catalog {
    public record Doc(String id, String date, String sender, String main, String type,
                      String filename, String path, List<String> tags) {}
    public record Metadata(String date, String sender, String main, String type, List<String> tags) {}
    public record Check(List<String> missing, List<String> uncatalogued) {}
    public record DeleteResult(String retainedBackup) {}
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final Path root;

    public Catalog(JdbcTemplate db, PlatformTransactionManager manager, @Value("${app.data}") String data) throws IOException {
        this.db=db; tx=new TransactionTemplate(manager);
        root=Path.of(data).toAbsolutePath().normalize().resolve("originale");
        Files.createDirectories(root);
        db.execute("PRAGMA foreign_keys=ON");
        db.execute("CREATE TABLE IF NOT EXISTS labels(kind TEXT NOT NULL, name TEXT NOT NULL, key TEXT NOT NULL, PRIMARY KEY(kind,key))");
        db.execute("CREATE TABLE IF NOT EXISTS documents(id TEXT PRIMARY KEY, date TEXT NOT NULL, sender TEXT NOT NULL, main TEXT NOT NULL, type TEXT NOT NULL, filename TEXT NOT NULL, path TEXT UNIQUE NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS document_tags(document_id TEXT NOT NULL REFERENCES documents(id) ON DELETE CASCADE, tag TEXT NOT NULL, PRIMARY KEY(document_id,tag))");
        for (String s: List.of("Finanzen","Wohnen","Versicherungen","Gesundheit")) label("main",s);
        for (String s: List.of("Rechnung","Info","Newsletter")) label("type",s);
        for (String s: List.of("Relevant für Steuererklärung","Todo")) label("extra",s);
    }
    public String root() { return root.toString(); }
    private static String key(String value) { return Normalizer.normalize(value.trim(),Normalizer.Form.NFC).toLowerCase(Locale.ROOT); }
    static String component(String value) {
        if(value==null) throw new IllegalArgumentException("Pflichtfeld fehlt.");
        String s=Normalizer.normalize(value.trim(),Normalizer.Form.NFC);
        if(s.isBlank() || s.length()>120 || s.matches("(?s).*[\\\\/:*?\"<>|\\p{Cntrl}].*") || s.endsWith(".") || s.equals("..") || s.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))
            throw new IllegalArgumentException("Ungültiger Name: keine Pfadzeichen, reservierten Namen oder mehr als 120 Zeichen verwenden.");
        return s;
    }
    public synchronized String label(String kind,String name) {
        if(!List.of("main","type","extra","sender").contains(kind)) throw new IllegalArgumentException("Unbekannte Tag-Art.");
        String value=component(name);
        if(kind.equals("extra") && value.contains(";")) throw new IllegalArgumentException("Zusätzliche Tags dürfen kein Semikolon enthalten.");
        db.update("INSERT OR IGNORE INTO labels(kind,name,key) VALUES(?,?,?)",kind,value,key(value));
        return db.queryForObject("SELECT name FROM labels WHERE kind=? AND key=?",String.class,kind,key(value));
    }
    public List<String> labels(String kind) { return db.queryForList("SELECT name FROM labels WHERE kind=? ORDER BY name",String.class,kind); }
    private Metadata validate(Metadata m) {
        String date=LocalDate.parse(m.date()).toString();
        // Validate everything before storing labels.
        component(m.sender()); component(m.main()); component(m.type());
        List<String> extras=m.tags()==null?List.of():m.tags().stream().filter(s->!s.isBlank()).map(Catalog::component).toList();
        return new Metadata(date,label("sender",m.sender()),label("main",m.main()),label("type",m.type()),extras.stream().map(s->label("extra",s)).distinct().toList());
    }
    public Doc get(String id) {
        return db.query("SELECT * FROM documents WHERE id=?", (r,n)-> new Doc(r.getString("id"),r.getString("date"),r.getString("sender"),r.getString("main"),r.getString("type"),r.getString("filename"),r.getString("path"),db.queryForList("SELECT tag FROM document_tags WHERE document_id=? ORDER BY tag",String.class,id)),id)
            .stream().findFirst().orElseThrow(()->new IllegalArgumentException("Dokument nicht gefunden."));
    }
    public List<Doc> search(String query,String period,String main,String type,List<String> tags) {
        if(period!=null && !period.isBlank()) {
            if(!period.matches("\\d{4}(-\\d{2})?(-\\d{2})?")) throw new IllegalArgumentException("Datum als JJJJ, JJJJ-MM oder JJJJ-MM-TT eingeben.");
            if(period.length()==10) LocalDate.parse(period);
            else if(period.length()==7) java.time.YearMonth.parse(period);
            else java.time.Year.parse(period);
        }
        String q=key(query==null?"":query);
        return db.queryForList("SELECT id FROM documents ORDER BY date DESC,filename",String.class).stream().map(this::get)
          .filter(d->q.isEmpty() || key(d.filename()+" "+d.sender()+" "+d.main()+" "+d.type()+" "+String.join(" ",d.tags())).contains(q))
          .filter(d->period==null || d.date().startsWith(period))
          .filter(d->main==null || main.isBlank() || key(d.main()).equals(key(main)))
          .filter(d->type==null || type.isBlank() || key(d.type()).equals(key(type)))
          .filter(d->tags==null || tags.stream().allMatch(t->d.tags().stream().anyMatch(s->key(s).equals(key(t))))) .toList();
    }
    private String relative(Metadata m,String filename) { return m.main()+"/"+m.sender()+"/"+m.date()+"_"+filename; }
    public Path file(String relative) throws IOException {
        Path p=root.resolve(relative).normalize();
        if(!p.startsWith(root)) throw new IOException("Ungültiger Dateipfad.");
        Path current=root;
        if(Files.isSymbolicLink(root)) throw new IOException("Symbolische Links sind nicht erlaubt.");
        for(Path c:root.relativize(p)) { current=current.resolve(c); if(Files.isSymbolicLink(current)) throw new IOException("Symbolische Links sind nicht erlaubt."); }
        return p;
    }
    private void tags(String id,List<String> tags) {
        db.update("DELETE FROM document_tags WHERE document_id=?",id);
        for(String tag:tags) db.update("INSERT INTO document_tags VALUES(?,?)",id,tag);
    }
    public synchronized Doc upload(MultipartFile upload,Metadata raw) throws IOException {
        if(upload.isEmpty()) throw new IllegalArgumentException("Die Datei ist leer.");
        String filename=component(upload.getOriginalFilename());
        Metadata m=validate(raw);
        String rel=relative(m,filename);
        Path destination=file(rel);
        // Niemals bestehende Dateien überschreiben. Bei gleichem Namen stabile ID ergänzen.
        if(Files.exists(destination) || db.queryForObject("SELECT count(*) FROM documents WHERE path=?",Integer.class,rel)>0) {
            int dot=filename.lastIndexOf('.');
            String suffix=" ("+UUID.randomUUID().toString().substring(0,8)+")";
            filename=dot>0?filename.substring(0,dot)+suffix+filename.substring(dot):filename+suffix;
            rel=relative(m,filename); destination=file(rel);
        }
        String id=UUID.randomUUID().toString(), finalName=filename, finalRel=rel;
        Files.createDirectories(destination.getParent());
        Path staged=Files.createTempFile(root,".upload-",".tmp");
        try {
            try(var in=upload.getInputStream()) { Files.copy(in,staged,StandardCopyOption.REPLACE_EXISTING); }
            Files.move(staged,destination);
            try {
                tx.executeWithoutResult(status->{db.update("INSERT INTO documents VALUES(?,?,?,?,?,?,?)",id,m.date(),m.sender(),m.main(),m.type(),finalName,finalRel); tags(id,m.tags());});
            } catch(RuntimeException e) { Files.delete(destination); throw e; }
        } finally { Files.deleteIfExists(staged); }
        return get(id);
    }
    public synchronized Doc update(String id,Metadata raw,boolean confirmed) throws IOException {
        return update(id,raw,get(id).filename(),confirmed);
    }
    public synchronized Doc update(String id,Metadata raw,String filename,boolean confirmed) throws IOException {
        Doc old=get(id);
        String name=component(filename);
        Metadata m=validate(raw);
        boolean pathChanged=!old.filename().equals(name) || !old.date().equals(m.date())
            || !old.sender().equals(m.sender()) || !old.main().equals(m.main());
        // Bestehende Pfade im alten Format bleiben bei reinen Tag-/Typänderungen erhalten.
        String rel=pathChanged?relative(m,name):old.path();
        boolean moving=!old.path().equals(rel);
        if(moving && !confirmed) throw new IllegalArgumentException("Achtung, du veränderst etwas, was sich aufs Dateisystem auswirkt. Bitte bestätigen.");
        Path source=file(old.path()), destination=file(rel);
        if(moving) {
            if(db.queryForObject("SELECT count(*) FROM documents WHERE path=? AND id<>?",Integer.class,rel,id)>0)
                throw new IllegalArgumentException("Der Zielpfad ist bereits katalogisiert. Bitte einen anderen Dateinamen wählen.");
            if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))
                throw new FileAlreadyExistsException(destination.toString());
            Files.createDirectories(destination.getParent());
            // Kein REPLACE_EXISTING und kein ATOMIC_MOVE: ein belegtes Ziel wird nicht ersetzt.
            Files.move(source,destination);
        }
        try {
            tx.executeWithoutResult(status->{db.update("UPDATE documents SET date=?,sender=?,main=?,type=?,filename=?,path=? WHERE id=?",m.date(),m.sender(),m.main(),m.type(),name,rel,id);tags(id,m.tags());});
        } catch(RuntimeException e) { if(moving) Files.move(destination,source); throw e; }
        return get(id);
    }
    public synchronized DeleteResult delete(String id,boolean deleteOriginal,boolean confirmed) throws IOException {
        if(!confirmed) throw new IllegalArgumentException("Bitte das Löschen ausdrücklich bestätigen.");
        Doc old=get(id);
        Path source=null, backup=null;
        if(deleteOriginal) {
            source=file(old.path());
            boolean present;
            try {
                var attributes=Files.readAttributes(source,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(!attributes.isRegularFile()) throw new IOException("Die Originaldatei ist keine reguläre Datei.");
                present=true;
            } catch(NoSuchFileException e) { present=false; }
            if(present) {
                // Die Kopie ermöglicht Wiederherstellung auch bei einem fehlgeschlagenen DB-Commit.
                backup=Files.createTempFile(root,".delete-backup-",".tmp");
                try { Files.copy(source,backup,StandardCopyOption.REPLACE_EXISTING); }
                catch(IOException e) { try { Files.deleteIfExists(backup); } catch(IOException cleanup) { e.addSuppressed(cleanup); } throw e; }
            }
        }
        final Path original=source, recovery=backup;
        try {
            tx.executeWithoutResult(status->{
                db.update("DELETE FROM document_tags WHERE document_id=?",id);
                db.update("DELETE FROM documents WHERE id=?",id);
                if(recovery!=null) {
                    try { Files.delete(original); }
                    catch(IOException e) { throw new UncheckedIOException(e); }
                }
            });
        } catch(RuntimeException e) {
            if(recovery!=null) {
                try {
                    // Niemals eine zwischenzeitlich neu angelegte Datei überschreiben.
                    if(!Files.exists(original,LinkOption.NOFOLLOW_LINKS)) Files.move(recovery,original);
                    else Files.delete(recovery);
                } catch(IOException restore) { e.addSuppressed(restore); }
            }
            if(e instanceof UncheckedIOException io) throw io.getCause();
            throw e;
        }
        if(recovery!=null) {
            try { Files.delete(recovery); }
            catch(IOException e) { return new DeleteResult(root.relativize(recovery).toString()); }
        }
        return new DeleteResult(null);
    }
    public synchronized Check check() throws IOException {
        Set<String> known=new HashSet<>(db.queryForList("SELECT path FROM documents",String.class));
        List<String> missing=new ArrayList<>();
        for(String p:known) { try { if(!Files.isRegularFile(file(p))) missing.add(p); } catch(IOException e) { missing.add(p+" ("+e.getMessage()+")"); } }
        List<String> unknown;
        try(var paths=Files.walk(root)) { unknown=paths.filter(p->!p.equals(root)).filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(p)).map(root::relativize).map(p->p.toString().replace(java.io.File.separatorChar,'/')).filter(p->!known.contains(p)).sorted().toList(); }
        Collections.sort(missing); return new Check(missing,unknown);
    }
}
