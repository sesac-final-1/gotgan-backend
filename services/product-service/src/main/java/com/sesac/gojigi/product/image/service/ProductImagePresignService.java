package com.sesac.gojigi.product.image.service;

import com.sesac.gojigi.product.image.client.ProductImageClient;
import com.sesac.gojigi.product.image.client.ProductImageClient.PresignedUpload;
import com.sesac.gojigi.product.image.dto.request.ImagePresignRequest;
import com.sesac.gojigi.product.image.dto.request.ImagePresignRequest.FileItem;
import com.sesac.gojigi.product.image.dto.response.ImagePresignResponse;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 상품 이미지 업로드 주소 발급 (PRD-03, product.md 17절). 담당 박지연.
 * 키는 products/{storeId}/{UUID}.{확장자} — 등록 · 수정은 이 모양의 내 스토어 키만 받는다(SellerProductService.checkImageKeys).
 */
@Service
@RequiredArgsConstructor
public class ProductImagePresignService {

    static final int MAX_FILES = 5;
    static final long MAX_FILE_SIZE = 10L * 1024 * 1024;

    /** 형식 → 키 확장자 (파일 이름이 아니라 형식으로 정한다) */
    private static final Map<String, String> EXTENSIONS = Map.of("image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ProductImageClient productImageClient;

    public List<ImagePresignResponse> presign(Long storeId, ImagePresignRequest request) {
        List<FileItem> files = request.files();
        if (files.isEmpty() || files.size() > MAX_FILES) {
            throw invalidImage("이미지는 한 번에 1~5개까지 올릴 수 있습니다.");
        }
        for (int i = 0; i < files.size(); i++) {
            check(i + 1, files.get(i));
        }
        return files.stream().map(file -> issue(storeId, file)).toList();
    }

    /** 하나라도 틀리면 전체 거절 — 메시지에 몇 번째 파일인지 (요청에 파일 이름이 없음) */
    private static void check(int order, FileItem file) {
        if (!EXTENSIONS.containsKey(file.contentType())) {
            throw invalidImage(order + "번째 파일: 이미지는 JPG · PNG · WEBP 만 올릴 수 있습니다.");
        }
        if (file.size() <= 0 || file.size() > MAX_FILE_SIZE) {
            throw invalidImage(order + "번째 파일: 이미지는 10MB 이하만 올릴 수 있습니다.");
        }
    }

    private ImagePresignResponse issue(Long storeId, FileItem file) {
        String key = "products/" + storeId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(file.contentType());
        PresignedUpload upload = productImageClient.presignUpload(key, file.contentType(), file.size());
        return new ImagePresignResponse(key, upload.url(), upload.expiresAt().atZone(KST).toOffsetDateTime());
    }

    private static ProductException invalidImage(String message) {
        return new ProductException(ProductErrorCode.PRODUCT_IMAGE_INVALID, message);
    }
}
