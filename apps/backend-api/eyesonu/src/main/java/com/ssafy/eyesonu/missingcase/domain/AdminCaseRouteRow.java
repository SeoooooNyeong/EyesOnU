package com.ssafy.eyesonu.missingcase.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminCaseRouteRow(
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
}
