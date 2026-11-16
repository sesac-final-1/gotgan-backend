package com.sesac.gojigi.admin.system.entity;

/** 부하테스트 시나리오 — script 는 k6 demo.js 의 SCENARIO 값(gotgan-config files/loadtest/demo.js) */
public enum LoadTestScenario {
    BROWSE("browse"),     // 둘러보기 (R)
    REVIEWS("reviews"),   // 리뷰 조회 폭주 (R)
    CART("cart"),         // 장바구니 CRUD — 부하용 계정 필요(「계정 준비」)
    MIXED("mixed");       // 둘러보기 70% + 장바구니 30%

    private final String script;

    LoadTestScenario(String script) {
        this.script = script;
    }

    public String script() {
        return script;
    }
}
