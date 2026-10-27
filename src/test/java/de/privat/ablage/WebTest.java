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
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Die Datei existiert bereits.")))
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
}
