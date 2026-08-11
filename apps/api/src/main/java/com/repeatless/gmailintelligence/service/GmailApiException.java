package com.repeatless.gmailintelligence.service;

/**
 * Wraps a failed Gmail / Google API call, preserving the HTTP status code so
 * callers can decide whether to retry, skip, or surface the error.
 */
public class GmailApiException extends IllegalStateException {

    private final int httpStatus;

    public GmailApiException(String message, Throwable cause, int httpStatus) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** True for 404 — the resource no longer exists (deleted/trashed thread). */
    public boolean isNotFound() {
        return httpStatus == 404;
    }

    /** True for 401 — access token is expired or revoked. */
    public boolean isUnauthorized() {
        return httpStatus == 401;
    }
}
