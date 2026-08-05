package com.ssafy.eyesonu.missingcase.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.common.exception.ApiException;
import com.ssafy.eyesonu.missingcase.domain.AdminCaseRouteRow;
import com.ssafy.eyesonu.missingcase.domain.CandidateSourceType;
import com.ssafy.eyesonu.missingcase.domain.MissingCaseRow;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRouteResponse;
import com.ssafy.eyesonu.missingcase.mapper.AdminCaseRouteMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AdminCaseRouteServiceTests {
    private final CaseQueryService caseQueryService = mock(CaseQueryService.class);
    private final AdminCaseRouteMapper routeMapper = mock(AdminCaseRouteMapper.class);
    private AdminCaseRouteService service;

    @BeforeEach
    void setUp() {
        service = new AdminCaseRouteService(caseQueryService, routeMapper);
    }

    @Test
    void returnsEmptyRouteForExistingCaseWithoutConfirmedCandidates() {
        when(caseQueryService.require(17L)).thenReturn(missingCase());
        when(routeMapper.findConfirmedRoutePoints(17L)).thenReturn(List.of());

        AdminCaseRouteResponse response = service.findRoute(17L);

        assertEquals(17L, response.caseId());
        assertEquals("EFU-17", response.caseNumber());
        assertEquals("김민수", response.missingName());
        assertEquals(List.of(), response.points());
    }

    @Test
    void mapsEveryConfirmedCandidateInMapperOrderWithCameraCoordinates() {
        when(caseQueryService.require(17L)).thenReturn(missingCase());
        when(routeMapper.findConfirmedRoutePoints(17L)).thenReturn(List.of(
                routePoint(501L, "camera-01", "2026-08-05T01:00:00Z"),
                routePoint(502L, "camera-02", "2026-08-05T01:05:00Z")));

        AdminCaseRouteResponse response = service.findRoute(17L);

        assertEquals(List.of(501L, 502L), response.points().stream().map(point -> point.candidateId()).toList());
        assertEquals(CandidateSourceType.REALTIME, response.points().getFirst().sourceType());
        assertEquals("camera-01", response.points().getFirst().cameraCode());
        assertEquals("카메라 1 주소", response.points().getFirst().address());
        assertEquals(new BigDecimal("37.5010000"), response.points().getFirst().latitude());
        assertEquals(new BigDecimal("127.0010000"), response.points().getFirst().longitude());
        assertEquals(Instant.parse("2026-08-05T01:00:00Z"), response.points().getFirst().firstDetectedAt());
    }

    @Test
    void propagatesCaseNotFoundWithoutQueryingCandidates() {
        when(caseQueryService.require(999L)).thenThrow(
                new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "사건을 찾을 수 없습니다."));

        ApiException exception = assertThrows(ApiException.class, () -> service.findRoute(999L));

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatus());
        assertEquals("RESOURCE_NOT_FOUND", exception.getCode());
        verify(routeMapper, never()).findConfirmedRoutePoints(999L);
    }

    private MissingCaseRow missingCase() {
        MissingCaseRow row = new MissingCaseRow();
        row.setId(17L);
        row.setCaseNumber("EFU-17");
        row.setMissingName("김민수");
        return row;
    }

    private AdminCaseRouteRow routePoint(Long candidateId, String cameraCode, String firstDetectedAt) {
        return new AdminCaseRouteRow(
                candidateId,
                CandidateSourceType.REALTIME,
                candidateId + 100,
                cameraCode,
                "카메라 " + candidateId,
                "카메라 1 주소",
                new BigDecimal("37.5010000"),
                new BigDecimal("127.0010000"),
                Instant.parse(firstDetectedAt),
                Instant.parse(firstDetectedAt).plusSeconds(30),
                new BigDecimal("0.9123"));
    }
}
