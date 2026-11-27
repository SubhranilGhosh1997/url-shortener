package com.subhranil.url_shortner.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShortenRequest {

    @NotBlank
    @io.swagger.v3.oas.annotations.media.Schema(description = "The long URL to shorten",
            example = "https://www.google.com/search?q=spring+boot+kafka+redis")
    private String url;
}
