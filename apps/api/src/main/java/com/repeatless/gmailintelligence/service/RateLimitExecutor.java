package com.repeatless.gmailintelligence.service;

import java.time.Duration;
import java.util.concurrent.Callable;

import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class RateLimitExecutor {

    public <T> T execute(Callable<T> callable, String operationName) {
        int maxAttempts = 5;
        long delayMillis = 500L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (WebClientResponseException exception) {
                if (!isRetryable(exception.getStatusCode()) || attempt == maxAttempts) {
                    throw new IllegalStateException(operationName + " failed: " + exception.getStatusText(), exception);
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
