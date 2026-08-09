package com.ssafy.eyesonu.missingcase.controller.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.eyesonu.common.api.ApiResponse;
import com.ssafy.eyesonu.missingcase.dto.admin.AdminCaseRouteResponse;
import com.ssafy.eyesonu.missingcase.service.AdminCaseRouteService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class AdminCaseRouteControllerTests {
    @Mock
    private AdminCaseRouteService routeService;

    @Test
    void returnsCaseRouteWithoutCaching() {
        AdminCaseRouteResponse route = new AdminCaseRouteResponse(17L, "EFU-17", "김민수", List.of());
        when(routeService.findRoute(17L)).thenReturn(route);
        AdminCaseRouteController controller = new AdminCaseRouteController(routeService);

        ResponseEntity<ApiResponse<AdminCaseRouteResponse>> response = controller.findRoute(17L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertEquals(route, response.getBody().data());
        verify(routeService).findRoute(17L);
    }
}
