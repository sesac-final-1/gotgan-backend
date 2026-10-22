package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.dto.response.AdminUserSummaryResponse;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * 실제 MySQL — 관리자 회원 검색(이름 · 이메일 LIKE, 상태 필터, 탈퇴 제외, 최신순 페이징)과 상세.
 * 다른 테스트가 남긴 회원과 섞이지 않도록 테스트마다 고유 태그를 이름 · 이메일에 넣고 그 태그로 검색한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, AdminUserService.class })
@ActiveProfiles("test")
class AdminUserServiceIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;
    @Autowired
    private UserRepository userRepository;

    private static String tag() {
        return "t" + System.nanoTime();
    }

    private User save(String email, String name) {
        return userRepository.saveAndFlush(User.signUp(email, name, null, "010-1234-5678"));
    }

    private static List<String> names(PageResponse<AdminUserSummaryResponse> page) {
        return page.content().stream().map(AdminUserSummaryResponse::name).toList();
    }

    @Test
    @DisplayName("검색어는 이름 또는 이메일 부분 일치 — 이메일은 대소문자 무시")
    void search_matchesNameOrEmail() {
        String tag = tag();
        save("a-" + tag + "@gotgan.com", "이메일만");                 // 이메일에 태그
        save("plain-" + System.nanoTime() + "@gotgan.com", "김" + tag);   // 이름에 태그
        save("other-" + System.nanoTime() + "@gotgan.com", "무관");

        assertThat(names(adminUserService.search(tag, null, 0, 20))).containsExactlyInAnyOrder("이메일만", "김" + tag);
        assertThat(names(adminUserService.search("  A-" + tag.toUpperCase() + "@GOTGAN ", null, 0, 20)))
                .containsExactly("이메일만");
    }

    @Test
    @DisplayName("상태 필터 · 탈퇴 회원 제외")
    void search_filtersStatusAndExcludesDeleted() {
        String tag = tag();
        save(tag + "-active@gotgan.com", "정상");
        User suspended = save(tag + "-suspended@gotgan.com", "정지");
        suspended.suspend("허위 리뷰", LocalDateTime.now(), null);
        User deleted = save(tag + "-deleted@gotgan.com", "탈퇴");
        deleted.softDelete(LocalDateTime.now());
        userRepository.flush();

        assertThat(names(adminUserService.search(tag, null, 0, 20))).containsExactlyInAnyOrder("정상", "정지");
        assertThat(names(adminUserService.search(tag, UserStatus.SUSPENDED, 0, 20))).containsExactly("정지");
        assertThat(names(adminUserService.search(tag, UserStatus.ACTIVE, 0, 20))).containsExactly("정상");
    }

    @Test
    @DisplayName("최신 가입순 서버 페이징 — 전체 건수 · 페이지 수 · 다음 페이지 여부")
    void search_pagesLatestFirst() {
        String tag = tag();
        save(tag + "-1@gotgan.com", "첫째");
        save(tag + "-2@gotgan.com", "둘째");
        save(tag + "-3@gotgan.com", "셋째");

        PageResponse<AdminUserSummaryResponse> first = adminUserService.search(tag, null, 0, 2);
        PageResponse<AdminUserSummaryResponse> second = adminUserService.search(tag, null, 1, 2);

        assertThat(names(first)).containsExactly("셋째", "둘째");
        assertThat(first.totalElements()).isEqualTo(3);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(names(second)).containsExactly("첫째");
        assertThat(second.hasNext()).isFalse();
    }

    @Test
    @DisplayName("검색어의 % · _ 는 와일드카드가 아니라 글자 그대로")
    void search_escapesWildcards() {
        String tag = tag();
        save(tag + "-a@gotgan.com", tag + "100%할인");
        save(tag + "-b@gotgan.com", tag + "100원");
        save(tag + "-c@gotgan.com", tag + "a_b");
        save(tag + "-d@gotgan.com", tag + "axb");

        assertThat(names(adminUserService.search(tag + "100%", null, 0, 20))).containsExactly(tag + "100%할인");
        assertThat(names(adminUserService.search(tag + "a_b", null, 0, 20))).containsExactly(tag + "a_b");
    }

    @Test
    @DisplayName("빈 검색어는 조건 없음 — 전체 목록")
    void search_blankQuery_returnsAll() {
        save(tag() + "@gotgan.com", "아무나");

        assertThat(adminUserService.search("  ", null, 0, 20).totalElements())
                .isEqualTo(adminUserService.search(null, null, 0, 20).totalElements())
                .isPositive();
    }

    @Test
    @DisplayName("상세 — 연락처 · 정지 사유 · 정지 시각(+09:00), 탈퇴 · 없는 회원은 USER_NOT_FOUND")
    void getDetail() {
        String tag = tag();
        User user = save(tag + "@gotgan.com", "정지회원");
        user.suspend("결제 도용 의심", LocalDateTime.of(2026, 10, 22, 14, 0), LocalDateTime.of(2026, 10, 29, 14, 0));
        userRepository.flush();

        AdminUserDetailResponse detail = adminUserService.getDetail(user.getId());

        assertThat(detail.phone()).isEqualTo("010-1234-5678");
        assertThat(detail.status()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(detail.statusReason()).isEqualTo("결제 도용 의심");
        assertThat(detail.suspendedAt()).hasToString("2026-10-22T14:00+09:00");
        assertThat(detail.suspendedUntil()).hasToString("2026-10-29T14:00+09:00");
        assertThat(detail.joinedAt()).isNotNull();

        user.softDelete(LocalDateTime.now());
        userRepository.flush();
        for (Long id : new Long[] { user.getId(), Long.MAX_VALUE }) {
            assertThatThrownBy(() -> adminUserService.getDetail(id))
                    .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }
    }
}
