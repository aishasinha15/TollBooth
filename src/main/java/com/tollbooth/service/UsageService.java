package com.tollbooth.service;

import com.tollbooth.domain.CacheResult;
import com.tollbooth.domain.ClientApp;
import com.tollbooth.domain.UsageRecord;
import com.tollbooth.repository.ClientAppRepository;
import com.tollbooth.repository.UsageRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Saves one row of the "bill" for every answered question. */
@Service
public class UsageService {

    private final UsageRecordRepository usageRecords;
    private final ClientAppRepository clientApps;

    public UsageService(UsageRecordRepository usageRecords, ClientAppRepository clientApps) {
        this.usageRecords = usageRecords;
        this.clientApps = clientApps;
    }

    @Transactional
    public void record(Long appId, String userId, String model,
                       int inputTokens, int outputTokens, BigDecimal costUsd,
                       CacheResult cacheResult, String routeReason, long latencyMs,
                       int piiMaskedCount) {
        // getReferenceById gives a lightweight stand-in for the app (just its id),
        // without running a SELECT. Enough to fill the foreign key.
        ClientApp app = clientApps.getReferenceById(appId);
        usageRecords.save(new UsageRecord(app, userId, model, inputTokens, outputTokens,
                costUsd, cacheResult, routeReason, latencyMs, piiMaskedCount));
    }
}
