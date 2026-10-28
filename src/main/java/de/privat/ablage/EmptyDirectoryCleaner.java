package de.privat.ablage;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/** Entfernt ausschließlich tatsächlich leere Verzeichnisse, ohne Datenbankzugriff. */
public final class EmptyDirectoryCleaner {
    public record Result(int deleted, List<String> failures) { }

    public interface Posix extends Library {
        int rmdir(String path);
    }

    public interface Windows extends StdCallLibrary {
        int GetFileAttributesW(WString path);
        Pointer CreateFileW(WString path, int access, int share, Pointer security, int disposition, int flags, Pointer template);
        boolean GetFileInformationByHandleEx(Pointer handle, int type, Pointer info, int size);
        boolean SetFileInformationByHandle(Pointer handle, int type, Pointer info, int size);
        boolean CloseHandle(Pointer handle);
    }

    static final class DirectoryOnly {
        private final Windows windows;
        private final Posix posix;

        DirectoryOnly() {
            windows = Platform.isWindows() ? Native.load("kernel32", Windows.class) : null;
            posix = windows == null ? Native.load(Platform.C_LIBRARY_NAME, Posix.class) : null;
        }

        private WString windowsPath(Path path) {
            String value = path.toAbsolutePath().normalize().toString();
            if (!value.startsWith("\\\\?\\"))
                value = value.startsWith("\\\\") ? "\\\\?\\UNC\\" + value.substring(2) : "\\\\?\\" + value;
            return new WString(value);
        }

        boolean isLink(Path path) throws IOException {
            if (Files.isSymbolicLink(path)) return true;
            if (windows == null) return false;
            int attributes = windows.GetFileAttributesW(windowsPath(path));
            if (attributes == -1) throw new IOException("Ordnerattribute nicht lesbar (Windows " + Native.getLastError() + ").");
            return (attributes & 0x400) != 0; // auch Junctions und andere Reparse Points auslassen
        }

        private int failureCode() {
            int code = Native.getLastError();
            return code == 0 ? Integer.MIN_VALUE : code;
        }

        /** 0 = gelöscht, -1 = falscher Typ/Link; sonst Betriebssystem-Fehlercode. */
        int deleteDirectory(Path path) {
            if (windows == null) {
                // rmdir kann niemals eine reguläre Datei oder einen symbolischen Link löschen.
                return posix.rmdir(path.toString()) == 0 ? 0 : failureCode();
            }
            // Ein geöffnetes Handle bindet die Prüfung und Löschung an dasselbe Verzeichnis.
            // OPEN_REPARSE_POINT verhindert das Folgen von Links/Junctions.
            Pointer handle = windows.CreateFileW(windowsPath(path), 0x10000 | 0x80, 7, null, 3,
                    0x02000000 | 0x00200000, null);
            if (handle == null || Pointer.nativeValue(handle) == -1L || Pointer.nativeValue(handle) == 0xffffffffL)
                return failureCode();
            try {
                try (Memory attributes = new Memory(8); Memory disposition = new Memory(1)) {
                    if (!windows.GetFileInformationByHandleEx(handle, 9, attributes, 8)) return failureCode();
                    int flags = attributes.getInt(0);
                    if ((flags & 0x10) == 0 || (flags & 0x400) != 0) return -1;
                    disposition.setByte(0, (byte) 1);
                    // FileDispositionInfo: Das Betriebssystem verweigert nicht leere Ordner atomar.
                    if (!windows.SetFileInformationByHandle(handle, 4, disposition, 1)) return failureCode();
                    return 0;
                }
            } finally {
                windows.CloseHandle(handle);
            }
        }

        boolean harmless(int code) {
            return code == -1 || code == 2 || (windows != null ? code == 3 || code == 145 || code == 267
                    : code == 17 || code == 20 || code == 39 || code == 66);
        }
    }

    static Result clean(Path configuredRoot) throws IOException {
        final DirectoryOnly directories;
        try { directories = new DirectoryOnly(); }
        catch (LinkageError e) { throw new IOException("Verzeichnis-Löschfunktion nicht verfügbar.", e); }
        if (!Files.isDirectory(configuredRoot, LinkOption.NOFOLLOW_LINKS) || directories.isLink(configuredRoot))
            throw new IOException("Der Ablage-Stammordner muss ein echtes Verzeichnis sein.");
        Path root = configuredRoot.toRealPath();
        int[] deleted = {0};
        List<String> failures = new ArrayList<>();
        // Ohne FOLLOW_LINKS: Dateien und Links werden weder gelöscht noch durchlaufen.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return directories.isLink(dir) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFileFailed(Path file, IOException error) {
                failures.add(root.relativize(file) + ": nicht lesbar.");
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) {
                if (dir.equals(root)) return FileVisitResult.CONTINUE;
                if (error != null) {
                    failures.add(root.relativize(dir) + ": konnte nicht vollständig geprüft werden.");
                    return FileVisitResult.CONTINUE;
                }
                try {
                    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) || directories.isLink(dir)) return FileVisitResult.CONTINUE;
                    if (!dir.toRealPath().startsWith(root)) return FileVisitResult.CONTINUE;
                    int code = directories.deleteDirectory(dir);
                    if (code == 0) deleted[0]++;
                    else if (!directories.harmless(code)) failures.add(root.relativize(dir) + ": nicht gelöscht (Betriebssystem-Fehler " + code + ").");
                } catch (IOException e) {
                    failures.add(root.relativize(dir) + ": nicht gelöscht.");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Result(deleted[0], List.copyOf(failures));
    }
}
