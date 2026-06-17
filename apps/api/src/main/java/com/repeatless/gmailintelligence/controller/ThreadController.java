package com.repeatless.gmailintelligence.controller;

import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.dto.ApiDtos.EmailSummaryResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadSummaryResponse;
import com.repeatless.gmailintelligence.service.EmailIntelligenceService;

@RestController
@RequestMapping("/api/threads")
public class ThreadController {

    private final EmailIntelligenceService emailIntelligenceService;

    public ThreadController(EmailIntelligenceService emailIntelligenceService) {
        this.emailIntelligenceService = emailIntelligenceService;
    }

    @GetMapping("/{threadId}/summary")
    public ThreadSummaryResponse summarizeThread(@RequestParam @NotBlank String userId, @PathVariable String threadId) {
        return emailIntelligenceService.summarizeThread(userId, threadId);
    }

    @GetMapping("/messages/{messageId}/summary")
    public EmailSummaryResponse summarizeEmail(@RequestParam @NotBlank String userId, @PathVariable String messageId) {
        return emailIntelligenceService.summarizeEmail(userId, messageId);
    }
}
