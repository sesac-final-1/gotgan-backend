package com.sesac.gojigi.store.apply.entity;

import java.util.List;

/**
 * 재제출로 고칠 수 있는 신청서 값 — 보내지 않은 항목은 서비스가 지금 값으로 채워 넘긴다 (store.md 2-3절).
 * 사업자등록번호 · 대표자(바뀌면 새 사업자) · 스토어명 · 주소(중복 검사 · 심사 기준)는 없다
 */
public record ApplicationCorrection(
        String companyName,
        String ecommerceRegNo,
        String managerPhone,
        String managerEmail,
        String businessAddress,
        Long categoryId,
        String intro,
        List<RepresentativeProduct> representativeProducts,
        String bankName,
        String accountNo,
        String accountHolder) {
}
