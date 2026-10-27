package com.sesac.gojigi.chat.room.repository;

import com.sesac.gojigi.chat.room.entity.ChatRead;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatReadRepository extends JpaRepository<ChatRead, ChatRead.Key> {

    /** 읽은 위치는 앞으로만 — 늦게 도착한 이전 읽음 요청이 되돌리지 않게 GREATEST */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO chat_read (chat_room_id, reader_key, last_read_message_id) VALUES (:roomId, :readerKey, :messageId)
            ON DUPLICATE KEY UPDATE last_read_message_id = GREATEST(last_read_message_id, VALUES(last_read_message_id))""")
    void markRead(@Param("roomId") Long roomId, @Param("readerKey") String readerKey, @Param("messageId") Long messageId);

    /** 보관 삭제 끝 — ChatRoomRepository.deleteEmptyOlderThan 이 지울 방의 읽음 위치 */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE r FROM chat_read r JOIN chat_room c ON c.chat_room_id = r.chat_room_id
            WHERE COALESCE(c.last_message_at, c.created_at) < :cutoff
              AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.chat_room_id = c.chat_room_id)""")
    int deleteOfEmptyRoomsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
