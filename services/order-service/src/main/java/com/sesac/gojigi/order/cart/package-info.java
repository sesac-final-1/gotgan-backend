/**
 * cart 도메인 — 장바구니. 원본은 MySQL, Redis 는 캐시 (10/13 결정 4 · 가이드라인 09장 · 13장 장애 격리 시연 ③)
 * <ul>
 *   <li>원본: MySQL {@code orderdb.cart_item} (user_id · product_id · product_option_id · quantity · checked, {@code uk_cart_item_user_option})
 *       — {@code repository/CartItemRepository} (JPA, {@code entity/CartItem})</li>
 *   <li>캐시: Redis Hash {@code cart:{userId}} (TTL 30일) — {@code repository/CartRedisRepository}</li>
 *   <li>쓰기(write-through): MySQL 먼저 커밋 → Redis 갱신. Redis 실패는 무시하고 warn 로그만 (컨벤션 2-6 부가 작업 격리)</li>
 *   <li>읽기(read-through): Redis → 미스 · 장애 시 MySQL 조회 → Redis 채움</li>
 *   <li>Redis 호출은 Resilience4j 서킷 브레이커 {@code redisCart} 인스턴스({@code application.yml}) + 폴백 메서드
 *       → Redis 가 죽어도 장바구니는 MySQL 로 정상 동작 (응답 지연만)</li>
 * </ul>
 * <p>하위 패키지: controller · service · repository · entity · dto(request · response) · exception · client. 이 도메인에 필요 없는 패키지는 만들지 않는다 (컨벤션 2-1).
 */
package com.sesac.gojigi.order.cart;
