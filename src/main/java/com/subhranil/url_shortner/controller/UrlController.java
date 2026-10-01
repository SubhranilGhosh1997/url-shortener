package com.subhranil.url_shortner.controller;

import com.subhranil.url_shortner.dto.AnalyticsResponse;
import com.subhranil.url_shortner.dto.ShortenRequest;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.service.UrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class UrlController {

    private final UrlService urlService;

    @PostMapping("/api/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        return ResponseEntity.ok(urlService.shortenUrl(req.getUrl()));
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        System.out.println(">>> REDIRECT REQUEST received for code: " + code);
        String url = urlService.resolveUrl(code);
        urlService.recordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    @GetMapping("/api/analytics/{code}")
    public ResponseEntity<AnalyticsResponse> analytics(
            @PathVariable String code) {

        return ResponseEntity.ok(
                urlService.getAnalytics(code)
        );
    }
}
