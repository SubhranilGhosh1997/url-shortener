package com.subhranil.url_shortner.security;

import com.subhranil.url_shortner.controller.UrlController;
import com.subhranil.url_shortner.dto.ShortenResponse;
import com.subhranil.url_shortner.exception.GlobalExceptionHandler;
import com.subhranil.url_shortner.exception.ResourceNotFoundException;
import com.subhranil.url_shortner.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Who may call what: the URL endpoints through the real security filter chain. */
@WebMvcTest(controllers = UrlController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class SecurityRulesTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UrlService urlService;

    @Test
    void shortenWithoutTokenIs401WithJsonError() throws Exception {
        mvc.perform(post("/api/shorten").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://a.com\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").exists());
        verifyNoInteractions(urlService);
    }

    @Test
    void garbageTokenIs401() throws Exception {
        mvc.perform(get("/api/urls").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));
    }

    @Test
    void shortenWithTokenUsesUserIdFromToken() throws Exception {
        when(urlService.shortenUrl(eq(42L), anyString()))
                .thenReturn(new ShortenResponse("abc1234", "http://localhost/abc1234", "https://a.com"));

        mvc.perform(post("/api/shorten").with(jwt().jwt(j -> j.subject("42")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://a.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("abc1234"));
        verify(urlService).shortenUrl(42L, "https://a.com");
    }

    @Test
    void listIsScopedToTheCaller() throws Exception {
        mvc.perform(get("/api/urls").with(jwt().jwt(j -> j.subject("7")))).andExpect(status().isOk());
        verify(urlService).listUrls(7L, 0, 20);
    }

    @Test
    void someoneElsesLinkLooksLikeItDoesNotExist() throws Exception {
        doThrow(new ResourceNotFoundException("Short URL not found: abc1234"))
                .when(urlService).deleteUrl(7L, "abc1234");

        mvc.perform(delete("/api/urls/abc1234").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteOwnLinkIs204() throws Exception {
        mvc.perform(delete("/api/urls/abc1234").with(jwt().jwt(j -> j.subject("7"))))
                .andExpect(status().isNoContent());
        verify(urlService).deleteUrl(7L, "abc1234");
    }

    @Test
    void redirectIsPublic() throws Exception {
        when(urlService.resolveUrl("abc1234")).thenReturn("https://a.com");
        mvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://a.com"));
    }

    @Test
    void previewIsPublic() throws Exception {
        when(urlService.preview("abc1234"))
                .thenReturn(new ShortenResponse("abc1234", "http://localhost/abc1234", "https://a.com"));
        mvc.perform(get("/api/preview/abc1234")).andExpect(status().isOk());
    }

    @Test
    void analyticsAndDeleteNeedAToken() throws Exception {
        mvc.perform(get("/api/analytics/abc1234")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/urls/abc1234")).andExpect(status().isUnauthorized());
        verifyNoInteractions(urlService);
    }

    @Test
    void unknownMultiSegmentPathsAreDenied() throws Exception {
        mvc.perform(get("/some/other/path")).andExpect(status().isUnauthorized());
    }
}
