package com.sesac.gojigi.admin.common.dto;

import java.util.List;

/** 페이지 응답 — ApiResponse<PageResponse<T>> 로 감싼다 (컨벤션 2-4). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);
        return new PageResponse<>(content, page, size, totalElements, totalPages, page + 1 < totalPages);
    }
}
