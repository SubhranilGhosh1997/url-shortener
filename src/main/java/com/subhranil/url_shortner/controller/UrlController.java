package com.subhranil.url_shortner.controller;

import com.subhranil.url_shortner.dto.AnalyticsResponse;
import com.subhranil.url_shortner.dto.PagedResponse;
import com.subhranil.url_shortner.dto.UrlSummary;
import com.subhranil.url_shortner.dto.ShortenRequest;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.exception.ErrorResponse;
import com.subhranil.url_shortner.security.CurrentUser;
import com.subhranil.url_shortner.service.UrlService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@Slf4j
@Tag(name = "URL Shortener", description = "Everything except preview needs a token: log in, then use Authorize.")
public class UrlController {

    private final UrlService urlService;

    @Operation(summary = "Shorten a URL",
            description = "The link belongs to you. The URL is normalized first (lowercase scheme and host, default "
                    + "port and bare trailing slash removed). If you shortened that URL before, your existing short "
                    + "link is returned instead of a new one. Only http:// and https:// URLs are accepted.")
    @ApiResponse(responseCode = "200", description = "Short URL created, or your existing one returned")
    @ApiResponse(responseCode = "400", description = "URL is missing, blank, malformed or not http/https",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not logged in", content = @Content)
    @PostMapping("/api/shorten")
    public ResponseEntity<ShortenResponse> shorten(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                                   @Valid @RequestBody ShortenRequest req) {
        return ResponseEntity.ok(urlService.shortenUrl(CurrentUser.id(jwt), req.getUrl()));
    }

    // Public. Hidden from Swagger UI: a browser fetch follows the 302 to the external site, which
    // always fails CORS. Use /api/preview/{code} in Swagger, or open the short URL in a tab.
    @Hidden
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        String url = urlService.resolveUrl(code);
        log.debug("Redirect {} -> {}", code, url);
        urlService.recordClick(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(url))
                .build();
    }

    @Operation(summary = "List my short links",
            description = "Your links only, newest first, with click counts. Paginated: size is capped at "
                    + UrlService.MAX_PAGE_SIZE + ".")
    @ApiResponse(responseCode = "200", description = "A page of your short links")
    @ApiResponse(responseCode = "401", description = "Not logged in", content = @Content)
    @GetMapping("/api/urls")
    public ResponseEntity<PagedResponse<UrlSummary>> listUrls(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Page number, starting at 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Items per page (1-100)") @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(urlService.listUrls(CurrentUser.id(jwt), page, size));
    }

    @Operation(summary = "Delete one of my short links",
            description = "The short URL stops working immediately and its click count is removed. "
                    + "The code is never reused.")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "No such link among yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not logged in", content = @Content)
    @DeleteMapping("/api/urls/{code}")
    public ResponseEntity<Void> deleteUrl(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
                                          @Parameter(description = "Short code") @PathVariable String code) {
        urlService.deleteUrl(CurrentUser.id(jwt), code);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Preview where a short link goes",
            description = "Public. Returns the original URL without redirecting and without counting a click. "
                    + "To follow the link for real (302 + click recorded), open the shortUrl in a browser tab.")
    @SecurityRequirements
    @ApiResponse(responseCode = "200", description = "Short link found")
    @ApiResponse(responseCode = "404", description = "Unknown short code",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/api/preview/{code}")
    public ResponseEntity<ShortenResponse> preview(
            @Parameter(description = "Short code") @PathVariable String code) {
        return ResponseEntity.ok(urlService.preview(code));
    }

    @Operation(summary = "Get click analytics for one of my short links")
    @ApiResponse(responseCode = "200", description = "Analytics found")
    @ApiResponse(responseCode = "404", description = "No such link among yours",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Not logged in", content = @Content)
    @GetMapping("/api/analytics/{code}")
    public ResponseEntity<AnalyticsResponse> analytics(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Short code") @PathVariable String code) {
        return ResponseEntity.ok(urlService.getAnalytics(CurrentUser.id(jwt), code));
    }
}
