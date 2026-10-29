package com.sesac.gojigi.chat.room.service;

import com.sesac.gojigi.chat.directory.ChatDirectory;
import com.sesac.gojigi.chat.room.dto.ChatRequests;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Counterpart;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Message;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Opened;
import com.sesac.gojigi.chat.room.dto.ChatResponses.Room;
import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomStatus;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import com.sesac.gojigi.chat.room.entity.ContextType;
import com.sesac.gojigi.chat.room.entity.MessageKind;
import com.sesac.gojigi.chat.room.entity.SenderRole;
import com.sesac.gojigi.chat.room.exception.ChatErrorCode;
import com.sesac.gojigi.chat.room.exception.ChatException;
import com.sesac.gojigi.chat.room.repository.ChatMessageRepository;
import com.sesac.gojigi.chat.room.repository.ChatReadRepository;
import com.sesac.gojigi.chat.room.repository.ChatRoomRepository;
import com.sesac.gojigi.common.web.auth.AuthUser;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 방 열기 · 목록 · 메시지 · 읽음 · 상담 상태 (chat.md 1~5절) + 실시간 전송(6절, STOMP SEND → send).
 * 저장 · 읽음 · 상태 변경은 ChatEvents 로 알리고, 커밋 뒤 realtime 이 구독자에게 전달 · 알림을 보낸다.
 * 남의 방은 404(있는지 알려 주지 않음). 상대 이름은 user · store 조회(ChatDirectory, 실패하면 번호), 관리자 쪽은 "곳간 운영팀" 고정(D-2).
 * 정지 · 폐점 스토어는 새 스토어 톡을 막고 기존 방은 읽기만(D-1).
 * user · store · product · settlement 조회(ChatDirectory — HTTP)는 트랜잭션 밖에서, 저장만 TransactionTemplate 으로 짧게 — 상대 서비스가 느려도 DB 커넥션을 잡고 있지 않게
 */
@Service
@RequiredArgsConstructor
public class ChatRoomService {

    static final String ADMIN_NAME = "곳간 운영팀";
    static final String RESOLVED_NOTICE = "상담이 종료됐어요. 더 궁금한 점이 있으면 다시 메시지를 보내 주세요";
    private static final String BUYER = "BUYER";
    private static final String SELLER = "SELLER";
    private static final String ADMIN = "ADMIN";
    private static final int MAX_PAGE_SIZE = 100;
    static final int MAX_CONTENT_LENGTH = 1000;   // chat_message.content VARCHAR(1000)
    private static final int ORDER_NO_DIGITS = 14;  // yyyyMMdd + 일련번호 6자리 (order OrderTxService.nextOrderNo)
    private static final Pattern EXTRA_NEWLINES = Pattern.compile("\\n{3,}");
    private static final List<ChatRoomType> BUYER_TYPES = List.of(ChatRoomType.STORE_INQUIRY, ChatRoomType.BUYER_SUPPORT);
    private static final List<ChatRoomType> SELLER_TYPES = List.of(ChatRoomType.STORE_INQUIRY, ChatRoomType.SELLER_SUPPORT);
    private static final List<ChatRoomType> SUPPORT_TYPES = List.of(ChatRoomType.SELLER_SUPPORT, ChatRoomType.BUYER_SUPPORT);

    private final ChatRoomRepository roomRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatReadRepository readRepository;
    private final ApplicationEventPublisher events;
    private final ChatDirectory directory;
    private final TransactionTemplate transactions;

