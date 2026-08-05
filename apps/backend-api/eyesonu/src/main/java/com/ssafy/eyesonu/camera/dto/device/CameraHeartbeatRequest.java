package com.ssafy.eyesonu.camera.dto.device;

import com.ssafy.eyesonu.recording.dto.MicrosecondOffsetDateTimeDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CameraHeartbeatRequest(
        @NotNull
        @Schema(
                description = "Pi5가 카메라 상태를 관측한 RFC 3339 날짜·시간입니다. UTC 오프셋은 필수이고 소수 초는 최대 6자리입니다.",
                example = "2026-07-20T02:00:00Z")
        @JsonDeserialize(using = MicrosecondOffsetDateTimeDeserializer.class)
        OffsetDateTime occurredAt,
        @NotBlank
        @Schema(
                description = "장치가 보고한 상태입니다. OFFLINE은 중앙 서버의 timeout 작업으로만 판정합니다.",
                allowableValues = {"ONLINE", "ERROR"},
                example = "ONLINE")
        String status,
        @Schema(
                description = "선택 사항인 장치 진단 상세 정보입니다. 데이터베이스에는 저장하지 않습니다.",
                example = "null")
        String detail) {
}
