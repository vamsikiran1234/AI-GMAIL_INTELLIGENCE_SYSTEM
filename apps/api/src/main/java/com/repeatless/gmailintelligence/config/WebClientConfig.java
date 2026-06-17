package com.repeatless.gmailintelligence.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfig {

    @Bean
    WebClient gmailWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(30))))
                .baseUrl("https://gmail.googleapis.com/gmail/v1")
                .build();
    }

    @Bean
    WebClient googleOauthWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(20))))
                .baseUrl("https://oauth2.googleapis.com")
                .build();
    }

    @Bean
    WebClient geminiWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(45))))
                .baseUrl("https://generativelanguage.googleapis.com")
                .build();
    }

    @Bean
    WebClient nimWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(45))))
                .baseUrl("https://integrate.api.nvidia.com")
                .build();
    }
}
