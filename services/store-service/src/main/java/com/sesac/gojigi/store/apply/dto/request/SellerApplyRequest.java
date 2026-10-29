package com.sesac.gojigi.store.apply.dto.request;

import com.sesac.gojigi.store.apply.entity.BizType;
import com.sesac.gojigi.store.apply.entity.DocType;
import com.sesac.gojigi.store.apply.entity.ExpectedMonthlySales;
import com.sesac.gojigi.store.apply.entity.RepresentativeProduct;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;

/**
 * 입점 신청 — 필드 이름은 storedb 컬럼명의 camelCase, 정산 계좌만 StoreApproved 와 같은 객체 (docs/api/store.md 2절).
 * 길이는 컬럼 크기와 같다. 서류 개수 · 형식은 서비스가 APPLY_DOCUMENT_INVALID 로 검사한다.
 */
public record SellerApplyRequest(
        @Schema(description = "사업자 구분", example = "INDIVIDUAL")
        @NotNull(message = "사업자 구분을 선택해 주세요.")
        BizType bizType,

        @Schema(description = "상호", example = "곳간상회")
        @NotBlank(message = "상호를 입력해 주세요.")
        @Size(max = 100, message = "상호는 100자 이하로 입력해 주세요.")
        String companyName,

        @Schema(description = "대표자", example = "홍길동")
        @NotBlank(message = "대표자를 입력해 주세요.")
        @Size(max = 50, message = "대표자는 50자 이하로 입력해 주세요.")
        String ownerName,

        @Schema(description = "사업자등록번호", example = "000-00-00001")
        @NotBlank(message = "사업자등록번호를 입력해 주세요.")
        @Pattern(regexp = "^\\d{3}-\\d{2}-\\d{5}$", message = "사업자등록번호는 000-00-00000 형식입니다.")
        String bizRegNo,

        @Schema(description = "통신판매업 신고번호", example = "2026-서울성동-0000")
        @NotBlank(message = "통신판매업 신고번호를 입력해 주세요.")
        @Size(max = 50, message = "통신판매업 신고번호는 50자 이하로 입력해 주세요.")
        String ecommerceRegNo,

        @Schema(description = "담당자 휴대폰 — 승인 때 고객 문의 연락처로 복사", example = "010-0000-1234")
        @NotBlank(message = "담당자 휴대폰을 입력해 주세요.")
        @Pattern(regexp = "^01[016789]-\\d{3,4}-\\d{4}$", message = "휴대폰 번호는 010-0000-0000 형식입니다.")
        String managerPhone,

        @Schema(description = "담당자 이메일 — 심사 결과 메일 수신 (StoreApproved.sellerEmail)", example = "seller@example.com")
        @NotBlank(message = "담당자 이메일을 입력해 주세요.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 100, message = "이메일은 100자 이하로 입력해 주세요.")
        String managerEmail,

        @Schema(description = "사업장 주소", example = "서울시 성동구 성수이로 00")
        @NotBlank(message = "사업장 주소를 입력해 주세요.")
        @Size(max = 255, message = "사업장 주소는 255자 이하로 입력해 주세요.")
        String businessAddress,

        @Schema(description = "스토어명 — 공백 · 대소문자를 무시하고 중복 검사", example = "곳간상회")
        @NotBlank(message = "스토어명을 입력해 주세요.")
        @Size(max = 50, message = "스토어명은 50자 이하로 입력해 주세요.")
        String storeName,

        @Schema(description = "스토어 주소 gotgan.kr/store/{slug} — 승인 후 변경 불가", example = "gotgan-store")
        @NotBlank(message = "스토어 주소를 입력해 주세요.")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$",
                message = "스토어 주소는 영소문자 · 숫자 · - 로 3~30자이며 - 로 시작하거나 끝날 수 없습니다.")
        String slug,

        @Schema(description = "희망 상위 카테고리 ID — 존재 여부는 store 가 확인하지 않음 (심사 참고용)", example = "1")
        @NotNull(message = "대표 카테고리를 선택해 주세요.")
        @Positive(message = "대표 카테고리가 올바르지 않습니다.")
        Long categoryId,

        @Schema(description = "스토어 소개 HTML (선택) — 저장 전 정화", example = "<p>리넨 의류를 만듭니다.</p>")
        @Size(max = 5000, message = "스토어 소개는 5000자 이하로 입력해 주세요.")
        String intro,

        @Schema(description = "판매 예정 대표 상품 2~10개")
        @NotNull(message = "판매 예정 상품을 입력해 주세요.")
        @Size(min = 2, max = 10, message = "판매 예정 상품은 2~10개를 입력해 주세요.")
        List<@Valid @NotNull ProductItem> representativeProducts,

        @Schema(description = "월 예상 매출 구간 (선택)", example = "FROM_5M_TO_20M")
        ExpectedMonthlySales expectedMonthlySales,

        @Schema(description = "운영 중인 채널 URL (선택)", example = "https://instagram.com/gotgan.store")
        @Size(max = 255, message = "운영 채널은 255자 이하로 입력해 주세요.")
        String salesChannel,

        @Schema(description = "입점 서류 — presign 으로 올린 키. 사업자등록증 · 통신판매업 신고증 · 통장 사본 각 1 + 상품 사진 3~10")
        @NotNull(message = "입점 서류를 올려 주세요.")
        List<@Valid @NotNull DocumentItem> documents,

        @Schema(description = "정산 계좌 — 승인 때 StoreApproved.settlementAccount 로 그대로")
        @NotNull(message = "정산 계좌를 입력해 주세요.")
        @Valid
        SettlementAccount settlementAccount,

        @Schema(description = "약관 동의 — 필수 3개는 true")
        @NotNull(message = "약관에 동의해 주세요.")
        @Valid
        Agreements agreements) {

    /** 스토어 주소로 쓸 수 없는 단어 — 화면 경로 · 관리 기능과 겹침 (스펙 STR-01) */
    static final Set<String> RESERVED_SLUGS = Set.of("admin", "seller", "new", "api", "me");

    @Schema(hidden = true)
    @AssertTrue(message = "사용할 수 없는 스토어 주소입니다.")
    public boolean isSlugNotReserved() {
        return slug == null || !RESERVED_SLUGS.contains(slug);
    }

    public List<RepresentativeProduct> toRepresentativeProducts() {
        return toRepresentativeProducts(representativeProducts);
    }

    /** 앞뒤 공백 정리 — 재제출(ApplicationResubmitRequest)도 같은 규칙 */
    public static List<RepresentativeProduct> toRepresentativeProducts(List<ProductItem> representativeProducts) {
        return representativeProducts.stream()
                .map(p -> new RepresentativeProduct(p.name().trim(), p.subCategory().trim(), p.price()))
                .toList();
    }

    public record ProductItem(
            @Schema(description = "상품명", example = "리넨 셔츠")
            @NotBlank(message = "판매 예정 상품명을 입력해 주세요.")
            @Size(max = 100, message = "판매 예정 상품명은 100자 이하로 입력해 주세요.")
            String name,

            @Schema(description = "세부 분류 이름", example = "셔츠")
            @NotBlank(message = "판매 예정 상품의 세부 분류를 선택해 주세요.")
            @Size(max = 50, message = "세부 분류는 50자 이하입니다.")
            String subCategory,

            @Schema(description = "예상 판매가 (원)", example = "39000")
            @NotNull(message = "판매 예정 상품의 예상 판매가를 입력해 주세요.")
            @Min(value = 1, message = "예상 판매가는 1원 이상입니다.")
            Long price) {
    }

    public record DocumentItem(
            @Schema(description = "서류 종류", example = "BUSINESS_REG")
            @NotNull(message = "서류 종류를 선택해 주세요.")
            DocType docType,

            @Schema(description = "presign 이 발급한 S3 키", example = "applications/4/3f2a0c5e-7d1b-4c1e-9a1f-0b2c3d4e5f60.pdf")
            @NotBlank(message = "서류 파일을 올려 주세요.")
            @Size(max = 255, message = "서류 키가 올바르지 않습니다.")
            String key,

            @Schema(description = "원본 파일명 (관리자 화면 표시)", example = "사업자등록증.pdf")
            @NotBlank(message = "서류 파일명이 없습니다.")
            @Size(max = 255, message = "서류 파일명은 255자 이하입니다.")
            String originalName) {
    }

    public record SettlementAccount(
            @Schema(description = "은행", example = "국민")
            @NotBlank(message = "정산 은행을 입력해 주세요.")
            @Size(max = 30, message = "은행은 30자 이하로 입력해 주세요.")
            String bank,

            @Schema(description = "계좌번호", example = "000000-00-000000")
            @NotBlank(message = "계좌번호를 입력해 주세요.")
            @Size(max = 30, message = "계좌번호는 30자 이하로 입력해 주세요.")
            String number,

            @Schema(description = "예금주", example = "홍길동")
            @NotBlank(message = "예금주를 입력해 주세요.")
            @Size(max = 50, message = "예금주는 50자 이하로 입력해 주세요.")
            String holder) {
    }

    public record Agreements(
            @Schema(description = "(필수) 판매자 이용약관")
            @AssertTrue(message = "판매자 이용약관에 동의해 주세요.")
            boolean sellerTerms,

            @Schema(description = "(필수) 전자금융거래 이용약관")
            @AssertTrue(message = "전자금융거래 이용약관에 동의해 주세요.")
            boolean electronicFinance,

            @Schema(description = "(필수) 개인정보 수집 및 이용 동의")
            @AssertTrue(message = "개인정보 수집 및 이용에 동의해 주세요.")
            boolean privacy,

            @Schema(description = "(선택) 마케팅 소식 수신 — 없으면 false")
            Boolean marketing) {

        public boolean marketingAgreed() {
            return Boolean.TRUE.equals(marketing);
        }
    }
}
