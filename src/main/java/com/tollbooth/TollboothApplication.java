package com.tollbooth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. @SpringBootApplication tells Spring to scan this package and
 * everything under it for components (controllers, services, repositories).
 * @ConfigurationPropertiesScan picks up our "tollbooth:" settings from application.yml.
 *
 * We exclude UserDetailsServiceAutoConfiguration because Tollbooth has no
 * username/password users, only API keys. Without this, Spring would create a
 * default user and print a random password at startup.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class TollboothApplication {

    public static void main(String[] args) {
        SpringApplication.run(TollboothApplication.class, args);
    }
}
