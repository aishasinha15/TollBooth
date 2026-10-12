package com.tollbooth.repository;

import com.tollbooth.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * Finds an active key by its hash AND loads its app in the same query.
     * "join fetch" matters: the app is a lazy relation, and without it,
     * reading app.getName() later (outside the database session) would crash.
     */
    @Query("select k from ApiKey k join fetch k.clientApp where k.keyHash = :hash and k.active = true")
    Optional<ApiKey> findActiveByHash(@Param("hash") String hash);

    boolean existsByKeyHash(String keyHash);
}
