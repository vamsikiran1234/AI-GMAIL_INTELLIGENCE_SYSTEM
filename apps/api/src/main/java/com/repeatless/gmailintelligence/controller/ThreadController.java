package com.repeatless.gmailintelligence.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.repeatless.gmailintelligence.dto.ApiDtos.EmailSummaryResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.SendRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.SendResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadListResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadMessagesResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadSummaryResponse;
import com.repeatless.gmailintelligence.service.EmailIntelligenceService;

@RestController
@RequestMapping("/api/threads")
public class ThreadController {

    private final EmailIntelligenceService emailIntelligenceService;

    public ThreadController(EmailIntelligenceService emailIntelligenceService) {
        this.emailIntelligenceService = emailIntelligenceService;
    }

    @GetMapping
    public ThreadListResponse listThreads(@RequestParam @NotBlank String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return emailIntelligenceService.listThreads(userId, page, pageSize);
    }

    @GetMapping("/{threadId}/messages")
    public ThreadMessagesResponse listMessages(@RequestParam @NotBlank String userId,
            @PathVariable String threadId) {
        return emailIntelligenceService.listThreadMessages(userId, threadId);
    }

    @GetMapping("/{threadId}/summary")
    public ThreadSummaryResponse summarizeThread(@RequestParam @NotBlank String userId,
            @PathVariable String threadId) {
        return emailIntelligenceService.summarizeThread(userId, threadId);
    }

    @GetMapping("/messages/{messageId}/summary")
    public EmailSummaryResponse summarizeEmail(@RequestParam @NotBlank String userId,
            @PathVariable String messageId) {
        return emailIntelligenceService.summarizeEmail(userId, messageId);
    }

    @PostMapping("/send")
    public SendResponse send(@Valid @RequestBody SendRequest request) {
        return emailIntelligenceService.sendDraft(request.userId(), request.draftId());
    }
}
