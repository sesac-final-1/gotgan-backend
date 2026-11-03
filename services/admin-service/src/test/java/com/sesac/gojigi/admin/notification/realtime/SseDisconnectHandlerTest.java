package com.sesac.gojigi.admin.notification.realtime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/** SSE 연결 끊김 — 공통 처리(ERROR · 500 ApiResponse)보다 먼저 받아 응답을 쓰지 않는다 (Q-구-7) */
class SseDisconnectHandlerTest {

    @RestController
    static class BrokenStream {
        @GetMapping("/broken")
        String broken() throws AsyncRequestNotUsableException {
            throw new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer: Broken pipe");
        }

        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("boom");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BrokenStream())
            .setControllerAdvice(new GlobalExceptionHandler(), new SseDisconnectHandler())
            .build();

    @Test
    @DisplayName("끊김(AsyncRequestNotUsable)은 이 핸들러가 받아 본문 없이 끝, 다른 예외는 그대로 공통 500")
    void disconnected_isSwallowed() throws Exception {
        mvc.perform(get("/broken")).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(get("/boom")).andExpect(status().isInternalServerError());
    }
}
