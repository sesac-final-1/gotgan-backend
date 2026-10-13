/**
 * 테스트 — main 과 같은 패키지 구조 (컨벤션 2-10).
 * 단위 {대상}Test · 통합 {대상}IntegrationTest, 메서드 {메서드}_{상황}_{결과} + @DisplayName.
 * DB 가 필요한 테스트는 Testcontainers (MySQL · Redis), H2 사용 안 함.
 */
package com.sesac.gojigi.payment;
