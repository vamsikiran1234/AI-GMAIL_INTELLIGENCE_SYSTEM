package com.repeatless.gmailintelligence.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.repeatless.gmailintelligence.config.AppProperties;
import com.repeatless.gmailintelligence.dto.ApiDtos.SyncStatusResponse;
import com.repeatless.gmailintelligence.service.EmailIntelligenceService;

/**
 * Internal webhook endpoint consumed by N8N workflows.
 * Protected by a shared secret so N8N can trigger sync without OAuth tokens.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalSyncController {

    private final EmailIntelligenceService emailIntelligenceService;
    private final AppProperties properties;

    public InternalSyncController(EmailIntelligenceService emailIntelligenceService, AppProperties properties) {
        this.emailIntelligenceService = emailIntelligenceService;
        this.properties = properties;
    }

    /**
     * N8N calls this endpoint once per user on the scheduled sync workflow.
     * Body: { "userId": "...", "secret": "N8N_INTERNAL_SECRET" }
     */
    @PostMapping("/sync-user")
    public SyncStatusResponse syncUser(@RequestBody InternalSyncRequest request) {
        String configuredSecret = properties.internalSecret();
        if (configuredSecret == null || configuredSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Internal sync is not configured");
        }
        if (!configuredSecret.equals(request.secret())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Invalid internal secret");
        }
        if (request.userId() == null || request.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userId is required");
        }
        return emailIntelligenceService.syncMailbox(request.userId());
    }

    public record InternalSyncRequest(String userId, String secret) {
    }
}
