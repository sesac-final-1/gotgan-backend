package com.sesac.gojigi.order.cart.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.cart.dto.response.CartItemsResponse;
import com.sesac.gojigi.order.cart.exception.CartErrorCode;
import com.sesac.gojigi.order.cart.exception.CartException;
import com.sesac.gojigi.order.cart.service.CartService;
import java.util.List;
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
class CartControllerTest {

    private static final String URL = "/api/carts/items";
    private static final CartItemsResponse CART = new CartItemsResponse(
            List.of(new CartItemsResponse.Item(11L, 101L, 3, true)), 1);

    @Mock
    private CartService cartService;

    @Mock
    private com.sesac.gojigi.order.cart.service.CartQueryService cartQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CartController(cartService, cartQueryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("담기 — 200 '장바구니에 담았습니다.' + 내 장바구니, 구매 불가면 409")
    void add() throws Exception {
        given(cartService.add(eq(4L), any())).willReturn(CART)
                .willThrow(new CartException(CartErrorCode.CART_PRODUCT_NOT_PURCHASABLE));
        String body = """
                {"items":[{"productOptionId":11,"quantity":2},{"productOptionId":12,"quantity":1}]}""";

        mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("장바구니에 담았습니다."))
                .andExpect(jsonPath("$.data.items[0].quantity").value(3))
                .andExpect(jsonPath("$.data.cartCount").value(1));
        mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CART_PRODUCT_NOT_PURCHASABLE"));
    }

    @Test
    @DisplayName("담기 — 빈 목록 · 수량 0 · 1000 은 400")
    void add_invalid() throws Exception {
        for (String body : List.of("""
                {"items":[]}""", """
                {"items":[{"productOptionId":11,"quantity":0}]}""", """
                {"items":[{"productOptionId":11,"quantity":1000}]}""")) {
            mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cartService);
    }

    @Test
    @DisplayName("삭제 — ?productOptionIds=11,12 로 단건 · 선택 삭제, 없으면 400")
    void remove() throws Exception {
        given(cartService.remove(4L, List.of(11L, 12L))).willReturn(CART);

        mockMvc.perform(delete(URL).header("X-User-Id", "4").param("productOptionIds", "11", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("장바구니에서 삭제했습니다."))
                .andExpect(jsonPath("$.data.cartCount").value(1));
        mockMvc.perform(delete(URL).header("X-User-Id", "4"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("조회 — 스토어 묶음 · 금액, 수량 · 선택 변경 — 둘 다 없거나 범위 밖이면 400, 선택 한꺼번에")
    void getUpdateCheck() throws Exception {
        com.sesac.gojigi.order.cart.dto.response.CartResponse.Line line = new com.sesac.gojigi.order.cart.dto.response.CartResponse.Line(
                11L, 101L, "사과", "특", null, 10_000L, 2, true, true, 100, 20_000L);
        given(cartQueryService.get(4L)).willReturn(new com.sesac.gojigi.order.cart.dto.response.CartResponse(
                List.of(new com.sesac.gojigi.order.cart.dto.response.CartResponse.StoreGroup(1L, "곳간상회", 20_000L, 3_000L,
                        50_000L, 30_000L, List.of(line))), List.of(), 1, 1, 20_000L, 3_000L, 23_000L));
        given(cartService.update(eq(4L), eq(11L), any())).willReturn(CART);
        given(cartService.check(4L, null, false)).willReturn(CART);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/carts").header("X-User-Id", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stores[0].amountToFree").value(30000))
                .andExpect(jsonPath("$.data.totalAmt").value(23000));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(URL + "/11").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("장바구니를 수정했습니다."));
        for (String body : List.of("{}", "{\"quantity\":0}", "{\"quantity\":1000}")) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(URL + "/11").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(URL + "/checked").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"checked\":false}"))
                .andExpect(status().isOk());
        org.mockito.Mockito.verify(cartService).check(4L, null, false);
    }
}
