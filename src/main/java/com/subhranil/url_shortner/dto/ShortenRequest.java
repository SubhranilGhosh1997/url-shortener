package com.subhranil.url_shortner.dto;

import com.subhranil.url_shortner.service.UrlNormalizer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShortenRequest {

    @NotBlank
    @Size(max = UrlNormalizer.MAX_LENGTH, message = "must be at most " + UrlNormalizer.MAX_LENGTH + " characters")
    @Schema(description = "The long URL to shorten (http or https, at most " + UrlNormalizer.MAX_LENGTH + " characters)",
            example = "https://www.google.com/search?q=spring+boot+kafka+redis")
    private String url;
}