    /** 1절 — 같은 방이 있으면 그 방(문맥 카드만 추가), 없으면 새로. 스토어 상태 · 카드 제목 조회는 먼저, 방 · 카드 저장만 트랜잭션 */
    public Opened open(AuthUser user, ChatRequests.OpenRoom request) {
        Opening opening = opening(user, request);
        ChatRoom found = roomRepository.findByTypeAndBuyerIdAndStoreId(request.type(), opening.buyerId(), opening.storeId())
                .orElse(null);
        if (found == null && request.type() == ChatRoomType.STORE_INQUIRY && !directory.storeOpen(opening.storeId())) {
            throw new ChatException(ChatErrorCode.CHAT_STORE_INACTIVE);
        }
        String title = request.context() == null ? null : contextTitle(request.type(), opening.storeId(), user, request.context());
        ChatRoom room = transactions.execute(tx -> {
            ChatRoom saved = found != null ? load(found.getId())
                    : roomRepository.save(ChatRoom.open(request.type(), opening.buyerId(), opening.storeId()));
            if (title != null) {
                appendContext(saved, user, opening.seat(), request.context(), title);
            }
            return saved;
        });
        return new Opened(view(room, opening.seat()), found == null);
    }

    /** 2절 — 내가 볼 수 있는 방 전체, 최근 메시지순 */
    public List<Room> rooms(AuthUser user, ChatRoomType type, ChatRoomStatus status) {
        Map<Long, Seated> seated = new LinkedHashMap<>();
        if (user.hasRole(BUYER)) {
            roomRepository.findByBuyerIdAndTypeIn(user.userId(), BUYER_TYPES)
                    .forEach(r -> seated.putIfAbsent(r.getId(), new Seated(r, ChatSeat.buyer(user.userId()))));
        }
        if (user.hasRole(SELLER) && user.storeId() != null) {
            roomRepository.findByStoreIdAndTypeIn(user.storeId(), SELLER_TYPES)
                    .forEach(r -> seated.putIfAbsent(r.getId(), new Seated(r, ChatSeat.seller(user.storeId()))));
        }
        if (user.hasRole(ADMIN)) {
            roomRepository.findByTypeIn(SUPPORT_TYPES)
                    .forEach(r -> seated.putIfAbsent(r.getId(), new Seated(r, ChatSeat.admin(false))));
        }
        List<Seated> visible = seated.values().stream()
                .filter(s -> type == null || s.room().getType() == type)
                .filter(s -> status == null || s.room().getStatus() == status)
                .sorted(Comparator.comparing((Seated s) -> recentAt(s.room())).reversed())
                .toList();
        return views(visible);
    }

    /** 4절 — 헤더 '톡' 배지 */
    public long unreadCount(AuthUser user) {
        return rooms(user, null, null).stream().mapToLong(Room::unreadCount).sum();
    }

    /** 3절 — 최신 → 과거, before 보다 이전 */
    public List<Message> messages(AuthUser user, Long roomId, Long before, int size) {
        Seated seated = seated(user, roomId);
        int limit = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return messageRepository.findByChatRoomIdAndIdLessThanOrderByIdDesc(roomId, before == null ? Long.MAX_VALUE : before,
                        Limit.of(limit)).stream()
                .map(m -> Message.of(m, seated.seat().role()))
                .toList();
    }

    /** 4절 — 그 메시지까지 읽음. 방의 마지막 메시지를 넘지 않게 · 읽기 전용 열람은 기록하지 않음 */
    @Transactional
    public void markRead(AuthUser user, Long roomId, Long lastMessageId) {
        Seated seated = seated(user, roomId);
        Long last = seated.room().getLastMessageId();
        if (seated.seat().readOnly() || last == null) {
            return;
        }
        long readTo = Math.min(lastMessageId, last);
        readRepository.markRead(roomId, seated.seat().readerKey(), readTo);
        events.publishEvent(new ChatEvents.ReadMoved(roomId, seated.seat().role(), readTo));
    }

