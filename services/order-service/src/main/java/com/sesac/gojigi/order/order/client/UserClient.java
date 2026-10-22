package com.sesac.gojigi.order.order.client;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.order.order.client.dto.PointRestoreResponse;
import com.sesac.gojigi.order.order.client.dto.PointUseRequest;
import com.sesac.gojigi.order.order.client.dto.PointUseResponse;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/** user-service 내부 API — 직접 쓰지 않고 UserClientAdapter(서킷 브레이커 "user")를 거친다 */
@HttpExchange("/internal")
public interface UserClient {

    @GetExchange("/users/{userId}")
    ApiResponse<UserSummaryResponse> getUser(@PathVariable Long userId);

    /** 포인트 사용 — sagaId 멱등 */
    @PostExchange("/points/use")
    ApiResponse<PointUseResponse> usePoints(@RequestBody PointUseRequest request);

    /** 포인트 사용 취소 (Saga 보상) — 되돌릴 사용이 없어도 200 */
    @DeleteExchange("/points/use/{sagaId}")
    ApiResponse<PointRestoreResponse> restorePoints(@PathVariable String sagaId);
}
