package com.sesac.gojigi.settlement.common.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import tools.jackson.databind.json.JsonMapper;

/** 메시지 1건 처리 규칙 — 성공 · 모르는 type 은 삭제, 실패는 남겨 재전달(3회 뒤 DLQ) */
class SqsEventPollerTest {

    private static final String QUEUE = "https://sqs.ap-northeast-2.amazonaws.com/0/sesac-gojigi-settlement-store-events";

    private final SqsClient sqsClient = mock(SqsClient.class);
    private final List<String> received = new ArrayList<>();
    private SqsEventPoller poller;

    @BeforeEach
    void setUp() {
        SqsEventListener ok = new SqsEventListener() {
            public String eventType() { return "StoreApproved"; }
            public void onMessage(String json) { received.add(json); }
        };
        SqsEventListener failing = new SqsEventListener() {
            public String eventType() { return "StoreStatusChanged"; }
            public void onMessage(String json) { throw new IllegalStateException("스냅샷 없음"); }
        };
        poller = new SqsEventPoller(sqsClient, JsonMapper.builder().build(), List.of(ok, failing), QUEUE, "", 20, 10);
    }

    private static Message message(String eventType) {
        return Message.builder().messageId("m-1").receiptHandle("rh-1")
                .body("{\"eventId\":\"e-1\",\"eventType\":\"" + eventType + "\",\"payload\":{}}").build();
    }

    @Test
    @DisplayName("리스너가 정상 처리하면 메시지 삭제")
    @SuppressWarnings("unchecked")
    void dispatch_success_deletesMessage() {
        poller.dispatch(QUEUE, message("StoreApproved"));

        org.assertj.core.api.Assertions.assertThat(received).hasSize(1);
        verify(sqsClient).deleteMessage(any(Consumer.class));
    }

    @Test
    @DisplayName("리스너가 예외를 던지면 삭제하지 않음 — 가시성 타임아웃 뒤 재전달")
    @SuppressWarnings("unchecked")
    void dispatch_failure_keepsMessage() {
        poller.dispatch(QUEUE, message("StoreStatusChanged"));

        verify(sqsClient, never()).deleteMessage(any(Consumer.class));
        verify(sqsClient, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    @DisplayName("처리할 리스너가 없는 eventType 은 삭제 (구독 필터 밖 메시지가 큐를 막지 않게)")
    @SuppressWarnings("unchecked")
    void dispatch_unknownType_deletes() {
        poller.dispatch(QUEUE, message("OrderPaid"));

        verify(sqsClient).deleteMessage(any(Consumer.class));
    }

    @Test
    @DisplayName("큐 URL 이 비어 있으면 폴러 스레드를 띄우지 않음 (로컬 · 테스트)")
    void start_noQueueUrl_noWorkers() {
        SqsEventPoller idle = new SqsEventPoller(sqsClient, JsonMapper.builder().build(), List.of(), "", "", 20, 10);
        idle.start();
        idle.stop();

        verify(sqsClient, never()).receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class));
    }
}
