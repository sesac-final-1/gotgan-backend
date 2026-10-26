package com.sesac.gojigi.product.category.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.product.TestcontainersConfiguration;
import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카테고리 관리 (/internal/categories) · 공개 트리 (/api/categories) — 실제 MySQL · Flyway V1~V2.
 * 테스트마다 카테고리 표를 비우고 필요한 것만 만든다 (아직 product 행이 없어 FK 걱정 없음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CategoryApiIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper jsonMapper;
    @Autowired CategoryRepository categoryRepository;

    @BeforeEach
    void clean() {
        // 하위 먼저 지워야 FK(fk_category_parent) 에 걸리지 않는다
        categoryRepository.deleteAll(categoryRepository.findAll().stream().filter(c -> !c.isTop()).toList());
        categoryRepository.deleteAll();
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String json) throws Exception {
        return mockMvc.perform(req.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long create(String json) throws Exception {
        String body = send(post("/internal/categories"), json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).path("data").path("id").asLong();
    }

    private void activate(long id) throws Exception {
        send(patch("/internal/categories/" + id), "{\"active\":true}").andExpect(status().isOk());
    }

    @Nested
    @DisplayName("생성")
    class Create {

        @Test
        @DisplayName("상위는 사용 중지 · 하위는 사용 중으로 만들고, sortOrder 생략은 같은 부모 안 맨 뒤, 빈 영문명은 null")
        void topInactiveChildActive() throws Exception {
            send(post("/internal/categories"), "{\"name\":\" 패션의류 \",\"nameEn\":\"Apparel\",\"sortOrder\":1}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.parentId").doesNotExist())
                    .andExpect(jsonPath("$.data.name").value("패션의류"))
                    .andExpect(jsonPath("$.data.active").value(false));
            long top = categoryRepository.findAll().getFirst().getId();

            send(post("/internal/categories"), "{\"parentId\":" + top + ",\"name\":\"셔츠\",\"nameEn\":\" \"}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.parentId").value(top))
                    .andExpect(jsonPath("$.data.nameEn").doesNotExist())
                    .andExpect(jsonPath("$.data.sortOrder").value(1))
                    .andExpect(jsonPath("$.data.active").value(true));
            send(post("/internal/categories"), "{\"parentId\":" + top + ",\"name\":\"팬츠\"}")
                    .andExpect(jsonPath("$.data.sortOrder").value(2));
            send(post("/internal/categories"), "{\"name\":\"뷰티\"}")
                    .andExpect(jsonPath("$.data.sortOrder").value(2)); // 상위끼리 맨 뒤 (패션의류 1 다음)
        }

        @Test
        @DisplayName("하위 아래(3단계) 400 · 없는 부모 404 · 같은 부모 아래 같은 이름 409 (다른 부모는 허용) · 이름 없음 400")
        void rules() throws Exception {
            long top = create("{\"name\":\"리빙\"}");
            long other = create("{\"name\":\"식품\"}");
            long child = create("{\"parentId\":" + top + ",\"name\":\"조명\"}");

            send(post("/internal/categories"), "{\"parentId\":" + child + ",\"name\":\"스탠드\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_DEPTH_EXCEEDED"));
            send(post("/internal/categories"), "{\"parentId\":999999,\"name\":\"x\"}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
            send(post("/internal/categories"), "{\"parentId\":" + top + ",\"name\":\"조명\"}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATED"));
            send(post("/internal/categories"), "{\"name\":\"리빙\"}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATED"));
            send(post("/internal/categories"), "{\"parentId\":" + other + ",\"name\":\"조명\"}")
                    .andExpect(status().isCreated());
            send(post("/internal/categories"), "{\"name\":\"  \"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }

    @Nested
    @DisplayName("수정 · 사용 여부")
    class Update {

        @Test
        @DisplayName("이름 · 영문명 · 정렬 수정 — 부모는 그대로, 자기 이름 유지는 허용, 형제 이름과 겹치면 409, 없는 카테고리 404")
        void update() throws Exception {
            long top = create("{\"name\":\"패션의류\"}");
            long shirts = create("{\"parentId\":" + top + ",\"name\":\"셔츠\"}");
            create("{\"parentId\":" + top + ",\"name\":\"팬츠\"}");

            send(put("/internal/categories/" + shirts), "{\"name\":\"셔츠\",\"nameEn\":\"Shirts\",\"sortOrder\":5}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.parentId").value(top))
                    .andExpect(jsonPath("$.data.nameEn").value("Shirts"))
                    .andExpect(jsonPath("$.data.sortOrder").value(5));
            send(put("/internal/categories/" + shirts), "{\"name\":\"팬츠\",\"sortOrder\":1}")
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_DUPLICATED"));
            send(put("/internal/categories/999999"), "{\"name\":\"x\",\"sortOrder\":1}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
            send(put("/internal/categories/" + shirts), "{\"name\":\"셔츠\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT")); // sortOrder 필수
        }

        @Test
        @DisplayName("사용 / 사용 중지 — 같은 값이면 그대로 200(멱등), 없는 카테고리 404, 값 없음 400")
        void changeActive() throws Exception {
            long top = create("{\"name\":\"뷰티\"}");

            send(patch("/internal/categories/" + top), "{\"active\":true}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
            send(patch("/internal/categories/" + top), "{\"active\":true}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
            send(patch("/internal/categories/999999"), "{\"active\":false}")
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
            send(patch("/internal/categories/" + top), "{}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            assertThat(categoryRepository.findById(top)).get().extracting(Category::isActive).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("트리")
    class Tree {

        @Test
        @DisplayName("관리자 트리는 비활성 포함 · 공개 트리는 꺼진 상위는 통째로, 꺼진 하위는 그것만 빼고, 둘 다 sortOrder 순")
        void trees() throws Exception {
            long fashion = create("{\"name\":\"패션의류\",\"nameEn\":\"Apparel\",\"sortOrder\":2}");
            long living = create("{\"name\":\"리빙\",\"sortOrder\":1}");
            long beauty = create("{\"name\":\"뷰티\",\"sortOrder\":3}");   // 켜지 않음 → 공개 트리에서 빠짐
            create("{\"parentId\":" + fashion + ",\"name\":\"팬츠\",\"sortOrder\":2}");
            long shirts = create("{\"parentId\":" + fashion + ",\"name\":\"셔츠\",\"sortOrder\":1}");
            long knit = create("{\"parentId\":" + fashion + ",\"name\":\"니트\",\"sortOrder\":3}");
            create("{\"parentId\":" + beauty + ",\"name\":\"립\"}");
            activate(fashion);
            activate(living);
            send(patch("/internal/categories/" + knit), "{\"active\":false}").andExpect(status().isOk());

            JsonNode admin = data(mockMvc.perform(get("/internal/categories")));
            assertThat(names(admin)).containsExactly("리빙", "패션의류", "뷰티");
            assertThat(names(admin.get(1).path("children"))).containsExactly("셔츠", "팬츠", "니트");
            assertThat(admin.get(2).path("active").asBoolean()).isFalse();
            assertThat(admin.get(1).path("children").get(2).path("active").asBoolean()).isFalse();

            JsonNode open = data(mockMvc.perform(get("/api/categories")));
            assertThat(names(open)).containsExactly("리빙", "패션의류");
            assertThat(names(open.get(1).path("children"))).containsExactly("셔츠", "팬츠");
            assertThat(open.get(1).path("nameEn").asString()).isEqualTo("Apparel");
            assertThat(open.get(1).path("children").get(0).path("id").asLong()).isEqualTo(shirts);
            assertThat(open.get(1).has("sortOrder")).isFalse();
            assertThat(open.get(1).has("active")).isFalse();
        }
    }

    private JsonNode data(ResultActions result) throws Exception {
        return jsonMapper.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private static List<String> names(JsonNode array) {
        return array.valueStream().map(n -> n.path("name").asString()).toList();
    }
}
