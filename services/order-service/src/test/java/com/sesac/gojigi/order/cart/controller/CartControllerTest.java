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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new CartController(cartService))
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
}
