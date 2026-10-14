package com.tollbooth.admin;

import com.tollbooth.admin.AdminDtos.AppSummary;
import com.tollbooth.admin.AdminDtos.KeySummary;
import com.tollbooth.admin.AdminDtos.NewKey;
import com.tollbooth.domain.ApiKey;
import com.tollbooth.domain.ClientApp;
import com.tollbooth.repository.ApiKeyRepository;
import com.tollbooth.repository.ClientAppRepository;
import com.tollbooth.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Onboarding: how a new app (say "Myntra") gets into Tollbooth.
 *
 *   1. The Tollbooth operator creates the app with a daily budget -> gets a key, shown ONCE.
 *   2. The app's developers put that key in their server's settings and call /v1/ask with it.
 *   3. Keys can be added (rotation) and revoked; a revoked key stops working on the next request.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final String SHOWN_ONCE = "Save this key now: it is shown only once. Tollbooth stores only its hash.";

    private final ClientAppRepository clientApps;
    private final ApiKeyRepository apiKeys;

    public AdminService(ClientAppRepository clientApps, ApiKeyRepository apiKeys) {
        this.clientApps = clientApps;
        this.apiKeys = apiKeys;
    }

    @Transactional
    public NewKey createApp(String name, BigDecimal dailyBudgetUsd) {
        String cleanName = name.trim();
        if (clientApps.existsByNameIgnoreCase(cleanName)) {
            throw new ConflictException("An app named '" + cleanName + "' already exists");
        }
        ClientApp app = clientApps.save(new ClientApp(cleanName, dailyBudgetUsd));
        log.info("Created app '{}' (id {}) with daily budget ${}", app.getName(), app.getId(), dailyBudgetUsd);
        return issueKey(app);
    }

    @Transactional
    public NewKey createKey(Long appId) {
        return issueKey(findApp(appId));
    }

    @Transactional(readOnly = true)
    public List<AppSummary> listApps() {
        return clientApps.findAll().stream()
                .map(app -> new AppSummary(app.getId(), app.getName(), app.getDailyBudgetUsd(),
                        app.getCreatedAt(), apiKeys.countByClientAppIdAndActiveTrue(app.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<KeySummary> listKeys(Long appId) {
        findApp(appId);
        return apiKeys.findByClientAppIdOrderByIdDesc(appId).stream()
                .map(k -> new KeySummary(k.getId(), k.getKeyPrefix(), k.isActive(), k.getCreatedAt()))
                .toList();
    }

    @Transactional
    public KeySummary revokeKey(Long keyId) {
        ApiKey key = apiKeys.findById(keyId)
                .orElseThrow(() -> new NotFoundException("No key with id " + keyId));
        key.setActive(false); // the change is saved when the transaction ends ("dirty checking")
        log.info("Revoked key {} ({})", keyId, key.getKeyPrefix());
        return new KeySummary(key.getId(), key.getKeyPrefix(), false, key.getCreatedAt());
    }

    @Transactional
    public AppSummary updateBudget(Long appId, BigDecimal dailyBudgetUsd) {
        ClientApp app = findApp(appId);
        app.setDailyBudgetUsd(dailyBudgetUsd);
        return new AppSummary(app.getId(), app.getName(), app.getDailyBudgetUsd(), app.getCreatedAt(),
                apiKeys.countByClientAppIdAndActiveTrue(appId));
    }

    private NewKey issueKey(ClientApp app) {
        String key = ApiKeyGenerator.newKey();
        ApiKey saved = apiKeys.save(new ApiKey(Hashing.sha256Hex(key), ApiKeyGenerator.prefixOf(key), app));
        return new NewKey(app.getId(), app.getName(), app.getDailyBudgetUsd(), saved.getId(), key, SHOWN_ONCE);
    }

    private ClientApp findApp(Long appId) {
        return clientApps.findById(appId).orElseThrow(() -> new NotFoundException("No app with id " + appId));
    }
}
