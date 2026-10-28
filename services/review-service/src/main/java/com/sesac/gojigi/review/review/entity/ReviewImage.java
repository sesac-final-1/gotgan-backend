package com.sesac.gojigi.review.review.entity;

import com.sesac.gojigi.review.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 리뷰 사진 — 리뷰당 최대 3장 · 10MB (REV-03). review_image → review 단방향.
 * 파일은 브라우저가 presigned PUT 으로 S3 에 직접 올리고, 여기에는 키 · 순서만 저장한다 (크기 제한은 presign 서명이 막음, V5).
 */
@Getter
@Entity
@Table(name = "review_image")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewImage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_image_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false, updatable = false)
    private Review review;

    @Column(name = "image_key", nullable = false, length = 300)
    private String imageKey;

    @Column(name = "sort_order", nullable = false, columnDefinition = "INT UNSIGNED")
    private int sortOrder;

    public ReviewImage(Review review, String imageKey, int sortOrder) {
        this.review = review;
        this.imageKey = imageKey;
        this.sortOrder = sortOrder;
    }
}
