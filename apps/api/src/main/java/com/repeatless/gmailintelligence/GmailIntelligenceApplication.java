package com.repeatless.gmailintelligence;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GmailIntelligenceApplication {

    public static void main(String[] args) {
        // Load .env file into system properties so Spring can resolve ${VAR} placeholders
        Dotenv dotenv = Dotenv.configure()
                .ignoreIfMissing()
                .load();
        dotenv.entries().forEach(entry -> {
            if (System.getenv(entry.getKey()) == null) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
        // Print the DB URL being used (mask password)
        String dbUrl = System.getProperty("DATABASE_URL", "NOT SET");
        String dbUser = System.getProperty("DATABASE_USERNAME", "NOT SET");
        System.out.println("[STARTUP] DATABASE_URL  = " + dbUrl);
        System.out.println("[STARTUP] DATABASE_USERNAME = " + dbUser);
        String dbPass = System.getProperty("DATABASE_PASSWORD", "NOT SET");
        System.out.println("[STARTUP] DATABASE_PASSWORD length = " + dbPass.length() + ", value=[" + dbPass + "]");
        SpringApplication.run(GmailIntelligenceApplication.class, args);
    }
}
