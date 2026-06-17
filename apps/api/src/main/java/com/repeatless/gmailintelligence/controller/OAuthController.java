package com.repeatless.gmailintelligence.controller;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.dto.ApiDtos.OAuthCallbackResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.OAuthStartResponse;
import com.repeatless.gmailintelligence.service.GmailOAuthService;

@RestController
@RequestMapping("/api/oauth/google")
public class OAuthController {

    private final GmailOAuthService gmailOAuthService;

    public OAuthController(GmailOAuthService gmailOAuthService) {
        this.gmailOAuthService = gmailOAuthService;
    }

    @GetMapping("/start")
    public OAuthStartResponse start(@RequestParam @NotBlank String userId) {
        return new OAuthStartResponse(gmailOAuthService.createAuthorizationUrl(userId));
    }

    @GetMapping("/callback")
    public OAuthCallbackResponse callback(@RequestParam String code, @RequestParam String state) {
        return gmailOAuthService.handleAuthorizationCode(state, code);
    }
}
