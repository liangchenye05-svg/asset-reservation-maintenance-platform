package com.example.equipmentmanagement;

import com.example.equipmentmanagement.dto.RepairOrderQuery;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.RepairOrderMapper;
import com.example.equipmentmanagement.service.RepairOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepairOrderQueryTest {
    RepairOrderMapper mapper;
    RepairOrderService service;
    @BeforeEach void setup() {
        mapper = mock(RepairOrderMapper.class);
        service = new RepairOrderService(mapper, mock(DeviceMapper.class));
    }
    @Test void countAndRowsHaveTheSameUserAndFilters() {
        var query = new RepairOrderQuery(2, 8, "PENDING", 3, "  needle  ");
        when(mapper.countFiltered(7, query)).thenReturn(12L);
        when(mapper.selectFiltered(7, query, 8L)).thenReturn(List.of());
        var result = service.getRepairOrdersPage(7, false, query);
        assertEquals("needle", query.keyword());
        assertEquals(12, result.getTotal()); assertEquals(2, result.getPage()); assertEquals(8, result.getPageSize());
        verify(mapper).countFiltered(7, query); verify(mapper).selectFiltered(7, query, 8L);
    }
    @Test void adminOmitsOwnerAndOffsetUsesLongArithmetic() {
        var query = new RepairOrderQuery(Integer.MAX_VALUE, 100, null, null, null);
        service.getRepairOrdersPage(9, true, query);
        verify(mapper).countFiltered(null, query);
        verify(mapper).selectFiltered(null, query, 214748364600L);
    }
    @Test void missingIdentityCannotUseAdminFlag() {
        var query = new RepairOrderQuery(1, 10, null, null, null);
        assertThrows(AccessDeniedException.class, () -> service.getRepairOrdersPage(null, true, query));
        verifyNoInteractions(mapper);
    }
    @Test void invalidPaginationIsRejectedAtEntry() {
        for (int[] values : List.of(new int[]{0,10}, new int[]{1,0}, new int[]{1,101}, new int[]{-1,8}))
            assertThrows(IllegalArgumentException.class, () -> new RepairOrderQuery(values[0],values[1],null,null,null));
    }
    @Test void invalidStatusAndDeviceAreRejectedAtEntry() {
        for (String status : List.of("", " ", "pending", "UNKNOWN"))
            assertThrows(IllegalArgumentException.class, () -> new RepairOrderQuery(1,10,status,null,null));
        assertThrows(IllegalArgumentException.class, () -> new RepairOrderQuery(1,10,null,0,null));
    }
    @Test void keywordLengthCountsCharactersAndBlankMeansNoFilter() {
        assertNull(new RepairOrderQuery(1,10,null,null,"  ").keyword());
        assertEquals(200, new RepairOrderQuery(1,10,null,null,"😀".repeat(100)).keyword().length());
        assertThrows(IllegalArgumentException.class, () -> new RepairOrderQuery(1,10,null,null,"字".repeat(101)));
    }
}
