package com.sesac.gojigi.admin.category.service;

import com.sesac.gojigi.admin.category.client.ProductClientAdapter;
import com.sesac.gojigi.admin.category.dto.request.AdminCategoryCreateRequest;
import com.sesac.gojigi.admin.category.dto.request.AdminCategoryUpdateRequest;
import com.sesac.gojigi.admin.category.exception.AdminCategoryErrorCode;
import com.sesac.gojigi.admin.category.exception.AdminCategoryException;
import com.sesac.gojigi.admin.settlement.client.SettlementClientAdapter;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카테고리 관리 조합 (CAT-02 · 10/20 A안) — 수수료 없는 상위 카테고리가 켜지지 않게 product · settlement 를 엮는다.
 * product 는 다른 서비스를 부르지 않으므로(CAT-03) 수수료 확인은 여기서 한다.
 * - 상위 생성: product 생성(사용 중지) → settlement 수수료(적용 시작일 = 오늘) → product 사용으로. 중간 실패면 카테고리는 꺼진 채 남는다
 * - 수정: 이름 · 영문명 · 정렬은 PUT, 사용 여부가 바뀔 때만 PATCH — 상위를 켤 때는 settlement 현재 율(current)이 있어야 함
 */
@Service
@RequiredArgsConstructor
public class AdminCategoryService {

    private final ProductClientAdapter productClientAdapter;
    private final SettlementClientAdapter settlementClientAdapter;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public JsonNode tree() {
        return productClientAdapter.categories();
    }

    public JsonNode create(AdminCategoryCreateRequest request) {
        boolean top = request.parentId() == null;
        if (top && request.commissionRate() == null) {
            throw new AdminCategoryException(AdminCategoryErrorCode.CATEGORY_COMMISSION_RATE_REQUIRED);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("parentId", request.parentId());
        body.put("name", request.name());
        body.put("nameEn", request.nameEn());
        body.put("sortOrder", request.sortOrder());
        JsonNode created = productClientAdapter.create(jsonMapper.valueToTree(body));
        if (!top) {
            return created;
        }
        long categoryId = created.path("id").asLong();
        try {
            settlementClientAdapter.updateCommissions(jsonMapper.valueToTree(List.of(Map.of(
                    "categoryId", categoryId, "rate", request.commissionRate(), "effectiveFrom", LocalDate.now(clock).toString()))));
            return productClientAdapter.changeActive(categoryId, jsonMapper.valueToTree(Map.of("active", true)));
        } catch (BusinessException e) {
            throw new AdminCategoryException(AdminCategoryErrorCode.CATEGORY_ACTIVATION_PENDING, e);
        }
    }

    public JsonNode update(Long categoryId, AdminCategoryUpdateRequest request) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", request.name());
        body.put("nameEn", request.nameEn());
        body.put("sortOrder", request.sortOrder());
        JsonNode updated = productClientAdapter.update(categoryId, jsonMapper.valueToTree(body));
        if (request.active() == null || request.active() == updated.path("active").asBoolean()) {
            return updated;
        }
        boolean top = updated.path("parentId").isNull() || updated.path("parentId").isMissingNode();
        if (request.active() && top && !hasCurrentCommission(categoryId)) {
            throw new AdminCategoryException(AdminCategoryErrorCode.CATEGORY_COMMISSION_REQUIRED);
        }
        return productClientAdapter.changeActive(categoryId, jsonMapper.valueToTree(Map.of("active", request.active())));
    }

    /** settlement 수수료 목록에서 이 카테고리의 현재 적용분(current)이 있는지 */
    private boolean hasCurrentCommission(Long categoryId) {
        return StreamSupport.stream(settlementClientAdapter.commissions().spliterator(), false)
                .anyMatch(c -> c.path("categoryId").asLong() == categoryId && !c.path("current").isNull()
                        && !c.path("current").isMissingNode());
    }
}
