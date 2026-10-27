package de.privat.ablage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockMultipartFile;
import org.sqlite.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CatalogTest {
    @TempDir Path temp;
    Catalog catalog;
    JdbcTemplate db;
    @BeforeEach void setup() throws Exception {
        var config=new SQLiteConfig();config.enforceForeignKeys(true);
        var ds=new SQLiteDataSource(config);ds.setUrl("jdbc:sqlite:"+temp.resolve("test.sqlite"));
        db=new JdbcTemplate(ds);
        catalog=new Catalog(db,new DataSourceTransactionManager(ds),temp.toString());
    }
    Catalog.Metadata metadata() { return new Catalog.Metadata("2025-06-10","Sparkasse","Finanzen","Rechnung",List.of("Relevant für Steuererklärung")); }
    MockMultipartFile upload() { return new MockMultipartFile("files","Kontoauszug.pdf","application/pdf",new byte[]{1,2,3,4}); }
    MockMultipartFile differentUpload() { return new MockMultipartFile("files","Kontoauszug.pdf","application/pdf",new byte[]{9,8,7}); }
    @Test void originalsUseReadablePathsAndKeepBytes() throws Exception {
        var d=catalog.upload(upload(),metadata());
        assertThat(d.path().replace('\\','/')).isEqualTo("Finanzen/Sparkasse/2025-06-10_Kontoauszug.pdf");
        assertThat(Files.readAllBytes(catalog.file(d.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.get(d.id()).tags()).containsExactly("Relevant für Steuererklärung");
    }
    @Test void labelsReuseSpellingAndNormalizeUnicode() {
        assertThat(catalog.label("extra","  relevant FÜR steuererklärung  ")).isEqualTo("Relevant für Steuererklärung");
        catalog.label("extra","Ärztlich");catalog.label("extra","A\u0308rztlich");
        assertThat(catalog.labels("extra").stream().filter(s->s.equals("Ärztlich")).count()).isEqualTo(1);
    }
    @Test void combinedSearchRequiresEveryFilter() throws Exception {
        catalog.upload(upload(),metadata());
        assertThat(catalog.search("konto","2025","Finanzen","Rechnung",List.of("Relevant für Steuererklärung"))).hasSize(1);
        assertThat(catalog.search("sparkasse","2025-06","","",List.of())).hasSize(1);
        assertThat(catalog.search("","2024","","",List.of())).isEmpty();
        assertThat(catalog.search("","2025","","Info",List.of())).isEmpty();
        assertThat(catalog.search("","2025","","",List.of("Relevant für Steuererklärung","Todo"))).isEmpty();
    }
    @Test void dateSearchRejectsInvalidMonths() {
        assertThatThrownBy(()->catalog.search("","2025-99","","",List.of())).isInstanceOf(java.time.DateTimeException.class);
    }
    @Test void collisionsNeverOverwriteOriginals() throws Exception {
        var first=catalog.upload(upload(),metadata());var second=catalog.upload(differentUpload(),metadata());
        assertThat(second.path()).isNotEqualTo(first.path());
        assertThat(catalog.check().missing()).isEmpty();assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void movingRequiresConfirmationAndPreservesBytes() throws Exception {
        var old=catalog.upload(upload(),metadata());
        var changed=new Catalog.Metadata("2026-01-01","Allianz","Versicherungen","Info",List.of("Todo"));
        assertThatThrownBy(()->catalog.update(old.id(),changed,false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.get(old.id()).path()).isEqualTo(old.path());assertThat(catalog.file(old.path())).exists();
        var updated=catalog.update(old.id(),changed,true);
        assertThat(catalog.file(old.path())).doesNotExist();assertThat(Files.readAllBytes(catalog.file(updated.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(updated.tags()).containsExactly("Todo");
    }
    @Test void metadataOnlyChangesNeedNoFilesystemConfirmation() throws Exception {
        var old=catalog.upload(upload(),metadata());
        var changed=new Catalog.Metadata(old.date(),old.sender(),old.main(),"Info",List.of("Todo"));
        assertThat(catalog.update(old.id(),changed,false).path()).isEqualTo(old.path());
    }
    @Test void targetCollisionLeavesCatalogAndBothFilesUntouched() throws Exception {
        var old=catalog.upload(upload(),metadata());
        var changed=new Catalog.Metadata("2026-01-01","Sparkasse","Finanzen","Rechnung",List.of());
        Path target=catalog.file("Finanzen/Sparkasse/2026-01-01_Kontoauszug.pdf");Files.writeString(target,"anderes Original");
        assertThatThrownBy(()->catalog.update(old.id(),changed,true)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(catalog.get(old.id()).path()).isEqualTo(old.path());assertThat(Files.readString(target)).isEqualTo("anderes Original");
    }
    @Test void checkFindsMissingAndUnknownFiles() throws Exception {
        var d=catalog.upload(upload(),metadata());Files.delete(catalog.file(d.path()));
        Files.writeString(Path.of(catalog.root()).resolve("nicht erfasst.zip"),"zip");
        assertThat(catalog.check().missing()).containsExactly(d.path());
        assertThat(catalog.check().uncatalogued()).containsExactly("nicht erfasst.zip");
    }
    @Test void failedDatabaseInsertRemovesNewFile() {
        db.execute("CREATE TRIGGER reject_insert BEFORE INSERT ON documents BEGIN SELECT RAISE(ABORT, 'test'); END");
        assertThatThrownBy(()->catalog.upload(upload(),metadata())).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM documents",Integer.class)).isZero();
        assertThatCode(()->assertThat(catalog.check().uncatalogued()).isEmpty()).doesNotThrowAnyException();
    }
    @Test void failedDatabaseUpdateMovesOriginalBack() throws Exception {
        var old=catalog.upload(upload(),metadata());
        db.execute("CREATE TRIGGER reject_update BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT, 'test'); END");
        var changed=new Catalog.Metadata("2026-01-01","Sparkasse","Finanzen","Info",List.of());
        assertThatThrownBy(()->catalog.update(old.id(),changed,true)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.file(old.path())).exists();assertThat(catalog.get(old.id()).date()).isEqualTo("2025-06-10");
        assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void pathTraversalAndWindowsReservedNamesAreRejected() {
        for(String s:List.of("../secret","/etc/passwd","a\\b","..","NUL","COM1.txt"))
            assertThatThrownBy(()->Catalog.component(s)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->catalog.file("../catalog.sqlite")).isInstanceOf(java.io.IOException.class);
    }
    @Test void symbolicLinksCannotEscapeArchive() throws Exception {
        Path outside=temp.resolve("outside");Files.createDirectories(outside);
        try { Files.createSymbolicLink(Path.of(catalog.root()).resolve("link"),outside); }
        catch (UnsupportedOperationException | java.io.IOException e) { org.junit.jupiter.api.Assumptions.abort("Symlinks auf diesem System nicht verfügbar: "+e.getMessage()); }
        assertThatThrownBy(()->catalog.file("link/secret.pdf")).isInstanceOf(java.io.IOException.class);
        assertThat(catalog.check().uncatalogued()).contains("link");
    }
    @Test void emptyUploadIsRejected() {
        assertThatThrownBy(()->catalog.upload(new MockMultipartFile("files","empty.pdf","application/pdf",new byte[0]),metadata())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void catalogSurvivesRestart() throws Exception {
        var doc=catalog.upload(upload(),metadata());
        setup();assertThat(catalog.get(doc.id()).path()).isEqualTo(doc.path());
    }
    @Test void renameKeepsDatePrefixAndFileBytes() throws Exception {
        var old=catalog.upload(upload(),metadata());
        var renamed=catalog.update(old.id(),metadata(),"Neue Rechnung.pdf",true);
        assertThat(renamed.filename()).isEqualTo("Neue Rechnung.pdf");
        assertThat(renamed.path()).isEqualTo("Finanzen/Sparkasse/2025-06-10_Neue Rechnung.pdf");
        assertThat(catalog.file(old.path())).doesNotExist();
        assertThat(Files.readAllBytes(catalog.file(renamed.path()))).containsExactly(new byte[]{1,2,3,4});
    }
    @Test void renameRequiresConfirmationAndRejectsInvalidNames() throws Exception {
        var old=catalog.upload(upload(),metadata());
        assertThatThrownBy(()->catalog.update(old.id(),metadata(),"Anders.pdf",false)).isInstanceOf(IllegalArgumentException.class);
        for(String name:List.of("../secret.pdf","","NUL","folder/file.pdf"))
            assertThatThrownBy(()->catalog.update(old.id(),metadata(),name,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.get(old.id()).filename()).isEqualTo(old.filename());
        assertThat(catalog.file(old.path())).exists();
    }
    @Test void renameNeverOverwritesUncataloguedFiles() throws Exception {
        var old=catalog.upload(upload(),metadata());
        Path occupied=catalog.file("Finanzen/Sparkasse/2025-06-10_Belegt.pdf");Files.writeString(occupied,"unbekanntes Original");
        assertThatThrownBy(()->catalog.update(old.id(),metadata(),"Belegt.pdf",true)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readString(occupied)).isEqualTo("unbekanntes Original");
        assertThat(catalog.get(old.id()).path()).isEqualTo(old.path());
        assertThat(catalog.file(old.path())).exists();
    }
    @Test void renameCannotStealCataloguedPathWhenItsFileIsMissing() throws Exception {
        var first=catalog.upload(upload(),metadata());
        var second=catalog.upload(new MockMultipartFile("files","Belegt.pdf","application/pdf",new byte[]{9}),metadata());
        Files.delete(catalog.file(second.path()));
        assertThatThrownBy(()->catalog.update(first.id(),metadata(),second.filename(),true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.get(first.id()).path()).isEqualTo(first.path());
        assertThat(catalog.file(first.path())).exists();assertThat(catalog.file(second.path())).doesNotExist();
    }
    @Test void failedRenameRestoresNamePathAndOriginal() throws Exception {
        var old=catalog.upload(upload(),metadata());
        db.execute("CREATE TRIGGER reject_rename BEFORE UPDATE ON documents BEGIN SELECT RAISE(ABORT, 'test'); END");
        assertThatThrownBy(()->catalog.update(old.id(),metadata(),"Neu.pdf",true)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.get(old.id()).filename()).isEqualTo(old.filename());
        assertThat(catalog.file(old.path())).exists();assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void legacyPathsStayUntilPathRelevantChanges() throws Exception {
        var old=catalog.upload(upload(),metadata());
        String legacy=old.path().replace("2025-06-10_","2025-06-10 ");
        Files.move(catalog.file(old.path()),catalog.file(legacy));
        db.update("UPDATE documents SET path=? WHERE id=?",legacy,old.id());
        var onlyTags=new Catalog.Metadata(old.date(),old.sender(),old.main(),"Info",List.of("Todo"));
        assertThat(catalog.update(old.id(),onlyTags,old.filename(),false).path()).isEqualTo(legacy);
        assertThat(catalog.update(old.id(),onlyTags,"Neu.pdf",true).path()).endsWith("2025-06-10_Neu.pdf");
    }
    @Test void deletingOnlyCatalogKeepsOriginalAndReportsItAsUnknown() throws Exception {
        var old=catalog.upload(upload(),metadata());
        catalog.delete(old.id(),false,true);
        assertThatThrownBy(()->catalog.get(old.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readAllBytes(catalog.file(old.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.check().uncatalogued()).containsExactly(old.path());
        assertThat(db.queryForObject("SELECT count(*) FROM document_tags WHERE document_id=?",Integer.class,old.id())).isZero();
        assertThat(catalog.labels("extra")).contains("Relevant für Steuererklärung");
    }
    @Test void deletingWithOriginalRemovesOnlySelectedDocument() throws Exception {
        var old=catalog.upload(upload(),metadata());var other=catalog.upload(differentUpload(),metadata());
        assertThat(catalog.delete(old.id(),true,true).retainedBackup()).isNull();
        assertThatThrownBy(()->catalog.get(old.id())).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.file(old.path())).doesNotExist();assertThat(catalog.file(other.path())).exists();
        assertThat(catalog.get(other.id()).tags()).containsExactly("Relevant für Steuererklärung");
        assertThat(catalog.check().missing()).isEmpty();assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void deletionRequiresExplicitConfirmationInBothModes() throws Exception {
        var old=catalog.upload(upload(),metadata());
        for(boolean deleteFile:List.of(false,true))
            assertThatThrownBy(()->catalog.delete(old.id(),deleteFile,false)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.get(old.id()).path()).isEqualTo(old.path());assertThat(catalog.file(old.path())).exists();
    }
    @Test void deletionAlsoWorksWhenOriginalIsAlreadyMissing() throws Exception {
        for(boolean deleteFile:List.of(false,true)) {
            var old=catalog.upload(upload(),metadata());Files.delete(catalog.file(old.path()));
            catalog.delete(old.id(),deleteFile,true);
            assertThatThrownBy(()->catalog.get(old.id())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void failedDeleteKeepsCatalogOriginalAndTagAssociations() throws Exception {
        var old=catalog.upload(upload(),metadata());
        db.execute("CREATE TRIGGER reject_delete BEFORE DELETE ON documents BEGIN SELECT RAISE(ABORT, 'test'); END");
        assertThatThrownBy(()->catalog.delete(old.id(),true,true)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.get(old.id()).tags()).containsExactly("Relevant für Steuererklärung");
        assertThat(Files.readAllBytes(catalog.file(old.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void failedDeleteCommitRestoresOriginalFromBackup() throws Exception {
        var old=catalog.upload(upload(),metadata());
        var failCommit=new java.util.concurrent.atomic.AtomicBoolean(false);
        var manager=new DataSourceTransactionManager(db.getDataSource()) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                if(!failCommit.get()) { super.doCommit(status); return; }
                throw new org.springframework.transaction.TransactionSystemException("Simulierter Commitfehler");
            }
        };
        manager.setRollbackOnCommitFailure(true);
        var failing=new Catalog(db,manager,temp.toString());
        failCommit.set(true);
        assertThatThrownBy(()->failing.delete(old.id(),true,true)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.get(old.id()).tags()).containsExactly("Relevant für Steuererklärung");
        assertThat(Files.readAllBytes(catalog.file(old.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void storesStandardSha256OfExactBytesAndPreservesItOnRename() throws Exception {
        var doc=catalog.upload(new MockMultipartFile("files","abc.pdf","application/pdf",new byte[]{97,98,99}),metadata());
        assertThat(doc.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(db.queryForObject("SELECT sha256 FROM documents WHERE id=?",String.class,doc.id())).isEqualTo(doc.sha256());
        assertThat(catalog.update(doc.id(),metadata(),"Umbenannt.pdf",true).sha256()).isEqualTo(doc.sha256());
    }
    @Test void duplicateContentReturnsExistingDocumentRegardlessOfNameOrMetadata() throws Exception {
        var doc=catalog.upload(upload(),metadata());
        var other=new Catalog.Metadata("2026-10-27","Neuer Absender","Neue Kategorie","Neuer Typ",List.of("Neuer Tag"));
        var copy=new MockMultipartFile("files","Ganz anders.zip","application/zip",new byte[]{1,2,3,4});
        assertThatThrownBy(()->catalog.upload(copy,other)).isInstanceOfSatisfying(Catalog.DuplicateDocumentException.class,
            e->assertThat(e.existing().id()).isEqualTo(doc.id()));
        assertThat(catalog.get(doc.id()).sender()).isEqualTo("Sparkasse");
        assertThat(catalog.labels("main")).doesNotContain("Neue Kategorie");
        assertThat(db.queryForObject("SELECT count(*) FROM documents",Integer.class)).isEqualTo(1);
        assertThat(catalog.check().uncatalogued()).isEmpty();
        try(var paths=Files.walk(Path.of(catalog.root()))) { assertThat(paths.filter(Files::isRegularFile).count()).isEqualTo(1); }
    }
    @Test void sameFilenameWithDifferentBytesIsStillAllowed() throws Exception {
        var first=catalog.upload(upload(),metadata());var second=catalog.upload(differentUpload(),metadata());
        assertThat(first.sha256()).isNotEqualTo(second.sha256());assertThat(first.path()).isNotEqualTo(second.path());
    }
    @Test void knownHashStillDetectsDuplicateWhenOriginalIsMissing() throws Exception {
        var doc=catalog.upload(upload(),metadata());Files.delete(catalog.file(doc.path()));
        assertThatThrownBy(()->catalog.upload(upload(),metadata())).isInstanceOfSatisfying(Catalog.DuplicateDocumentException.class,
            e->assertThat(e.existing().id()).isEqualTo(doc.id()));
        assertThat(catalog.check().missing()).containsExactly(doc.path());
    }
    @Test void removingCatalogEntryAllowsUploadingSameContentAgain() throws Exception {
        var doc=catalog.upload(upload(),metadata());catalog.delete(doc.id(),false,true);
        var added=catalog.upload(upload(),metadata());
        assertThat(added.id()).isNotEqualTo(doc.id());assertThat(added.sha256()).isEqualTo(doc.sha256());
        assertThat(catalog.file(doc.path())).exists();assertThat(catalog.file(added.path())).exists();
    }
    @Test void databaseAlsoRejectsNewDuplicateHash() throws Exception {
        var doc=catalog.upload(upload(),metadata());
        assertThatThrownBy(()->db.update("INSERT INTO documents(id,date,sender,main,type,filename,path,sha256) VALUES(?,?,?,?,?,?,?,?)",
            "other",doc.date(),doc.sender(),doc.main(),doc.type(),"copy.pdf","another.pdf",doc.sha256())).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM documents",Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousUploadsCreateOnlyOneDocument() throws Exception {
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> action=()->{
                try { return catalog.upload(upload(),metadata()).id(); }
                catch(Catalog.DuplicateDocumentException e) { return e.existing().id(); }
            };
            var first=executor.submit(action);var second=executor.submit(action);
            assertThat(first.get()).isEqualTo(second.get());
        }
        assertThat(db.queryForObject("SELECT count(*) FROM documents",Integer.class)).isEqualTo(1);
        assertThat(catalog.check().uncatalogued()).isEmpty();
    }
    @Test void legacyMigrationHashesOriginalsAndKeepsExistingDuplicates() throws Exception {
        Path data=temp.resolve("legacy");Files.createDirectories(data.resolve("originale"));
        Files.write(data.resolve("originale/first.pdf"),new byte[]{1,2,3,4});
        Files.write(data.resolve("originale/second.pdf"),new byte[]{1,2,3,4});
        var ds=new SQLiteDataSource();ds.setUrl("jdbc:sqlite:"+data.resolve("catalog.sqlite"));
        var legacy=new JdbcTemplate(ds);
        legacy.execute("CREATE TABLE documents(id TEXT PRIMARY KEY, date TEXT NOT NULL, sender TEXT NOT NULL, main TEXT NOT NULL, type TEXT NOT NULL, filename TEXT NOT NULL, path TEXT UNIQUE NOT NULL)");
        for(String name:List.of("first","second","missing")) legacy.update("INSERT INTO documents VALUES(?,?,?,?,?,?,?)",name,"2025-06-10","Sparkasse","Finanzen","Rechnung",name+".pdf",name+".pdf");
        var migrated=new Catalog(legacy,new DataSourceTransactionManager(ds),data.toString());
        assertThat(migrated.get("first").sha256()).isEqualTo(migrated.get("second").sha256()).hasSize(64);
        assertThat(migrated.get("missing").sha256()).isNull();
        assertThat(legacy.queryForObject("SELECT count(*) FROM documents",Integer.class)).isEqualTo(3);
        assertThat(legacy.queryForObject("PRAGMA user_version",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->migrated.upload(upload(),metadata())).isInstanceOf(Catalog.DuplicateDocumentException.class);
        // Erneuter Start ist idempotent und löscht keine bestehenden Dubletten.
        new Catalog(legacy,new DataSourceTransactionManager(ds),data.toString());
        assertThat(legacy.queryForObject("SELECT count(*) FROM documents",Integer.class)).isEqualTo(3);
    }
    @Test void restoredUnhashedOriginalIsIncludedInNextUploadCheck() throws Exception {
        var doc=catalog.upload(upload(),metadata());
        db.update("UPDATE documents SET sha256=NULL WHERE id=?",doc.id());
        Files.delete(catalog.file(doc.path()));setup();
        assertThat(catalog.get(doc.id()).sha256()).isNull();
        Files.write(catalog.file(doc.path()),new byte[]{1,2,3,4});
        assertThatThrownBy(()->catalog.upload(upload(),metadata())).isInstanceOf(Catalog.DuplicateDocumentException.class);
        assertThat(catalog.get(doc.id()).sha256()).isEqualTo(doc.sha256());
    }
    @Test void uploadAcceptsMissingBlankAndWhitespaceDatesWithoutPrefix() throws Exception {
        String[] dates={null,"","   "};
        for(int i=0;i<dates.length;i++) {
            var raw=new Catalog.Metadata(dates[i],"Sparkasse","Finanzen","Info",List.of());
            var doc=catalog.upload(new MockMultipartFile("files","Ohne Datum "+i+".pdf","application/pdf",new byte[]{(byte)(70+i)}),raw);
            assertThat(doc.date()).isEmpty();
            assertThat(doc.path()).isEqualTo("Finanzen/Sparkasse/Ohne Datum "+i+".pdf");
            assertThat(db.queryForObject("SELECT date FROM documents WHERE id=?",String.class,doc.id())).isEmpty();
        }
        setup();assertThat(catalog.search("","","","",List.of(),true)).hasSize(3);
    }
    @Test void undatedFilterCombinesWithOtherFiltersAndExcludesDatedDocuments() throws Exception {
        var undated=new Catalog.Metadata(null,"Sparkasse","Finanzen","Rechnung",List.of("Relevant für Steuererklärung"));
        var one=catalog.upload(upload(),undated);
        catalog.upload(differentUpload(),new Catalog.Metadata("","Sparkasse","Wohnen","Info",List.of()));
        var dated=catalog.upload(new MockMultipartFile("files","Datiert.pdf","application/pdf",new byte[]{71,72}),metadata());
        assertThat(catalog.search("","","","",List.of())).hasSize(3);
        assertThat(catalog.search("Sparkasse","","Finanzen","Rechnung",List.of("Relevant für Steuererklärung"),true)).containsExactly(one);
        assertThat(catalog.search("","2025","","",List.of())).containsExactly(dated);
        assertThat(catalog.search("","2025-06","","",List.of())).containsExactly(dated);
        assertThat(catalog.search("","2025-06-10","","",List.of())).containsExactly(dated);
        assertThat(catalog.search("","2025","","",List.of(),true)).hasSize(2);
        assertThat(catalog.search("","ignored","","",List.of(),true)).hasSize(2);
    }
    @Test void addingAndRemovingDateRequiresConfirmationAndPreservesOriginalAndHash() throws Exception {
        var without=new Catalog.Metadata("","Sparkasse","Finanzen","Rechnung",List.of("Relevant für Steuererklärung"));
        var old=catalog.upload(upload(),without);
        assertThatThrownBy(()->catalog.update(old.id(),metadata(),false)).isInstanceOf(IllegalArgumentException.class);
        var dated=catalog.update(old.id(),metadata(),true);
        assertThat(dated.path()).isEqualTo("Finanzen/Sparkasse/2025-06-10_Kontoauszug.pdf");
        assertThat(catalog.file(old.path())).doesNotExist();
        assertThatThrownBy(()->catalog.update(old.id(),without,false)).isInstanceOf(IllegalArgumentException.class);
        var cleared=catalog.update(old.id(),without,true);
        assertThat(cleared.path()).isEqualTo(old.path());assertThat(cleared.date()).isEmpty();
        assertThat(cleared.sha256()).isEqualTo(old.sha256());
        assertThat(Files.readAllBytes(catalog.file(cleared.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.file(dated.path())).doesNotExist();
    }
    @Test void undatedTagOnlyEditDoesNotMoveOriginalOrRequireConfirmation() throws Exception {
        var raw=new Catalog.Metadata("","Sparkasse","Finanzen","Rechnung",List.of());
        var old=catalog.upload(upload(),raw);
        var changed=catalog.update(old.id(),new Catalog.Metadata(null,"Sparkasse","Finanzen","Info",List.of("Todo")),false);
        assertThat(changed.path()).isEqualTo(old.path());assertThat(changed.date()).isEmpty();
        assertThat(changed.tags()).containsExactly("Todo");
    }
    @Test void removingDateCannotOverwriteAnExistingUndatedFile() throws Exception {
        var old=catalog.upload(upload(),metadata());
        Path target=catalog.file("Finanzen/Sparkasse/Kontoauszug.pdf");Files.writeString(target,"anderes Original");
        var without=new Catalog.Metadata("","Sparkasse","Finanzen","Rechnung",List.of());
        assertThatThrownBy(()->catalog.update(old.id(),without,true)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(catalog.get(old.id()).date()).isEqualTo(old.date());
        assertThat(catalog.file(old.path())).exists();assertThat(Files.readString(target)).isEqualTo("anderes Original");
    }
    @Test void nonEmptyInvalidDatesAreStillRejectedAndDoNotLeaveFiles() {
        for(String date:List.of("2025-02-30","kein Datum","2025-13-01"))
            assertThatThrownBy(()->catalog.upload(upload(),new Catalog.Metadata(date,"Sparkasse","Finanzen","Info",List.of())))
                .isInstanceOf(java.time.DateTimeException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM documents",Integer.class)).isZero();
        assertThatCode(()->assertThat(catalog.check().uncatalogued()).isEmpty()).doesNotThrowAnyException();
    }
    @Test void duplicateRecognitionAlsoWorksWithoutDate() throws Exception {
        var raw=new Catalog.Metadata(null,"Sparkasse","Finanzen","Info",List.of());
        var old=catalog.upload(upload(),raw);
        assertThatThrownBy(()->catalog.upload(upload(),metadata())).isInstanceOfSatisfying(Catalog.DuplicateDocumentException.class,
            e->assertThat(e.existing().id()).isEqualTo(old.id()));
    }
}
