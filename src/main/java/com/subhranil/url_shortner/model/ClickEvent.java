package com.subhranil.url_shortner.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private String shortCode;
    private Instant timestamp;
}
