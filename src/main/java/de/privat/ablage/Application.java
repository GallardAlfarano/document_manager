package de.privat.ablage;

import java.nio.file.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
public class Application {
    public static void main(String[] args) throws Exception {
        SpringApplication.run(Application.class, args);
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        // Lokales Einzelbenutzerprogramm. CSRF-Schutz bleibt aktiv.
        return http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .formLogin(f -> f.disable()).httpBasic(b -> b.disable())
            .headers(h -> h.frameOptions(f -> f.sameOrigin())).build();
    }
    @Bean javax.sql.DataSource dataSource(org.springframework.core.env.Environment env) throws Exception {
        Path data = Path.of(env.getProperty("app.data", "./data")).toAbsolutePath();
        Files.createDirectories(data);
        var config = new org.sqlite.SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5000);
        var ds = new org.sqlite.SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + data.resolve("catalog.sqlite"));
        return ds;
    }
}
