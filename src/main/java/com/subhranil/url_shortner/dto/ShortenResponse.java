package com.subhranil.url_shortner.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShortenResponse {
    private String shortCode;
    private String shortUrl;
    private String originalUrl;
}
