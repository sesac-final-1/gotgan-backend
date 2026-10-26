package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.order.dto.request.SellerShipRequest;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.dto.response.SellerBulkResponse;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** 판매자 일괄 처리 — 상품주문마다 따로, 일부 실패는 failed 로 · 중복 ID 는 한 번만 · 스토어 없으면 403 */
@ExtendWith(MockitoExtension.class)
class SellerDeliveryServiceTest {

    @Mock private DeliveryTxService deliveryTx;

    @InjectMocks
    private SellerDeliveryService service;

    private static final OrderItemResponse DONE = OrderItemResponse.from(OrderFixtures.item(OrderFixtures.order()));

    @Test
    @DisplayName("발주확인 — 성공 · 내 스토어 아님 · 상태 위반을 나눠 돌려주고, 같은 ID 는 한 번만")
    void prepare_partial() {
        // given
        given(deliveryTx.prepare(1L, 70L, 1L)).willReturn(DONE);
        given(deliveryTx.prepare(1L, 70L, 2L)).willThrow(new OrderException(OrderErrorCode.ORDER_ITEM_NOT_FOUND));
        given(deliveryTx.prepare(1L, 70L, 3L)).willThrow(new OrderException(OrderErrorCode.ORDER_INVALID_STATUS));

        // when
        SellerBulkResponse result = service.prepare(1L, 70L, List.of(1L, 2L, 3L, 1L));

        // then
        assertThat(result.succeeded()).hasSize(1);
        assertThat(result.failed()).extracting(SellerBulkResponse.Failure::orderItemId, SellerBulkResponse.Failure::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, "ORDER_ITEM_NOT_FOUND"),
                        org.assertj.core.groups.Tuple.tuple(3L, "ORDER_INVALID_STATUS"));
        verify(deliveryTx, times(1)).prepare(1L, 70L, 1L);
    }

    @Test
    @DisplayName("송장 등록 — 같은 상품주문이 두 번이면 처음 것만, 택배사 · 송장번호를 그대로 넘김")
    void ship_dedupesFirstWins() {
        given(deliveryTx.ship(1L, 70L, 5L, "CJ대한통운", null)).willReturn(DONE);

        SellerBulkResponse result = service.ship(1L, 70L, List.of(new SellerShipRequest.Item(5L, "CJ대한통운", null),
                new SellerShipRequest.Item(5L, "롯데택배", "1234567")));

        assertThat(result.succeeded()).hasSize(1);
        assertThat(result.failed()).isEmpty();
    }

    @Test
    @DisplayName("배송완료 — 자동 배송완료와 동시에 처리돼 @Version 충돌이면 ORDER_INVALID_STATUS 로")
    void deliver_optimisticLock() {
        given(deliveryTx.deliver(1L, 70L, 9L))
                .willThrow(new ObjectOptimisticLockingFailureException("OrderItem", 9L));

        SellerBulkResponse result = service.deliver(1L, 70L, List.of(9L));

        assertThat(result.failed()).singleElement()
                .satisfies(f -> assertThat(f.code()).isEqualTo("ORDER_INVALID_STATUS"));
    }

    @Test
    @DisplayName("스토어가 없는 판매자 — 403 FORBIDDEN, 처리하지 않음")
    void noStore_forbidden() {
        assertThatThrownBy(() -> service.prepare(null, 70L, List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> service.ship(null, 70L, List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deliver(null, 70L, List.of())).isInstanceOf(BusinessException.class);
        verifyNoInteractions(deliveryTx);
    }
}
