package com.tollbooth.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HashingTest {

    @Test
    void producesKnownSha256() {
        // Official SHA-256 test vector for "abc"
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sameInputGivesSameHash() {
        assertThat(Hashing.sha256Hex("tb_demo_key_change_me"))
                .isEqualTo(Hashing.sha256Hex("tb_demo_key_change_me"));
    }

    @Test
    void differentInputGivesDifferentHash() {
        assertThat(Hashing.sha256Hex("key-a")).isNotEqualTo(Hashing.sha256Hex("key-b"));
    }
}
