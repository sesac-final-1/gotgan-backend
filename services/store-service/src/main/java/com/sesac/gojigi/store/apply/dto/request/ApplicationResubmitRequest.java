package com.sesac.gojigi.store.apply.dto.request;

import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.DocumentItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.ProductItem;
import com.sesac.gojigi.store.apply.dto.request.SellerApplyRequest.SettlementAccount;
import com.sesac.gojigi.store.apply.entity.BizType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 보완 재제출 (docs/api/store.md 2-3절, STR-09) — 모든 항목 선택, 보내지 않은(null) 항목은 그대로.
 * 규칙은 신청(2절)과 같고 빈 문자열로 지울 수 없다. 사업자 구분 · 대표자 · 사업자등록번호를 바꾸면 사업자등록증도 다시 내야 한다.
 * 스토어명 · 주소를 바꾸면 신청 때처럼 스토어 · 다른 진행 중 신청과 중복을 다시 확인한다(승인 때 이름이 겹쳐 보완요청된 경우, 스펙 STR-04).
 */
public record ApplicationResubmitRequest(
        @Schema(description = "사업자 구분 — 바꾸면 사업자등록증도 다시", example = "CORPORATE")
        BizType bizType,

        @Schema(description = "대표자 — 바꾸면 사업자등록증도 다시", example = "홍길동")
        @Pattern(regexp = NOT_BLANK, message = "대표자를 비울 수 없습니다.")
        @Size(max = 50, message = "대표자는 50자 이하로 입력해 주세요.")
        String ownerName,

        @Schema(description = "사업자등록번호 — 바꾸면 사업자등록증도 다시", example = "000-00-00001")
        @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자등록번호는 000-00-00000 형식입니다.")
        String bizRegNo,

        @Schema(description = "스토어명 — 바꾸면 중복 다시 확인(공백 · 대소문자 무시)", example = "곳간상회")
        @Pattern(regexp = NOT_BLANK, message = "스토어명을 비울 수 없습니다.")
        @Size(max = 50, message = "스토어명은 50자 이하로 입력해 주세요.")
        String storeName,

        @Schema(description = "스토어 주소 gotgan.kr/store/{slug} — 바꾸면 중복 다시 확인, 승인 후 변경 불가", example = "gotgan-store")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$",
                message = "스토어 주소는 영문 소문자 · 숫자 · 하이픈 3~30자로, 하이픈으로 시작하거나 끝날 수 없습니다.")
        String slug,

        @Schema(description = "상호", example = "곳간상회")
        @Pattern(regexp = NOT_BLANK, message = "상호를 비울 수 없습니다.")
        @Size(max = 100, message = "상호는 100자 이하로 입력해 주세요.")
        String companyName,

        @Schema(description = "통신판매업 신고번호", example = "2026-서울성동-0000")
        @Pattern(regexp = NOT_BLANK, message = "통신판매업 신고번호를 비울 수 없습니다.")
        @Size(max = 50, message = "통신판매업 신고번호는 50자 이하로 입력해 주세요.")
        String ecommerceRegNo,

        @Schema(description = "담당자 휴대폰", example = "010-0000-1234")
        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "휴대폰 번호는 010-0000-0000 형식입니다.")
        String managerPhone,

        @Schema(description = "담당자 이메일 — 심사 결과 메일 수신", example = "seller@example.com")
        @Pattern(regexp = NOT_BLANK, message = "담당자 이메일을 비울 수 없습니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String managerEmail,

        @Schema(description = "사업장 주소", example = "서울시 성동구 성수이로 00")
        @Pattern(regexp = NOT_BLANK, message = "사업장 주소를 비울 수 없습니다.")
        @Size(max = 255, message = "사업장 주소는 255자 이하로 입력해 주세요.")
        String businessAddress,

        @Schema(description = "희망 상위 카테고리 ID", example = "1")
        @Positive(message = "대표 카테고리가 올바르지 않습니다.")
        Long categoryId,

        @Schema(description = "스토어 소개 HTML — 저장 전 정화, 빈 문자열이면 소개 없음", example = "<p>리넨 의류를 만듭니다.</p>")
        @Size(max = 5000, message = "스토어 소개는 5000자 이하로 입력해 주세요.")
        String intro,

        @Schema(description = "판매 예정 대표 상품 2~10개 — 보내면 목록 전체를 바꿈")
        @Size(min = 2, max = 10, message = "판매 예정 상품은 2~10개를 입력해 주세요.")
        List<@Valid @NotNull ProductItem> representativeProducts,

        @Schema(description = "정산 계좌 — 보내면 세 값 모두 바꿈")
        @Valid
        SettlementAccount settlementAccount,

        @Schema(description = "다시 내는 서류 — 보완요청 서류(requestedDocs)를 모두, 그 밖의 종류는 안 됨. 한 장 서류 1개 · 상품 사진 3~10장")
        List<@Valid @NotNull DocumentItem> documents) {

    /** 공백만 있는 값 거절 — null 은 통과(보내지 않음 = 그대로) */
    static final String NOT_BLANK = "(?s).*\\S.*";

    @Schema(hidden = true)
    @AssertTrue(message = "사용할 수 없는 스토어 주소입니다.")
    public boolean isSlugNotReserved() {
        return slug == null || !SellerApplyRequest.RESERVED_SLUGS.contains(slug);
    }

    public List<DocumentItem> documentsOrEmpty() {
        return documents == null ? List.of() : documents;
    }
}
