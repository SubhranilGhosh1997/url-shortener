package com.subhranil.url_shortner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
// Speeds up "was this URL shortened before?" in UrlService.shortenUrl. Not unique: older duplicates exist.
@Table(indexes = @Index(name = "idx_url_mapping_original_url", columnList = "originalUrl"))
@Data
@NoArgsConstructor
public class UrlMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String shortCode;

    @Column(nullable = false, length = 2048)
    private String originalUrl;

    private LocalDateTime createdAt;

}
