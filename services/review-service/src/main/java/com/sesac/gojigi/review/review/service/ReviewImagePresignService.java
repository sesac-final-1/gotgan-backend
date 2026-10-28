package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.review.review.client.ReviewImageClient;
import com.sesac.gojigi.review.review.client.ReviewImageClient.PresignedUpload;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewImagePresignRequest.FileItem;
import com.sesac.gojigi.review.review.dto.response.ReviewImagePresignResponse;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 리뷰 사진 업로드 주소 발급 (REV-03, review.md 12절). 담당 이경석.
 * 키는 reviews/{userId}/{UUID}.{확장자} — 작성 · 수정은 이 모양의 내 키만 받는다(ReviewImageKeys).
 */
@Service
@RequiredArgsConstructor
public class ReviewImagePresignService {

    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** 형식 → 키 확장자 (파일 이름이 아니라 형식으로 정한다) — product 와 같은 3종 */
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ReviewImageClient reviewImageClient;

    public List<ReviewImagePresignResponse> presign(Long userId, ReviewImagePresignRequest request) {
        List<FileItem> files = request.files();
        if (files.isEmpty() || files.size() > ReviewImageKeys.MAX_IMAGES) {
            throw invalidImage("사진은 한 번에 1~" + ReviewImageKeys.MAX_IMAGES + "장까지 올릴 수 있습니다.");
        }
        for (int i = 0; i < files.size(); i++) {
            check(i + 1, files.get(i));
        }
        return files.stream().map(file -> issue(userId, file)).toList();
    }

    /** 하나라도 틀리면 전체 거절 — 메시지에 몇 번째 파일인지 (요청에 파일 이름이 없음) */
    private static void check(int order, FileItem file) {
        if (!EXTENSIONS.containsKey(file.contentType())) {
            throw invalidImage(order + "번째 파일: 사진은 JPG · PNG · WEBP 만 올릴 수 있습니다.");
        }
        if (file.size() <= 0 || file.size() > MAX_FILE_SIZE) {
            throw invalidImage(order + "번째 파일: 사진은 10MB 이하만 올릴 수 있습니다.");
        }
    }

    private ReviewImagePresignResponse issue(Long userId, FileItem file) {
        String key = ReviewImageKeys.PREFIX + userId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(file.contentType());
        PresignedUpload upload = reviewImageClient.presignUpload(key, file.contentType(), file.size());
        return new ReviewImagePresignResponse(key, upload.url(), upload.expiresAt().atZone(KST).toOffsetDateTime());
    }

    private static ReviewException invalidImage(String message) {
        return new ReviewException(ReviewErrorCode.REVIEW_IMAGE_INVALID, message);
    }
}
