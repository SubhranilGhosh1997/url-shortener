package com.subhranil.url_shortner.controller;

import com.subhranil.url_shortner.exception.GlobalExceptionHandler;
import com.subhranil.url_shortner.security.JsonAuthenticationEntryPoint;
import com.subhranil.url_shortner.security.JwtConfig;
import com.subhranil.url_shortner.security.SecurityConfig;
import com.subhranil.url_shortner.service.UrlNormalizer;
import com.subhranil.url_shortner.service.UrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Request validation on POST /api/shorten, before anything reaches the service or the database. */
@WebMvcTest(controllers = UrlController.class)
@Import({SecurityConfig.class, JwtConfig.class, JsonAuthenticationEntryPoint.class, GlobalExceptionHandler.class})
class ShortenValidationTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    UrlService urlService;

    @Test
    void tooLongUrlIs400WithClearMessage() throws Exception {
        String url = "https://example.com/" + "a".repeat(UrlNormalizer.MAX_LENGTH);

        mvc.perform(post("/api/shorten").with(jwt().jwt(j -> j.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("url must be at most 2048 characters"));
        verify(urlService, never()).shortenUrl(anyLong(), anyString());
    }

    @Test
    void urlOfExactlyMaxLengthPassesValidation() throws Exception {
        String prefix = "https://example.com/";
        String url = prefix + "a".repeat(UrlNormalizer.MAX_LENGTH - prefix.length());

        mvc.perform(post("/api/shorten").with(jwt().jwt(j -> j.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"" + url + "\"}"))
                .andExpect(status().isOk());
        verify(urlService).shortenUrl(1L, url);
    }
}
