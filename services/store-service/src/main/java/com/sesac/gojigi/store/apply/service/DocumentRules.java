package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.entity.DocType;
import java.util.Map;
import java.util.Set;

/**
 * 입점 서류 형식 · 크기 기준 — presign 발급(1차)과 신청 때 S3 확인(2차)이 같은 기준을 쓴다 (docs/api/store.md 2절 · 2-1절).
 * 서류 3종은 PDF · JPG · PNG, 상품 사진은 JPG · PNG, 파일마다 10MB 이하.
 */
final class DocumentRules {

    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    private static final Set<String> DOCUMENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");
    private static final Map<String, String> EXTENSIONS = Map.of("application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png");

    private DocumentRules() {
    }

    static boolean allows(DocType docType, String contentType) {
        return contentType != null && (docType == DocType.PRODUCT_PHOTO ? PHOTO_TYPES : DOCUMENT_TYPES).contains(contentType);
    }

    static boolean withinSize(long size) {
        return size > 0 && size <= MAX_FILE_SIZE;
    }

    /** 허용 형식만 들어온다 (allows 통과 후 호출) — 키 확장자는 파일 이름이 아니라 형식으로 정한다 */
    static String extension(String contentType) {
        return EXTENSIONS.get(contentType);
    }

    static String typeMessage(DocType docType) {
        return docType == DocType.PRODUCT_PHOTO
                ? "대표 상품 사진은 JPG · PNG 만 올릴 수 있습니다."
                : label(docType) + "은 PDF · JPG · PNG 만 올릴 수 있습니다.";
    }

    static String label(DocType docType) {
        return switch (docType) {
            case BUSINESS_REG -> "사업자등록증";
            case ECOMMERCE_REG -> "통신판매업 신고증";
            case BANK_COPY -> "통장 사본";
            case PRODUCT_PHOTO -> "대표 상품 사진";
        };
    }
}
