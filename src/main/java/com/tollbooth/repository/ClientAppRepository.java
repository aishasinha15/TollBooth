package com.tollbooth.repository;

import com.tollbooth.domain.ClientApp;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data writes the SQL for these methods from their names. */
public interface ClientAppRepository extends JpaRepository<ClientApp, Long> {

    Optional<ClientApp> findByName(String name);
}
