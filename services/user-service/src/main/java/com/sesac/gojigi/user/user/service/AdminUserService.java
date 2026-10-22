package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserSummaryResponse;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 관리자 회원 관리 — admin-service 가 /internal/users 로 호출한다 (관리자 인가는 gateway · admin-service 에서 끝남).
 * 탈퇴 회원은 목록 · 상세 모두 제외.
 */
@Service
@RequiredArgsConstructor
public class AdminUserService {
    private final UserRepository userRepository;

    /** 회원 목록 — 서버 페이징, 최신 가입순. */
    public PageResponse<AdminUserSummaryResponse> search(String q, UserStatus status, int page, int size) {
        return PageResponse.from(userRepository
                .searchForAdmin(status, containsPattern(q), PageRequest.of(page, size))
                .map(AdminUserSummaryResponse::from));
    }

    /** 회원 상세 — 없는 회원 · 탈퇴 회원은 USER_NOT_FOUND */
    public AdminUserDetailResponse getDetail(Long userId) {
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .map(AdminUserDetailResponse::from)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));
    }

    /** LIKE '%q%' — 검색어의 % · _ 를 와일드카드가 아닌 글자로 (escape 문자 '!', UserRepository.searchForAdmin) */
    private static String containsPattern(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String escaped = q.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped + "%";
    }
}
