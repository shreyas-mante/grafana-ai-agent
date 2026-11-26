package com.pulseagent.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Accepts DATABASE_URL in the postgres://user:pass@host/db form that Neon and Render show,
 * or a jdbc: URL. With no URL, uses an embedded H2 database file (local development).
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean
    DataSource dataSource(AppProperties props) throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("pulseagent-db");
        String url = props.platform().databaseUrl();
        if (url == null || url.isBlank()) {
            Path dir = Path.of(props.knowledge().dataDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            cfg.setJdbcUrl("jdbc:h2:file:" + dir.resolve("pulseagent") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
            cfg.setUsername("sa");
            cfg.setPassword("");
            log.info("Database: embedded H2 in {}", dir);
        } else if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            URI u = URI.create(url.replaceFirst("^postgres(ql)?://", "http://"));
            String[] userInfo = u.getRawUserInfo() == null ? new String[0] : u.getRawUserInfo().split(":", 2);
            String query = u.getRawQuery() == null ? "sslmode=require" : u.getRawQuery();
            cfg.setJdbcUrl("jdbc:postgresql://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "") + u.getRawPath() + "?" + query);
            if (userInfo.length > 0) cfg.setUsername(URLDecoder.decode(userInfo[0], StandardCharsets.UTF_8));
            if (userInfo.length > 1) cfg.setPassword(URLDecoder.decode(userInfo[1], StandardCharsets.UTF_8));
            log.info("Database: PostgreSQL at {}", u.getHost());
        } else if (url.startsWith("jdbc:")) {
            cfg.setJdbcUrl(url);
            log.info("Database: {}", url.replaceAll("password=[^&;]*", "password=***"));
        } else {
            throw new IllegalStateException("DATABASE_URL must start with postgres://, postgresql:// or jdbc:");
        }
        return new HikariDataSource(cfg);
    }
}
