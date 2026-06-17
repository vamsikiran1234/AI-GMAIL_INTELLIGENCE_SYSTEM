package com.repeatless.gmailintelligence.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.dto.ApiDtos.SyncRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.SyncStatusResponse;
import com.repeatless.gmailintelligence.service.EmailIntelligenceService;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final EmailIntelligenceService emailIntelligenceService;

    public SyncController(EmailIntelligenceService emailIntelligenceService) {
        this.emailIntelligenceService = emailIntelligenceService;
    }

    @PostMapping
    public SyncStatusResponse sync(@Valid @RequestBody SyncRequest request) {
        return emailIntelligenceService.syncMailbox(request.userId());
    }
}
