package com.subhranil.url_shortner.repository;

import com.subhranil.url_shortner.model.UrlMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    /** Public lookup for redirects and previews: any owner. */
    Optional<UrlMapping> findByShortCode(String shortCode);

    /** Owner-scoped lookup for analytics and delete. */
    Optional<UrlMapping> findByShortCodeAndUserId(String shortCode, Long userId);

    Optional<UrlMapping> findFirstByUserIdAndOriginalUrlAndShortCodeIsNotNullOrderByIdAsc(Long userId, String originalUrl);

    // Skips rows whose code was never set (saved once, then the second save failed).
    Page<UrlMapping> findByUserIdAndShortCodeIsNotNull(Long userId, Pageable pageable);

}
