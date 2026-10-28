package com.sesac.gojigi.product.product.repository;

import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.sesac.gojigi.product.category.entity.QCategory;
import com.sesac.gojigi.product.option.entity.QProductOption;
import com.sesac.gojigi.product.product.entity.ProductSort;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.entity.QProduct;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 공개 상품 목록 · 검색 (product.md 9절) — 조건이 많아 QueryDSL.
 * 쇼핑몰에 보이는 상품(displayable)만: 판매중 · 삭제 안 됨 · 스토어 운영 중 · 하위 · 상위 카테고리 모두 사용 중.
 * 상품은 categoryId 만 들고 있어(연관 없음) 카테고리는 ID 로 조인한다. 결과는 [상품, 상위 카테고리 ID].
 */
@Repository
@RequiredArgsConstructor
public class ProductSearchRepository {

    private static final QProduct p = QProduct.product;
    private static final QCategory sub = new QCategory("sub");
    private static final QCategory top = new QCategory("top");
    private static final QProductOption o = QProductOption.productOption;
    private static final char ESCAPE = '!';

    private final JPAQueryFactory queryFactory;

    public record Condition(Long categoryId, Long storeId, String keyword, Long minPrice, Long maxPrice,
                            boolean excludeSoldOut, ProductSort sort) {
    }

    /** 한 페이지 — [Product, 상위 카테고리 ID]. 정렬이 같으면 productId 큰 순 */
    public List<Tuple> search(Condition condition, long offset, int limit) {
        return baseQuery(condition)
                .select(p, top.id)
                .orderBy(orderBy(condition.sort()))
                .offset(offset)
                .limit(limit)
                .fetch();
    }

    public long count(Condition condition) {
        Long count = baseQuery(condition).select(p.count()).fetchOne();
        return count == null ? 0 : count;
    }

    private JPAQuery<?> baseQuery(Condition c) {
        BooleanBuilder where = new BooleanBuilder()
                .and(p.deletedAt.isNull())
                .and(p.status.eq(ProductStatus.ON_SALE))
                .and(p.storeActive.isTrue())
                .and(sub.active.isTrue())
                .and(top.active.isTrue());
        if (c.categoryId() != null) {
            where.and(sub.id.eq(c.categoryId()).or(sub.parentId.eq(c.categoryId())));   // 하위면 그 하위만, 상위면 그 아래 전부
        }
        if (c.storeId() != null) {
            where.and(p.storeId.eq(c.storeId()));
        }
        if (c.keyword() != null) {
            String pattern = "%" + escapeLike(c.keyword()) + "%";
            where.and(p.name.like(pattern, ESCAPE).or(sub.name.like(pattern, ESCAPE)));
        }
        if (c.minPrice() != null) {
            where.and(p.salePrice.goe(c.minPrice()));
        }
        if (c.maxPrice() != null) {
            where.and(p.salePrice.lt(c.maxPrice()));   // 화면 가격대 버튼과 같은 경계 — min ≤ 판매가 < max
        }
        if (c.excludeSoldOut()) {
            where.and(JPAExpressions.selectOne().from(o)
                    .where(o.product.id.eq(p.id), o.deletedAt.isNull(), o.stock.gt(0))
                    .exists());
        }
        return queryFactory.from(p)
                .join(sub).on(sub.id.eq(p.categoryId))
                .join(top).on(top.id.eq(sub.parentId))
                .where(where);
    }

    private static OrderSpecifier<?>[] orderBy(ProductSort sort) {
        return switch (sort) {
            case NEW -> new OrderSpecifier<?>[] {p.createdAt.desc(), p.id.desc()};
            case PRICE_ASC -> new OrderSpecifier<?>[] {p.salePrice.asc(), p.id.desc()};
            case PRICE_DESC -> new OrderSpecifier<?>[] {p.salePrice.desc(), p.id.desc()};
        };
    }

    /** 사용자가 입력한 % · _ 를 글자 그대로 찾게 — 이스케이프 문자 ! 자신도 */
    static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
