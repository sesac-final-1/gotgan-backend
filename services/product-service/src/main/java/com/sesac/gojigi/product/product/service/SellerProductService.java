package com.sesac.gojigi.product.product.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.entity.ProductImage;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.option.entity.ProductOption;
import com.sesac.gojigi.product.option.repository.ProductOptionRepository;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest;
import com.sesac.gojigi.product.product.dto.request.ProductCreateRequest.OptionItem;
import com.sesac.gojigi.product.product.dto.request.ProductStatusChangeRequest;
import com.sesac.gojigi.product.product.dto.request.ProductStockUpdateRequest;
import com.sesac.gojigi.product.product.dto.response.ProductStatusChangeResponse;
import com.sesac.gojigi.product.product.dto.response.ProductStockUpdateResponse;
import com.sesac.gojigi.product.product.dto.response.SellerProductDetailResponse;
import com.sesac.gojigi.product.product.dto.response.SellerProductResponse;
import com.sesac.gojigi.product.product.entity.HiddenReason;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import com.sesac.gojigi.product.stock.exception.StockErrorCode;
import com.sesac.gojigi.product.stock.exception.StockException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.owasp.html.PolicyFactory;
import org.owasp.html.Sanitizers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 판매자 상품 목록 · 등록 · 수정 · 상세 · 재고 수정 · 판매중지/재개 · 삭제 (PRD-01 · 02 · 03 · 04 · 05 · 15, product.md 11 ~ 16절). 목록은 관리자 스토어 상품 목록(6-1절)도 같이 쓴다. 스토어 = 게이트웨이가 넣은 X-Store-Id — 역할 확인은 게이트웨이 몫.
 * TODO 박지연: 정지 · 폐점 스토어도 등록되면 바로 노출됨(storeActive=true 로 시작) — StoreStatusChanged 소비 PR 에서 함께 정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerProductService {

    static final int MAX_OPTIONS = 100;
    static final int MAX_IMAGES = 5;
    static final int MAX_OPTION_TEXT = 30;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 상세 설명에 허용할 태그 — 글자 서식 · 문단 · 링크 · 이미지 · 표. script · style · on* 속성은 지운다 */
    private static final PolicyFactory DESCRIPTION_POLICY = Sanitizers.FORMATTING.and(Sanitizers.BLOCKS)
            .and(Sanitizers.LINKS).and(Sanitizers.IMAGES).and(Sanitizers.TABLES);

    private final ProductRepository productRepository;
    private final ProductOptionRepository productOptionRepository;
    private final CategoryRepository categoryRepository;
    private final ImageUrls imageUrls;
    private final Clock clock;

    /**
     * 스토어 상품 전체 (11절 · 6-1절) — 페이징 · 필터 없음(화면이 셈 · 검색 · 정렬, 프론트 컨벤션 4-7), 삭제된 상품만 빠짐, 등록일 최신 순.
     * 재고 합계 · 대표 이미지는 상품마다 컬렉션을 읽지 않고 묶음 쿼리 두 번으로.
     */
    public List<SellerProductResponse> list(Long storeId) {
        List<Product> products = productRepository.findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId);
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, Long> stocks = toMap(productRepository.sumStockByProductIds(ids), row -> ((Number) row[1]).longValue());
        Map<Long, String> imageKeys = toMap(productRepository.findMainImageKeys(ids), row -> (String) row[1]);
        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return products.stream()
                .map(p -> toListItem(p, stocks.getOrDefault(p.getId(), 0L), imageKeys.get(p.getId()), categories))
                .toList();
    }

    /** 수정 화면용 상세 (12절) — 다른 스토어 상품도 "없음"과 같은 404 (있는지 알려 주지 않음) */
    public SellerProductDetailResponse get(Long storeId, Long productId) {
        Product product = getOwnProduct(storeId, productId);
        Category category = categoryRepository.findById(product.getCategoryId()).orElseThrow(); // 카테고리는 삭제하지 않는다
        Category top = categoryRepository.findById(category.getParentId()).orElseThrow();
        return SellerProductDetailResponse.of(product, category, top, imageUrls);
    }

    @Transactional
    public SellerProductDetailResponse create(Long storeId, ProductCreateRequest request) {
        Category category = getSubCategory(request.categoryId());
        Category top = categoryRepository.findById(category.getParentId())
                .filter(Category::isActive)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_CATEGORY_INVALID));
        List<String> optionNames = optionNames(request.option1Name(), request.option2Name());
        List<OptionSpec> options = checkOptions(optionNames.size(), request.options());
        List<String> imageKeys = checkImageKeys(storeId, request.imageKeys(), Set.of());
        ProductStatus status = initialStatus(request.status());

        Product product = Product.builder()
                .storeId(storeId)
                .categoryId(category.getId())
                .name(request.name().strip())
                .price(request.price())
                .discountRate(request.discountRate())
                .description(sanitizeDescription(request.description()))
                .material(request.material().strip())
                .origin(request.origin().strip())
                .option1Name(optionNames.isEmpty() ? null : optionNames.getFirst())
                .option2Name(optionNames.size() < 2 ? null : optionNames.get(1))
                .status(status)
                .build();
        options.forEach(spec -> product.addOption(spec.toNewOption()));   // 상품 복사(12절 응답 재등록)의 productOptionId 는 무시
        for (int i = 0; i < imageKeys.size(); i++) {
            product.addImage(ProductImage.of(imageKeys.get(i), i));
        }
        productRepository.save(product);
        log.info("상품 등록 - storeId={}, productId={}, options={}", storeId, product.getId(), options.size());
        return SellerProductDetailResponse.of(product, category, top, imageUrls);
    }

    /**
     * 수정 (13절 PUT) — 등록과 같은 본문 · 같은 검사, status 는 무시하고 재고 · 판매 상태는 바꾸지 않는다(BLOCKED 도 수정 가능, 상태 그대로).
     * 옵션은 productOptionId 로 맞춘다: 있으면 추가금만 · 없으면 새 조합 · 요청에 없는 기존 조합은 소프트 삭제.
     * 이미지는 요청 순서대로 다시 저장 — 이 상품에 이미 붙어 있는 키(시드 이미지 포함)는 모양 검사 없이 받는다.
     */
    @Transactional
    public SellerProductDetailResponse update(Long storeId, Long productId, ProductCreateRequest request) {
        Product product = getOwnProduct(storeId, productId);
        Category category = getSubCategory(request.categoryId());
        Category top = categoryRepository.findById(category.getParentId())
                .filter(Category::isActive)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_CATEGORY_INVALID));
        List<String> optionNames = optionNames(request.option1Name(), request.option2Name());
        List<OptionSpec> options = checkOptions(optionNames.size(), request.options());
        Set<String> currentKeys = product.getImages().stream().map(ProductImage::getS3Key).collect(Collectors.toSet());
        List<String> imageKeys = checkImageKeys(storeId, request.imageKeys(), currentKeys);

        applyOptions(product, options);
        product.update(category.getId(), request.name().strip(), request.price(), request.discountRate(),
                sanitizeDescription(request.description()), request.material().strip(), request.origin().strip(),
                optionNames.isEmpty() ? null : optionNames.getFirst(), optionNames.size() < 2 ? null : optionNames.get(1));
        product.replaceImages(imageKeys);
        productRepository.flush();   // 새 조합 ID · 수정 시각을 응답에 담으려고
        log.info("상품 수정 - storeId={}, productId={}, options={}", storeId, productId, options.size());
        return SellerProductDetailResponse.of(product, category, top, imageUrls);
    }

    /**
     * 삭제 (16절) — 상태와 상관없이(BLOCKED 포함) 상품만 소프트 삭제, 옵션 · 재고 이력은 그대로.
     * 지난 주문은 스냅샷이라 영향 없고, 장바구니에 담긴 옵션은 4절 조회에서 purchasable=false · 예약은 409 가 된다.
     */
    @Transactional
    public void delete(Long storeId, Long productId) {
        Product product = getOwnProduct(storeId, productId);
        product.delete(LocalDateTime.now(clock));
        log.info("상품 삭제 - storeId={}, productId={}", storeId, productId);
    }

    /**
     * 재고 수정 (14절) — 전부 바꾸거나 하나도 안 바꿈. 옵션마다 "지금 재고 = 화면에서 본 재고"일 때만 바꾸고, 하나라도 그 사이 바뀌었으면 409 로 전체 롤백.
     * 옵션 ID 오름차순으로 UPDATE — 재고 예약과 같은 순서라 데드락이 나지 않는다. status 는 바꾸지 않음(BLOCKED 도 수정 가능), 재고 이력은 남기지 않음.
     */
    @Transactional
    public ProductStockUpdateResponse updateStocks(Long storeId, Long productId, ProductStockUpdateRequest request) {
        getOwnProduct(storeId, productId);
        List<ProductStockUpdateRequest.Item> items = request.items().stream()
                .sorted(Comparator.comparing(ProductStockUpdateRequest.Item::productOptionId))
                .toList();
        if (items.stream().map(ProductStockUpdateRequest.Item::productOptionId).distinct().count() != items.size()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "같은 옵션이 두 번 들어 있습니다.");
        }
        Set<Long> liveIds = new HashSet<>(productOptionRepository.findLiveIdsByProductId(productId));
        List<Long> notOwned = items.stream().map(ProductStockUpdateRequest.Item::productOptionId).filter(id -> !liveIds.contains(id)).toList();
        if (!notOwned.isEmpty()) {
            throw new ProductException(ProductErrorCode.PRODUCT_OPTION_NOT_FOUND,
                    "이 상품의 옵션이 아니거나 삭제된 옵션입니다. (옵션 " + notOwned + ")");
        }
        for (ProductStockUpdateRequest.Item item : items) {
            if (productOptionRepository.updateStockIfUnchanged(item.productOptionId(), item.stock(), item.seenStock()) == 0) {
                throw new StockException(StockErrorCode.STOCK_CHANGED,
                        "그 사이 주문이 들어와 재고가 바뀌었어요. 다시 불러온 뒤 입력해 주세요. (옵션 " + item.productOptionId() + ")");
            }
        }
        log.info("재고 수정 - storeId={}, productId={}, options={}", storeId, productId,
                items.stream().map(ProductStockUpdateRequest.Item::productOptionId).toList());
        return new ProductStockUpdateResponse(items.stream()
                .map(item -> new ProductStockUpdateResponse.Option(item.productOptionId(), item.stock()))
                .toList());
    }

    /**
     * 판매중지 · 재개 (15절) — 건마다 따로: 하나가 실패해도 나머지는 반영(예외 대신 failed 에 담음, 트랜잭션은 하나).
     * 상품 행을 먼저 잠가 최신 상태를 본다 — 그 사이 관리자가 강제 판매중지했으면 BLOCKED 로 실패 처리(차단이 풀리지 않게).
     * 이미 그 상태면 성공(멱등), 재고 0 이어도 재개 가능(품절로 보임).
     */
    @Transactional
    public ProductStatusChangeResponse changeStatus(Long storeId, ProductStatusChangeRequest request) {
        ProductStatus status = request.status();
        if (status != ProductStatus.ON_SALE && status != ProductStatus.STOPPED) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "판매 상태는 ON_SALE · STOPPED 만 선택할 수 있습니다.");
        }
        List<Long> ids = List.copyOf(new LinkedHashSet<>(request.productIds()));   // 같은 ID 는 한 번 · 요청 순서 유지

        // 반드시 트랜잭션의 첫 쿼리 — 이후 판단이 잠금을 얻은 뒤의 최신 상태로
        Map<Long, Product> products = productRepository.findAllByIdForUpdate(ids).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        List<Long> success = new ArrayList<>();
        List<ProductStatusChangeResponse.Failure> failed = new ArrayList<>();
        for (Long id : ids) {
            Product product = products.get(id);
            if (product == null || product.getDeletedAt() != null || !product.getStoreId().equals(storeId)) {
                failed.add(failure(id, ProductErrorCode.PRODUCT_NOT_FOUND));
            } else if (product.getStatus() == ProductStatus.BLOCKED) {
                failed.add(failure(id, ProductErrorCode.PRODUCT_BLOCKED));
            } else {
                product.changeSaleStatus(status);
                success.add(id);
            }
        }
        log.info("판매 상태 변경 - storeId={}, status={}, success={}, failed={}", storeId, status, success,
                failed.stream().map(ProductStatusChangeResponse.Failure::productId).toList());
        return new ProductStatusChangeResponse(success, failed);
    }

    private static ProductStatusChangeResponse.Failure failure(Long productId, ProductErrorCode code) {
        return new ProductStatusChangeResponse.Failure(productId, code.getCode(), code.getMessage());
    }

    /** 내 스토어의 삭제 안 된 상품 — 다른 스토어 상품도 "없음"과 같은 404 (있는지 알려 주지 않음, 12절) */
    private Product getOwnProduct(Long storeId, Long productId) {
        return productRepository.findByIdAndDeletedAtIsNull(productId)
                .filter(p -> p.getStoreId().equals(storeId))
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    /** 기존 조합은 추가금만(값이 다르면 400) · 새 조합은 추가 · 요청에 없는 기존 조합은 소프트 삭제 */
    private void applyOptions(Product product, List<OptionSpec> specs) {
        Map<Long, ProductOption> live = product.getOptions().stream()
                .filter(o -> o.getDeletedAt() == null)
                .collect(Collectors.toMap(ProductOption::getId, Function.identity()));
        Set<Long> kept = new HashSet<>();
        for (OptionSpec spec : specs) {
            if (spec.productOptionId() == null) {
                product.addOption(spec.toNewOption());
                continue;
            }
            ProductOption option = live.get(spec.productOptionId());
            if (option == null) {
                throw invalidOption("이 상품의 옵션이 아니거나 삭제된 옵션입니다. (옵션 " + spec.productOptionId() + ")");
            }
            if (!option.hasValues(spec.value1(), spec.value2())) {
                throw invalidOption("기존 조합의 옵션 값은 바꿀 수 없습니다. 새 조합으로 추가해 주세요. (옵션 " + spec.productOptionId() + ")");
            }
            option.changeExtraPrice(spec.extraPrice());
            kept.add(option.getId());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        live.values().stream().filter(o -> !kept.contains(o.getId())).forEach(o -> o.delete(now));
    }

    /** 사용 중인 하위만 — 없거나 · 상위이거나 · 꺼진 카테고리는 같은 코드 (CAT-03, product 자기 DB 로 검증) */
    private Category getSubCategory(Long categoryId) {
        return categoryRepository.findById(categoryId)
                .filter(c -> !c.isTop() && c.isActive())
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_CATEGORY_INVALID));
    }

    /** 옵션 그룹 0~2개. 그룹 2 만 있거나 두 이름이 같으면 잘못된 구성 */
    private static List<String> optionNames(String option1Name, String option2Name) {
        String first = blankToNull(option1Name);
        String second = blankToNull(option2Name);
        if (first == null && second != null) {
            throw invalidOption("옵션 그룹 1 이름을 입력해 주세요.");
        }
        List<String> names = Stream.of(first, second).filter(Objects::nonNull).toList();
        for (String name : names) {
            checkOptionText(name);
        }
        if (names.size() == 2 && names.get(0).equals(names.get(1))) {
            throw invalidOption("옵션 그룹 이름이 같습니다.");
        }
        return names;
    }

    /** 조합 1~100개 · 그룹 수와 값 개수가 맞음 · 조합 중복 없음 · 추가금 · 재고 0 이상. 그룹이 없으면 값이 null 인 1개만 */
    private static List<OptionSpec> checkOptions(int groupCount, List<OptionItem> items) {
        if (items == null || items.isEmpty() || items.size() > MAX_OPTIONS) {
            throw invalidOption("옵션 조합은 1~100개여야 합니다.");
        }
        if (groupCount == 0 && items.size() != 1) {
            throw invalidOption("옵션이 없는 상품은 조합을 1개만 보내 주세요.");
        }
        Set<List<String>> seen = new HashSet<>();
        List<OptionSpec> options = new ArrayList<>();
        for (OptionItem item : items) {
            if (item == null) {
                throw invalidOption("빈 옵션 조합이 있습니다.");
            }
            String value1 = blankToNull(item.option1Value());
            String value2 = blankToNull(item.option2Value());
            if ((value1 != null) != (groupCount >= 1) || (value2 != null) != (groupCount == 2)) {
                throw invalidOption("옵션 그룹 수와 옵션 값 개수가 맞지 않습니다.");
            }
            if (value1 != null) {
                checkOptionText(value1);
            }
            if (value2 != null) {
                checkOptionText(value2);
            }
            if (item.extraPrice() == null || item.extraPrice() < 0 || item.stock() == null || item.stock() < 0) {
                throw invalidOption("추가금과 재고는 0 이상이어야 합니다.");
            }
            if (!seen.add(Arrays.asList(value1, value2))) {
                throw invalidOption("같은 옵션 조합이 있습니다.");
            }
            options.add(new OptionSpec(item.productOptionId(), value1, value2, item.extraPrice(), item.stock()));
        }
        return options;
    }

    /**
     * 1~5개 · 중복 없음 · presign 이 발급하는 내 스토어 키 모양(products/{storeId}/{UUID}.{jpg|png|webp}) 또는 이 상품에 이미 붙어 있는 키(수정).
     * S3 존재 확인은 하지 않는다.
     */
    private static List<String> checkImageKeys(Long storeId, List<String> keys, Set<String> currentKeys) {
        if (keys == null || keys.isEmpty() || keys.size() > MAX_IMAGES) {
            throw invalidImage("상품 이미지는 1~5장이어야 합니다.");
        }
        Pattern ownKey = Pattern.compile("products/" + storeId + "/[A-Za-z0-9-]{1,64}\\.(jpg|png|webp)");
        if (keys.stream().anyMatch(key -> key == null || !(currentKeys.contains(key) || ownKey.matcher(key).matches()))) {
            throw invalidImage("업로드 주소 발급으로 받은 내 스토어 이미지만 사용할 수 있습니다.");
        }
        if (new HashSet<>(keys).size() != keys.size()) {
            throw invalidImage("같은 이미지가 중복되었습니다.");
        }
        return keys;
    }

    /** 등록은 ON_SALE(기본) · STOPPED 만 — BLOCKED 는 관리자만 */
    private static ProductStatus initialStatus(ProductStatus status) {
        if (status == null) {
            return ProductStatus.ON_SALE;
        }
        if (status == ProductStatus.BLOCKED) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "판매 상태는 ON_SALE · STOPPED 만 선택할 수 있습니다.");
        }
        return status;
    }

    private SellerProductResponse toListItem(Product p, long totalStock, String imageKey, Map<Long, Category> categories) {
        Category category = categories.get(p.getCategoryId());
        Category top = category == null || category.isTop() ? category : categories.get(category.getParentId());
        HiddenReason hiddenReason = HiddenReason.of(p, category != null && category.isActive() && top != null && top.isActive());
        return new SellerProductResponse(p.getId(), p.getName(), p.getCategoryId(),
                category == null ? null : category.getName(), top == null ? null : top.getName(),
                p.getPrice(), p.getDiscountRate(), p.getSalePrice(), imageUrls.of(imageKey), totalStock, totalStock == 0,
                p.getStatus(), p.getStatusReason(), hiddenReason == null, hiddenReason,
                p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(KST).toOffsetDateTime());
    }

    private static <V> Map<Long, V> toMap(List<Object[]> rows, Function<Object[], V> value) {
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], value));
    }

    private static String sanitizeDescription(String description) {
        return StringUtils.hasText(description) ? blankToNull(DESCRIPTION_POLICY.sanitize(description)) : null;
    }

    private static void checkOptionText(String text) {
        if (text.length() > MAX_OPTION_TEXT) {
            throw invalidOption("옵션 이름 · 값은 30자 이하로 입력해 주세요.");
        }
    }

    private static ProductException invalidOption(String message) {
        return new ProductException(ProductErrorCode.PRODUCT_OPTION_INVALID, message);
    }

    private static ProductException invalidImage(String message) {
        return new ProductException(ProductErrorCode.PRODUCT_IMAGE_INVALID, message);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** 검사를 마친 옵션 조합 한 줄 — 등록은 새 조합으로, 수정은 productOptionId 로 기존 조합과 맞춘다 */
    private record OptionSpec(Long productOptionId, String value1, String value2, long extraPrice, int stock) {

        ProductOption toNewOption() {
            return ProductOption.of(value1, value2, extraPrice, stock);
        }
    }
}
