package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.common.s3.PresignedUpload;
import com.sesac.gojigi.store.apply.dto.request.DocumentPresignRequest;
import com.sesac.gojigi.store.apply.dto.request.DocumentPresignRequest.FileItem;
import com.sesac.gojigi.store.apply.dto.response.DocumentPresignResponse;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import com.sesac.gojigi.store.apply.exception.ApplyException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 입점 서류 업로드 주소 발급 (STR-02, docs/api/store.md 2-1절). 담당 박지연.
 * 개수 · 형식 · 크기만 1차로 막고, 서류 구성(3종 각 1 + 사진 3~10)은 신청 때 검사한다 — 폼 단계마다 나눠 불러도 되게.
 * 키는 applications/{userId}/{UUID}.{확장자} — 신청은 내 경로 키만 받는다.
 */
@Service
@RequiredArgsConstructor
public class SellerDocumentPresignService {

    static final int MAX_FILES = 13;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SellerDocumentClient sellerDocumentClient;

    public List<DocumentPresignResponse> presign(Long userId, DocumentPresignRequest request) {
        List<FileItem> files = request.files();
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw invalidDocument("파일은 한 번에 1~13개까지 올릴 수 있습니다.");
        }
        for (int i = 0; i < files.size(); i++) {
            check(i + 1, files.get(i));
        }
        return files.stream().map(file -> issue(userId, file)).toList();
    }

    /** 하나라도 틀리면 전체 거절 — 메시지에 몇 번째 파일인지 (요청에 파일 이름이 없음) */
    private static void check(int order, FileItem file) {
        if (!DocumentRules.allows(file.docType(), file.contentType())) {
            throw invalidDocument(order + "번째 파일: " + DocumentRules.typeMessage(file.docType()));
        }
        if (!DocumentRules.withinSize(file.size())) {
            throw invalidDocument(order + "번째 파일: 파일은 10MB 이하만 올릴 수 있습니다.");
        }
    }

    private DocumentPresignResponse issue(Long userId, FileItem file) {
        String key = "applications/" + userId + "/" + UUID.randomUUID() + "." + DocumentRules.extension(file.contentType());
        PresignedUpload upload = sellerDocumentClient.presignUpload(key, file.contentType(), file.size());
        return new DocumentPresignResponse(key, upload.url(), upload.expiresAt().atZone(KST).toOffsetDateTime());
    }

    private static ApplyException invalidDocument(String message) {
        return new ApplyException(ApplyErrorCode.APPLY_DOCUMENT_INVALID, message);
    }
}
