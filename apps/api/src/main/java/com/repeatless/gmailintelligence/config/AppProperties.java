package com.repeatless.gmailintelligence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String frontendOrigin,
        Gmail gmail,
        Ai ai,
        Security security
) {

    public record Gmail(String clientId, String clientSecret, String redirectUri) {
    }

    public record Ai(Gemini gemini, Nim nim) {
    }

    public record Gemini(String apiKey, String model) {
    }

    public record Nim(String apiKey, String model) {
    }

    public record Security(String tokenEncryptionKeyBase64) {
    }
}
