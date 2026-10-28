package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.review.review.client.ReviewImageClient;
import com.sesac.gojigi.review.review.client.ReviewImageClient.PresignedUpload;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest.FileItem;
import com.sesac.gojigi.review.review.dto.response.ReviewImagePresignResponse;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReviewImagePresignServiceTest {

    private static final Instant EXPIRES = Instant.parse("2026-10-28T06:10:00Z");

    @Mock
    private ReviewImageClient reviewImageClient;

    @InjectMocks
    private ReviewImagePresignService service;

    private static ReviewImagePresignRequest request(FileItem... files) {
        return new ReviewImagePresignRequest(List.of(files));
    }

    @Test
    @DisplayName("사진마다 reviews/{userId}/{UUID}.{확장자} 키로 발급 — 요청 순서대로, 만료 시각은 KST")
    void presign_issuesOwnKeysInOrder() {
        // given
        given(reviewImageClient.presignUpload(anyString(), anyString(), anyLong()))
                .willAnswer(inv -> new PresignedUpload("https://s3/" + inv.getArgument(0), EXPIRES));

        // when
        List<ReviewImagePresignResponse> issued = service.presign(4L, request(
                new FileItem("image/jpeg", 1_000L), new FileItem("image/png", 2_000L), new FileItem("image/webp", 3_000L)));

        // then
        assertThat(issued).hasSize(3);
        assertThat(issued.get(0).key()).matches("reviews/4/[0-9a-f-]{36}\\.jpg");
        assertThat(issued.get(1).key()).endsWith(".png");
        assertThat(issued.get(2).key()).endsWith(".webp");
        assertThat(issued.get(0).uploadUrl()).isEqualTo("https://s3/" + issued.get(0).key());
        assertThat(issued.get(0).expiresAt()).isEqualTo(OffsetDateTime.parse("2026-10-28T15:10:00+09:00"));
    }

    @Test
    @DisplayName("10MB 딱 맞으면 발급된다")
    void presign_exactlyTenMegabytes_isAllowed() {
        given(reviewImageClient.presignUpload(anyString(), eq("image/jpeg"), eq(ReviewImagePresignService.MAX_FILE_SIZE)))
                .willReturn(new PresignedUpload("https://s3/a", EXPIRES));

        assertThat(service.presign(4L, request(new FileItem("image/jpeg", ReviewImagePresignService.MAX_FILE_SIZE))))
                .hasSize(1);
    }

    @Test
    @DisplayName("0장 · 4장은 400 REVIEW_IMAGE_INVALID, 발급하지 않는다")
    void presign_zeroOrFourFiles_throwsInvalid() {
        FileItem jpg = new FileItem("image/jpeg", 1_000L);

        assertThatThrownBy(() -> service.presign(4L, request()))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID);
        assertThatThrownBy(() -> service.presign(4L, request(jpg, jpg, jpg, jpg)))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID)
                .hasMessage("사진은 한 번에 1~3장까지 올릴 수 있습니다.");
        verifyNoInteractions(reviewImageClient);
    }

    @ParameterizedTest(name = "{0} · {1}바이트")
    @CsvSource({
            "image/gif, 1000, 2번째 파일: 사진은 JPG · PNG · WEBP 만 올릴 수 있습니다.",
            "image/jpeg, 0, 2번째 파일: 사진은 10MB 이하만 올릴 수 있습니다.",
            "image/jpeg, 10485761, 2번째 파일: 사진은 10MB 이하만 올릴 수 있습니다."
    })
    @DisplayName("형식 · 크기 위반은 몇 번째 파일인지 알려 주고 전체 거절")
    void presign_invalidFile_rejectsAll(String contentType, long size, String message) {
        assertThatThrownBy(() -> service.presign(4L, request(new FileItem("image/jpeg", 1_000L), new FileItem(contentType, size))))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID)
                .hasMessage(message);
        verifyNoInteractions(reviewImageClient);
    }
}
