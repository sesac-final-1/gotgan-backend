package com.sesac.gojigi.user.address.service;

import com.sesac.gojigi.user.address.dto.request.AddressRequest;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.entity.Address;
import com.sesac.gojigi.user.address.exception.AddressErrorCode;
import com.sesac.gojigi.user.address.exception.AddressException;
import com.sesac.gojigi.user.address.repository.AddressRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송지 — 기본 배송지는 회원당 1개 (새 기본 지정 시 기존 기본 해제를 같은 트랜잭션에서).
 * 같은 회원의 동시 요청으로 기본이 2개 되지 않게 회원 행을 잠그고(SELECT ... FOR UPDATE) 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressService {

    /** 회원당 배송지 최대 개수 (삭제한 배송지는 세지 않음) */
    static final int MAX_ADDRESSES = 5;

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** 등록 — main=true 거나 첫 배송지면 기본 배송지. 이미 최대 개수면 ADDRESS_LIMIT_EXCEEDED (회원 행 잠금 안에서 세어 동시 등록에도 초과 없음) */
    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        User user = lockMember(userId);
        long count = addressRepository.countActiveByUserId(userId);
        if (count >= MAX_ADDRESSES) {
            throw new AddressException(AddressErrorCode.ADDRESS_LIMIT_EXCEEDED);
        }
        boolean makeDefault = request.main() || count == 0;
        if (makeDefault) {
            addressRepository.clearDefault(userId);
        }
        Address address = addressRepository.save(Address.builder()
                .user(user)
                .label(request.label().trim())
                .receiverName(request.name().trim())
                .receiverPhone(request.normalizedPhone())
                .zipcode(request.zip())
                .address1(request.addr().trim())
                .address2(request.trimmedDetail())
                .isDefault(makeDefault)
                .build());
        log.info("배송지 등록 userId={} addressId={} default={}", userId, address.getId(), makeDefault);
        return AddressResponse.from(address);
    }

    /** 배송지 수정 */
    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressRequest request) {
        lockMember(userId);
        Address address = findOwned(userId, addressId);
        boolean makeDefault = request.main() && !address.isDefault();
        if (makeDefault) {
            addressRepository.clearDefault(userId);
            address = findOwned(userId, addressId);   // 조건부 UPDATE 가 영속성 컨텍스트를 비워 다시 읽는다
            address.markDefault();
        }
        address.update(request.label().trim(), request.name().trim(), request.normalizedPhone(), request.zip(),
                request.addr().trim(), request.trimmedDetail());
        log.info("배송지 수정 userId={} addressId={} default={}", userId, addressId, address.isDefault());
        return AddressResponse.from(address);   // 트랜잭션 커밋 때 변경 감지로 UPDATE
    }

    /** 목록 — 기본 배송지 먼저, 그다음 최근 등록순 (최대 5개라 페이징 없음). 주문서 배송지 선택도 이 목록 */
    @Transactional(readOnly = true)
    public List<AddressResponse> list(Long userId) {
        userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));   // 탈퇴 회원
        return addressRepository.findActiveByUserId(userId).stream().map(AddressResponse::from).toList();
    }

    /** 기본 지정 — 기존 기본을 해제하고 이 배송지를 기본으로. 이미 기본이면 바꾸지 않는다 */
    @Transactional
    public AddressResponse markDefault(Long userId, Long addressId) {
        lockMember(userId);
        Address address = findOwned(userId, addressId);
        if (!address.isDefault()) {
            addressRepository.clearDefault(userId);
            address = findOwned(userId, addressId);   // 조건부 UPDATE 가 영속성 컨텍스트를 비워 다시 읽는다
            address.markDefault();
            log.info("기본 배송지 지정 userId={} addressId={}", userId, addressId);
        }
        return AddressResponse.from(address);
    }

    /**
     * 삭제 — 소프트 삭제(deleted_at). 기본 배송지는 삭제 불가(ADDRESS_DEFAULT_DELETE_NOT_ALLOWED) — 다른 배송지를 기본으로 지정한 뒤 삭제.
     * 회원 행 잠금 안에서 확인해 기본 지정과 겹쳐도 기본 배송지가 지워지지 않는다. 삭제한 배송지는 최대 개수에서 빠진다.
     */
    @Transactional
    public void delete(Long userId, Long addressId) {
        lockMember(userId);
        Address address = findOwned(userId, addressId);
        if (address.isDefault()) {
            throw new AddressException(AddressErrorCode.ADDRESS_DEFAULT_DELETE_NOT_ALLOWED);
        }
        address.softDelete(LocalDateTime.now(clock));
        log.info("배송지 삭제 userId={} addressId={}", userId, addressId);
    }

    /** 회원 행 잠금 — 같은 회원의 등록 · 수정이 겹쳐 기본이 2개 되지 않게 (없는 회원 · 탈퇴면 USER_NOT_FOUND) */
    private User lockMember(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
    }

    /** 내 배송지 — 없는 · 삭제된 · 다른 회원의 배송지는 모두 ADDRESS_NOT_FOUND */
    private Address findOwned(Long userId, Long addressId) {
        return addressRepository.findActiveByIdAndUserId(addressId, userId)
                .orElseThrow(() -> new AddressException(AddressErrorCode.ADDRESS_NOT_FOUND));
    }
}