    /**
     * 6절 SEND — 앞뒤 공백 제거 · 개행 3줄 이상은 2줄로, 1~1,000자. 읽기 전용 열람이면 CHAT_NOT_ALLOWED.
     * 닫힌 상담방에 관리자가 아닌 쪽이 보내면 다시 OPEN. 스토어 상태 조회는 먼저, 저장만 트랜잭션
     */
    public void send(AuthUser user, Long roomId, String content) {
        Seated seated = seated(user, roomId);
        if (seated.seat().readOnly()) {
            throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
        }
        String text = normalize(content);
        if (seated.room().getType() == ChatRoomType.STORE_INQUIRY && !directory.storeOpen(seated.room().getStoreId())) {
            throw new ChatException(ChatErrorCode.CHAT_STORE_INACTIVE);   // D-1 — 기존 방은 읽기만
        }
        transactions.executeWithoutResult(tx -> {
            ChatRoom room = load(roomId);
            if (room.getStatus() == ChatRoomStatus.RESOLVED && seated.seat().role() != SenderRole.ADMIN) {
                room.changeStatus(ChatRoomStatus.OPEN);
                events.publishEvent(new ChatEvents.StatusChanged(roomId, ChatRoomStatus.OPEN));
            }
            ChatMessage message = messageRepository.save(ChatMessage.text(roomId, user.userId(), seated.seat().role(), text));
            room.touch(message);
            events.publishEvent(new ChatEvents.MessageSaved(room, message));
        });
    }

    /** 6절 SUBSCRIBE — 방 멤버(관리자 읽기 전용 열람 포함)만. 아니면 404 */
    public ChatSeat seat(AuthUser user, Long roomId) {
        return seated(user, roomId).seat();
    }

    /** 5절 — 관리자만 · 상담방만. RESOLVED 로 바꾸면 SYSTEM 안내. 응답의 상대 이름 조회는 커밋 뒤 */
    public Room changeStatus(AuthUser user, Long roomId, ChatRoomStatus status) {
        if (!user.hasRole(ADMIN)) {
            throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
        }
        ChatRoom room = transactions.execute(tx -> {
            ChatRoom changed = load(roomId);
            if (!changed.getType().isSupport()) {
                throw new ChatException(ChatErrorCode.CHAT_INVALID_TYPE);
            }
            if (changed.getStatus() != status) {
                changed.changeStatus(status);
                events.publishEvent(new ChatEvents.StatusChanged(roomId, status));
                if (status == ChatRoomStatus.RESOLVED) {
                    ChatMessage notice = messageRepository.save(ChatMessage.system(changed.getId(), RESOLVED_NOTICE));
                    changed.touch(notice);
                    events.publishEvent(new ChatEvents.MessageSaved(changed, notice));
                }
            }
            return changed;
        });
        return view(room, ChatSeat.admin(false));
    }

    /** 방 종류 · 역할로 같은 방 기준(buyerId · storeId)과 내 자리를 정한다 (1절 표) */
    private Opening opening(AuthUser user, ChatRequests.OpenRoom request) {
        return switch (request.type()) {
            case STORE_INQUIRY -> {
                if (!user.hasRole(BUYER)) {
                    throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
                }
                Long storeId = required(request.storeId());
                if (storeId.equals(user.storeId())) {
                    throw new ChatException(ChatErrorCode.CHAT_SELF);
                }
                yield new Opening(user.userId(), storeId, ChatSeat.buyer(user.userId()));
            }
            case SELLER_SUPPORT -> {
                if (user.hasRole(ADMIN)) {
                    yield new Opening(ChatRoom.NONE, required(request.storeId()), ChatSeat.admin(false));
                }
                if (!user.hasRole(SELLER) || user.storeId() == null) {
                    throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
                }
                yield new Opening(ChatRoom.NONE, user.storeId(), ChatSeat.seller(user.storeId()));
            }
            case BUYER_SUPPORT -> {
                if (!user.hasRole(BUYER)) {
                    throw new ChatException(ChatErrorCode.CHAT_NOT_ALLOWED);
                }
                yield new Opening(user.userId(), ChatRoom.NONE, ChatSeat.buyer(user.userId()));
            }
        };
    }

