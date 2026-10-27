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
        var first=catalog.upload(upload(),metadata());var second=catalog.upload(upload(),metadata());
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
        var old=catalog.upload(upload(),metadata());var other=catalog.upload(upload(),metadata());
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
        var manager=new DataSourceTransactionManager(db.getDataSource()) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                throw new org.springframework.transaction.TransactionSystemException("Simulierter Commitfehler");
            }
        };
        manager.setRollbackOnCommitFailure(true);
        var failing=new Catalog(db,manager,temp.toString());
        assertThatThrownBy(()->failing.delete(old.id(),true,true)).isInstanceOf(RuntimeException.class);
        assertThat(catalog.get(old.id()).tags()).containsExactly("Relevant für Steuererklärung");
        assertThat(Files.readAllBytes(catalog.file(old.path()))).containsExactly(new byte[]{1,2,3,4});
        assertThat(catalog.check().uncatalogued()).isEmpty();
    }
}
