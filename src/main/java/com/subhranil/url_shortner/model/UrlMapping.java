package com.subhranil.url_shortner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// The schema is owned by Flyway (db/migration/V*__*.sql); Hibernate only validates against it.
// Constraints and indexes live in the SQL, not in annotations here.
@Entity
@Data
@NoArgsConstructor
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String shortCode;

    /** Owner (users.id, from the JWT "sub"). No JPA relation on purpose: see V3 migration. */
    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    @Column(nullable = false)
    private LocalDateTime createdAt;

}
