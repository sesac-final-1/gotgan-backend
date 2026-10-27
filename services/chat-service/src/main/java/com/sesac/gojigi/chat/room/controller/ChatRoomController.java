package com.sesac.gojigi.chat.room.controller;

import com.sesac.gojigi.chat.room.dto.ChatRequests;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Message;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Opened;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Room;
import com.sesac.gojigi.chat.room.dto.ChatResponses.UnreadCount;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.service.ChatRoomService;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.auth.CurrentUser;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 1:1 채팅 REST — 게이트웨이 /api/chats/** → chat-service, 로그인한 누구나. 방 단위 권한은 서비스가 (남의 방 404) */
@Tag(name = "채팅", description = "스토어 톡 · 판매자 운영 상담 · 고객센터 1:1 상담 (docs/api/chat.md)")
@RestController
@RequestMapping("/api/chats")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @Operation(summary = "방 열기", description = """
            {type, storeId?, context?: {type, id}} → 처음이면 201 · 있던 방이면 200 (문맥 카드만 추가).
            403 CHAT_NOT_ALLOWED · 400 CHAT_SELF · INVALID_INPUT(storeId 없음)""")
    @PostMapping("/rooms")
    public ResponseEntity<ApiResponse<Room>> open(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                  @Valid @RequestBody ChatRequests.OpenRoom request) {
        Opened opened = chatRoomService.open(user, request);
        return ResponseEntity.status(opened.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success("채팅방을 열었습니다.", opened.room()));
    }

    @Operation(summary = "내 방 목록", description = "최근 메시지순, 페이징 없음. status 는 상담방에만")
    @GetMapping("/rooms")
    public ResponseEntity<ApiResponse<List<Room>>> rooms(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                         @RequestParam(required = false) ChatRoomType type,
                                                         @RequestParam(required = false) ChatRoomStatus status) {
        return ResponseEntity.ok(ApiResponse.success("채팅방 목록을 조회했습니다.", chatRoomService.rooms(user, type, status)));
    }

    @Operation(summary = "메시지", description = "최신 → 과거, before(messageId) 보다 이전. 남의 방 404 CHAT_ROOM_NOT_FOUND")
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<List<Message>>> messages(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                               @PathVariable Long roomId,
                                                               @RequestParam(required = false) Long before,
                                                               @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(ApiResponse.success("메시지를 조회했습니다.", chatRoomService.messages(user, roomId, before, size)));
    }

    @Operation(summary = "읽음", description = "{lastMessageId} 까지 읽음 → 204")
    @PatchMapping("/rooms/{roomId}/read")
    public ResponseEntity<Void> read(@Parameter(hidden = true) @CurrentUser AuthUser user, @PathVariable Long roomId,
                                     @Valid @RequestBody ChatRequests.Read request) {
        chatRoomService.markRead(user, roomId, request.lastMessageId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "안 읽은 수", description = "헤더 '톡' 배지 — 판매자는 스토어 단위, 관리자는 상담방 전체")
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCount>> unreadCount(@Parameter(hidden = true) @CurrentUser AuthUser user) {
        return ResponseEntity.ok(ApiResponse.success("안 읽은 수를 조회했습니다.", new UnreadCount(chatRoomService.unreadCount(user))));
    }

    @Operation(summary = "상담 상태 (ADMIN)", description = "{status: OPEN/RESOLVED} — 상담방만, 스토어 톡은 400 CHAT_INVALID_TYPE")
    @PatchMapping("/rooms/{roomId}/status")
    public ResponseEntity<ApiResponse<Room>> changeStatus(@Parameter(hidden = true) @CurrentUser AuthUser user,
                                                          @PathVariable Long roomId,
                                                          @Valid @RequestBody ChatRequests.Status request) {
        return ResponseEntity.ok(ApiResponse.success("상담 상태를 바꿨습니다.", chatRoomService.changeStatus(user, roomId, request.status())));
    }
}
