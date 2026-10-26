package com.sesac.gojigi.store.apply.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.store.apply.client.SellerDocumentClient;
import com.sesac.gojigi.store.apply.client.SellerDocumentClient.PresignedUpload;
import com.sesac.gojigi.store.apply.dto.request.DocumentPresignRequest;
import com.sesac.gojigi.store.apply.dto.request.DocumentPresignRequest.FileItem;
import com.sesac.gojigi.store.apply.dto.response.DocumentPresignResponse;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.exception.ApplyErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerDocumentPresignServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-26T06:10:00Z");
    private static final long MAX = 10L * 1024 * 1024;

    @Mock
    private SellerDocumentClient sellerDocumentClient;

    private SellerDocumentPresignService service;

    @BeforeEach
    void setUp() {
        service = new SellerDocumentPresignService(sellerDocumentClient);
    }

    @Test
    @DisplayName("파일마다 내 경로 키 · 형식별 확장자로 발급하고, 요청 순서대로 돌려준다")
    void presign_validFiles_returnsKeysInOrder() {
        // given
        given(sellerDocumentClient.presignUpload(anyString(), anyString(), anyLong()))
                .willAnswer(inv -> new PresignedUpload("https://s3.example/" + inv.getArgument(0), EXPIRES));

        // when
        List<DocumentPresignResponse> result = service.presign(4L, request(
                new FileItem(DocType.BUSINESS_REG, "application/pdf", 1000L),
                new FileItem(DocType.BANK_COPY, "image/png", 2000L),
                new FileItem(DocType.PRODUCT_PHOTO, "image/jpeg", MAX)));

        // then
        assertThat(result).extracting(DocumentPresignResponse::key)
                .satisfiesExactly(
                        key -> assertThat(key).matches("applications/4/[0-9a-f-]{36}\\.pdf"),
                        key -> assertThat(key).matches("applications/4/[0-9a-f-]{36}\\.png"),
                        key -> assertThat(key).matches("applications/4/[0-9a-f-]{36}\\.jpg"));
        assertThat(result).extracting(DocumentPresignResponse::key).doesNotHaveDuplicates();
        assertThat(result.get(0).uploadUrl()).isEqualTo("https://s3.example/" + result.get(0).key());
        assertThat(result.get(0).expiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-26T15:10:00+09:00"));
    }

    @Test
    @DisplayName("발급 요청의 형식 · 크기를 그대로 서명에 넘긴다")
    void presign_validFile_passesTypeAndSizeToClient() {
        // given
        given(sellerDocumentClient.presignUpload(startsWith("applications/4/"), eq("application/pdf"), eq(482113L)))
                .willReturn(new PresignedUpload("https://s3.example/put", EXPIRES));

        // when · then
        assertThat(service.presign(4L, request(new FileItem(DocType.ECOMMERCE_REG, "application/pdf", 482113L))))
                .hasSize(1);
    }

    @Test
    @DisplayName("0개 · 14개는 400 APPLY_DOCUMENT_INVALID, S3 서명 안 함")
    void presign_fileCountOutOfRange_throwsInvalid() {
        FileItem photo = new FileItem(DocType.PRODUCT_PHOTO, "image/png", 1L);

        assertInvalid(new DocumentPresignRequest(List.of()), "1~13개");
        assertInvalid(new DocumentPresignRequest(Collections.nCopies(14, photo)), "1~13개");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "상품 사진 PDF        | PRODUCT_PHOTO | application/pdf | 1          | 대표 상품 사진은 JPG · PNG 만",
            "서류 webp            | BUSINESS_REG  | image/webp      | 1          | 사업자등록증은 PDF · JPG · PNG 만",
            "서류 gif             | BANK_COPY     | image/gif       | 1          | 통장 사본은 PDF · JPG · PNG 만",
            "10MB 초과            | BUSINESS_REG  | application/pdf | 10485761   | 10MB 이하",
            "0 바이트             | PRODUCT_PHOTO | image/jpeg      | 0          | 10MB 이하"
    })
    @DisplayName("형식 · 크기 위반은 400 APPLY_DOCUMENT_INVALID, S3 서명 안 함")
    void presign_invalidFile_throwsInvalid(String caseName, DocType docType, String contentType, long size, String message) {
        assertInvalid(request(new FileItem(docType, contentType, size)), message);
    }

    @Test
    @DisplayName("하나만 틀려도 전체 거절 — 메시지에 몇 번째 파일인지")
    void presign_oneInvalidAmongMany_rejectsAllWithOrder() {
        assertInvalid(request(
                new FileItem(DocType.BUSINESS_REG, "application/pdf", 1L),
                new FileItem(DocType.PRODUCT_PHOTO, "application/pdf", 1L)), "2번째 파일");
    }

    private void assertInvalid(DocumentPresignRequest request, String messagePart) {
        assertThatThrownBy(() -> service.presign(4L, request))
                .hasMessageContaining(messagePart)
                .extracting("errorCode").isEqualTo(ApplyErrorCode.APPLY_DOCUMENT_INVALID);
        verifyNoInteractions(sellerDocumentClient);
    }

    private static DocumentPresignRequest request(FileItem... files) {
        return new DocumentPresignRequest(List.of(files));
    }
}
