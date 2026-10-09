package com.tollbooth.web;

import com.tollbooth.repository.ClientAppRepository;
import com.tollbooth.repository.ModelPriceRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A tiny endpoint to prove all three pieces work together:
 * the app is running, MySQL has our seed data, and Redis answers.
 * GET http://localhost:8080/v1/status
 */
@RestController
@RequestMapping("/v1")
public class StatusController {

    private final ClientAppRepository clientApps;
    private final ModelPriceRepository modelPrices;
    private final StringRedisTemplate redis;

    public StatusController(ClientAppRepository clientApps,
                            ModelPriceRepository modelPrices,
                            StringRedisTemplate redis) {
        this.clientApps = clientApps;
        this.modelPrices = modelPrices;
        this.redis = redis;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        // Write and read a value to prove Redis round-trips work
        redis.opsForValue().set("tollbooth:status-check", "ok");
        String redisValue = redis.opsForValue().get("tollbooth:status-check");

        return Map.of(
                "service", "tollbooth",
                "clientApps", clientApps.count(),
                "modelPrices", modelPrices.findAll().stream().map(p -> p.getModelName()).toList(),
                "redis", redisValue
        );
    }
}
