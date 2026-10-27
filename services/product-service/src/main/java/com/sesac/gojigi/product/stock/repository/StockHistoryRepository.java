package com.sesac.gojigi.product.stock.repository;

import com.sesac.gojigi.product.stock.entity.StockHistory;
import com.sesac.gojigi.product.stock.entity.StockType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 재고 원장. 예약 · 해제는 옵션 행을 먼저 잠근 뒤 여기를 읽는다 — 잠금이 트랜잭션의 첫 쿼리라
 * 이 조회가 보는 스냅샷은 잠금을 얻은 뒤(앞 요청 커밋 뒤)로 잡힌다 (MySQL REPEATABLE READ: 첫 일반 읽기 때 스냅샷).
 */
public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    /** 같은 sagaId 예약 재요청이면 처음 응답을 이 기록으로 다시 만든다 */
    List<StockHistory> findByTypeAndRefKeyOrderByProductOptionId(StockType type, String refKey);

    /** 해제 표식(또는 해제 완료)이 있으면 늦게 도착한 예약을 거절한다 */
    boolean existsByTypeAndRefKey(StockType type, String refKey);
}
