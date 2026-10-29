package com.sesac.gojigi.store.apply.entity;

import java.util.List;

/**
 * 재제출로 고칠 수 있는 신청서 값 — 보내지 않은 항목은 서비스가 지금 값으로 채워 넘긴다 (store.md 2-3절).
 * 스토어명 · 주소(중복 검사 · 심사 기준)는 없다. 사업자 구분 · 대표자 · 사업자등록번호를 바꾸는 재제출은 사업자등록증을 함께 받는다(서비스 검사)
 */
public record ApplicationCorrection(
        BizType bizType,
        String ownerName,
        String bizRegNo,
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
