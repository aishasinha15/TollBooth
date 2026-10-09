package com.tollbooth.repository;

import com.tollbooth.domain.UsageRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    long countByClientAppId(Long clientAppId);
}
