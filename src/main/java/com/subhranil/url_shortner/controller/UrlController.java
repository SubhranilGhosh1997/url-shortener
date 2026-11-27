package com.subhranil.url_shortner.controller;

import com.subhranil.url_shortner.dto.AnalyticsResponse;
import com.subhranil.url_shortner.dto.PagedResponse;
import com.subhranil.url_shortner.dto.UrlSummary;
import com.subhranil.url_shortner.dto.ShortenRequest;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.exception.ErrorResponse;
import com.subhranil.url_shortner.service.UrlService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@Tag(name = "URL Shortener")
public class UrlController {

    private final UrlService urlService;

    @Operation(summary = "Shorten a URL")
    @ApiResponse(responseCode = "200", description = "Short URL created")
    @ApiResponse(responseCode = "400", description = "URL is missing or blank", content = @Content)
    @PostMapping("/api/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest req) {
        return ResponseEntity.ok(urlService.shortenUrl(req.getUrl()));
    }

    // Hidden from Swagger UI: a browser fetch follows the 302 to the external site, which
    // always fails CORS. Use /api/preview/{code} in Swagger, or open the short URL in a tab.
    @Hidden
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        System.out.println(">>> REDIRECT REQUEST received for code: " + code);
        String url = urlService.resolveUrl(code);
        urlService.recordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    @Operation(summary = "List all short links",
            description = "Newest first, with click counts. Paginated: size is capped at "
                    + UrlService.MAX_PAGE_SIZE + ". Public for now: every link is visible to anyone "
                    + "until user accounts are added.")
    @ApiResponse(responseCode = "200", description = "A page of short links")
    @GetMapping("/api/urls")
    public ResponseEntity<PagedResponse<UrlSummary>> listUrls(
            @Parameter(description = "Page number, starting at 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Items per page (1-100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(urlService.listUrls(page, size));
    }

    @Operation(summary = "Preview where a short link goes",
            description = "Returns the original URL without redirecting and without counting a click. "
                    + "To follow the link for real (302 + click recorded), open the shortUrl in a browser tab.")
    @ApiResponse(responseCode = "200", description = "Short link found")
    @ApiResponse(responseCode = "404", description = "Unknown short code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/api/preview/{code}")
    public ResponseEntity<ShortenResponse> preview(
            @Parameter(description = "Short code", example = "1") @PathVariable String code) {
        return ResponseEntity.ok(urlService.preview(code));
    }

    @Operation(summary = "Get click analytics for a short link")
    @ApiResponse(responseCode = "200", description = "Analytics found")
    @ApiResponse(responseCode = "404", description = "Unknown short code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/api/analytics/{code}")
    public ResponseEntity<AnalyticsResponse> analytics(
            @Parameter(description = "Short code", example = "1") @PathVariable String code) {

        return ResponseEntity.ok(
                urlService.getAnalytics(code)
        );
    }
}
