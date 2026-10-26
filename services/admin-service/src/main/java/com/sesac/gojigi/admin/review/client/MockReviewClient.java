package com.sesac.gojigi.admin.review.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * review 내부 API 배포 전 임시 — 어떤 리뷰 ID 든 있는 것으로 보고, 블라인드 상태만 메모리에 둔다 (파드 재시작 · 여러 파드면 초기화).
 * TODO(구지훈, review 내부 API 배포 시): ConfigMap REVIEW_CLIENT_MOCK=false → ReviewClientAdapter 로 교체하고 이 클래스 삭제
 */
@Component
@ConditionalOnProperty(name = "app.client.review-mock", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MockReviewClient implements ReviewReader {

    private final JsonMapper jsonMapper;
    private final Map<Long, String> blindReasons = new ConcurrentHashMap<>();

    @Override
    public JsonNode get(Long reviewId) {
        ObjectNode review = jsonMapper.createObjectNode()
                .put("reviewId", reviewId)
                .put("content", "(review-service 연결 전 — 임시 리뷰 " + reviewId + ")")
                .put("blinded", blindReasons.containsKey(reviewId));
        review.putNull("storeId");
        review.putNull("userId");
        return review.put("blindReason", blindReasons.get(reviewId));
    }

    @Override
    public JsonNode blind(Long reviewId, boolean blinded, String reason) {
        if (blinded) {
            blindReasons.put(reviewId, reason);
        } else {
            blindReasons.remove(reviewId);
        }
        return get(reviewId);
    }
}
