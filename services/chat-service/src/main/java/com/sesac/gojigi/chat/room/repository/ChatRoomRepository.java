package com.sesac.gojigi.chat.room.repository;

import com.sesac.gojigi.chat.room.entity.ChatRoom;
import com.sesac.gojigi.chat.room.entity.ChatRoomType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    Optional<ChatRoom> findByTypeAndBuyerIdAndStoreId(ChatRoomType type, Long buyerId, Long storeId);

    /** 구매자 쪽 방 — 스토어 톡 · 고객 상담 */
    List<ChatRoom> findByBuyerIdAndTypeIn(Long buyerId, Collection<ChatRoomType> types);

    /** 판매자 쪽 방 — 스토어 단위 */
    List<ChatRoom> findByStoreIdAndTypeIn(Long storeId, Collection<ChatRoomType> types);

    /** 관리자 쪽 방 — 상담방 2종 전체 */
    List<ChatRoom> findByTypeIn(Collection<ChatRoomType> types);

    /** 보관 삭제 끝 — 마지막 메시지가 기준보다 오래됐고 메시지가 하나도 남지 않은 방 (chat_read 를 먼저 지운다) */
    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            DELETE FROM chat_room
            WHERE COALESCE(last_message_at, created_at) < :cutoff
              AND NOT EXISTS (SELECT 1 FROM chat_message m WHERE m.chat_room_id = chat_room.chat_room_id)""")
    int deleteEmptyOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
