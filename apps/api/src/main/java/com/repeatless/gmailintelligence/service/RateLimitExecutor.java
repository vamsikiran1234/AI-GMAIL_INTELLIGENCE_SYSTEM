package com.repeatless.gmailintelligence.service;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class RateLimitExecutor {

    public <T> T execute(Callable<T> callable, String operationName) {
        return execute(callable, operationName, 5);
    }

    public <T> T execute(Callable<T> callable, String operationName, int maxAttempts) {
        long delayMillis = 500L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (WebClientResponseException exception) {
                if (!isRetryable(exception.getStatusCode()) || attempt == maxAttempts) {
                    String responseBody = exception.getResponseBodyAsString();
                    String message = operationName + " failed: " + exception.getStatusText();
                    if (responseBody != null && !responseBody.isBlank()) {
                        message += " - " + responseBody;
                    }
                    // Preserve the HTTP status code in the exception type so callers
                    // (e.g. fetchThreadOrFallback) can distinguish 404 from real errors.
                    throw new GmailApiException(message, exception, exception.getStatusCode().value());
                }
                sleep(delayMillis);
                delayMillis = Math.min(delayMillis * 2, 8_000L);
            } catch (Exception exception) {
                if (attempt == maxAttempts) {
                    throw new IllegalStateException(operationName + " failed", exception);
                }
                sleep(delayMillis);
                delayMillis = Math.min(delayMillis * 2, 8_000L);
            }
        }
        throw new IllegalStateException(operationName + " failed after retries");
    }

    private boolean isRetryable(HttpStatusCode statusCode) {
        int status = statusCode.value();
        return status == 429 || status >= 500;
    }

    private void sleep(long delayMillis) {
        try {
            Thread.sleep(Duration.ofMillis(delayMillis).toMillis());
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Retry interrupted", interruptedException);
        }
    }
}
