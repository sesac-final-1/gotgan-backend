package com.sesac.gojigi.store.store.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.common.html.IntroHtml;
import com.sesac.gojigi.store.store.dto.request.SellerStoreUpdateRequest;
import com.sesac.gojigi.store.store.dto.response.SellerStoreResponse;
import com.sesac.gojigi.store.store.entity.Store;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.repository.StoreRepository;
import java.util.Objects;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 스토어 설정 수정 (담당 박지연, STR-06 · store.md 13절).
 * 스토어 행을 잠가(FOR UPDATE) 바꾼다 — store 에 @Version 이 없어, 그 사이 관리자가 바꾼 상태를 판매자 저장(전체 컬럼 UPDATE)이 덮어쓰지 않게.
 * 이벤트는 없음 — 배송비는 order 가 주문 때 1절을 읽어 스냅샷으로 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerStoreService {

    private final StoreRepository storeRepository;
    private final StoreQueryService storeQueryService;

    @Transactional
    public SellerStoreResponse update(Long storeId, SellerStoreUpdateRequest request) {
        Store store = storeRepository.findForUpdate(storeId)
                .orElseThrow(() -> new StoreException(StoreErrorCode.STORE_NOT_FOUND));
        String previousImageKey = store.getImageKey();
        String imageKey = checkImageKey(storeId, request.imageKey(), previousImageKey);

        store.updateSettings(IntroHtml.sanitize(request.intro()), imageKey, request.color(), request.csPhone(),
                request.shippingFee(), request.freeShippingThreshold(), request.courier(), request.dispatchNote());
        log.info("스토어 설정 수정 - storeId={}, imageChanged={}", storeId, !Objects.equals(imageKey, previousImageKey));
        return storeQueryService.getSellerStore(storeId);
    }

    /**
     * 대표 이미지 키 — 비우면 이미지 없음, 지금 키 그대로면 유지, 새 키는 업로드 주소 발급으로 받은 내 스토어 키 모양만(S3 존재는 안 봄 — product 와 같음).
     * 다른 스토어 · 다른 폴더 키를 막아 남의 이미지를 대표로 걸지 못하게
     */
    static String checkImageKey(Long storeId, String requested, String current) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String key = requested.trim();
        if (key.equals(current)) {
            return key;
        }
        Pattern ownKey = Pattern.compile("stores/" + storeId + "/[A-Za-z0-9-]{1,64}\\.(jpg|png|webp)");
        if (!ownKey.matcher(key).matches()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "업로드 주소 발급으로 받은 내 스토어 이미지만 사용할 수 있습니다.");
        }
        return key;
    }
}
