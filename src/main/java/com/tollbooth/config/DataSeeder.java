package com.tollbooth.config;

import com.tollbooth.domain.ApiKey;
import com.tollbooth.domain.ClientApp;
import com.tollbooth.domain.ModelPrice;
import com.tollbooth.repository.ApiKeyRepository;
import com.tollbooth.repository.ClientAppRepository;
import com.tollbooth.repository.ModelPriceRepository;
import com.tollbooth.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs once at startup and inserts demo data if it isn't there yet:
 * one demo app, its API key (stored as a hash) and the model prices.
 * Safe to run many times: it never creates duplicates.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final TollboothProperties props;
    private final ClientAppRepository clientApps;
    private final ApiKeyRepository apiKeys;
    private final ModelPriceRepository modelPrices;

    public DataSeeder(TollboothProperties props,
                      ClientAppRepository clientApps,
                      ApiKeyRepository apiKeys,
                      ModelPriceRepository modelPrices) {
        this.props = props;
        this.clientApps = clientApps;
        this.apiKeys = apiKeys;
        this.modelPrices = modelPrices;
    }

    @Override
    @Transactional
    public void run(String... args) {
        TollboothProperties.Demo demo = props.demo();

        ClientApp app = clientApps.findByName(demo.appName())
                .orElseGet(() -> clientApps.save(new ClientApp(demo.appName(), demo.dailyBudgetUsd())));
        // Keep the budget in sync with application.yml, so you can change it and restart
        app.setDailyBudgetUsd(demo.dailyBudgetUsd());
        clientApps.save(app);

        String keyHash = Hashing.sha256Hex(demo.apiKey());
        if (!apiKeys.existsByKeyHash(keyHash)) {
            apiKeys.save(new ApiKey(keyHash, app));
        }

        // Upsert prices so changing application.yml updates them on the next start
        for (TollboothProperties.ModelConfig m : props.models()) {
            ModelPrice price = modelPrices.findById(m.name())
                    .orElseGet(() -> new ModelPrice(m.name(), m.inputPricePerMillion(), m.outputPricePerMillion()));
            price.setInputPricePerMillion(m.inputPricePerMillion());
            price.setOutputPricePerMillion(m.outputPricePerMillion());
            modelPrices.save(price);
        }

        log.info("Seed data ready: app '{}' (id {}), daily budget ${}, {} model prices",
                app.getName(), app.getId(), app.getDailyBudgetUsd(), props.models().size());
    }
}
