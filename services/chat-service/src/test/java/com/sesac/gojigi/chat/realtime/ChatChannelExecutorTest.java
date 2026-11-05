package com.sesac.gojigi.chat.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.chat.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;

/**
 * 메시지 채널과 알림 전송은 스레드를 나눠 쓴다. Boot 는 AsyncTaskExecutor 빈이 하나뿐이면 그것을 WebSocket 채널에도 씌워서,
 * 알림용 2스레드에서 메시지 저장 · 전달까지 돌며 왕복이 밀렸다(11/5 k6 S5 스레드 덤프).
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatChannelExecutorTest {

    @Autowired @Qualifier("clientInboundChannelExecutor") ThreadPoolTaskExecutor inbound;
    @Autowired @Qualifier("clientOutboundChannelExecutor") ThreadPoolTaskExecutor outbound;
    @Autowired @Qualifier("chatNotifyExecutor") ThreadPoolTaskExecutor notify;

    @Test
    @DisplayName("입력 · 출력 채널은 알림 스레드 풀과 다른 풀이고, 설정한 스레드 수(4)를 쓴다")
    void channelsDoNotShareNotifyExecutor() {
        assertThat(inbound).isNotSameAs(notify);
        assertThat(outbound).isNotSameAs(notify);
        assertThat(inbound.getCorePoolSize()).isEqualTo(4);
        assertThat(outbound.getCorePoolSize()).isEqualTo(4);
        assertThat(notify.getCorePoolSize()).isEqualTo(2);
    }
}
