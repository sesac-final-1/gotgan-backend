package com.sesac.gojigi.admin.notification.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.admin.notification.service.NotificationService;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;

/** 알림 이벤트 폴러 — 큐 URL 이 비면 띄우지 않음, 처리 성공이면 삭제 · 실패면 남겨 재전달 */
class NotificationEventPollerTest {

    private final SqsClient sqs = mock(SqsClient.class);
    private final NotificationService service = mock(NotificationService.class);
    private final Message message = Message.builder().messageId("m-1").receiptHandle("rh-1").body("{\"eventType\":\"OrderPaid\"}").build();

    @Test
    @DisplayName("큐 URL 이 모두 비면(로컬 · 테스트) 시작해도 받지 않는다")
    void blankQueues_noWorkers() {
        NotificationEventPoller poller = new NotificationEventPoller(sqs, service, "", "", "");

        poller.start();
        assertThat(poller.isRunning()).isTrue();
        poller.stop();

        assertThat(poller.isRunning()).isFalse();
        verify(sqs, never()).receiveMessage(any(software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest.class));
    }

    @Test
    @DisplayName("처리 성공 → 그 메시지 삭제")
    @SuppressWarnings("unchecked")
    void dispatch_success_deletes() {
        NotificationEventPoller poller = new NotificationEventPoller(sqs, service, "https://sqs/q-store", "", "");

        poller.dispatch("https://sqs/q-store", message);

        verify(service).receiveEvent("{\"eventType\":\"OrderPaid\"}");
        verify(sqs).deleteMessage(any(Consumer.class));
    }

    @Test
    @DisplayName("처리 실패(알림함 장애 등) → 삭제하지 않아 가시성 타임아웃 뒤 재전달 (3회 실패 시 DLQ)")
    @SuppressWarnings("unchecked")
    void dispatch_failure_keeps() {
        NotificationEventPoller poller = new NotificationEventPoller(sqs, service, "https://sqs/q-store", "", "");
        willThrow(new IllegalStateException("dynamodb down")).given(service).receiveEvent(any());

        poller.dispatch("https://sqs/q-store", message);

        verify(sqs, never()).deleteMessage(any(Consumer.class));
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }
}
