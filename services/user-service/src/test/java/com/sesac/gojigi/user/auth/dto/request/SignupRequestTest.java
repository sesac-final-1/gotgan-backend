package com.sesac.gojigi.user.auth.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class SignupRequestTest {

    private static jakarta.validation.ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static Set<String> violatedFields(SignupRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    @DisplayName("올바른 입력은 통과")
    void validate_validRequest_passes() {
        assertThat(violatedFields(new SignupRequest("new1@gotgan.com", "Newbie!pass1", "새회원", "010-1234-5678"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = { "abcdefgh1", "12345678!", "Ab!1", "        " })
    @DisplayName("비밀번호 — 8자 미만 · 영문 없음 · 특수문자 없음 · 공백만이면 거절 (realm 정책과 같게)")
    void validate_weakPassword_rejected(String password) {
        assertThat(violatedFields(new SignupRequest("new1@gotgan.com", password, "새회원", "010-1234-5678")))
                .containsExactly("password");
    }

    @ParameterizedTest
    @ValueSource(strings = { "02-123-4567", "010-12-5678", "01212345678", "010-1234-56789", "phone" })
    @DisplayName("휴대폰 — 01X 휴대폰 번호가 아니면 거절")
    void validate_invalidPhone_rejected(String phone) {
        assertThat(violatedFields(new SignupRequest("new1@gotgan.com", "Newbie!pass1", "새회원", phone)))
                .containsExactly("phone");
    }

    @Test
    @DisplayName("이메일 형식 · 이름 빈 값은 거절")
    void validate_invalidEmailAndBlankName_rejected() {
        assertThat(violatedFields(new SignupRequest("not-an-email", "Newbie!pass1", " ", "010-1234-5678")))
                .containsExactlyInAnyOrder("email", "name");
    }

    @ParameterizedTest
    @CsvSource({ "01012345678, 010-1234-5678", "010-1234-5678, 010-1234-5678", "0111234567, 011-123-4567",
            "011-123-4567, 011-123-4567" })
    @DisplayName("휴대폰은 하이픈 형식으로 정규화 — 11자리 3-4-4 · 10자리 3-3-4")
    void normalizedPhone_formatsWithHyphens(String input, String expected) {
        assertThat(new SignupRequest("a@b.com", "Newbie!pass1", "a", input).normalizedPhone()).isEqualTo(expected);
    }

    @Test
    @DisplayName("이메일은 소문자 · 공백 제거, 이름은 앞뒤 공백 제거, toString 에 비밀번호 없음")
    void normalizers_andToString() {
        SignupRequest request = new SignupRequest(" New1@Gotgan.COM ", "Newbie!pass1", " 새회원 ", "010-1234-5678");

        assertThat(request.normalizedEmail()).isEqualTo("new1@gotgan.com");
        assertThat(request.trimmedName()).isEqualTo("새회원");
        assertThat(request.toString()).doesNotContain("Newbie!pass1").contains("password=***");
    }
}
