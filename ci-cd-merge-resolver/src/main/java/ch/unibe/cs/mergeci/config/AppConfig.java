package ch.unibe.cs.mergeci.config;

import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {
    public static final int MAX_THREADS = Math.min(Runtime.getRuntime().availableProcessors(), 12);
}
