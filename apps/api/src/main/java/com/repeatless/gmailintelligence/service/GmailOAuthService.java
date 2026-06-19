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
        gmailDataStore.ensureUser(stateUserId, emailAddress, stateUserId);
        String encryptedRefreshToken = tokenCryptoService.encrypt(refreshToken);
        gmailDataStore.saveConnection(
                stateUserId,
                emailAddress,
                encryptedRefreshToken,
                accessToken,
                Instant.now().plusSeconds(tokenResponse.expires_in() == null ? 3600 : tokenResponse.expires_in()),
                null);
        return new OAuthCallbackResponse(stateUserId, emailAddress);
    }

    public GmailDataStore.GmailConnectionRecord requireActiveConnection(String userId) {
        return gmailDataStore.findConnection(userId)
                .orElseThrow(() -> new IllegalStateException("No Gmail connection found for user " + userId));
    }

    public String resolveAccessToken(String userId) {
        GmailDataStore.GmailConnectionRecord connection = requireActiveConnection(userId);
        if (connection.accessTokenExpiresAt() != null && connection.accessTokenExpiresAt().isAfter(Instant.now().plusSeconds(60))) {
            return connection.accessToken();
        }
        String refreshToken = tokenCryptoService.decrypt(connection.encryptedRefreshToken());
        GmailApiClient.OAuthTokenResponse refreshed = gmailApiClient.refreshAccessToken(refreshToken);
        String newAccessToken = refreshed.access_token();
        gmailDataStore.saveConnection(
                connection.userId(),
                connection.emailAddress(),
                connection.encryptedRefreshToken(),
                newAccessToken,
                Instant.now().plusSeconds(refreshed.expires_in() == null ? 3600 : refreshed.expires_in()),
                connection.lastHistoryId());
        return newAccessToken;
    }
}
