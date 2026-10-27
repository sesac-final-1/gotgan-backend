package com.sesac.gojigi.user.point.repository;

import com.sesac.gojigi.user.point.entity.PointEarnBlock;
import org.springframework.data.jpa.repository.JpaRepository;

/** 적립 제외 상품주문 — 적립 · 회수 모두 회원 행을 잠근 뒤 읽고 쓴다 (uk_point_earn_block_order_item_id 가 최종 방어선) */
public interface PointEarnBlockRepository extends JpaRepository<PointEarnBlock, Long> {

    boolean existsByOrderItemId(Long orderItemId);
}
