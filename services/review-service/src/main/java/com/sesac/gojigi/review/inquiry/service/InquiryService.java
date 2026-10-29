package com.sesac.gojigi.review.inquiry.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.common.util.TextSanitizer;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryAnswerRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.SellerInquiryTab;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryAnswerResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryCreateResponse;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.ProductInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquiryResponse;
import com.sesac.gojigi.review.inquiry.dto.response.SellerInquirySummaryResponse;
import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import com.sesac.gojigi.review.inquiry.exception.InquiryErrorCode;
import com.sesac.gojigi.review.inquiry.exception.InquiryException;
import com.sesac.gojigi.review.inquiry.repository.ProductInquiryRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 문의 등록 · 수정 · 삭제 · 조회 · 판매자 답변 (REV-07 ~ 09, docs/api/review.md 13 ~ 20절). 담당 이경석.
 * 등록 때 product · user 를 동기 조회해 스토어 · 상품명 · 작성자 이름을 저장한다 — 트랜잭션 밖에서 부른다(DB 커넥션을 잡고 기다리지 않게).
 * 조회는 저장한 스냅샷만 읽는다 — 다른 서비스를 부르지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InquiryService {

    /** 최신 등록순, 같은 시각이면 나중 ID 먼저 */
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

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

    /** 상품 상세 Q&A (공개) — 비밀글은 작성자 · 그 스토어 판매자가 아니면 내용 · 답변을 비운다. viewer 는 비로그인이면 null */
    @Transactional(readOnly = true)
    public PageResponse<ProductInquiryResponse> listForProduct(Long productId, Long viewerUserId, Long viewerStoreId,
            int page, int size) {
        return PageResponse.from(inquiryRepository.findByProductIdAndDeletedAtIsNull(productId, PageRequest.of(page, size, NEWEST))
                .map(i -> ProductInquiryResponse.of(i, viewerUserId, viewerStoreId)));
    }

    /** 마이페이지 내 문의 — 비밀글 · 답변 포함 전부 */
    @Transactional(readOnly = true)
    public PageResponse<InquiryResponse> listMine(Long userId, int page, int size) {
        return PageResponse.from(inquiryRepository.findByUserIdAndDeletedAtIsNull(userId, PageRequest.of(page, size, NEWEST))
                .map(InquiryResponse::from));
    }

    /** 판매자 문의 — 내 스토어 문의, 비밀글도 내용을 보여 준다 */
    @Transactional(readOnly = true)
    public PageResponse<SellerInquiryResponse> listForSeller(Long storeId, SellerInquiryTab tab, int page, int size) {
        requireStore(storeId);
        return PageResponse.from(inquiryRepository.findForSeller(storeId, tab == SellerInquiryTab.UNANSWERED,
                        tab == SellerInquiryTab.ANSWERED, PageRequest.of(page, size, NEWEST))
                .map(SellerInquiryResponse::from));
    }

    /** 판매자 탭 숫자 — 미답변 · 답변완료 · 전체 (삭제 제외) */
    @Transactional(readOnly = true)
    public SellerInquirySummaryResponse summarizeForSeller(Long storeId) {
        requireStore(storeId);
        long all = inquiryRepository.countByStoreIdAndDeletedAtIsNull(storeId);
        long unanswered = inquiryRepository.countByStoreIdAndAnsweredAtIsNullAndDeletedAtIsNull(storeId);
        return new SellerInquirySummaryResponse(unanswered, all - unanswered, all);
    }

    /**
     * 판매자 답변 작성 · 수정 (REV-09) — 문의당 1개, 있으면 덮어쓴다. 없음 · 삭제됨 · 다른 스토어 문의는 같은 404.
     * 답변이 달리면 구매자 수정이 막힌다(edit 의 INQUIRY_ALREADY_ANSWERED).
     */
    @Transactional
    public InquiryAnswerResponse answer(Long storeId, Long inquiryId, InquiryAnswerRequest request) {
        requireStore(storeId);
        ProductInquiry inquiry = inquiryRepository.findById(inquiryId)
                .filter(i -> !i.isDeleted() && i.belongsToStore(storeId))
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.INQUIRY_NOT_FOUND));
        String content = TextSanitizer.plainText(request.content());
        if (content == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "답변을 입력해 주세요.");
        }

        boolean edited = inquiry.isAnswered();
        inquiry.answer(content, LocalDateTime.now(clock));
        log.info("판매자 답변 {} inquiryId={} storeId={}", edited ? "수정" : "작성", inquiry.getId(), storeId);
        return InquiryAnswerResponse.from(inquiry);
    }

    /** 없음 · 삭제됨 · 남의 문의는 같은 404 — 다른 회원 문의의 존재를 드러내지 않는다 */
    private ProductInquiry findOwnInquiry(Long userId, Long inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .filter(i -> !i.isDeleted() && i.isOwnedBy(userId))
                .orElseThrow(() -> new InquiryException(InquiryErrorCode.INQUIRY_NOT_FOUND));
    }

    /** 판매자인데 스토어 정보(X-Store-Id)가 없음 — 승인 전 · 토큰 갱신 전 */
    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
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
