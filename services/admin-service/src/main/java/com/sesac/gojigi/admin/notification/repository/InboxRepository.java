package com.sesac.gojigi.admin.notification.repository;

import com.sesac.gojigi.admin.notification.dto.NewNotification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.Select;

/**
 * 알림함 (DynamoDB sesac-gojigi-inbox) — PK 받는 쪽 · SK {epoch ms 13자리}#{sourceId}, TTL 90일.
 * 받는 쪽이 몇 개(회원 · 스토어 · 관리자)뿐이라 받는 쪽마다 Query 하고 화면 쪽에서 합친다.
 */
@Repository
public class InboxRepository {

    private static final Duration KEEP = Duration.ofDays(90);

    private final DynamoDbClient dynamoDb;
    private final String table;

    public InboxRepository(DynamoDbClient dynamoDb, @Value("${app.notification.inbox-table}") String table) {
        this.dynamoDb = dynamoDb;
        this.table = table;
    }

    /** 저장된 알림 한 줄 */
    public record Item(String recipient, String sortKey, String type, String title, String body, String link, boolean read, Instant createdAt) {
    }

    /** 새 알림이면 true — 같은 키(이벤트 재전달 · 같은 sourceId)는 덮어쓰지 않고 false (읽음 상태 유지) */
    public boolean saveIfAbsent(NewNotification n) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", s(n.recipient()));
        item.put("SK", s(n.sortKey()));
        item.put("type", s(n.type()));
        item.put("title", s(n.title()));
        if (n.body() != null && !n.body().isBlank()) {
            item.put("body", s(n.body()));
        }
        if (n.link() != null && !n.link().isBlank()) {
            item.put("link", s(n.link()));
        }
        item.put("read", AttributeValue.fromBool(false));
        item.put("createdAt", s(n.occurredAt().toString()));
        item.put("ttl", AttributeValue.fromN(String.valueOf(n.occurredAt().plus(KEEP).getEpochSecond())));
        try {
            dynamoDb.putItem(b -> b.tableName(table).item(item).conditionExpression("attribute_not_exists(SK)"));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** 받는 쪽 하나의 최신 알림 — before(SK) 가 있으면 그보다 이전 것만 */
    public List<Item> latest(String recipient, String before, int limit) {
        QueryRequest.Builder q = QueryRequest.builder().tableName(table).scanIndexForward(false).limit(limit);
        if (before == null) {
            q.keyConditionExpression("PK = :pk").expressionAttributeValues(Map.of(":pk", s(recipient)));
        } else {
            q.keyConditionExpression("PK = :pk AND SK < :before").expressionAttributeValues(Map.of(":pk", s(recipient), ":before", s(before)));
        }
        return dynamoDb.query(q.build()).items().stream().map(InboxRepository::toItem).toList();
    }

    /** 안 읽은 알림 수 (90일 TTL 이라 받는 쪽마다 많아야 수백 건) */
    public long countUnread(String recipient) {
        long count = 0;
        Map<String, AttributeValue> start = null;
        do {
            QueryResponse res = dynamoDb.query(unread(recipient, start).select(Select.COUNT).build());
            count += res.count();
            start = res.hasLastEvaluatedKey() && !res.lastEvaluatedKey().isEmpty() ? res.lastEvaluatedKey() : null;
        } while (start != null);
        return count;
    }

    /** 안 읽은 알림의 SK (모두 읽음 처리용) */
    public List<String> unreadKeys(String recipient) {
        List<String> keys = new ArrayList<>();
        Map<String, AttributeValue> start = null;
        do {
            QueryResponse res = dynamoDb.query(unread(recipient, start).projectionExpression("SK").build());
            res.items().forEach(i -> keys.add(i.get("SK").s()));
            start = res.hasLastEvaluatedKey() && !res.lastEvaluatedKey().isEmpty() ? res.lastEvaluatedKey() : null;
        } while (start != null);
        return keys;
    }

    /** 읽음 — 없는 알림이면 false */
    public boolean markRead(String recipient, String sortKey) {
        try {
            dynamoDb.updateItem(b -> b.tableName(table).key(Map.of("PK", s(recipient), "SK", s(sortKey)))
                    .updateExpression("SET #r = :t").conditionExpression("attribute_exists(SK)")
                    .expressionAttributeNames(Map.of("#r", "read")).expressionAttributeValues(Map.of(":t", AttributeValue.fromBool(true))));
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    private QueryRequest.Builder unread(String recipient, Map<String, AttributeValue> start) {
        return QueryRequest.builder().tableName(table).keyConditionExpression("PK = :pk").filterExpression("#r = :f")
                .expressionAttributeNames(Map.of("#r", "read"))
                .expressionAttributeValues(Map.of(":pk", s(recipient), ":f", AttributeValue.fromBool(false)))
                .exclusiveStartKey(start);
    }

    private static Item toItem(Map<String, AttributeValue> i) {
        return new Item(i.get("PK").s(), i.get("SK").s(), i.get("type").s(), i.get("title").s(), str(i, "body"), str(i, "link"),
                Boolean.TRUE.equals(i.get("read").bool()), Instant.parse(i.get("createdAt").s()));
    }

    private static String str(Map<String, AttributeValue> i, String name) {
        AttributeValue v = i.get(name);
        return v == null ? null : v.s();
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }
}