    /** 문맥 카드 제목 (트랜잭션 밖 — 이름 조회). CHAT_ROOM 은 고객 상담에만, 내 스토어 톡만 */
    private String contextTitle(ChatRoomType roomType, Long storeId, AuthUser user, ChatRequests.Context context) {
        String title = context.type().fallbackTitle(context.id());
        if (context.type() == ContextType.CHAT_ROOM) {
            if (roomType != ChatRoomType.BUYER_SUPPORT) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT, "스토어 톡 도움 요청은 고객센터 상담에서만 할 수 있습니다.");
            }
            ChatRoom inquiry = roomRepository.findById(context.id())
                    .filter(r -> r.getType() == ChatRoomType.STORE_INQUIRY && r.getBuyerId().equals(user.userId()))
                    .orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
            String storeName = directory.storeName(inquiry.getStoreId());
            title = storeName == null ? title : title + " (" + storeName + ")";
        } else if (context.type() == ContextType.STORE) {
            String storeName = directory.storeName(context.id());
            title = storeName == null ? title : "스토어 " + storeName;
        } else if (context.type() == ContextType.PRODUCT) {
            String productName = directory.productName(context.id());
            title = productName == null ? title : productName;
        } else if (context.type() == ContextType.ORDER) {
            title = "주문 " + orderNo(context.id());
        } else if (context.type() == ContextType.SETTLEMENT) {
            title = settlementTitle(roomType, storeId, context.id(), title);
        }
        return title;
    }

    /** 문맥 카드 저장 — 마지막 카드와 같은 대상이면 생략 */
    private void appendContext(ChatRoom room, AuthUser user, ChatSeat seat, ChatRequests.Context context, String title) {
        boolean same =messageRepository.findFirstByChatRoomIdAndKindOrderByIdDesc(room.getId(), MessageKind.CONTEXT)
                .filter(last -> last.sameContext(context.type(), context.id()))
                .isPresent();
        if (!same) {
            ChatMessage card = ChatMessage.context(room.getId(), user.userId(), seat.role(), context.type(), context.id(), title);
            room.touch(messageRepository.save(card));
            events.publishEvent(new ChatEvents.MessageSaved(room, card));
        }
    }

    private ChatRoom load(Long roomId) {
        return roomRepository.findById(roomId).orElseThrow(() -> new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND));
    }

    /** 방 + 내 자리 — 자리가 없으면 404 */
    private Seated seated(AuthUser user, Long roomId) {
        ChatRoom room = load(roomId);
        ChatSeat seat = seatOf(room, user);
        if (seat == null) {
            throw new ChatException(ChatErrorCode.CHAT_ROOM_NOT_FOUND);
        }
        return new Seated(room, seat);
    }

    /** 구매자 → 판매자(내 스토어) → 관리자 순. 관리자는 스토어 톡을 '도움 요청'된 것만 읽기 전용으로 */
    private ChatSeat seatOf(ChatRoom room, AuthUser user) {
        ChatRoomType type = room.getType();
        if (type != ChatRoomType.SELLER_SUPPORT && room.getBuyerId().equals(user.userId())) {
            return ChatSeat.buyer(user.userId());
        }
        if (type != ChatRoomType.BUYER_SUPPORT && user.hasRole(SELLER) && room.getStoreId().equals(user.storeId())) {
            return ChatSeat.seller(user.storeId());
        }
        if (user.hasRole(ADMIN)) {
            if (type.isSupport()) {
                return ChatSeat.admin(false);
            }
            if (messageRepository.existsByContextTypeAndContextId(ContextType.CHAT_ROOM, room.getId())) {
                return ChatSeat.admin(true);
            }
        }
        return null;
    }

    private Room view(ChatRoom room, ChatSeat seat) {
        return views(List.of(new Seated(room, seat))).getFirst();
    }

    /** 미리보기 · 안 읽은 수를 자리(readerKey)별 한 번의 쿼리로 */
    private List<Room> views(List<Seated> seatedRooms) {
        List<Long> lastIds = seatedRooms.stream().map(s -> s.room().getLastMessageId()).filter(Objects::nonNull).toList();
        Map<Long, ChatMessage> lasts = messageRepository.findAllById(lastIds).stream()
                .collect(Collectors.toMap(ChatMessage::getId, Function.identity()));
        Map<Long, Long> unread = new HashMap<>();
        seatedRooms.stream()
                .filter(s -> s.room().getLastMessageId() != null)
                .collect(Collectors.groupingBy(Seated::seat, Collectors.mapping(s -> s.room().getId(), Collectors.toList())))
                .forEach((seat, roomIds) -> messageRepository.countUnread(roomIds, seat.readerKey(), seat.role().name())
                        .forEach(c -> unread.put(c.getRoomId(), c.getCount())));
        List<Room> views = new ArrayList<>();
        for (Seated s : seatedRooms) {
            ChatRoom room = s.room();
            views.add(Room.of(room, counterpart(room, s.seat().role()), lasts.get(room.getLastMessageId()),
                    unread.getOrDefault(room.getId(), 0L)));
        }
        return views;
    }

    /**
     * 상대 표시 (1절) — 구매자에겐 스토어명, 판매자에겐 가린 구매자 이름(김*연), 관리자에겐 스토어명 · 회원 이름, 관리자 쪽은 "곳간 운영팀".
     * 이름을 못 받으면 번호("스토어 #5" · "회원 #10")
     */
    private Counterpart counterpart(ChatRoom room, SenderRole viewer) {
        return switch (viewer) {
            case BUYER -> room.getType() == ChatRoomType.STORE_INQUIRY
                    ? new Counterpart(storeLabel(room.getStoreId()), SenderRole.SELLER)
                    : new Counterpart(ADMIN_NAME, SenderRole.ADMIN);
            case SELLER -> room.getType() == ChatRoomType.STORE_INQUIRY
                    ? new Counterpart(userLabel(room.getBuyerId(), true), SenderRole.BUYER)
                    : new Counterpart(ADMIN_NAME, SenderRole.ADMIN);
            default -> room.getType() == ChatRoomType.SELLER_SUPPORT
                    ? new Counterpart(storeLabel(room.getStoreId()), SenderRole.SELLER)
                    : new Counterpart(userLabel(room.getBuyerId(), false), SenderRole.BUYER);
        };
    }

    private String storeLabel(Long storeId) {
        String name = directory.storeName(storeId);
        return name != null ? name : "스토어 #" + storeId;
    }

    private String userLabel(Long userId, boolean masked) {
        String name = directory.userName(userId);
        if (name == null) {
            return "회원 #" + userId;
        }
        return masked ? ChatDirectory.mask(name) : name;
    }

    private static LocalDateTime recentAt(ChatRoom room) {
        return room.getLastMessageAt() != null ? room.getLastMessageAt() : room.getCreatedAt();
    }

    /** 주문 카드 id = 주문번호의 숫자(20261027-000001 → 20261027000001) — 제목에선 다시 주문번호 모양으로 */
    static String orderNo(Long id) {
        String digits = String.valueOf(id);
        return digits.length() == ORDER_NO_DIGITS ? digits.substring(0, 8) + "-" + digits.substring(8) : "#" + digits;
    }

    /** 정산 카드 — 판매자 운영 상담에만 · 그 스토어 정산만. 제목 "정산 2026-W43 · 1,234,000원", 조회 실패면 번호 */
    private String settlementTitle(ChatRoomType roomType, Long storeId, Long settlementId, String fallback) {
        if (roomType != ChatRoomType.SELLER_SUPPORT) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "정산 문의는 판매자 운영 상담에서만 할 수 있습니다.");
        }
        ChatDirectory.SettlementInfo info = directory.settlement(settlementId);
        if (info == null) {
            return fallback;
        }
        if (!info.storeId().equals(storeId)) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "이 스토어의 정산만 문의할 수 있습니다.");
        }
        return "정산 " + info.week() + " · " + String.format("%,d원", info.payoutAmt());
    }

    private static String normalize(String content) {
        String text = content == null ? "" : EXTRA_NEWLINES.matcher(content.replace("\r\n", "\n")).replaceAll("\n\n").strip();
        if (text.isEmpty() || text.length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "메시지는 1~" + MAX_CONTENT_LENGTH + "자로 보내 주세요.");
        }
        return text;
    }

    private static Long required(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "storeId 가 필요합니다.");
        }
        return storeId;
    }

    private record Opening(Long buyerId, Long storeId, ChatSeat seat) {
    }

    private record Seated(ChatRoom room, ChatSeat seat) {
    }
}
