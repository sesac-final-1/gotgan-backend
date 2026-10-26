package com.sesac.gojigi.admin.application.service;

import com.sesac.gojigi.admin.category.client.ProductClientAdapter;
import com.sesac.gojigi.admin.store.client.StoreClientAdapter;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 입점 심사 (STR-03 · 04 · 05 · 09 · 10) — store 응답을 그대로 전달하되, store 는 카테고리 이름을 모르므로(store.md 3절)
 * 목록 · 상세에 product 카테고리로 categoryName 을 붙인다. 카테고리를 못 읽으면 이름 없이(심사는 계속할 수 있게) 그대로 돌려준다.
 * 승인 · 반려 · 보완요청은 store 그대로 — 체크리스트는 화면만 검사(STR-10), 보완 기한은 store 가 +7일
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminApplicationService {

    private final StoreClientAdapter storeClientAdapter;
    private final ProductClientAdapter productClientAdapter;

    public JsonNode list(String status) {
        JsonNode applications = storeClientAdapter.applications(status);
        Map<Long, String> names = categoryNames();
        applications.forEach(a -> withCategoryName(a, names));
        return applications;
    }

    public JsonNode detail(Long applicationId) {
        JsonNode application = storeClientAdapter.application(applicationId);
        withCategoryName(application, categoryNames());
        return application;
    }

    public JsonNode approve(Long applicationId) {
        return storeClientAdapter.approve(applicationId);
    }

    public JsonNode reject(Long applicationId, JsonNode body) {
        return storeClientAdapter.reject(applicationId, body);
    }

    public JsonNode supplement(Long applicationId, JsonNode body) {
        return storeClientAdapter.supplement(applicationId, body);
    }

    /** 카테고리 id → 이름 (상위 · 하위 모두) — product 장애면 빈 맵 */
    private Map<Long, String> categoryNames() {
        Map<Long, String> names = new HashMap<>();
        try {
            for (JsonNode top : productClientAdapter.categories()) {
                names.put(top.path("id").asLong(), top.path("name").asString());
                top.path("children").forEach(c -> names.put(c.path("id").asLong(), c.path("name").asString()));
            }
        } catch (BusinessException e) {
            log.warn("입점 심사 카테고리 이름 생략 - code={}", e.getErrorCode().getCode());
        }
        return names;
    }

    private static void withCategoryName(JsonNode application, Map<Long, String> names) {
        if (application instanceof ObjectNode object && application.hasNonNull("categoryId")) {
            object.put("categoryName", names.get(application.path("categoryId").asLong()));
        }
    }
}
