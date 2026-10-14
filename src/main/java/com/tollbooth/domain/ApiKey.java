package com.tollbooth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An API key belonging to a client app.
 * We store only a SHA-256 hash of the key, never the key itself,
 * so a database leak doesn't leak usable keys (same idea as passwords).
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_app_id")
    private ClientApp clientApp;

    /**
     * The first characters of the key, e.g. "tb_live_Q3x9", so a person can tell keys apart
     * in a list without the key ever being shown again. Nullable so older rows stay valid.
     */
    @Column(name = "key_prefix", length = 16)
    private String keyPrefix;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ApiKey() {
    }

    public ApiKey(String keyHash, String keyPrefix, ClientApp clientApp) {
        this.keyHash = keyHash;
        this.keyPrefix = keyPrefix;
        this.clientApp = clientApp;
    }

    public Long getId() { return id; }
    public String getKeyHash() { return keyHash; }
    public ClientApp getClientApp() { return clientApp; }
    public String getKeyPrefix() { return keyPrefix; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }

    public void setActive(boolean active) { this.active = active; }
}
