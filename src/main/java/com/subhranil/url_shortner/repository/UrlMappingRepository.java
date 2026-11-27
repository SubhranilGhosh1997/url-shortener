package com.subhranil.url_shortner.repository;

import com.subhranil.url_shortner.model.UrlMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {
    Optional<UrlMapping> findByShortCode(String shortCode);

    // Skips rows whose code was never set (saved once, then the second save failed).
    Page<UrlMapping> findByShortCodeIsNotNull(Pageable pageable);

}
