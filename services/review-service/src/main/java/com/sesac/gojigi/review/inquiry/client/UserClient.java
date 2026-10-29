package com.sesac.gojigi.review.inquiry.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.review.inquiry.client.dto.UserSummaryResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/** user-service 내부 API — 문의 작성자 표시 이름. 직접 쓰지 않고 InquirySnapshotReader 를 거친다 */
public interface UserClient {

    @GetExchange("/internal/users/{userId}")
    ApiResponse<UserSummaryResponse> getUser(@PathVariable Long userId);
}
