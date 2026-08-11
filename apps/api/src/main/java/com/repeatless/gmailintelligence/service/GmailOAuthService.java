package com.repeatless.gmailintelligence.service;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.repeatless.gmailintelligence.dto.ApiDtos.OAuthCallbackResponse;

@Service
public class GmailOAuthService {

    private final GmailApiClient gmailApiClient;
    private final GmailDataStore gmailDataStore;
    private final TokenCryptoService tokenCryptoService;

    public GmailOAuthService(GmailApiClient gmailApiClient, GmailDataStore gmailDataStore,
            TokenCryptoService tokenCryptoService) {
        this.gmailApiClient = gmailApiClient;
        this.gmailDataStore = gmailDataStore;
        this.tokenCryptoService = tokenCryptoService;
    }

    public String createAuthorizationUrl(String userId) {
        return gmailApiClient.buildAuthorizationUrl(userId);
    }

    public OAuthCallbackResponse handleAuthorizationCode(String stateUserId, String authorizationCode) {
        GmailApiClient.OAuthTokenResponse tokenResponse = gmailApiClient.exchangeAuthorizationCode(authorizationCode);
        String accessToken = tokenResponse.access_token();
        String refreshToken = tokenResponse.refresh_token();
        String emailAddress = gmailApiClient.getUserProfileEmail(accessToken);
        // Ensure the app_user row exists before inserting gmail_connection (FK requirement)
        String effectiveUserId = gmailDataStore.ensureUser(stateUserId, emailAddress, stateUserId);
        String encryptedRefreshToken = tokenCryptoService.encrypt(refreshToken);
        gmailDataStore.saveConnection(
            effectiveUserId,
                emailAddress,
                encryptedRefreshToken,
                accessToken,
                Instant.now().plusSeconds(tokenResponse.expires_in() == null ? 3600 : tokenResponse.expires_in()),
                null);
        return new OAuthCallbackResponse(effectiveUserId, emailAddress);
    }

    public GmailDataStore.GmailConnectionRecord requireActiveConnection(String userId) {
        return gmailDataStore.findConnection(userId)
                .orElseThrow(() -> new IllegalStateException("No Gmail connection found for user " + userId));
    }

    /**
     * Returns a valid access token for the user.
     *
     * Strategy:
     * 1. If the stored token has more than 60 seconds left, return it immediately.
     * 2. Otherwise refresh via the stored refresh token.
     *
     * The stored expiry is not always reliable (e.g. the DB was written with a
     * wrong clock or a stale value). Callers that receive a 401 from Gmail should
     * call {@link #forceRefreshAccessToken(String)} to obtain a new token.
     */
    public String resolveAccessToken(String userId) {
        GmailDataStore.GmailConnectionRecord connection = requireActiveConnection(userId);
        if (connection.accessTokenExpiresAt() != null
                && connection.accessTokenExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return connection.accessToken();
        }
        return doRefresh(connection);
    }

    /**
     * Unconditionally obtains a fresh access token by using the stored refresh
     * token. Use this when a Gmail API call returns 401 to recover without
     * restarting the sync.
     */
    public String forceRefreshAccessToken(String userId) {
        GmailDataStore.GmailConnectionRecord connection = requireActiveConnection(userId);
        System.out.println("[GmailOAuthService] Force-refreshing access token for user " + userId);
        return doRefresh(connection);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private String doRefresh(GmailDataStore.GmailConnectionRecord connection) {
        String refreshToken = tokenCryptoService.decrypt(connection.encryptedRefreshToken());
        GmailApiClient.OAuthTokenResponse refreshed = gmailApiClient.refreshAccessToken(refreshToken);
        String newAccessToken = refreshed.access_token();
        long expiresIn = refreshed.expires_in() == null ? 3600L : refreshed.expires_in();
        gmailDataStore.saveConnection(
                connection.userId(),
                connection.emailAddress(),
                connection.encryptedRefreshToken(),
                newAccessToken,
                Instant.now().plusSeconds(expiresIn),
                connection.lastHistoryId());
        return newAccessToken;
    }
}
