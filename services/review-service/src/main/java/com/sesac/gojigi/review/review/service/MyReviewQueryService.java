package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 마이페이지 리뷰 관리 조회 (REV-05, review.md 4 · 5절) — 내가 쓴 리뷰 · 작성 가능한 리뷰. 담당 이경석 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyReviewQueryService {

    /** 최신 작성순, 같은 시각이면 나중 ID 먼저 */
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final ReviewRepository reviewRepository;
    private final ReviewEligibilityRepository eligibilityRepository;
    private final ReviewImages reviewImages;
    private final Clock clock;

    public PageResponse<MyReviewResponse> listMine(Long userId, int page, int size) {
        return reviewImages.toPage(reviewRepository.findByUserIdAndDeletedAtIsNull(userId, PageRequest.of(page, size, NEWEST)),
                MyReviewResponse::from);
    }

    public List<WritableReviewResponse> listWritable(Long userId) {
        return eligibilityRepository.findWritable(userId, LocalDateTime.now(clock)).stream()
                .map(WritableReviewResponse::from)
                .toList();
    }
}
