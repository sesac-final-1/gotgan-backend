package com.sesac.gojigi.product.image.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.client.ProductImageClient;
import com.sesac.gojigi.product.image.client.ProductImageClient.PresignedUpload;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 상품 이미지 업로드 주소 발급 (POST /api/seller/products/images/presign, product.md 17절) — 실제 MySQL · Flyway.
 * S3 서명은 ProductImageClientTest 가 보고, 여기서는 클라이언트를 목으로 바꿔 요청 검사 · 헤더 · 등록 API 와 키 모양이 맞는지 본다.
 * 트레이스 샘플링 0 — Zipkin 전송 실패 WARN 이 LogbackConfigTest 가짜 Loki 에 섞이지 않게.
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SellerProductImageApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired CategoryRepository categoryRepository;
    @Autowired ProductRepository productRepository;

    @MockitoBean ProductImageClient productImageClient;

    @AfterEach
    void clean() {
        productRepository.deleteAll();
        categoryRepository.deleteAll(categoryRepository.findAll().stream().filter(c -> !c.isTop()).toList());
        categoryRepository.deleteAll();
    }

    private ResultActions presign(String storeId, String json) throws Exception {
        var req = post("/api/seller/products/images/presign").header("X-User-Id", "2").header("X-User-Roles", "SELLER")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return mockMvc.perform(storeId == null ? req : req.header("X-Store-Id", storeId));
    }

    @Test
    @DisplayName("발급한 키 그대로 상품 등록이 통과한다 — 200 요청 순서 · 내 스토어 키 · 만료 +09:00 → 등록 201")
    void presign_validFiles_keysAcceptedByRegister() throws Exception {
        // given
        given(productImageClient.presignUpload(anyString(), anyString(), anyLong()))
                .willAnswer(inv -> new PresignedUpload("https://s3.example/" + inv.getArgument(0), Instant.parse("2026-10-27T06:10:00Z")));
        Category top = Category.top("패션의류", null, 1);
        top.changeActive(true);
        top = categoryRepository.save(top);
        Category shirts = categoryRepository.save(Category.child(top.getId(), "셔츠", null, 1));

        // when
        String body = presign("1", "{\"files\":[{\"contentType\":\"image/jpeg\",\"size\":1000},{\"contentType\":\"image/webp\",\"size\":2000}]}")
                // then
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].key").value(org.hamcrest.Matchers.matchesPattern("products/1/[0-9a-f-]{36}\\.jpg")))
                .andExpect(jsonPath("$.data[1].key").value(org.hamcrest.Matchers.matchesPattern("products/1/[0-9a-f-]{36}\\.webp")))
                .andExpect(jsonPath("$.data[0].expiresAt").value("2026-10-27T15:10:00+09:00"))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = jsonMapper.readTree(body).path("data");
        List<String> keys = List.of(data.get(0).path("key").asString(), data.get(1).path("key").asString());

        mockMvc.perform(post("/api/seller/products")
                        .header("X-User-Id", "2").header("X-User-Roles", "SELLER").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(Map.of(
                                "categoryId", shirts.getId(), "name", "presign 키 상품", "price", 10000, "discountRate", 0,
                                "material", "면", "origin", "대한민국",
                                "options", List.of(Map.of("extraPrice", 0, "stock", 1)), "imageKeys", keys))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.images[0].key").value(keys.get(0)));
    }

    @Test
    @DisplayName("형식 위반 — 400 PRODUCT_IMAGE_INVALID '2번째 파일', 주소는 하나도 발급하지 않음")
    void presign_invalidType_imageInvalid() throws Exception {
        presign("1", "{\"files\":[{\"contentType\":\"image/png\",\"size\":1},{\"contentType\":\"image/gif\",\"size\":1}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRODUCT_IMAGE_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("2번째 파일")));
        verifyNoInteractions(productImageClient);
    }

    @Test
    @DisplayName("files 없음 · 크기 없음 — 400 INVALID_INPUT")
    void presign_missingFields_invalidInput() throws Exception {
        presign("1", "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        presign("1", "{\"files\":[{\"contentType\":\"image/png\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("X-Store-Id 없음 — 403 FORBIDDEN")
    void presign_noStoreHeader_forbidden() throws Exception {
        presign(null, "{\"files\":[{\"contentType\":\"image/png\",\"size\":1}]}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        verifyNoInteractions(productImageClient);
    }
}
