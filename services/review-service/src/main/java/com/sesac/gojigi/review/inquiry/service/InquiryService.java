package com.sesac.gojigi.review.inquiry.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.common.util.TextSanitizer;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.repository.ProductInquiryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 문의 등록 · 수정 · 삭제 (REV-07, docs/api/review.md 13 ~ 15절). 담당 이경석.
 * 등록 때 product · user 를 동기 조회해 스토어 · 상품명 · 작성자 이름을 저장한다 — 트랜잭션 밖에서 부른다(DB 커넥션을 잡고 기다리지 않게).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    private final ProductInquiryRepository inquiryRepository;
    private final InquirySnapshotReader snapshotReader;
    private final Clock clock;

    public InquiryCreateResponse write(Long userId, InquiryCreateRequest request) {
        String content = sanitizeContent(request.content());
        InquirySnapshot snapshot = snapshotReader.read(userId, request.productId());
        ProductInquiry inquiry = inquiryRepository.save(ProductInquiry.builder()
                .userId(userId).productId(request.productId()).storeId(snapshot.storeId())
                .productName(snapshot.productName()).thumbnailUrl(snapshot.thumbnailUrl())
                .writerDisplayName(snapshot.writerDisplayName())
                .content(content).secret(request.secret())
                .build());
        log.info("상품 문의 등록 inquiryId={} userId={} productId={} storeId={} secret={}",
                inquiry.getId(), userId, inquiry.getProductId(), inquiry.getStoreId(), inquiry.isSecret());
        return new InquiryCreateResponse(inquiry.getId());
    }

    /** 내 문의 수정 — 판매자가 답변한 뒤에는 막는다(답변과 질문이 어긋나지 않게). 없음 · 삭제됨 · 남의 문의는 같은 404 */
    @Transactional
    public InquiryResponse edit(Long userId, Long inquiryId, InquiryUpdateRequest request) {
        ProductInquiry inquiry = findOwnInquiry(userId, inquiryId);
        if (inquiry.isAnswered()) {
            throw new InquiryException(InquiryErrorCode.INQUIRY_ALREADY_ANSWERED);
        }
        inquiry.edit(sanitizeContent(request.content()), request.secret());
        log.info("상품 문의 수정 inquiryId={} userId={} secret={}", inquiry.getId(), userId, inquiry.isSecret());
        return InquiryResponse.from(inquiry);
    }

    /** 내 문의 삭제 — soft delete, 답변이 달린 문의도 지울 수 있다 */
    @Transactional
    public void delete(Long userId, Long inquiryId) {
        ProductInquiry inquiry = findOwnInquiry(userId, inquiryId);
        inquiry.softDelete(LocalDateTime.now(clock));
        log.info("상품 문의 삭제 inquiryId={} userId={} answered={}", inquiry.getId(), userId, inquiry.isAnswered());
    }

    /** 없음 · 삭제됨 · 남의 문의는 같은 404 — 다른 회원 문의의 존재를 드러내지 않는다 */
    private ProductInquiry findOwnInquiry(Long userId, Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .filter(i -> !i.isDeleted() && i.isOwnedBy(userId))
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.INQUIRY_NOT_FOUND));
    }

    /** 태그를 지운 본문 — 남는 글자가 없으면 형식 오류 */
    private static String sanitizeContent(String content) {
        String sanitized = TextSanitizer.plainText(content);
        if (sanitized == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "문의 내용을 입력해 주세요.");
        }
        return sanitized;
    }
}
