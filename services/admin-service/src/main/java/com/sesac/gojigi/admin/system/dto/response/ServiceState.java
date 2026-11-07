package com.sesac.gojigi.admin.system.dto.response;

/**
 * 서비스 · 기능 상태 — UP: 원하는 수만큼 Ready · DEGRADED: 일부만 Ready(동작은 함) · DOWN: Ready 0개 ·
 * RECOVERING: 내린 뒤 일부가 다시 Ready(새 파드 기동 중). 기능 상태는 UP · DEGRADED · DOWN 만
 */
public enum ServiceState {
    UP,
    DEGRADED,
    DOWN,
    RECOVERING
}
