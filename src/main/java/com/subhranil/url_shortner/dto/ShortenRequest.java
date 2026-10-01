package com.subhranil.url_shortner.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShortenRequest {

    @NotBlank
    private String url;
}
