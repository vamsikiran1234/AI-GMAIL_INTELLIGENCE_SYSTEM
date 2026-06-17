package com.repeatless.gmailintelligence.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.dto.ApiDtos.ChatRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.ChatResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.DraftRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.DraftResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterDigestRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterDigestResponse;
import com.repeatless.gmailintelligence.service.EmailIntelligenceService;

@RestController
@RequestMapping("/api/assistant")
public class AssistantController {

    private final EmailIntelligenceService emailIntelligenceService;

    public AssistantController(EmailIntelligenceService emailIntelligenceService) {
        this.emailIntelligenceService = emailIntelligenceService;
    }

    @PostMapping("/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request) {
        return emailIntelligenceService.createDraft(request);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return emailIntelligenceService.answerQuestion(request);
    }

    @PostMapping("/newsletter-digest")
    public NewsletterDigestResponse digest(@Valid @RequestBody NewsletterDigestRequest request) {
        return emailIntelligenceService.buildNewsletterDigest(request.userId(), request.days());
    }
}
