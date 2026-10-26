package com.sesac.gojigi.admin.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * created_at · updated_at 의 주인은 DB (DEFAULT CURRENT_TIMESTAMP(6) · ON UPDATE). 엔티티는 @Generated 로 읽기만 한다.
 * JPA Auditing(@CreatedDate · @EnableJpaAuditing) 은 쓰지 않는다 (컨벤션 3-5).
 */
@Getter
@MappedSuperclass
public abstract class BaseTimeEntity {

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    @Generated(event = { EventType.INSERT, EventType.UPDATE })
    @Column(name = "updated_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime updatedAt;
}
