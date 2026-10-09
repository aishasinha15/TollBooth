package com.tollbooth.repository;

import com.tollbooth.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyHashAndActiveTrue(String keyHash);

    boolean existsByKeyHash(String keyHash);
}
