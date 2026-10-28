package de.privat.ablage;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@Controller
public class Web {
    public record DuplicateNotice(String uploadedName, Catalog.Doc existing) {
    }

    private final Catalog catalog;

    public Web(Catalog catalog) {
        this.catalog = catalog;
    }

    @ModelAttribute
    public void lists(Model model) {
        model.addAttribute("mains", catalog.labels("main"));
        model.addAttribute("types", catalog.labels("type"));
        model.addAttribute("extras", catalog.labels("extra"));
        model.addAttribute("senders", catalog.labels("sender"));
    }

    static List<String> tags(String value) {
        return value == null ? List.of() : Arrays.stream(value.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GetMapping("/")
    String index(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String period,
                 @RequestParam(defaultValue = "") String main, @RequestParam(defaultValue = "") String type, @RequestParam(defaultValue = "") String tags, @RequestParam(defaultValue = "false") boolean undated,
                 @RequestParam(defaultValue = "") String createdPeriod, @RequestParam(defaultValue = "1") int page, Model model) {
        var result = catalog.page(q, period, main, type, tags(tags), undated, createdPeriod, page);
        model.addAttribute("docs", result.docs());
        model.addAttribute("result", result);
        model.addAttribute("createdPeriod", createdPeriod);
        model.addAttribute("undated", undated);
        model.addAttribute("q", q);
        model.addAttribute("period", period);
        model.addAttribute("main", main);
        model.addAttribute("type", type);
        model.addAttribute("tagQuery", tags);
        return "index";
    }

    @GetMapping("/upload")
    String upload() {
        return "upload";
    }

    @PostMapping("/upload")
    String upload(@RequestParam MultipartFile[] files, @RequestParam(defaultValue = "") String date, @RequestParam String sender,
                  @RequestParam String main, @RequestParam String type, @RequestParam(defaultValue = "") String tags, RedirectAttributes flash) {
        var metadata = new Catalog.Metadata(date, sender, main, type, tags(tags));
        int count = 0;
        List<String> errors = new ArrayList<>();
        List<DuplicateNotice> duplicates = new ArrayList<>();
        for (var file : files) {
            try {
                catalog.upload(file, metadata);
                count++;
            } catch (Catalog.DuplicateDocumentException e) {
                duplicates.add(new DuplicateNotice(file.getOriginalFilename(), e.existing()));
            } catch (Exception e) {
                errors.add(file.getOriginalFilename() + ": " + message(e));
            }
        }
        if (count > 0 || !errors.isEmpty())
            flash.addFlashAttribute("notice", count + " Dokument(e) gespeichert." + (errors.isEmpty() ? "" : " Fehler: " + String.join(" / ", errors)));
        if (!duplicates.isEmpty()) flash.addFlashAttribute("duplicates", duplicates);
        return "redirect:/";
    }

    @GetMapping("/documents/{id}/edit")
    String edit(@PathVariable String id, Model model) {
        var doc = catalog.get(id);
        boolean available = false;
        String preview = "none";
        try {
            var path = catalog.file(doc.path());
            available = Files.isRegularFile(path) && Files.isReadable(path);
            if (available) {
                var type = previewMediaType(path);
                if (type.isPresent()) preview = type.get().equals(MediaType.APPLICATION_PDF) ? "pdf" : "image";
            }
        } catch (java.io.IOException e) {
            available = false;
        }
        model.addAttribute("doc", doc);
        model.addAttribute("originalAvailable", available);
        model.addAttribute("previewKind", preview);
        return "edit";
    }

    @PostMapping("/documents/{id}/edit")
    String edit(@PathVariable String id, @RequestParam(defaultValue = "") String date, @RequestParam String sender, @RequestParam String main,
                @RequestParam String type, @RequestParam(required = false) String filename, @RequestParam(defaultValue = "") String tags, @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes flash) throws Exception {
        String name = filename == null ? catalog.get(id).filename() : filename;
        catalog.update(id, new Catalog.Metadata(date, sender, main, type, tags(tags)), name, confirmed);
        flash.addFlashAttribute("notice", "Änderungen gespeichert.");
        return "redirect:/";
    }

    @GetMapping("/documents/{id}/delete")
    String delete(@PathVariable String id, Model model) {
        model.addAttribute("doc", catalog.get(id));
        return "delete";
    }

    @PostMapping("/documents/{id}/delete")
    String delete(@PathVariable String id, @RequestParam String originalAction,
                  @RequestParam(defaultValue = "false") boolean confirmed, RedirectAttributes flash) throws Exception {
        if (!List.of("keep", "delete").contains(originalAction))
            throw new IllegalArgumentException("Bitte wählen, ob die Originaldatei erhalten bleiben soll.");
        boolean removeFile = originalAction.equals("delete");
        var result = catalog.delete(id, removeFile, confirmed);
        String notice = removeFile ? "Katalogeintrag und Originaldatei gelöscht (falls vorhanden)." : "Katalogeintrag gelöscht. Die Originaldatei bleibt erhalten und wird im Dateiabgleich als nicht katalogisiert angezeigt.";
        if (result.retainedBackup() != null)
            notice += " Eine temporäre Sicherungskopie konnte nicht entfernt werden: " + result.retainedBackup() + ". Bitte im Dateiabgleich prüfen und manuell entfernen.";
        flash.addFlashAttribute("notice", notice);
        return "redirect:/";
    }

    @GetMapping("/documents/{id}/file")
    ResponseEntity<FileSystemResource> download(@PathVariable String id) throws Exception {
        var doc = catalog.get(id);
        var path = catalog.file(doc.path());
        if (!Files.isRegularFile(path))
            throw new IllegalArgumentException("Originaldatei fehlt. Bitte Dateiabgleich ausführen.");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(doc.filename(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }

    // Nur passive Bildformate und PDF inline ausliefern, nie HTML oder SVG.
    // Die Signatur verhindert, dass eine umbenannte HTML-Datei als Vorschau angeboten wird.
    private static Optional<MediaType> previewMediaType(Path path) throws java.io.IOException {
        byte[] prefix;
        try (var in = Files.newInputStream(path)) {
            prefix = in.readNBytes(12);
        }
        if (startsWith(prefix, new byte[]{37, 80, 68, 70, 45})) return Optional.of(MediaType.APPLICATION_PDF);
        if (startsWith(prefix, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
            return Optional.of(MediaType.IMAGE_PNG);
        if (startsWith(prefix, new byte[]{(byte) 255, (byte) 216, (byte) 255}))
            return Optional.of(MediaType.IMAGE_JPEG);
        if (startsWith(prefix, "GIF87a".getBytes(StandardCharsets.US_ASCII)) || startsWith(prefix, "GIF89a".getBytes(StandardCharsets.US_ASCII)))
            return Optional.of(MediaType.IMAGE_GIF);
        if (prefix.length >= 12 && new String(prefix, 0, 4, StandardCharsets.US_ASCII).equals("RIFF") && new String(prefix, 8, 4, StandardCharsets.US_ASCII).equals("WEBP"))
            return Optional.of(MediaType.parseMediaType("image/webp"));
        if (startsWith(prefix, new byte[]{66, 77})) return Optional.of(MediaType.parseMediaType("image/bmp"));
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }

    @GetMapping("/documents/{id}/preview")
    ResponseEntity<FileSystemResource> preview(@PathVariable String id) throws Exception {
        var doc = catalog.get(id);
        var path = catalog.file(doc.path());
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) return ResponseEntity.notFound().build();
        var type = previewMediaType(path);
        if (type.isEmpty()) return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
        return ResponseEntity.ok().contentType(type.get()).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(doc.filename(), StandardCharsets.UTF_8).build().toString())
                .header("Content-Security-Policy", "frame-ancestors 'self'")
                .body(new FileSystemResource(path));
    }

    @GetMapping("/check")
    String check(Model model) throws Exception {
        model.addAttribute("check", catalog.check());
        model.addAttribute("root", catalog.root());
        return "check";
    }

    @GetMapping("/tags")
    String labels() {
        return "tags";
    }

    @PostMapping("/tags")
    String labels(@RequestParam String kind, @RequestParam String name, RedirectAttributes flash) {
        catalog.label(kind, name);
        flash.addFlashAttribute("notice", "Eintrag angelegt bzw. vorhandene Schreibweise wiederverwendet.");
        return "redirect:/tags";
    }

    private static String message(Exception e) {
        if (e instanceof FileAlreadyExistsException)
            return "Der Zielname ist bereits belegt. Es wurde nichts überschrieben.";
        return e instanceof IllegalArgumentException || e instanceof java.time.DateTimeException ? e.getMessage() : "Vorgang fehlgeschlagen. Prüfe Dateinamen, Schreibrechte und Dateiabgleich.";
    }

    @ExceptionHandler(Exception.class)
    String error(Exception e, Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setStatus(400);
        model.addAttribute("error", message(e));
        return "error";
    }
}
