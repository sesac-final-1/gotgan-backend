package com.sesac.gojigi.store.apply.repository;

import com.sesac.gojigi.store.apply.entity.ApplicationDocument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationDocumentRepository extends JpaRepository<ApplicationDocument, Long> {
}
