package com.sesac.gojigi.order.cart.repository;

import com.sesac.gojigi.order.cart.entity.CartItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 장바구니 원본 — 회원 · 옵션당 1행 (uk_cart_item_user_option) */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdOrderByIdAsc(Long userId);

    Optional<CartItem> findByUserIdAndProductOptionId(Long userId, Long productOptionId);

    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM CartItem c WHERE c.userId = :userId AND c.productOptionId IN :optionIds")
    int deleteOptions(@Param("userId") Long userId, @Param("optionIds") Collection<Long> optionIds);
}
