package com.sesac.gojigi.user.point.repository;

import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 포인트 이력 — INSERT 전용. 잔액 UPDATE(UserRepository)와 같은 트랜잭션에서 저장한다 */
public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /** 내 포인트 이력 최신순 (idx_point_history_user_created) */
    @Query(value = "select h from PointHistory h where h.user.id = :userId order by h.createdAt desc, h.id desc",
            countQuery = "select count(h) from PointHistory h where h.user.id = :userId")
    Page<PointHistory> findByUserId(@Param("userId") Long userId, Pageable pageable);

    /** 멱등 — 같은 (type, ref_key) 가 이미 처리됐는지. uk_point_history_type_ref_key 가 최종 방어선 */
    boolean existsByTypeAndRefKey(PointType type, String refKey);

    /** 취소 · 반품 시 그 상품주문의 적립(EARN) · 사용(USE) 등 이력 조회 (idx_point_history_order_item) */
    List<PointHistory> findByOrderItemIdAndTypeIn(Long orderItemId, Collection<PointType> types);
}
