package com.sesac.gojigi.product.category.service;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

import com.sesac.gojigi.product.category.dto.request.CategoryCreateRequest;
import com.sesac.gojigi.product.category.dto.request.CategoryUpdateRequest;
import com.sesac.gojigi.product.category.dto.response.AdminCategoryTreeResponse;
import com.sesac.gojigi.product.category.dto.response.CategoryResponse;
import com.sesac.gojigi.product.category.dto.response.CategoryTreeResponse;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.exception.CategoryErrorCode;
import com.sesac.gojigi.product.category.exception.CategoryException;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 카테고리 관리 (CAT-02 · product.md 7절) · 공개 트리 (CAT-01 · 8절).
 * 상위를 켜기 전 수수료율 확인은 admin 이 한다 — product 는 다른 서비스를 부르지 않는다(CAT-03).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    /** 상품 수는 GROUP BY category_id 한 번 — 상품은 하위 id 만 가지므로 상위는 응답 조립 때 하위 합 */
    public List<AdminCategoryTreeResponse> adminTree() {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> children = childrenByParent(all);
        Map<Long, Long> productCount = productRepository.countByCategory().stream()
                .collect(toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return all.stream()
                .filter(Category::isTop)
                .map(top -> AdminCategoryTreeResponse.of(top, children.getOrDefault(top.getId(), List.of()), productCount))
                .toList();
    }

    /** 꺼진 상위는 하위까지 통째로, 꺼진 하위는 그 하위만 뺀다 */
    public List<CategoryTreeResponse> publicTree() {
        List<Category> active = categoryRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(Category::isActive)
                .toList();
        Map<Long, List<Category>> children = childrenByParent(active);
        return active.stream()
                .filter(Category::isTop)
                .map(top -> CategoryTreeResponse.of(top, children.getOrDefault(top.getId(), List.of())))
                .toList();
    }

    @Transactional
    public CategoryResponse create(CategoryCreateRequest request) {
        Long parentId = request.parentId();
        if (parentId != null && !getCategory(parentId).isTop()) {
            throw new CategoryException(CategoryErrorCode.CATEGORY_DEPTH_EXCEEDED);
        }
        String name = request.name().strip();
        checkNameAvailable(parentId, name, null);
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : categoryRepository.nextSortOrder(parentId);
        String nameEn = blankToNull(request.nameEn());
        Category category = parentId == null
                ? Category.top(name, nameEn, sortOrder)
                : Category.child(parentId, name, nameEn, sortOrder);
        return CategoryResponse.from(categoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse update(Long categoryId, CategoryUpdateRequest request) {
        Category category = getCategory(categoryId);
        String name = request.name().strip();
        checkNameAvailable(category.getParentId(), name, categoryId);
        category.update(name, blankToNull(request.nameEn()), request.sortOrder());
        return CategoryResponse.from(category);
    }

    /** 같은 값이면 아무것도 바꾸지 않고 현재 상태 (멱등). 그 아래 상품 status 는 그대로 — 노출 여부는 조회 때 계산 */
    @Transactional
    public CategoryResponse changeActive(Long categoryId, boolean active) {
        Category category = getCategory(categoryId);
        category.changeActive(active);
        return CategoryResponse.from(category);
    }

    private Category getCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryException(CategoryErrorCode.CATEGORY_NOT_FOUND));
    }

    private void checkNameAvailable(Long parentId, String name, Long excludeId) {
        if (categoryRepository.existsSiblingName(parentId, name, excludeId)) {
            throw new CategoryException(CategoryErrorCode.CATEGORY_NAME_DUPLICATED);
        }
    }

    private static Map<Long, List<Category>> childrenByParent(List<Category> sorted) {
        return sorted.stream().filter(c -> !c.isTop()).collect(groupingBy(Category::getParentId)); // 그룹 안 순서는 입력(sortOrder) 순서 유지
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }
}
