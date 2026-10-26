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
    private final Catalog catalog;
    public Web(Catalog catalog) { this.catalog=catalog; }
    @ModelAttribute public void lists(Model model) {
        model.addAttribute("mains",catalog.labels("main")); model.addAttribute("types",catalog.labels("type"));
        model.addAttribute("extras",catalog.labels("extra")); model.addAttribute("senders",catalog.labels("sender"));
    }
    static List<String> tags(String value) { return value==null?List.of():Arrays.stream(value.split(";")).map(String::trim).filter(s->!s.isEmpty()).toList(); }
    @GetMapping("/") String index(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String period,
        @RequestParam(defaultValue="") String main,@RequestParam(defaultValue="") String type,@RequestParam(defaultValue="") String tags,Model model) {
        model.addAttribute("docs",catalog.search(q,period,main,type,tags(tags)));
        model.addAttribute("q",q); model.addAttribute("period",period);model.addAttribute("main",main);model.addAttribute("type",type);model.addAttribute("tagQuery",tags);
        return "index";
    }
    @GetMapping("/upload") String upload(Model model) { model.addAttribute("today",java.time.LocalDate.now().toString());return "upload"; }
    @PostMapping("/upload") String upload(@RequestParam MultipartFile[] files,@RequestParam String date,@RequestParam String sender,
        @RequestParam String main,@RequestParam String type,@RequestParam(defaultValue="") String tags,RedirectAttributes flash) {
        var metadata=new Catalog.Metadata(date,sender,main,type,tags(tags));
        int count=0; List<String> errors=new ArrayList<>();
        for(var file:files) { try {catalog.upload(file,metadata);count++;}catch(Exception e) {errors.add(file.getOriginalFilename()+": "+message(e));} }
        flash.addFlashAttribute("notice",count+" Dokument(e) gespeichert."+(errors.isEmpty()?"":" Fehler: "+String.join(" / ",errors)));
        return "redirect:/";
    }
    @GetMapping("/documents/{id}/edit") String edit(@PathVariable String id,Model model) { model.addAttribute("doc",catalog.get(id));return "edit"; }
    @PostMapping("/documents/{id}/edit") String edit(@PathVariable String id,@RequestParam String date,@RequestParam String sender,@RequestParam String main,
        @RequestParam String type,@RequestParam(defaultValue="") String tags,@RequestParam(defaultValue="false") boolean confirmed,RedirectAttributes flash) throws Exception {
        catalog.update(id,new Catalog.Metadata(date,sender,main,type,tags(tags)),confirmed);
        flash.addFlashAttribute("notice","Änderungen gespeichert."); return "redirect:/";
    }
    @GetMapping("/documents/{id}/file") ResponseEntity<FileSystemResource> download(@PathVariable String id) throws Exception {
        var doc=catalog.get(id);var path=catalog.file(doc.path());
        if(!Files.isRegularFile(path)) throw new IllegalArgumentException("Originaldatei fehlt. Bitte Dateiabgleich ausführen.");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(doc.filename(),StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(path));
    }
    @GetMapping("/check") String check(Model model) throws Exception {model.addAttribute("check",catalog.check());model.addAttribute("root",catalog.root());return "check";}
    @GetMapping("/tags") String labels() {return "tags";}
    @PostMapping("/tags") String labels(@RequestParam String kind,@RequestParam String name,RedirectAttributes flash) {
        catalog.label(kind,name);flash.addFlashAttribute("notice","Eintrag angelegt bzw. vorhandene Schreibweise wiederverwendet.");return "redirect:/tags";
    }
    private static String message(Exception e) {
        return e instanceof IllegalArgumentException || e instanceof java.time.DateTimeException ? e.getMessage() : "Speichern fehlgeschlagen. Prüfe Dateinamen, Schreibrechte und Dateiabgleich.";
    }
    @ExceptionHandler(Exception.class) String error(Exception e,Model model,jakarta.servlet.http.HttpServletResponse response) {
        response.setStatus(400);model.addAttribute("error",message(e));return "error";
    }
}
