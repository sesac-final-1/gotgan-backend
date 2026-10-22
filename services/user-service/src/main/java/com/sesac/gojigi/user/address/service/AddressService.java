package com.sesac.gojigi.user.address.service;

import com.sesac.gojigi.user.address.dto.request.CreateAddressRequest;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.entity.Address;
import com.sesac.gojigi.user.address.exception.AddressErrorCode;
import com.sesac.gojigi.user.address.exception.AddressException;
import com.sesac.gojigi.user.address.repository.AddressRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
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

    /** 등록 — main=true 거나 첫 배송지면 기본 배송지. 이미 최대 개수면 ADDRESS_LIMIT_EXCEEDED (회원 행 잠금 안에서 세어 동시 등록에도 초과 없음) */
    @Transactional
    public AddressResponse create(Long userId, CreateAddressRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));   // 없는 회원 · 탈퇴
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
}
