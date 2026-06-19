package com.repeatless.gmailintelligence.controller;

import java.net.URI;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.config.AppProperties;
import com.repeatless.gmailintelligence.dto.ApiDtos.OAuthCallbackResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.OAuthStartResponse;
import com.repeatless.gmailintelligence.service.GmailOAuthService;

@RestController
@RequestMapping("/api/oauth/google")
public class OAuthController {

    private final GmailOAuthService gmailOAuthService;
    private final AppProperties properties;

    public OAuthController(GmailOAuthService gmailOAuthService, AppProperties properties) {
        this.gmailOAuthService = gmailOAuthService;
        this.properties = properties;
    }

    @GetMapping("/start")
    public OAuthStartResponse start(@RequestParam @NotBlank String userId) {
        return new OAuthStartResponse(gmailOAuthService.createAuthorizationUrl(userId));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String code, @RequestParam String state) {
        try {
            OAuthCallbackResponse result = gmailOAuthService.handleAuthorizationCode(state, code);
            String redirectUrl = properties.frontendOrigin()
                    + "?userId=" + result.accountId()
                    + "&email=" + result.emailAddress()
                    + "&connected=true";
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        } catch (Exception ex) {
            // Redirect to frontend with error so user sees a readable message instead of 500
            String errorMessage = java.net.URLEncoder.encode(
                    ex.getMessage() != null ? ex.getMessage() : "OAuth callback failed",
                    java.nio.charset.StandardCharsets.UTF_8);
            String redirectUrl = properties.frontendOrigin() + "?error=" + errorMessage;
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(redirectUrl)).build();
        }
    }
}
