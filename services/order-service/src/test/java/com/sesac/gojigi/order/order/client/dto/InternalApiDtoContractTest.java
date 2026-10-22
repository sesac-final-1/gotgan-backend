package com.sesac.gojigi.order.order.client.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 다른 서비스 내부 API DTO — 문서(docs/api/payment.md · product.md, user InternalPointController)의 JSON 과
 * 필드 이름이 맞는지. 요청은 직렬화한 키를, 응답은 문서 예시 JSON 을 읽은 값을 본다.
 */
class InternalApiDtoContractTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Nested
    @DisplayName("payment")
    class Payment {

        @Test
        @DisplayName("결제 준비 · 검증 요청은 문서의 필드 이름으로 나간다")
        void prepareAndVerify_serialize() {
            // when
            JsonNode prepare = json.valueToTree(new PaymentPrepareRequest(123L, "20261021-000123", 64_400L, "saga-1"));
            JsonNode verify = json.valueToTree(new PaymentVerifyRequest("saga-1", "20261021-000123", 64_400L));

            // then
            assertThat(prepare.propertyNames()).containsExactlyInAnyOrder("orderId", "orderNo", "amount", "sagaId");
            assertThat(verify.propertyNames()).containsExactlyInAnyOrder("sagaId", "orderNo", "amount");
        }

        @Test
        @DisplayName("전액 취소는 상품주문 · 금액이 비고, 부분 취소는 둘 다 채운다")
        void cancelRequest_fullAndPartial() {
            // when
            PaymentCancelRequest full = PaymentCancelRequest.full("saga-1", "결제 대기 만료");
            PaymentCancelRequest partial = PaymentCancelRequest.partial("saga-2", 456L, 32_200L, "구매자 취소");

            // then
            assertThat(full).isEqualTo(new PaymentCancelRequest("saga-1", null, null, "결제 대기 만료"));
            assertThat(partial).isEqualTo(new PaymentCancelRequest("saga-2", 456L, 32_200L, "구매자 취소"));
        }

        @Test
        @DisplayName("취소 응답 — 결제와 취소 내역을 읽는다 (모르는 필드는 무시)")
        void cancelResponse_deserialize() {
            // given
            String body = """
                    {"payment": {"orderNo": "20261021-000123", "portonePaymentId": "pay-20261021-000123",
                                 "status": "PARTIAL_CANCELLED", "amount": 64400, "cancelledAmount": 32200,
                                 "method": "CARD", "paidAt": "2026-10-21T09:12:33+09:00", "newField": 1},
                     "cancel": {"sagaId": "saga-2", "orderItemId": 456, "cancelAmount": 32200,
                                "portoneCancellationId": "cancel-1", "cancelledAt": "2026-10-21T10:00:00+09:00"}}
                    """;

            // when
            PaymentCancelResponse response = json.readValue(body, PaymentCancelResponse.class);

            // then — 오프셋은 Jackson 이 UTC 로 맞추므로 같은 순간인지로 비교
            assertThat(response).usingRecursiveComparison()
                    .withComparatorForType(OffsetDateTime.timeLineOrder(), OffsetDateTime.class)
                    .isEqualTo(new PaymentCancelResponse(new PaymentResponse("20261021-000123", "pay-20261021-000123",
                    "PARTIAL_CANCELLED", 64_400L, 32_200L, "CARD", OffsetDateTime.parse("2026-10-21T09:12:33+09:00")),
                            new PaymentCancelResponse.Cancel("saga-2", 456L, 32_200L, "cancel-1",
                                    OffsetDateTime.parse("2026-10-21T10:00:00+09:00"))));
        }
    }

    @Nested
    @DisplayName("user 포인트")
    class Point {

        @Test
        @DisplayName("사용 요청을 보내고, 사용 · 사용 취소 결과를 읽는다 (되돌릴 게 없으면 balanceAfter null)")
        void useAndRestore() {
            // when
            JsonNode request = json.valueToTree(new PointUseRequest("saga-1", 4L, "20261022-000123", 1_500L));
            PointUseResponse used = json.readValue("""
                    {"sagaId": "saga-1", "userId": 4, "orderNo": "20261022-000123", "amount": 1500, "balanceAfter": 500}
                    """, PointUseResponse.class);
            PointRestoreResponse nothing = json.readValue("""
                    {"sagaId": "saga-1", "restoredAmount": 0, "balanceAfter": null}
                    """, PointRestoreResponse.class);

            // then
            assertThat(request.propertyNames()).containsExactlyInAnyOrder("sagaId", "userId", "orderNo", "amount");
            assertThat(used).isEqualTo(new PointUseResponse("saga-1", 4L, "20261022-000123", 1_500L, 500L));
            assertThat(nothing).isEqualTo(new PointRestoreResponse("saga-1", 0L, null));
        }
    }

    @Nested
    @DisplayName("product 재고 · 옵션")
    class Product {

        private final List<StockItem> items = List.of(new StockItem(11L, 2), new StockItem(25L, 1));

        @Test
        @DisplayName("재고 예약 · 해제 · 복원 요청을 문서 모양으로 보낸다")
        void stockRequests_serialize() {
            // when
            JsonNode reserve = json.valueToTree(new StockReserveRequest("saga-1", items));
            JsonNode release = json.valueToTree(new StockReleaseRequest(items));
            JsonNode restore = json.valueToTree(new StockRestoreRequest(789L, 11L, 1));

            // then
            assertThat(reserve.get("sagaId").asString()).isEqualTo("saga-1");
            assertThat(reserve.get("items").get(0).propertyNames()).containsExactlyInAnyOrder("productOptionId", "quantity");
            assertThat(release.get("items")).hasSize(2);
            assertThat(restore.propertyNames()).containsExactlyInAnyOrder("claimId", "productOptionId", "quantity");
        }

        @Test
        @DisplayName("예약 응답과 가격 · 구매 가능 조회 응답을 읽는다")
        void stockAndOptionResponses_deserialize() {
            // when
            StockReservationResponse reserved = json.readValue("""
                    {"sagaId": "saga-1", "items": [{"productOptionId": 11, "quantity": 2}, {"productOptionId": 25, "quantity": 1}]}
                    """, StockReservationResponse.class);
            List<ProductOptionResponse> options = json.readValue("""
                    [{"productOptionId": 11, "productId": 3, "productName": "프렌치 리넨 오버핏 셔츠",
                      "optionName": "오트밀 / M", "storeId": 1, "categoryId": 301, "topCategoryId": 3,
                      "salePrice": 75650, "extraPrice": 0, "unitPrice": 75650, "stock": 142,
                      "purchasable": true, "imageUrl": "https://img/products/1/a1b2.jpg"}]
                    """, new TypeReference<>() { });

            // then
            assertThat(reserved).isEqualTo(new StockReservationResponse("saga-1", items));
            assertThat(options).containsExactly(new ProductOptionResponse(11L, 3L, "프렌치 리넨 오버핏 셔츠",
                    "오트밀 / M", 1L, 301L, 3L, 75_650L, 0L, 75_650L, 142, true, "https://img/products/1/a1b2.jpg"));
        }
    }
}
