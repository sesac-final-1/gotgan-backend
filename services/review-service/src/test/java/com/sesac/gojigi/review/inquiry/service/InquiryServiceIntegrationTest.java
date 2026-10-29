package com.sesac.gojigi.review.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader;
import com.sesac.gojigi.review.inquiry.client.InquirySnapshotReader.InquirySnapshot;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryAnswerRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryCreateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.InquiryUpdateRequest;
import com.sesac.gojigi.review.inquiry.dto.request.SellerInquiryTab;
import com.sesac.gojigi.review.inquiry.dto.response.InquiryAnswerResponse;
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
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 상품 문의 등록 · 수정 · 삭제 — 스냅샷 저장 · 본문 정화 · 본인 · 답변 후 수정 막기 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, InquiryService.class, InquiryServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class InquiryServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 10, 0);
    private static final long BUYER = 4L;
    private static final long OTHER = 5L;
    /** 조회 테스트끼리 · 다른 실행과 상품 · 스토어 · 회원 ID 가 겹치지 않게 (테스트마다 커밋) */
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private InquiryService inquiryService;
    @Autowired
    private ProductInquiryRepository inquiryRepository;
    @MockitoBean
    private InquirySnapshotReader snapshotReader;

    private long saveInquiry(long userId) {
        return inquiryRepository.save(ProductInquiry.builder()
                .userId(userId).productId(101L).storeId(7L).productName("린넨 오버핏 셔츠").writerDisplayName("곳간러버")
                .content("M이랑 L 중 어떤 걸 추천하시나요?").secret(false)
                .build()).getId();
    }

    private ProductInquiry reload(long inquiryId) {
        return inquiryRepository.findById(inquiryId).orElseThrow();
    }

    private static void assertCode(Throwable thrown, ErrorCode code) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getErrorCode()).isEqualTo(code));
    }

    @Nested
    @DisplayName("등록")
    class Write {

        @Test
        @DisplayName("상품 · 회원 스냅샷과 정화한 본문을 저장한다 — storeId 는 상품에서")
        void write_savesSnapshotAndSanitizedContent() {
            // given
            given(snapshotReader.read(BUYER, 101L))
                    .willReturn(new InquirySnapshot(7L, "린넨 오버핏 셔츠", "https://cdn/p.jpg", "곳간러버"));

            // when
            long inquiryId = inquiryService.write(BUYER,
                    new InquiryCreateRequest(101L, "<b>세이지</b> 컬러 재입고 언제 되나요?<script>x</script>", true)).inquiryId();

            // then
            ProductInquiry saved = reload(inquiryId);
            assertThat(saved.getUserId()).isEqualTo(BUYER);
            assertThat(saved.getStoreId()).isEqualTo(7L);
            assertThat(saved.getProductName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(saved.getThumbnailUrl()).isEqualTo("https://cdn/p.jpg");
            assertThat(saved.getWriterDisplayName()).isEqualTo("곳간러버");
            assertThat(saved.getContent()).isEqualTo("세이지 컬러 재입고 언제 되나요?");
            assertThat(saved.isSecret()).isTrue();
            assertThat(saved.isAnswered()).isFalse();
        }

        @Test
        @DisplayName("태그를 지우면 남는 글자가 없음 — 400 INVALID_INPUT, 다른 서비스를 부르지 않는다")
        void write_onlyTags_rejectedBeforeRemoteCall() {
            Throwable thrown = catchThrowable(
                    () -> inquiryService.write(BUYER, new InquiryCreateRequest(101L, "<script>alert(1)</script>", false)));

            assertCode(thrown, CommonErrorCode.INVALID_INPUT);
            verify(snapshotReader, never()).read(anyLong(), anyLong());
        }

        @Test
        @DisplayName("없는 상품 — 스냅샷 오류 그대로, 저장하지 않는다")
        void write_productNotFound_notSaved() {
            given(snapshotReader.read(BUYER, 999L)).willThrow(new InquiryException(InquiryErrorCode.INQUIRY_PRODUCT_NOT_FOUND));
            long before = inquiryRepository.count();

            assertThatThrownBy(() -> inquiryService.write(BUYER, new InquiryCreateRequest(999L, "재입고 되나요?", false)))
                    .isInstanceOf(InquiryException.class);
            assertThat(inquiryRepository.count()).isEqualTo(before);
        }
    }

    @Nested
    @DisplayName("수정")
    class Edit {

        @Test
        @DisplayName("답변 전 내 문의 — 본문 · 비밀글을 바꾸고 수정된 값을 돌려준다")
        void edit_ownUnanswered_updates() {
            // given
            long inquiryId = saveInquiry(BUYER);

            // when
            InquiryResponse response = inquiryService.edit(BUYER, inquiryId,
                    new InquiryUpdateRequest("어깨가 <i>넓은</i> 편인데 L 이 나을까요?", true));

            // then
            assertThat(response.content()).isEqualTo("어깨가 넓은 편인데 L 이 나을까요?");
            assertThat(response.secret()).isTrue();
            assertThat(response.productName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(response.answerContent()).isNull();
            assertThat(response.createdAt()).isNotNull();
            ProductInquiry saved = reload(inquiryId);
            assertThat(saved.getContent()).isEqualTo("어깨가 넓은 편인데 L 이 나을까요?");
            assertThat(saved.isSecret()).isTrue();
        }

        @Test
        @DisplayName("판매자가 답변한 문의 — 409 INQUIRY_ALREADY_ANSWERED, 그대로")
        void edit_answered_rejected() {
            // given
            long inquiryId = saveInquiry(BUYER);
            ProductInquiry inquiry = reload(inquiryId);
            inquiry.answer("M 추천드립니다", NOW);
            inquiryRepository.save(inquiry);

            // when
            Throwable thrown = catchThrowable(
                    () -> inquiryService.edit(BUYER, inquiryId, new InquiryUpdateRequest("바꿀래요", true)));

            // then
            assertCode(thrown, InquiryErrorCode.INQUIRY_ALREADY_ANSWERED);
            assertThat(reload(inquiryId).getContent()).isEqualTo("M이랑 L 중 어떤 걸 추천하시나요?");
        }

        @Test
        @DisplayName("남의 문의 · 삭제된 문의 · 없는 문의 — 같은 404")
        void edit_notOwnOrDeleted_notFound() {
            long others = saveInquiry(OTHER);
            long deleted = saveInquiry(BUYER);
            inquiryService.delete(BUYER, deleted);
            InquiryUpdateRequest request = new InquiryUpdateRequest("바꿀래요", false);

            for (long inquiryId : new long[] { others, deleted, Long.MAX_VALUE }) {
                Throwable thrown = catchThrowable(() -> inquiryService.edit(BUYER, inquiryId, request));
                assertCode(thrown, InquiryErrorCode.INQUIRY_NOT_FOUND);
            }
        }
    }

    @Nested
    @DisplayName("삭제")
    class Delete {

        @Test
        @DisplayName("내 문의 — 답변이 달려 있어도 소프트 삭제")
        void delete_ownAnswered_softDeleted() {
            // given
            long inquiryId = saveInquiry(BUYER);
            ProductInquiry inquiry = reload(inquiryId);
            inquiry.answer("다음 주 재입고 예정입니다", NOW);
            inquiryRepository.save(inquiry);

            // when
            inquiryService.delete(BUYER, inquiryId);

            // then
            ProductInquiry deleted = reload(inquiryId);
            assertThat(deleted.isDeleted()).isTrue();
            assertThat(deleted.getDeletedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("남의 문의 · 이미 삭제된 문의 — 404, 남의 문의는 그대로")
        void delete_notOwnOrDeleted_notFound() {
            long others = saveInquiry(OTHER);
            long deleted = saveInquiry(BUYER);
            inquiryService.delete(BUYER, deleted);

            assertCode(catchThrowable(() -> inquiryService.delete(BUYER, others)),
                    InquiryErrorCode.INQUIRY_NOT_FOUND);
            assertCode(catchThrowable(() -> inquiryService.delete(BUYER, deleted)),
                    InquiryErrorCode.INQUIRY_NOT_FOUND);
            assertThat(reload(others).isDeleted()).isFalse();
        }
    }

    /** 조회용 문의 — 저장 순서대로 created_at 이 늘어난다 */
    private ProductInquiry saveFor(long userId, long productId, long storeId, boolean secret, String content) {
        return inquiryRepository.saveAndFlush(ProductInquiry.builder()
                .userId(userId).productId(productId).storeId(storeId).productName("린넨 오버핏 셔츠")
                .thumbnailUrl("https://cdn/p.jpg").writerDisplayName("곳간러버").content(content).secret(secret)
                .build());
    }

    private void answer(ProductInquiry inquiry, String answer) {
        inquiry.answer(answer, NOW);
        inquiryRepository.save(inquiry);
    }

    private void softDelete(ProductInquiry inquiry) {
        inquiry.softDelete(NOW);
        inquiryRepository.save(inquiry);
    }

    @Nested
    @DisplayName("상품 문의 목록 (공개)")
    class ListForProduct {

        @Test
        @DisplayName("삭제 제외 최신순, 비밀글은 비로그인 · 남에게 내용 · 답변을 비우고 상태 · 이름만")
        void list_masksSecretForOthers() {
            // given
            long productId = SEQ.incrementAndGet();
            long writer = SEQ.incrementAndGet();
            ProductInquiry open = saveFor(writer, productId, 7L, false, "세탁기 사용 가능한가요?");
            answer(open, "네, 울코스로 가능합니다");
            ProductInquiry secret = saveFor(writer, productId, 7L, true, "배송지 변경 가능할까요?");
            answer(secret, "변경해 드렸습니다");
            softDelete(saveFor(writer, productId, 7L, false, "지운 문의"));
            saveFor(writer, SEQ.incrementAndGet(), 7L, false, "다른 상품 문의");

            // when
            PageResponse<ProductInquiryResponse> anonymous = inquiryService.listForProduct(productId, null, null, 0, 10);
            PageResponse<ProductInquiryResponse> other = inquiryService.listForProduct(productId, OTHER, 99L, 0, 10);

            // then
            assertThat(anonymous.totalElements()).isEqualTo(2);
            assertThat(anonymous.content()).extracting(ProductInquiryResponse::inquiryId).containsExactly(secret.getId(), open.getId());
            ProductInquiryResponse masked = anonymous.content().getFirst();
            assertThat(masked.secret()).isTrue();
            assertThat(masked.content()).isNull();
            assertThat(masked.answerContent()).isNull();
            assertThat(masked.answered()).isTrue();
            assertThat(masked.answeredAt()).isNotNull();
            assertThat(masked.writerDisplayName()).isEqualTo("곳간러버");
            assertThat(masked.mine()).isFalse();
            assertThat(anonymous.content().get(1).content()).isEqualTo("세탁기 사용 가능한가요?");
            assertThat(anonymous.content().get(1).answerContent()).isEqualTo("네, 울코스로 가능합니다");
            assertThat(other.content().getFirst().content()).isNull();
        }

        @Test
        @DisplayName("비밀글 — 작성자(mine) · 그 스토어 판매자는 내용 · 답변을 본다")
        void list_secretReadableByWriterAndStore() {
            // given
            long productId = SEQ.incrementAndGet();
            long writer = SEQ.incrementAndGet();
            ProductInquiry secret = saveFor(writer, productId, 7L, true, "배송지 변경 가능할까요?");
            answer(secret, "변경해 드렸습니다");

            // when
            ProductInquiryResponse byWriter = inquiryService.listForProduct(productId, writer, null, 0, 10).content().getFirst();
            ProductInquiryResponse bySeller = inquiryService.listForProduct(productId, 900L, 7L, 0, 10).content().getFirst();

            // then
            assertThat(byWriter.mine()).isTrue();
            assertThat(byWriter.content()).isEqualTo("배송지 변경 가능할까요?");
            assertThat(byWriter.answerContent()).isEqualTo("변경해 드렸습니다");
            assertThat(bySeller.mine()).isFalse();
            assertThat(bySeller.content()).isEqualTo("배송지 변경 가능할까요?");
        }

        @Test
        @DisplayName("페이지 — size 만큼, 없는 상품은 빈 목록")
        void list_paging() {
            long productId = SEQ.incrementAndGet();
            for (int i = 0; i < 3; i++) {
                saveFor(BUYER, productId, 7L, false, "문의 " + i);
            }

            PageResponse<ProductInquiryResponse> second = inquiryService.listForProduct(productId, null, null, 1, 2);

            assertThat(second.content()).extracting(ProductInquiryResponse::content).containsExactly("문의 0");
            assertThat(second.totalElements()).isEqualTo(3);
            assertThat(second.hasNext()).isFalse();
            assertThat(inquiryService.listForProduct(Long.MAX_VALUE, null, null, 0, 10).content()).isEmpty();
        }
    }

    @Test
    @DisplayName("내 문의 — 내 것만(비밀글 · 답변 포함), 삭제 제외 최신순, 상품 스냅샷")
    void listMine_ownOnly() {
        // given
        long me = SEQ.incrementAndGet();
        ProductInquiry first = saveFor(me, 101L, 7L, true, "배송지 변경 가능할까요?");
        answer(first, "변경해 드렸습니다");
        ProductInquiry second = saveFor(me, 102L, 8L, false, "재입고 언제 되나요?");
        softDelete(saveFor(me, 103L, 8L, false, "지운 문의"));
        saveFor(SEQ.incrementAndGet(), 101L, 7L, false, "남의 문의");

        // when
        PageResponse<InquiryResponse> page = inquiryService.listMine(me, 0, 10);

        // then
        assertThat(page.content()).extracting(InquiryResponse::inquiryId).containsExactly(second.getId(), first.getId());
        InquiryResponse answered = page.content().get(1);
        assertThat(answered.secret()).isTrue();
        assertThat(answered.content()).isEqualTo("배송지 변경 가능할까요?");
        assertThat(answered.answerContent()).isEqualTo("변경해 드렸습니다");
        assertThat(answered.productName()).isEqualTo("린넨 오버핏 셔츠");
        assertThat(answered.thumbnailUrl()).isEqualTo("https://cdn/p.jpg");
    }

    @Nested
    @DisplayName("판매자 문의")
    class Seller {

        @Test
        @DisplayName("탭 — 미답변 · 답변완료 · 전체, 내 스토어 · 삭제 제외, 비밀글도 내용을 보여 준다 · 숫자")
        void listAndSummary_byTab() {
            // given
            long storeId = SEQ.incrementAndGet();
            ProductInquiry unanswered = saveFor(BUYER, 101L, storeId, true, "배송지 변경 가능할까요?");
            ProductInquiry answeredOne = saveFor(BUYER, 102L, storeId, false, "재입고 언제 되나요?");
            answer(answeredOne, "다음 주 예정입니다");
            softDelete(saveFor(BUYER, 101L, storeId, false, "지운 문의"));
            saveFor(BUYER, 101L, SEQ.incrementAndGet(), false, "다른 스토어 문의");

            // when
            PageResponse<SellerInquiryResponse> all = inquiryService.listForSeller(storeId, SellerInquiryTab.ALL, 0, 10);
            PageResponse<SellerInquiryResponse> open = inquiryService.listForSeller(storeId, SellerInquiryTab.UNANSWERED, 0, 10);
            PageResponse<SellerInquiryResponse> done = inquiryService.listForSeller(storeId, SellerInquiryTab.ANSWERED, 0, 10);
            SellerInquirySummaryResponse summary = inquiryService.summarizeForSeller(storeId);

            // then
            assertThat(all.content()).extracting(SellerInquiryResponse::inquiryId).containsExactly(answeredOne.getId(), unanswered.getId());
            assertThat(open.content()).singleElement().satisfies(r -> {
                assertThat(r.inquiryId()).isEqualTo(unanswered.getId());
                assertThat(r.secret()).isTrue();
                assertThat(r.content()).isEqualTo("배송지 변경 가능할까요?");
                assertThat(r.writerDisplayName()).isEqualTo("곳간러버");
                assertThat(r.productName()).isEqualTo("린넨 오버핏 셔츠");
                assertThat(r.answerContent()).isNull();
            });
            assertThat(done.content()).singleElement().satisfies(r -> {
                assertThat(r.answerContent()).isEqualTo("다음 주 예정입니다");
                assertThat(r.answeredAt()).isNotNull();
            });
            assertThat(summary).isEqualTo(new SellerInquirySummaryResponse(1, 1, 2));
        }

        @Test
        @DisplayName("문의 없는 스토어 — 빈 목록 · 숫자 0")
        void emptyStore_zero() {
            long storeId = SEQ.incrementAndGet();

            assertThat(inquiryService.listForSeller(storeId, SellerInquiryTab.ALL, 0, 10).content()).isEmpty();
            assertThat(inquiryService.summarizeForSeller(storeId)).isEqualTo(new SellerInquirySummaryResponse(0, 0, 0));
        }

        @Test
        @DisplayName("스토어 정보 없음 — 403 FORBIDDEN")
        void withoutStore_forbidden() {
            assertCode(catchThrowable(() -> inquiryService.listForSeller(null, SellerInquiryTab.ALL, 0, 10)), CommonErrorCode.FORBIDDEN);
            assertCode(catchThrowable(() -> inquiryService.summarizeForSeller(null)), CommonErrorCode.FORBIDDEN);
            assertCode(catchThrowable(() -> inquiryService.answer(null, 1L, new InquiryAnswerRequest("답변"))), CommonErrorCode.FORBIDDEN);
        }

        @Test
        @DisplayName("답변 — 정화해 저장 · 시각, 다시 쓰면 덮어쓰고 미답변에서 빠진다 · 구매자 수정은 막힌다")
        void answer_savesAndOverwrites() {
            // given
            long storeId = SEQ.incrementAndGet();
            ProductInquiry inquiry = saveFor(BUYER, 101L, storeId, true, "배송지 변경 가능할까요?");

            // when
            InquiryAnswerResponse first = inquiryService.answer(storeId, inquiry.getId(), new InquiryAnswerRequest("<b>변경</b>해 드렸습니다"));
            InquiryAnswerResponse second = inquiryService.answer(storeId, inquiry.getId(), new InquiryAnswerRequest("변경해 드렸습니다. 감사합니다"));

            // then
            assertThat(first.answerContent()).isEqualTo("변경해 드렸습니다");
            assertThat(second.inquiryId()).isEqualTo(inquiry.getId());
            assertThat(second.answerContent()).isEqualTo("변경해 드렸습니다. 감사합니다");
            assertThat(second.answeredAt()).isEqualTo(NOW.atZone(KST).toOffsetDateTime());
            assertThat(reload(inquiry.getId()).getAnswerContent()).isEqualTo("변경해 드렸습니다. 감사합니다");
            assertThat(inquiryService.summarizeForSeller(storeId)).isEqualTo(new SellerInquirySummaryResponse(0, 1, 1));
            assertCode(catchThrowable(() -> inquiryService.edit(BUYER, inquiry.getId(), new InquiryUpdateRequest("바꿀래요", false))),
                    InquiryErrorCode.INQUIRY_ALREADY_ANSWERED);
        }

        @Test
        @DisplayName("답변 — 다른 스토어 · 삭제된 · 없는 문의 404, 태그만 있으면 400")
        void answer_rejected() {
            long storeId = SEQ.incrementAndGet();
            ProductInquiry others = saveFor(BUYER, 101L, SEQ.incrementAndGet(), false, "다른 스토어 문의");
            ProductInquiry deleted = saveFor(BUYER, 101L, storeId, false, "지운 문의");
            softDelete(deleted);
            ProductInquiry mine = saveFor(BUYER, 101L, storeId, false, "재입고 되나요?");
            InquiryAnswerRequest request = new InquiryAnswerRequest("네");

            for (long inquiryId : new long[] { others.getId(), deleted.getId(), Long.MAX_VALUE }) {
                assertCode(catchThrowable(() -> inquiryService.answer(storeId, inquiryId, request)), InquiryErrorCode.INQUIRY_NOT_FOUND);
            }
            assertCode(catchThrowable(() -> inquiryService.answer(storeId, mine.getId(), new InquiryAnswerRequest("<script>x</script>"))),
                    CommonErrorCode.INVALID_INPUT);
            assertThat(reload(others.getId()).isAnswered()).isFalse();
            assertThat(reload(mine.getId()).isAnswered()).isFalse();
        }
    }
}
