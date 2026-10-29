package com.sesac.gojigi.store.apply.entity;

import com.sesac.gojigi.store.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 입점 서류 (storedb application_document). 파일은 비공개 seller-docs 버킷에 있고 DB 에는 키만.
 * 재제출하면 새 행을 넣고 이전 행은 is_current = FALSE 로 남긴다 — 관리자가 이전 제출본과 비교 (store.md 2-3절).
 */
@Getter
@Entity
@Table(name = "application_document")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApplicationDocument extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "application_document_id")
    private Long id;

    @Column(name = "store_application_id", nullable = false)
    private Long storeApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 30)
    private DocType docType;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    /** S3 HeadObject 로 확인한 값 — 클라이언트가 보낸 값을 믿지 않는다 */
    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private int fileSize;

    @Column(name = "is_current", nullable = false)
    private boolean current;

    public static ApplicationDocument of(Long storeApplicationId, DocType docType, String s3Key, String originalName,
            String contentType, int fileSize) {
        ApplicationDocument document = new ApplicationDocument();
        document.storeApplicationId = storeApplicationId;
        document.docType = docType;
        document.s3Key = s3Key;
        document.originalName = originalName;
        document.contentType = contentType;
        document.fileSize = fileSize;
        document.current = true;
        return document;
    }

    /** 재제출로 같은 종류의 새 서류가 들어오면 이전 제출본으로 (행은 지우지 않음) */
    public void replace() {
        this.current = false;
    }
}
