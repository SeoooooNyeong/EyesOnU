package com.ssafy.eyesonu.missingcase.mapper;

import com.ssafy.eyesonu.missingcase.domain.AdminCaseRouteRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminCaseRouteMapper {
    List<AdminCaseRouteRow> findConfirmedRoutePoints(@Param("caseId") Long caseId);
}
