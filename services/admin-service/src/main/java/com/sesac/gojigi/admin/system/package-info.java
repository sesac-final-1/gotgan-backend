/**
 * system 도메인 — F-1 장애 시연 대시보드 (11/5 회의 · 협업 요청 문서 4장 계약)
 * <p>쿠버네티스 API(in-cluster ServiceAccount)로 sesac-gojigi 네임스페이스 파드 상태를 읽어 서비스 · 사용자 기능 상태를 계산하고,
 * 허용 목록의 서비스 파드를 지운다(app.chaos.enabled 가 켜졌을 때만). 내리기 기록은 admindb chaos_event.
 * <p>하위 패키지: controller · service · client · repository · entity · dto · exception
 */
package com.sesac.gojigi.admin.system;
