package com.sesac.gojigi.chat.room;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.chat.TestcontainersConfiguration;
import com.sesac.gojigi.chat.directory.ChatDirectory;
import com.sesac.gojigi.chat.room.service.ChatRoomService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import java.util.List;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 채팅 REST (chat.md 1~5절) — 방 열기 규칙 · 문맥 카드 · 목록 · 안 읽은 수 · 남의 방 404 · 관리자 읽기 전용 열람 · 상담 상태.
 * 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ChatRoomApiTest {

    private static final long BUYER_ID = 10L;
    private static final long OTHER_BUYER_ID = 11L;
    private static final long SELLER_ID = 20L;
    private static final long STORE_ID = 5L;
    private static final long ADMIN_ID = 1L;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatDirectory directory;   // user · store 이름 · 스토어 상태 — 기본은 이름 없음(번호 표시) · 운영 중

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM chat_read");
        jdbc.update("DELETE FROM chat_message");
        jdbc.update("DELETE FROM chat_room");
        given(directory.storeOpen(any())).willReturn(true);
    }

    @Test
    @DisplayName("스토어 톡: 처음 201 · 같은 방 200, 같은 문맥은 카드를 다시 붙이지 않고 다른 문맥만 추가")
    void storeInquiryReusesRoomAndContext() throws Exception {
        given(directory.productName(101L)).willReturn("햇사과 5kg");
        long roomId = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"PRODUCT\",\"id\":101}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.counterpart.name").value("스토어 #5"))
                .andExpect(jsonPath("$.data.counterpart.role").value("SELLER"))
                .andExpect(jsonPath("$.data.buyerId").value(BUYER_ID))
                .andExpect(jsonPath("$.data.status").value(nullValue()))
                .andExpect(jsonPath("$.data.lastMessage.preview").value("햇사과 5kg")));

        open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"PRODUCT\",\"id\":101}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId));
        open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"ORDER_ITEM\",\"id\":7}}")
                .andExpect(status().isOk());

        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", roomId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].kind").value("CONTEXT"))
                .andExpect(jsonPath("$.data[0].context.title").value("상품주문 #7"))
                .andExpect(jsonPath("$.data[0].mine").value(true))
                .andExpect(jsonPath("$.data[1].context.type").value("PRODUCT"));
    }

    @Test
    @DisplayName("판매자는 스토어 단위로 보고, 구매자 카드는 판매자에게 안 읽음 — 읽음 처리하면 0")
    void sellerSeesStoreRoomsAndUnread() throws Exception {
        long roomId = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"PRODUCT\",\"id\":101}}"));

        mockMvc.perform(seller(get("/api/chats/rooms")))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("회원 #10"))
                .andExpect(jsonPath("$.data[0].unreadCount").value(1));
        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/unread-count"))).andExpect(jsonPath("$.data.count").value(0));
        mockMvc.perform(seller(get("/api/chats/unread-count"))).andExpect(jsonPath("$.data.count").value(1));

        long lastId = jdbc.queryForObject("SELECT last_message_id FROM chat_room WHERE chat_room_id = ?", Long.class, roomId);
        mockMvc.perform(seller(patch("/api/chats/rooms/{id}/read", roomId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastMessageId\":" + (lastId + 1000) + "}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(seller(get("/api/chats/unread-count"))).andExpect(jsonPath("$.data.count").value(0));
        Long stored = jdbc.queryForObject("SELECT last_read_message_id FROM chat_read WHERE reader_key = 'STORE#5'", Long.class);
        org.assertj.core.api.Assertions.assertThat(stored).isEqualTo(lastId); // 방의 마지막 메시지를 넘지 않게
    }

    @Test
    @DisplayName("방 열기 규칙 — 403 CHAT_NOT_ALLOWED · 400 CHAT_SELF · 400 INVALID_INPUT")
    void openRules() throws Exception {
        open(post("/api/chats/rooms").header("X-User-Id", SELLER_ID).header("X-User-Roles", "SELLER").header("X-Store-Id", STORE_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":9}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_NOT_ALLOWED"));
        open(seller(post("/api/chats/rooms")), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHAT_SELF"));
        open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        open(buyer(BUYER_ID), "{\"type\":\"SELLER_SUPPORT\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_NOT_ALLOWED"));
        open(admin(post("/api/chats/rooms")), "{\"type\":\"BUYER_SUPPORT\"}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHAT_NOT_ALLOWED"));
        open(admin(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("판매자 운영 상담: 관리자가 storeId 로 열면 판매자가 여는 방과 같다 — 판매자에겐 '곳간 운영팀'")
    void sellerSupportSharedByStore() throws Exception {
        long roomId = id(open(admin(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\",\"storeId\":5,\"context\":{\"type\":\"STORE\",\"id\":5}}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.counterpart.name").value("스토어 #5"))
                .andExpect(jsonPath("$.data.status").value("OPEN"))
                .andExpect(jsonPath("$.data.buyerId").value(nullValue())));

        open(seller(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roomId").value(roomId))
                .andExpect(jsonPath("$.data.counterpart.name").value("곳간 운영팀"))
                .andExpect(jsonPath("$.data.unreadCount").value(1));
    }

    @Test
    @DisplayName("남의 방은 404 — 다른 구매자 · 관계없는 판매자 · 도움 요청 전 관리자")
    void othersRoomIsNotFound() throws Exception {
        long roomId = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}"));

        mockMvc.perform(buyer(OTHER_BUYER_ID, get("/api/chats/rooms/{id}/messages", roomId)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_ROOM_NOT_FOUND"));
        mockMvc.perform(get("/api/chats/rooms/{id}/messages", roomId).header("X-User-Id", 21).header("X-User-Roles", "BUYER,SELLER").header("X-Store-Id", 6))
                .andExpect(status().isNotFound());
        mockMvc.perform(admin(get("/api/chats/rooms/{id}/messages", roomId))).andExpect(status().isNotFound());
        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", 999_999))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("운영팀에 도움 요청: 고객 상담에 내 스토어 톡 카드를 붙이면 관리자가 그 방을 읽기 전용으로 본다")
    void escalationGivesAdminReadOnlyAccess() throws Exception {
        long storeRoom = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"PRODUCT\",\"id\":1}}"));
        String help = "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"CHAT_ROOM\",\"id\":" + storeRoom + "}}";

        open(buyer(OTHER_BUYER_ID), help).andExpect(status().isNotFound()); // 남의 스토어 톡은 붙일 수 없다
        open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":6,\"context\":{\"type\":\"CHAT_ROOM\",\"id\":" + storeRoom + "}}")
                .andExpect(status().isBadRequest());
        open(buyer(BUYER_ID), help).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.counterpart.name").value("곳간 운영팀"));

        mockMvc.perform(admin(get("/api/chats/rooms/{id}/messages", storeRoom)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mine").value(false));
        mockMvc.perform(admin(patch("/api/chats/rooms/{id}/read", storeRoom)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lastMessageId\":1}"))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_read WHERE chat_room_id = ?", Integer.class, storeRoom)).isZero();
        mockMvc.perform(admin(get("/api/chats/rooms"))) // 관리자 목록은 상담방만
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].type").value("BUYER_SUPPORT"))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("회원 #10"));
    }

    @Test
    @DisplayName("상담 상태: 관리자가 RESOLVED → SYSTEM 안내(안 읽은 수에는 안 셈) · 상태 필터 · 스토어 톡 400 · 관리자 외 403")
    void statusChange() throws Exception {
        long supportRoom = id(open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\"}"));
        long storeRoom = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}"));

        patchStatus(buyer(BUYER_ID, patch("/api/chats/rooms/{id}/status", supportRoom)), "RESOLVED")
                .andExpect(status().isForbidden());
        patchStatus(admin(patch("/api/chats/rooms/{id}/status", storeRoom)), "RESOLVED")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CHAT_INVALID_TYPE"));
        patchStatus(admin(patch("/api/chats/rooms/{id}/status", supportRoom)), "RESOLVED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"))
                .andExpect(jsonPath("$.data.lastMessage.preview").value(startsWith("상담이 종료됐어요")));
        patchStatus(admin(patch("/api/chats/rooms/{id}/status", supportRoom)), "RESOLVED").andExpect(status().isOk()); // 같은 상태는 그대로

        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", supportRoom)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].senderRole").value("SYSTEM"));
        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/unread-count"))).andExpect(jsonPath("$.data.count").value(0));
        mockMvc.perform(admin(get("/api/chats/rooms")).param("status", "RESOLVED")).andExpect(jsonPath("$.data", hasSize(1)));
        mockMvc.perform(admin(get("/api/chats/rooms")).param("status", "OPEN")).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms")).param("type", "STORE_INQUIRY"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].lastMessage").value(nullValue()));

        patchStatus(admin(patch("/api/chats/rooms/{id}/status", supportRoom)), "OPEN")
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    @Test
    @DisplayName("메시지 페이지: before 보다 이전 것을 size 만큼, 최신 → 과거")
    void messagesPage() throws Exception {
        long roomId = 0;
        for (int productId = 1; productId <= 3; productId++) {
            roomId = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5,\"context\":{\"type\":\"PRODUCT\",\"id\":" + productId + "}}"));
        }
        String newest = mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", roomId)).param("size", "1"))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].context.id").value(3))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        long newestId = json.readTree(newest).path("data").get(0).path("messageId").asLong();

        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", roomId)).param("before", String.valueOf(newestId)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].context.id").value(2))
                .andExpect(jsonPath("$.data[1].context.id").value(1));
    }

    @Test
    @DisplayName("상대 이름: 구매자엔 스토어명 · 판매자엔 가린 구매자 이름 · 관리자엔 스토어명/회원 이름, 스토어 · 도움 요청 카드 제목에 스토어명")
    void counterpartNamesAndCardTitles() throws Exception {
        given(directory.storeName(STORE_ID)).willReturn("리넨하우스");
        given(directory.userName(BUYER_ID)).willReturn("김지연");
        long storeRoom = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}")
                .andExpect(jsonPath("$.data.counterpart.name").value("리넨하우스")));
        mockMvc.perform(seller(get("/api/chats/rooms")).param("type", "STORE_INQUIRY"))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("김*연"));

        open(admin(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\",\"storeId\":5,\"context\":{\"type\":\"STORE\",\"id\":5}}")
                .andExpect(jsonPath("$.data.counterpart.name").value("리넨하우스"))
                .andExpect(jsonPath("$.data.lastMessage.preview").value("스토어 리넨하우스"));
        open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"CHAT_ROOM\",\"id\":" + storeRoom + "}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("스토어 톡 #" + storeRoom + " (리넨하우스)"));
        mockMvc.perform(admin(get("/api/chats/rooms")).param("type", "BUYER_SUPPORT"))
                .andExpect(jsonPath("$.data[0].counterpart.name").value("김지연"));
    }

    @Test
    @DisplayName("D-1: 정지 · 폐점 스토어엔 새 스토어 톡 409 CHAT_STORE_INACTIVE, 기존 방은 보기만 되고 보내면 CHAT_STORE_INACTIVE")
    void inactiveStoreBlocksNewRoomsAndSending(@Autowired ChatRoomService chatRoomService) throws Exception {
        long existing = id(open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}"));
        given(directory.storeOpen(STORE_ID)).willReturn(false);

        open(buyer(OTHER_BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHAT_STORE_INACTIVE"));
        open(buyer(BUYER_ID), "{\"type\":\"STORE_INQUIRY\",\"storeId\":5}").andExpect(status().isOk()); // 기존 방은 열림
        mockMvc.perform(buyer(BUYER_ID, get("/api/chats/rooms/{id}/messages", existing))).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> chatRoomService.send(new AuthUser(BUYER_ID, List.of("BUYER"), null), existing, "안녕하세요"))
                .hasMessageContaining("운영 중이 아닌");
    }

    @Test
    @DisplayName("주문 카드는 주문번호 모양으로, 정산 카드는 주차 · 지급액 — 정산은 판매자 상담 · 그 스토어 것만")
    void orderAndSettlementCards() throws Exception {
        open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"ORDER\",\"id\":20261027000001}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("주문 20261027-000001"));
        open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"ORDER_ITEM\",\"id\":7}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("상품주문 #7"));

        given(directory.settlement(31L)).willReturn(new ChatDirectory.SettlementInfo(STORE_ID, "2026-W43", 1_234_000));
        given(directory.settlement(32L)).willReturn(new ChatDirectory.SettlementInfo(99L, "2026-W43", 10));
        open(seller(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\",\"context\":{\"type\":\"SETTLEMENT\",\"id\":31}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("정산 2026-W43 · 1,234,000원"));
        open(seller(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\",\"context\":{\"type\":\"SETTLEMENT\",\"id\":32}}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        open(seller(post("/api/chats/rooms")), "{\"type\":\"SELLER_SUPPORT\",\"context\":{\"type\":\"SETTLEMENT\",\"id\":33}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("정산 #33"));   // 조회 실패 — 번호
        open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"SETTLEMENT\",\"id\":31}}")
                .andExpect(status().isBadRequest());
        open(buyer(BUYER_ID), "{\"type\":\"BUYER_SUPPORT\",\"context\":{\"type\":\"ORDER\",\"id\":123}}")
                .andExpect(jsonPath("$.data.lastMessage.preview").value("주문 #123"));   // 주문번호 모양이 아니면 번호
    }

    private ResultActions open(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions patchStatus(MockHttpServletRequestBuilder request, String status) throws Exception {
        return mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    private long id(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data").path("roomId").asLong();
    }

    private MockHttpServletRequestBuilder buyer(long userId) {
        return buyer(userId, post("/api/chats/rooms"));
    }

    private static MockHttpServletRequestBuilder buyer(long userId, MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", userId).header("X-User-Roles", "BUYER");
    }

    /** 판매자도 BUYER 역할을 함께 가진다 (판매자 전환 때 SELLER 추가) */
    private static MockHttpServletRequestBuilder seller(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", SELLER_ID).header("X-User-Roles", "BUYER,SELLER").header("X-Store-Id", STORE_ID);
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN");
    }
}
