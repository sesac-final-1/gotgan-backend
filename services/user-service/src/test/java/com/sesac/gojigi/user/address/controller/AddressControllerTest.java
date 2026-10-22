package com.sesac.gojigi.user.address.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.user.address.dto.response.AddressResponse;
import com.sesac.gojigi.user.address.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AddressControllerTest {

    @Mock
    private AddressService addressService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AddressController(addressService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static final String BODY = """
            {"label":"집","name":"김곳간","phone":"010-1234-5678","zip":"06236","addr":"서울특별시 강남구 테헤란로 152","detail":"21층","main":true}""";

    @Test
    @DisplayName("등록 — 201, 명세 필드 이름(id · label · name · phone · zip · addr · detail · main)으로 응답")
    void create() throws Exception {
        given(addressService.create(eq(4L), any())).willReturn(
                new AddressResponse(10L, "집", "김곳간", "010-1234-5678", "06236", "서울특별시 강남구 테헤란로 152", "21층", true));

        mockMvc.perform(post("/api/users/me/addresses").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.zip").value("06236"))
                .andExpect(jsonPath("$.data.main").value(true));
    }

    @Test
    @DisplayName("형식 오류 — 우편번호 5자리 아님 · 휴대폰 아님 · 받는 분 없음은 400, 서비스 호출 없음")
    void create_validation() throws Exception {
        for (String body : new String[] {
                BODY.replace("\"06236\"", "\"1234\""),
                BODY.replace("\"010-1234-5678\"", "\"02-123-4567\""),
                BODY.replace("\"김곳간\"", "\"\"") }) {
            mockMvc.perform(post("/api/users/me/addresses").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(addressService);
    }

    @Test
    @DisplayName("X-User-Id 없으면 401")
    void create_withoutUser() throws Exception {
        mockMvc.perform(post("/api/users/me/addresses").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(addressService);
    }
}
