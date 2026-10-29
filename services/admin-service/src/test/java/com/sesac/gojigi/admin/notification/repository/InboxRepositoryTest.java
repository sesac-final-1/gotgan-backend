package com.sesac.gojigi.admin.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.admin.notification.dto.NewNotification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

/** 알림함 DynamoDB 요청 모양 — 조건부 쓰기(멱등) · 최신순 조회 · 안 읽은 수(페이지 넘김) · 읽음 */
@SuppressWarnings("unchecked")
class InboxRepositoryTest {

    private static final Instant AT = Instant.parse("2026-10-27T01:00:00Z");
    private final DynamoDbClient dynamo = mock(DynamoDbClient.class);
    private final InboxRepository repository = new InboxRepository(dynamo, "sesac-gojigi-inbox");

    @Test
    @DisplayName("저장 — 표식(SRC#sourceId)과 알림을 한 트랜잭션으로 · 키 · 내용 · 안 읽음 · TTL 90일, 같은 sourceId 가 있으면(재전달 · 재시도) false")
    void saveIfAbsent() {
        NewNotification n = new NewNotification("USER#4", "e-1", "ORDER_PAID", "결제가 완료됐어요", "머그 · 28,000원", "/mypage/orders/N1", AT);
        ArgumentCaptor<Consumer<TransactWriteItemsRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);

        assertThat(repository.saveIfAbsent(n)).isTrue();
        verify(dynamo).transactWriteItems(captor.capture());
        TransactWriteItemsRequest.Builder b = TransactWriteItemsRequest.builder();
        captor.getValue().accept(b);
        List<Put> puts = b.build().transactItems().stream().map(TransactWriteItem::put).toList();
        assertThat(puts).allSatisfy(p -> {
            assertThat(p.tableName()).isEqualTo("sesac-gojigi-inbox");
            assertThat(p.conditionExpression()).isEqualTo("attribute_not_exists(SK)");
        });
        assertThat(puts.get(0).item().get("SK").s()).isEqualTo("SRC#e-1");
        Map<String, AttributeValue> item = puts.get(1).item();
        assertThat(item.get("PK").s()).isEqualTo("USER#4");
        assertThat(item.get("SK").s()).isEqualTo("1793062800000#e-1");
        assertThat(item.get("read").bool()).isFalse();
        assertThat(item.get("ttl").n()).isEqualTo(String.valueOf(AT.plusSeconds(90L * 24 * 3600).getEpochSecond()));
        assertThat(puts.get(0).item().get("ttl")).isEqualTo(item.get("ttl"));

        given(dynamo.transactWriteItems(any(Consumer.class))).willThrow(TransactionCanceledException.builder().build());
        assertThat(repository.saveIfAbsent(new NewNotification("USER#4", "e-1", "ORDER_PAID", "t", null, null, AT.plusSeconds(5)))).isFalse();
    }

    @Test
    @DisplayName("최신순 조회 — before 가 있으면 SK < before, 결과를 항목으로 (빈 본문 · 링크는 null)")
    void latest() {
        Map<String, AttributeValue> row = Map.of("PK", AttributeValue.fromS("STORE#1"), "SK", AttributeValue.fromS("1793062800000#e-2"),
                "type", AttributeValue.fromS("SETTLEMENT_PAID"), "title", AttributeValue.fromS("정산금이 지급됐어요"),
                "read", AttributeValue.fromBool(true), "createdAt", AttributeValue.fromS(AT.toString()));
        given(dynamo.query(any(QueryRequest.class))).willReturn(QueryResponse.builder().items(row).build());

        List<InboxRepository.Item> items = repository.latest("STORE#1", "1793062800001#x", 20);

        ArgumentCaptor<QueryRequest> q = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo).query(q.capture());
        assertThat(q.getValue().keyConditionExpression()).isEqualTo("PK = :pk AND SK < :before");
        assertThat(q.getValue().scanIndexForward()).isFalse();
        assertThat(q.getValue().limit()).isEqualTo(20);
        assertThat(items).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("정산금이 지급됐어요");
            assertThat(i.read()).isTrue();
            assertThat(i.body()).isNull();
            assertThat(i.createdAt()).isEqualTo(AT);
        });

        repository.latest("STORE#1", null, 5);
        verify(dynamo, times(2)).query(q.capture());
        assertThat(q.getValue().keyConditionExpression()).isEqualTo("PK = :pk AND SK < :end");   // 표식 행(SRC#…) 제외
        assertThat(q.getValue().expressionAttributeValues().get(":end").s()).isEqualTo("A");
    }

    @Test
    @DisplayName("안 읽은 수 · 키 — read = false 필터, 다음 페이지가 있으면 이어서")
    void unread_paginates() {
        given(dynamo.query(any(QueryRequest.class))).willReturn(
                QueryResponse.builder().count(2).items(Map.of("SK", AttributeValue.fromS("a")), Map.of("SK", AttributeValue.fromS("b")))
                        .lastEvaluatedKey(Map.of("PK", AttributeValue.fromS("USER#4"), "SK", AttributeValue.fromS("b"))).build(),
                QueryResponse.builder().count(1).items(Map.of("SK", AttributeValue.fromS("c"))).build());

        assertThat(repository.countUnread("USER#4")).isEqualTo(3);

        given(dynamo.query(any(QueryRequest.class))).willReturn(
                QueryResponse.builder().items(Map.of("SK", AttributeValue.fromS("a"))).lastEvaluatedKey(Map.of("SK", AttributeValue.fromS("a"))).build(),
                QueryResponse.builder().items(Map.of("SK", AttributeValue.fromS("b"))).build());
        assertThat(repository.unreadKeys("USER#4")).containsExactly("a", "b");

        ArgumentCaptor<QueryRequest> q = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamo, times(4)).query(q.capture());
        assertThat(q.getAllValues().getFirst().filterExpression()).isEqualTo("#r = :f");
    }

    @Test
    @DisplayName("읽음 — 있는 알림만(조건부), 없으면 false")
    void markRead() {
        ArgumentCaptor<Consumer<UpdateItemRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);

        assertThat(repository.markRead("USER#4", "sk")).isTrue();
        verify(dynamo).updateItem(captor.capture());
        UpdateItemRequest.Builder b = UpdateItemRequest.builder();
        captor.getValue().accept(b);
        assertThat(b.build().conditionExpression()).isEqualTo("attribute_exists(SK)");
        assertThat(b.build().key().get("SK").s()).isEqualTo("sk");

        given(dynamo.updateItem(any(Consumer.class))).willThrow(ConditionalCheckFailedException.builder().build());
        assertThat(repository.markRead("USER#4", "gone")).isFalse();
    }
}
