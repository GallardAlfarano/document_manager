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
}
