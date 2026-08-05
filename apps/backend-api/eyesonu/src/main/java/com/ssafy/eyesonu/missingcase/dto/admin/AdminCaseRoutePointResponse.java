package com.ssafy.eyesonu.missingcase.dto.admin;

import com.ssafy.eyesonu.missingcase.domain.AdminCaseRouteRow;
import com.ssafy.eyesonu.missingcase.domain.CandidateSourceType;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminCaseRoutePointResponse(
        Long candidateId,
        CandidateSourceType sourceType,
        Long cameraId,
        String cameraCode,
        String cameraName,
        String address,
        BigDecimal latitude,
        BigDecimal longitude,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        BigDecimal bestSimilarity) {

    public static AdminCaseRoutePointResponse from(AdminCaseRouteRow row) {
        return new AdminCaseRoutePointResponse(
                row.candidateId(), row.sourceType(), row.cameraId(), row.cameraCode(), row.cameraName(),
                row.address(), row.latitude(), row.longitude(), row.firstDetectedAt(), row.lastDetectedAt(),
                row.bestSimilarity());
    }
}
