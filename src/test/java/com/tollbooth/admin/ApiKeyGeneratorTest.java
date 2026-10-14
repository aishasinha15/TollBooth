package com.tollbooth.admin;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    @Test
    void keysHaveThePrefixAndOnlyUrlSafeCharacters() {
        String key = ApiKeyGenerator.newKey();

        assertThat(key).startsWith("tb_live_");
        // 32 random bytes = 43 Base64 characters without padding
        assertThat(key).hasSize("tb_live_".length() + 43);
        assertThat(key).matches("tb_live_[A-Za-z0-9_-]+");
    }

    @Test
    void keysAreNeverRepeated() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ApiKeyGenerator.newKey());
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void prefixShowsOnlyTheStart() {
        assertThat(ApiKeyGenerator.prefixOf("tb_live_ABCDEFGHIJ")).isEqualTo("tb_live_ABCD");
        assertThat(ApiKeyGenerator.prefixOf("short")).isEqualTo("short");
    }
}
