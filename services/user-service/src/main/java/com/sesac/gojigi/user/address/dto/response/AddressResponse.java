package com.sesac.gojigi.user.address.dto.response;

import com.sesac.gojigi.user.address.entity.Address;

/** 배송지 — API 명세 Address {id, label, name, phone, zip, addr, detail, main} */
public record AddressResponse(Long id, String label, String name, String phone, String zip, String addr, String detail,
                              boolean main) {

    public static AddressResponse from(Address a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getReceiverName(), a.getReceiverPhone(), a.getZipcode(),
                a.getAddress1(), a.getAddress2(), a.isDefault());
    }
}
