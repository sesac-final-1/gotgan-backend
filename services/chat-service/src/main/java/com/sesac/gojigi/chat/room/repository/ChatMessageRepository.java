package com.sesac.gojigi.chat.room.repository;

import com.sesac.gojigi.chat.room.entity.ChatMessage;
import com.sesac.gojigi.chat.room.entity.ContextType;
import com.sesac.gojigi.chat.room.entity.MessageKind;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 최신 → 과거, before 보다 이전 (idx_chat_message_room) */
    List<ChatMessage> findByChatRoomIdAndIdLessThanOrderByIdDesc(Long chatRoomId, Long before, Limit limit);

    /** 마지막 문맥 카드 — 같은 문맥이면 카드를 다시 붙이지 않는다 */
    Optional<ChatMessage> findFirstByChatRoomIdAndKindOrderByIdDesc(Long chatRoomId, MessageKind kind);

    /** 스토어 톡이 '운영팀에 도움 요청'으로 상담방에 붙었는지 — 관리자 읽기 전용 열람 */
    boolean existsByContextTypeAndContextId(ContextType contextType, Long contextId);

    /** 방마다 안 읽은 수 — 같은 쪽(readerKey · 내 역할)이 보낸 것과 SYSTEM 안내는 세지 않는다 */
    @Query(nativeQuery = true, value = """
            SELECT m.chat_room_id AS roomId, COUNT(*) AS count
            FROM chat_message m
            LEFT JOIN chat_read r ON r.chat_room_id = m.chat_room_id AND r.reader_key = :readerKey
            WHERE m.chat_room_id IN (:roomIds)
              AND m.chat_message_id > COALESCE(r.last_read_message_id, 0)
              AND m.sender_role NOT IN (:senderRole, 'SYSTEM')
            GROUP BY m.chat_room_id""")
    List<RoomCount> countUnread(@Param("roomIds") Collection<Long> roomIds, @Param("readerKey") String readerKey,
            @Param("senderRole") String senderRole);

    /** 보관 삭제 한 묶음 — idx_chat_message_created 범위로 오래된 것부터 (chat.md 8-1절) */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM chat_message WHERE created_at < :cutoff ORDER BY chat_message_id LIMIT :batchSize""")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);

    interface RoomCount {
        Long getRoomId();

        long getCount();
    }
}
