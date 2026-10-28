package de.privat.ablage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc
class WebTest {
    @TempDir static Path temp;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {registry.add("app.data",()->temp.toString());}
    @Autowired MockMvc mvc;
    @Autowired Catalog catalog;
    @Test void allPagesRender() throws Exception {
        for(String path:new String[]{"/","/upload","/tags","/check"}) mvc.perform(get(path)).andExpect(status().isOk());
    }
    @Test void csrfRejectsUntrustedWrites() throws Exception {
        mvc.perform(post("/tags").param("kind","extra").param("name","Angriff")).andExpect(status().isForbidden());
        assertThat(catalog.labels("extra")).doesNotContain("Angriff");
    }
    @Test void uploadEditDownloadAndSearch() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files","Test.pdf","application/pdf",new byte[]{5,6,7}))
            .param("date","2025-06-10").param("sender","ADAC").param("main","Finanzen").param("type","Rechnung")
            .param("tags","Relevant für Steuererklärung").with(csrf())).andExpect(status().is3xxRedirection());
        var doc=catalog.search("Test.pdf","2025","","",java.util.List.of()).getFirst();
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk());
        mvc.perform(get("/documents/"+doc.id()+"/file")).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{5,6,7}));
        mvc.perform(get("/").param("period","2025").param("type","Rechnung").param("tags","Relevant für Steuererklärung"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Test.pdf")));
        mvc.perform(post("/documents/"+doc.id()+"/edit").with(csrf()).param("date","2026-01-01").param("sender","ADAC")
            .param("main","Finanzen").param("type","Rechnung")).andExpect(status().isBadRequest());
        assertThat(catalog.get(doc.id()).date()).isEqualTo("2025-06-10");
    }
    Catalog.Doc document(String name) throws Exception {
        return catalog.upload(new MockMultipartFile("files",name,"application/pdf",name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            new Catalog.Metadata("2025-10-27","ADAC","Finanzen","Rechnung",java.util.List.of()));
    }
    @Test void renameThroughBrowserForm() throws Exception {
        var doc=document("Vorher.pdf");
        mvc.perform(post("/documents/"+doc.id()+"/edit").with(csrf()).param("date",doc.date()).param("sender",doc.sender())
            .param("main",doc.main()).param("type",doc.type()).param("filename","Nachher.pdf").param("confirmed","true"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).endsWith("2025-10-27_Nachher.pdf");
        mvc.perform(get("/documents/"+doc.id()+"/file")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.containsString("Nachher.pdf")));
    }
    @Test void deletePageDoesNotDeleteAndRequiresCsrfChoiceAndConfirmation() throws Exception {
        var doc=document("Bestaetigen.pdf");String route="/documents/"+doc.id()+"/delete";
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Originaldatei behalten")));
        mvc.perform(post(route).param("originalAction","delete").param("confirmed","true")).andExpect(status().isForbidden());
        mvc.perform(post(route).with(csrf()).param("originalAction","delete")).andExpect(status().isBadRequest());
        mvc.perform(post(route).with(csrf()).param("originalAction","unknown").param("confirmed","true")).andExpect(status().isBadRequest());
        mvc.perform(post(route).with(csrf()).param("confirmed","true")).andExpect(status().isBadRequest());
        assertThat(catalog.get(doc.id()).path()).isEqualTo(doc.path());assertThat(catalog.file(doc.path())).exists();
    }
    @Test void deleteInBothModesThroughBrowserForm() throws Exception {
        for(String action:new String[]{"keep","delete"}) {
            var doc=document("Loeschen-"+action+".pdf");
            mvc.perform(post("/documents/"+doc.id()+"/delete").with(csrf()).param("originalAction",action).param("confirmed","true"))
                .andExpect(status().is3xxRedirection());
            assertThatThrownBy(()->catalog.get(doc.id())).isInstanceOf(IllegalArgumentException.class);
            assertThat(java.nio.file.Files.exists(catalog.file(doc.path()))).isEqualTo(action.equals("keep"));
        }
    }
    @Test void duplicateUploadShowsMessageWithLinkInsteadOfCreatingCopy() throws Exception {
        var existing=document("Bereits vorhanden.pdf");
        byte[] bytes=filesForTest(existing);
        var response=mvc.perform(multipart("/upload").file(new MockMultipartFile("files","Anderer Name.pdf","application/pdf",bytes))
            .param("date","2026-10-27").param("sender","Anderer Absender").param("main","Wohnen").param("type","Info").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("duplicates"))
            .andExpect(flash().attributeCount(1)).andReturn();
        mvc.perform(get("/").flashAttrs(response.getFlashMap())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.matchesPattern("(?s).*Die Datei existiert\\s+bereits\\..*")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/documents/"+existing.id()+"/edit\"")));
        assertThat(catalog.search("Anderer Name.pdf","","","",java.util.List.of())).isEmpty();
        assertThat(catalog.get(existing.id()).sender()).isEqualTo("ADAC");
    }
    byte[] filesForTest(Catalog.Doc doc) throws Exception { return java.nio.file.Files.readAllBytes(catalog.file(doc.path())); }
    @Test void mixedBatchStoresNewFilesAndReportsAllDuplicates() throws Exception {
        var existing=document("Mischung vorhanden.pdf");
        byte[] same=filesForTest(existing);
        var response=mvc.perform(multipart("/upload")
            .file(new MockMultipartFile("files","Duplikat 1.pdf","application/pdf",same))
            .file(new MockMultipartFile("files","Neu.pdf","application/pdf",new byte[]{21,22,23,24}))
            .file(new MockMultipartFile("files","Duplikat 2.pdf","application/pdf",same))
            .param("date","2026-10-27").param("sender","Batch").param("main","Finanzen").param("type","Info").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("notice","1 Dokument(e) gespeichert."))
            .andReturn();
        assertThat((java.util.List<?>)response.getFlashMap().get("duplicates")).hasSize(2);
        assertThat(catalog.search("Neu.pdf","2026-10-27","","",java.util.List.of())).hasSize(1);
        mvc.perform(get("/").flashAttrs(response.getFlashMap())).andExpect(status().isOk());
    }
    @Test void duplicatesWithinSameBatchAreReportedWithWorkingLink() throws Exception {
        var response=mvc.perform(multipart("/upload")
            .file(new MockMultipartFile("files","Erste Kopie.pdf","application/pdf",new byte[]{41,42,43}))
            .file(new MockMultipartFile("files","Zweite Kopie.pdf","application/pdf",new byte[]{41,42,43}))
            .param("date","2026-10-27").param("sender","Gleicher Batch").param("main","Finanzen").param("type","Info").with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("notice","1 Dokument(e) gespeichert."))
            .andReturn();
        var notices=(java.util.List<?>)response.getFlashMap().get("duplicates");
        assertThat(notices).hasSize(1);
        var existing=((Web.DuplicateNotice)notices.getFirst()).existing();
        mvc.perform(get("/documents/"+existing.id()+"/edit")).andExpect(status().isOk());
    }
    Catalog.Doc previewDocument(String name,byte[] bytes) throws Exception {
        return catalog.upload(new MockMultipartFile("files",name,"application/octet-stream",bytes),
            new Catalog.Metadata("2025-10-27","Vorschautest","Finanzen","Info",java.util.List.of()));
    }
    @Test void documentPageIncludesDownloadAndFallbackForUnsupportedFormat() throws Exception {
        var doc=previewDocument("Archiv.zip",new byte[]{80,75,3,4,1,2,3});
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/documents/"+doc.id()+"/file\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("keine direkte Vorschau")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<iframe"))));
        mvc.perform(get("/documents/"+doc.id()+"/preview")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get("/documents/"+doc.id()+"/file")).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")))
            .andExpect(content().bytes(filesForTest(doc)));
    }
    @Test void imagePreviewUsesContentSignatureAndAllowsSameOriginEmbedding() throws Exception {
        byte[] png=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        // Selbst nach einer Umbenennung bleibt die Vorschau anhand des Inhalts korrekt.
        var doc=previewDocument("Bild ohne Bildendung.dat",png);
        mvc.perform(get("/documents/"+doc.id()+"/preview")).andExpect(status().isOk())
            .andExpect(content().contentType("image/png")).andExpect(content().bytes(png))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("inline;")))
            .andExpect(header().string("X-Frame-Options","SAMEORIGIN"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<img")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("src=\"/documents/"+doc.id()+"/preview\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("In neuem Tab öffnen")));
    }
    byte[] samplePdf() {
        var pdf=new StringBuilder("%PDF-1.4\n");
        var objects=java.util.List.of("<< /Type /Catalog /Pages 2 0 R >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] >>");
        var offsets=new java.util.ArrayList<Integer>();
        for(int i=0;i<objects.size();i++) { offsets.add(pdf.length());pdf.append(i+1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n"); }
        int start=pdf.length();pdf.append("xref\n0 4\n0000000000 65535 f \n");
        for(int offset:offsets) pdf.append(String.format(java.util.Locale.ROOT,"%010d 00000 n \n",offset));
        pdf.append("trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n").append(start).append("\n%%EOF\n");
        return pdf.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
    @Test void pdfPreviewSupportsBrowserViewerAndByteRanges() throws Exception {
        byte[] pdf=samplePdf();var doc=previewDocument("Vorschau.pdf",pdf);
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<iframe")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("title=\"PDF-Vorschau\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/documents/"+doc.id()+"/file\"")));
        mvc.perform(get("/documents/"+doc.id()+"/preview")).andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf")).andExpect(content().bytes(pdf))
            .andExpect(header().string("X-Frame-Options","SAMEORIGIN"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("inline;")));
        mvc.perform(get("/documents/"+doc.id()+"/preview").header("Range","bytes=0-4"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range","bytes 0-4/"+pdf.length))
            .andExpect(content().bytes(new byte[]{37,80,68,70,45}));
    }
    @Test void activeContentCannotBeDisplayedInlineEvenWithPdfOrImageExtension() throws Exception {
        for(String name:new String[]{"Falsches PDF.pdf","Falsches Bild.png","Aktives Bild.svg"}) {
            byte[] bytes=("<svg xmlns='http://www.w3.org/2000/svg'><script>alert('"+name+"')</script></svg>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var doc=previewDocument(name,bytes);
            mvc.perform(get("/documents/"+doc.id()+"/preview")).andExpect(status().isUnsupportedMediaType());
            mvc.perform(get("/documents/"+doc.id()+"/file")).andExpect(status().isOk())
                .andExpect(content().contentType("application/octet-stream"))
                .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;")));
            mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<iframe"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<img"))));
        }
    }
    @Test void missingOriginalShowsNoticeWithoutBreakingMetadataEditing() throws Exception {
        var doc=document("Fehlendes Original.pdf");java.nio.file.Files.delete(catalog.file(doc.path()));
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Originaldatei fehlt oder ist nicht lesbar")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"filename\"")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<iframe"))));
        mvc.perform(get("/documents/"+doc.id()+"/preview")).andExpect(status().isNotFound());
    }
    @Test void uploadWithoutDateAndLaterAddOrClearDateThroughForm() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files","OptionalesDatum.pdf","application/pdf",new byte[]{81,82,83}))
            .param("sender","Datumstest").param("main","Finanzen").param("type","Info").with(csrf()))
            .andExpect(status().is3xxRedirection());
        var doc=catalog.search("OptionalesDatum.pdf","","","",java.util.List.of(),true).getFirst();
        assertThat(doc.date()).isEmpty();assertThat(doc.path()).endsWith("/OptionalesDatum.pdf");
        String edit="/documents/"+doc.id()+"/edit";
        mvc.perform(post(edit).with(csrf()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("date","2026-10-27")).andExpect(status().isBadRequest());
        mvc.perform(post(edit).with(csrf()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("date","2026-10-27").param("confirmed","true")).andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).endsWith("/2026-10-27_OptionalesDatum.pdf");
        // Kein date-Parameter entspricht ebenfalls einem leeren Datum.
        mvc.perform(post(edit).with(csrf()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("confirmed","true")).andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).isEqualTo(doc.path());
        mvc.perform(get(edit)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Datum (optional)")));
        mvc.perform(get("/documents/"+doc.id()+"/delete")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Ohne Datum")));
    }
    @Test void undatedFilterIsAvailableInBrowserAndCombinesWithSearchAndTags() throws Exception {
        var undated=catalog.upload(new MockMultipartFile("files","FilterDatum ohne.pdf","application/pdf",new byte[]{84,85}),
            new Catalog.Metadata("","Datumsfilter","Finanzen","Info",java.util.List.of("Todo")));
        var dated=catalog.upload(new MockMultipartFile("files","FilterDatum mit.pdf","application/pdf",new byte[]{86,87}),
            new Catalog.Metadata("2025-06-10","Datumsfilter","Finanzen","Info",java.util.List.of("Todo")));
        mvc.perform(get("/").param("q","FilterDatum").param("undated","true").param("period","2025").param("tags","Todo"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",java.util.List.of(undated)))
            .andExpect(model().attribute("undated",true))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Nur Einträge ohne Datum")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("<td>Ohne Datum</td>")));
        mvc.perform(get("/").param("q","FilterDatum").param("period","2025"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",java.util.List.of(dated)));
        mvc.perform(get("/").param("q","FilterDatum"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(2)));
    }
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.JdbcTemplate captureDb;
    Catalog.Doc webCaptured(String name,String created) {
        String id=java.util.UUID.randomUUID().toString();
        captureDb.update("INSERT INTO documents(id,date,sender,main,type,filename,path,created_at) VALUES(?,?,?,?,?,?,?,?)",
            id,"","HTTP Archiv","Finanzen","Info",name,"http-fixtures/"+id+"/"+name,created);
        return catalog.get(id);
    }
    @Test void homepageDefaultsToThirtyNewestAndPreservesFiltersWhenPaging() throws Exception {
        var inserted=new java.util.ArrayList<Catalog.Doc>();
        for(int i=0;i<35;i++) inserted.add(webCaptured("Seitenprobe "+i+".pdf",
            java.time.Instant.parse("2099-10-28T12:00:00Z").plusSeconds(i).toString()));
        var response=mvc.perform(get("/")).andExpect(status().isOk())
            .andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(30))).andReturn();
        var visible=(java.util.List<Catalog.Doc>)response.getModelAndView().getModel().get("docs");
        assertThat(visible.getFirst().id()).isEqualTo(inserted.getLast().id());
        assertThat(visible.getLast().id()).isEqualTo(inserted.get(5).id());
        mvc.perform(get("/").param("q","Seitenprobe").param("createdPeriod","2099").param("undated","true").param("main","Finanzen"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(30)))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("page=2")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("createdPeriod=2099")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("q=Seitenprobe")));
        mvc.perform(get("/").param("q","Seitenprobe").param("createdPeriod","2099").param("page","2"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(5)));
    }
    @Test void captureTimestampIsDisplayedWithoutEditableFieldAndCannotBeForged() throws Exception {
        var doc=document("Erfassung unveraenderlich.pdf");
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Erfasst am:")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString(doc.createdAtLabel())))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"createdAt\""))));
        mvc.perform(post("/documents/"+doc.id()+"/edit").with(csrf()).param("date",doc.date()).param("sender",doc.sender())
            .param("main",doc.main()).param("type",doc.type()).param("createdAt","2000-01-01T00:00:00Z"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).createdAt()).isEqualTo(doc.createdAt());
        var wanted=webCaptured("Erfassungsfilter HTTP.pdf","2098-10-28T11:00:00.000Z");
        mvc.perform(get("/").param("q","Erfassungsfilter HTTP").param("createdPeriod","2098-10-28"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",java.util.List.of(wanted)));
    }
    @Test void multilineCommentCanBeEditedSearchedAndClearedThroughBrowser() throws Exception {
        var doc=document("Kommentartest.pdf");String route="/documents/"+doc.id()+"/edit";
        String comment="Rückruf beim Anbieter\nKulanzregelung noch offen.";
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("comment",comment)).andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).comment()).isEqualTo(comment);
        assertThat(catalog.get(doc.id()).path()).isEqualTo(doc.path());
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"comment\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("edit-layout")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Kulanzregelung noch offen.")));
        mvc.perform(get("/").param("q","KULANZREGELUNG")).andExpect(status().isOk())
            .andExpect(model().attribute("docs",java.util.List.of(catalog.get(doc.id()))));
        // Fehlender Parameter bleibt abwärtskompatibel; ein explizit leeres Feld löscht die Notiz.
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type()))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).comment()).isEqualTo(comment);
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("comment","")).andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).comment()).isEmpty();
    }
    @Test void commentIsEscapedInsideTextareaRatherThanRenderedAsHtml() throws Exception {
        var doc=document("Sicherer Kommentar.pdf");
        String comment="</textarea><script>alert(123)</script>\nNotiz";
        mvc.perform(post("/documents/"+doc.id()+"/edit").with(csrf()).param("date",doc.date()).param("sender",doc.sender())
            .param("main",doc.main()).param("type",doc.type()).param("comment",comment)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;/textarea&gt;&lt;script&gt;")))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert(123)</script>"))));
        assertThat(catalog.get(doc.id()).comment()).isEqualTo(comment);
    }
    @Test void uploadIncludesOptionalComment() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files","Notiz beim Upload.pdf","application/pdf",new byte[]{91,92,93}))
            .param("sender","Notiztest").param("main","Finanzen").param("type","Info").param("comment","Telefonat\nNachreichung vereinbart.")
            .with(csrf())).andExpect(status().is3xxRedirection());
        var docs=catalog.search("Nachreichung","","","",java.util.List.of());
        assertThat(docs).hasSize(1);assertThat(docs.getFirst().comment()).isEqualTo("Telefonat\nNachreichung vereinbart.");
    }
    @Test void browserUploadAndRenameAvoidDuplicateDatePrefixes() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files","2026-04-27_2026-04-27_HTTP Präfix.pdf","application/pdf",new byte[]{109,110}))
            .param("date","2026-04-27").param("sender","Prefix HTTP").param("main","Finanzen").param("type","Info").with(csrf()))
            .andExpect(status().is3xxRedirection());
        var doc=catalog.search("HTTP Präfix","","","",java.util.List.of()).getFirst();
        assertThat(doc.filename()).isEqualTo("HTTP Präfix.pdf");assertThat(doc.path()).endsWith("/2026-04-27_HTTP Präfix.pdf");
        mvc.perform(post("/documents/"+doc.id()+"/edit").with(csrf()).param("date",doc.date()).param("sender",doc.sender())
            .param("main",doc.main()).param("type",doc.type()).param("filename","2025-01-01_2024-01-01_HTTP Umbenannt.pdf").param("confirmed","true"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).endsWith("/2026-04-27_HTTP Umbenannt.pdf");
    }
    @Test void editPageProposesCanonicalLegacyNameButRequiresConfirmationToSave() throws Exception {
        var doc=document("BrowserPrefixLegacy.pdf");String legacyName=doc.date()+"_"+doc.filename();
        String legacyPath="Finanzen/ADAC/"+doc.date()+"_"+legacyName;
        java.nio.file.Files.move(catalog.file(doc.path()),catalog.file(legacyPath));
        captureDb.update("UPDATE documents SET filename=?,path=? WHERE id=?",legacyName,legacyPath,doc.id());
        String route="/documents/"+doc.id()+"/edit";
        mvc.perform(get(route)).andExpect(status().isOk()).andExpect(model().attribute("editFilename","BrowserPrefixLegacy.pdf"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-filename=\""+legacyName+"\"")));
        assertThat(catalog.get(doc.id()).path()).isEqualTo(legacyPath);
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main())
            .param("type",doc.type()).param("filename","BrowserPrefixLegacy.pdf")).andExpect(status().isBadRequest());
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main())
            .param("type",doc.type()).param("filename","BrowserPrefixLegacy.pdf").param("confirmed","true"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).isEqualTo(doc.path());
        assertThat(catalog.get(doc.id()).createdAt()).isEqualTo(doc.createdAt());
    }
    @Test void uploadAndEditSupportOptionalSubcategoryWithConfirmation() throws Exception {
        mvc.perform(multipart("/upload").file(new MockMultipartFile("files","Unterkategorie HTTP.pdf","application/pdf",new byte[]{111,112,113}))
            .param("date","2026-04-27").param("sender","Sub Test").param("main","Finanzen").param("type","Info").param("subcategory","Steuer")
            .with(csrf())).andExpect(status().is3xxRedirection());
        var doc=catalog.search("Unterkategorie HTTP","","","",java.util.List.of()).getFirst();
        assertThat(doc.path()).isEqualTo("Finanzen/Steuer/Sub Test/2026-04-27_Unterkategorie HTTP.pdf");
        mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"subcategory\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-subcategory=\"Steuer\"")));
        String route="/documents/"+doc.id()+"/edit";
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("subcategory","")).andExpect(status().isBadRequest());
        mvc.perform(post(route).with(csrf()).param("date",doc.date()).param("sender",doc.sender()).param("main",doc.main()).param("type",doc.type())
            .param("subcategory","").param("confirmed","true")).andExpect(status().is3xxRedirection());
        assertThat(catalog.get(doc.id()).path()).isEqualTo("Finanzen/Sub Test/2026-04-27_Unterkategorie HTTP.pdf");
    }
    @Test void subcategoriesCanBeManagedWithAParentAndAreAvailableAsSuggestions() throws Exception {
        mvc.perform(post("/tags").with(csrf()).param("kind","sub").param("name","Haus A").param("main","Häuser"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/tags").with(csrf()).param("kind","sub").param("name","Ohne Elternkategorie"))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/tags")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Haus A")));
        mvc.perform(get("/upload")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"subcategoryChoices\"")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("data-main=\"Häuser\"")));
    }
    @Test void subcategoryFilterIsDisplayedAndPreservedInPaginationLinks() throws Exception {
        String scope="HTTP Filter Unterkategorie";
        for(int i=0;i<31;i++) {
            var doc=webCaptured("Sub Paging "+i+".pdf",java.time.Instant.parse("2097-01-01T00:00:00Z").plusSeconds(i).toString());
            captureDb.update("UPDATE documents SET subcategory=? WHERE id=?",scope,doc.id());
        }
        mvc.perform(get("/").param("subcategory",scope).param("main","Finanzen"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(30)))
            .andExpect(model().attribute("subcategory",scope))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("subcategory=")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("page=2")));
        mvc.perform(get("/").param("subcategory",scope).param("main","Finanzen").param("page","2"))
            .andExpect(status().isOk()).andExpect(model().attribute("docs",org.hamcrest.Matchers.hasSize(1)));
    }
    @Test void unknownSenderIsSuggestedAndUploadsHaveNoSenderDirectory() throws Exception {
        mvc.perform(get("/upload")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"Unbekannt\"")));
        for(int i=0;i<2;i++) {
            String child=i==0?"":"Steuern";String name="OhneAbsenderHTTP "+i+".pdf";
            byte[] bytes={(byte)(114+i),116};
            mvc.perform(multipart("/upload").file(new MockMultipartFile("files",name,"application/pdf",bytes))
                .param("sender","Unbekannt").param("main","Finanzen").param("type","Info").param("subcategory",child).with(csrf()))
                .andExpect(status().is3xxRedirection());
            var doc=catalog.search(name,"","","",java.util.List.of()).getFirst();
            assertThat(doc.path()).isEqualTo("Finanzen/"+(child.isEmpty()?"":child+"/")+name);
            mvc.perform(get("/documents/"+doc.id()+"/file")).andExpect(status().isOk()).andExpect(content().bytes(bytes));
            mvc.perform(get("/documents/"+doc.id()+"/edit")).andExpect(status().isOk());
        }
    }
    @Test void senderChangeToUnknownRequiresConfirmationAndMovesFile() throws Exception {
        var old=document("Absenderwechsel HTTP.pdf");String route="/documents/"+old.id()+"/edit";
        mvc.perform(post(route).with(csrf()).param("date",old.date()).param("sender","Unbekannt").param("main",old.main()).param("type",old.type()))
            .andExpect(status().isBadRequest());
        mvc.perform(post(route).with(csrf()).param("date",old.date()).param("sender","Unbekannt").param("main",old.main()).param("type",old.type()).param("confirmed","true"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(old.id()).path()).isEqualTo("Finanzen/2025-10-27_Absenderwechsel HTTP.pdf");
        assertThat(catalog.file(old.path())).doesNotExist();
        mvc.perform(post(route).with(csrf()).param("date",old.date()).param("sender",old.sender()).param("main",old.main()).param("type",old.type()).param("confirmed","true"))
            .andExpect(status().is3xxRedirection());
        assertThat(catalog.get(old.id()).path()).isEqualTo(old.path());
    }
    @Test void databaseToolsCleanupIsAnExplicitCsrfProtectedActionAndShowsCount() throws Exception {
        Path empty=java.nio.file.Files.createDirectories(Path.of(catalog.root()).resolve("HTTP Cleanup/A/B"));
        Path kept=Path.of(catalog.root()).resolve("HTTP Cleanup/Behalten/Datei.txt");java.nio.file.Files.createDirectories(kept.getParent());java.nio.file.Files.writeString(kept,"Bleibt");
        mvc.perform(get("/check")).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Datenbank-Tools")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Leere Ordner löschen")));
        assertThat(empty).isDirectory();
        mvc.perform(post("/check/empty-directories")).andExpect(status().isForbidden());assertThat(empty).isDirectory();
        var response=mvc.perform(post("/check/empty-directories").with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/check")).andReturn();
        String notice=(String)response.getFlashMap().get("notice");
        assertThat(Integer.parseInt(notice.split(" ")[0])).isGreaterThanOrEqualTo(2);
        mvc.perform(get("/check").flashAttrs(response.getFlashMap())).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString(notice)));
        assertThat(empty).doesNotExist();assertThat(java.nio.file.Files.readString(kept)).isEqualTo("Bleibt");
        assertThat(Path.of(catalog.root())).isDirectory();
    }
    @Test void anEmptyArchiveReportsZeroDeletedFolders() throws Exception {
        // Eigenständige Service-Tests prüfen den exakten Nullfall; im gemeinsamen HTTP-Kontext
        // erst alle übrigen leeren Ordner entfernen, dann erneut aufrufen.
        mvc.perform(post("/check/empty-directories").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post("/check/empty-directories").with(csrf())).andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("notice","0 leere Ordner gelöscht."));
    }
}
