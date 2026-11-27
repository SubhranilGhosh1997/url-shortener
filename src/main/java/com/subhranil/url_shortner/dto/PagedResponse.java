package com.subhranil.url_shortner.dto;

import java.util.List;

/** A stable page shape for the API, instead of serializing Spring Data's Page directly. */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
}
