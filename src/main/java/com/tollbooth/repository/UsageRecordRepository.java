package com.tollbooth.repository;

import com.tollbooth.domain.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    long countByClientAppId(Long clientAppId);

    /**
     * Phase 7 usage report: one app's rows in a time window.
     * Spring Data writes the SQL from the method name:
     *   WHERE client_app_id = ? AND created_at >= ? AND created_at < ?
     * The index idx_usage_app_time (client_app_id, created_at) makes this fast.
     */
    List<UsageRecord> findByClientAppIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Long clientAppId, Instant from, Instant to);

    /** The dashboard's "recent requests" list: newest first. */
    List<UsageRecord> findTop50ByClientAppIdOrderByIdDesc(Long clientAppId);
}
