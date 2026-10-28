package de.privat.ablage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class EmptyDirectoryCleanerTest {
    @TempDir Path temp;
    Path root;
    @BeforeEach void setup() throws Exception {
        root=Files.createDirectory(temp.resolve("originale"));
    }
    @Test void childrenAreDeletedBeforeTheirNowEmptyParentsAndRootRemains() throws Exception {
        Files.createDirectories(root.resolve("A/B"));Files.createDirectories(root.resolve("A/C"));
        var result=EmptyDirectoryCleaner.clean(root);
        assertThat(result.deleted()).isEqualTo(3);assertThat(result.failures()).isEmpty();
        assertThat(root.resolve("A")).doesNotExist();assertThat(root).isDirectory();
        assertThat(EmptyDirectoryCleaner.clean(root).deleted()).isZero();
    }
    @Test void allFilesIncludingHiddenEmptyAndUncataloguedFilesArePreserved() throws Exception {
        String[] names={"Original.pdf",".versteckt","leer.zip"};
        for(int i=0;i<names.length;i++) {
            Path dir=Files.createDirectories(root.resolve("Dateien "+i));
            Files.write(dir.resolve(names[i]),i==2?new byte[0]:new byte[]{1,2,3});
            Files.createDirectories(dir.resolve("leerer Unterordner"));
        }
        var result=EmptyDirectoryCleaner.clean(root);
        assertThat(result.deleted()).isEqualTo(3);assertThat(result.failures()).isEmpty();
        for(int i=0;i<names.length;i++) assertThat(root.resolve("Dateien "+i).resolve(names[i])).isRegularFile();
        assertThat(Files.readAllBytes(root.resolve("Dateien 0/Original.pdf"))).containsExactly(new byte[]{1,2,3});
        assertThat(Files.size(root.resolve("Dateien 2/leer.zip"))).isZero();
    }
    @Test void nonEmptyAncestorsAndFoldersOutsideTheArchiveRemain() throws Exception {
        Files.createDirectories(root.resolve("A/B"));Files.createDirectories(root.resolve("A/C/D"));
        Files.writeString(root.resolve("A/C/Notiz.txt"),"Behalten");
        Path outside=Files.createDirectories(temp.resolve("ausserhalb/leer"));
        assertThat(EmptyDirectoryCleaner.clean(root).deleted()).isEqualTo(2);
        assertThat(root.resolve("A/C")).isDirectory();assertThat(outside).isDirectory();
        assertThat(Files.readString(root.resolve("A/C/Notiz.txt"))).isEqualTo("Behalten");
    }
    @Test void evenTheUnderlyingDeleteOperationCannotDeleteRegularFilesOrNonEmptyDirectories() throws Exception {
        Path file=root.resolve("Datei.pdf");Files.write(file,new byte[]{9,8,7});
        Path dir=Files.createDirectory(root.resolve("nicht leer"));Files.writeString(dir.resolve("spaeter hinzugefuegt.txt"),"Neu");
        var operation=new EmptyDirectoryCleaner.DirectoryOnly();
        assertThat(operation.deleteDirectory(file)).isNotZero();
        assertThat(operation.deleteDirectory(dir)).isNotZero();
        assertThat(Files.readAllBytes(file)).containsExactly(new byte[]{9,8,7});
        assertThat(Files.readString(dir.resolve("spaeter hinzugefuegt.txt"))).isEqualTo("Neu");
    }
    @Test void symbolicLinksAreNeitherFollowedNorDeleted() throws Exception {
        Path target=Files.createDirectories(temp.resolve("extern/Ziel/leer"));
        Path parent=Files.createDirectory(root.resolve("Links"));Path link=parent.resolve("link");
        try {Files.createSymbolicLink(link,target.getParent());}
        catch(UnsupportedOperationException|java.io.IOException e){org.junit.jupiter.api.Assumptions.abort("Symlinks nicht verfügbar: "+e.getMessage());}
        var result=EmptyDirectoryCleaner.clean(root);
        assertThat(result.deleted()).isZero();assertThat(Files.isSymbolicLink(link)).isTrue();
        assertThat(parent).isDirectory();assertThat(target).isDirectory();
        assertThatThrownBy(()->EmptyDirectoryCleaner.clean(link)).isInstanceOf(java.io.IOException.class);
        assertThat(target).isDirectory();
    }
    @Test void windowsJunctionsAreNotTraversed() throws Exception {
        if(!com.sun.jna.Platform.isWindows()) return;
        Path target=Files.createDirectories(temp.resolve("junction-target/leer"));
        Path junction=root.resolve("junction");
        var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",junction.toString(),target.getParent().toString()).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).withFailMessage(output).isZero();
        assertThat(EmptyDirectoryCleaner.clean(root).deleted()).isZero();
        assertThat(target).isDirectory();assertThat(junction).exists();
        Files.delete(junction);
    }
}
