package com.sesac.gojigi.admin.system.dto.request;

import jakarta.validation.constraints.Size;

/** 시나리오 내리기 — reason 선택(비우면 시나리오 이름으로 기록) */
public record ScenarioRequest(@Size(max = 255) String reason) {
}
