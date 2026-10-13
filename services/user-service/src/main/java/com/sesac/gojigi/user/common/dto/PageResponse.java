package com.sesac.gojigi.user.common.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/** 페이지 응답 — ApiResponse<PageResponse<T>> 로 감싼다 (컨벤션 2-4). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean hasNext) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages(), page.hasNext());
    }
}
