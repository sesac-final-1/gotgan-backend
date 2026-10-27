package com.sesac.gojigi.product.image.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.product.image.client.ProductImageClient;
import com.sesac.gojigi.product.image.client.ProductImageClient.PresignedUpload;
import com.sesac.gojigi.product.image.dto.request.ImagePresignRequest;
import com.sesac.gojigi.product.image.dto.request.ImagePresignRequest.FileItem;
import com.sesac.gojigi.product.image.dto.response.ImagePresignResponse;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductImagePresignServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-27T06:10:00Z");
    private static final long MAX = 10L * 1024 * 1024;

    @Mock
    private ProductImageClient productImageClient;

    private ProductImagePresignService service;

    @BeforeEach
    void setUp() {
        service = new ProductImagePresignService(productImageClient);
    }

    private static ImagePresignRequest request(FileItem... files) {
        return new ImagePresignRequest(List.of(files));
    }

    @Test
    @DisplayName("파일마다 내 스토어 키 · 형식별 확장자로 발급하고, 요청 순서대로 돌려준다")
    void presign_validFiles_returnsKeysInOrder() {
        // given
        given(productImageClient.presignUpload(anyString(), anyString(), anyLong()))
                .willAnswer(inv -> new PresignedUpload("https://s3.example/" + inv.getArgument(0), EXPIRES));

        // when
        List<ImagePresignResponse> result = service.presign(1L, request(
                new FileItem("image/jpeg", 1000L),
                new FileItem("image/png", 2000L),
                new FileItem("image/webp", MAX)));

        // then
        assertThat(result).extracting(ImagePresignResponse::key)
                .satisfiesExactly(
                        key -> assertThat(key).matches("products/1/[0-9a-f-]{36}\\.jpg"),
                        key -> assertThat(key).matches("products/1/[0-9a-f-]{36}\\.png"),
                        key -> assertThat(key).matches("products/1/[0-9a-f-]{36}\\.webp"));
        assertThat(result).extracting(ImagePresignResponse::key).doesNotHaveDuplicates();
        assertThat(result.get(0).uploadUrl()).isEqualTo("https://s3.example/" + result.get(0).key());
        assertThat(result.get(0).expiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-27T15:10:00+09:00"));
    }

    @Test
    @DisplayName("발급 요청의 형식 · 크기를 그대로 서명에 넘긴다")
    void presign_validFile_passesTypeAndSizeToClient() {
        // given
        given(productImageClient.presignUpload(startsWith("products/7/"), eq("image/png"), eq(834512L)))
                .willReturn(new PresignedUpload("https://s3.example/put", EXPIRES));

        // when · then
        assertThat(service.presign(7L, request(new FileItem("image/png", 834512L))))
                .extracting(ImagePresignResponse::uploadUrl).containsExactly("https://s3.example/put");
    }

    static Stream<Arguments> invalidRequests() {
        FileItem ok = new FileItem("image/jpeg", 1000L);
        return Stream.of(
                Arguments.of("0개", List.of(), "1~5개"),
                Arguments.of("6개", List.of(ok, ok, ok, ok, ok, ok), "1~5개"),
                Arguments.of("GIF", List.of(ok, new FileItem("image/gif", 1000L)), "2번째 파일: 이미지는 JPG · PNG · WEBP"),
                Arguments.of("PDF", List.of(new FileItem("application/pdf", 1000L)), "1번째 파일: 이미지는 JPG · PNG · WEBP"),
                Arguments.of("10MB 초과", List.of(new FileItem("image/png", MAX + 1)), "1번째 파일: 이미지는 10MB"),
                Arguments.of("0바이트", List.of(ok, ok, new FileItem("image/png", 0L)), "3번째 파일: 이미지는 10MB"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    @DisplayName("개수 · 형식 · 크기 위반 — 하나라도 틀리면 발급 없이 전체 거절, 몇 번째 파일인지 알려 줌")
    void presign_invalidFiles_throwsImageInvalid(String caseName, List<FileItem> files, String message) {
        // when · then
        assertThatThrownBy(() -> service.presign(1L, new ImagePresignRequest(files)))
                .extracting("errorCode", "message")
                .satisfies(values -> {
                    assertThat(values.get(0)).isEqualTo(ProductErrorCode.PRODUCT_IMAGE_INVALID);
                    assertThat((String) values.get(1)).contains(message);
                });
        verifyNoInteractions(productImageClient);
    }
}
