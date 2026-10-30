package com.example.equipmentmanagement;

import com.example.equipmentmanagement.entity.Reservation;
import com.example.equipmentmanagement.exception.ReservationNotFoundException;
import com.example.equipmentmanagement.mapper.DeviceMapper;
import com.example.equipmentmanagement.mapper.ReservationMapper;
import com.example.equipmentmanagement.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReservationQueryTest {
    ReservationMapper mapper;
    ReservationService service;
    @BeforeEach void setup() {
        mapper = mock(ReservationMapper.class);
        service = new ReservationService(mapper, mock(DeviceMapper.class));
    }
    @Test void ordinaryUserScopeIsAppliedToCountAndRows() {
        when(mapper.countPage(7, "PENDING", 3)).thenReturn(12L);
        when(mapper.selectPage(7, "PENDING", 3, 10L, 10)).thenReturn(List.of());
        var page = service.getReservations(7, false, 2, 10, " PENDING ", 3);
        assertEquals(12L, page.getTotal()); assertEquals(2, page.getPage());
        verify(mapper).countPage(7, "PENDING", 3);
        verify(mapper).selectPage(7, "PENDING", 3, 10L, 10);
    }
    @Test void adminCanReadAllOwnersAndLargeOffsetsDoNotOverflow() {
        service.getReservations(9, true, Integer.MAX_VALUE, 100, "", null);
        verify(mapper).countPage(null, null, null);
        verify(mapper).selectPage(null, null, null, 214748364600L, 100);
    }
    @Test void invalidPageStatusAndDeviceFiltersNeverReachDatabase() {
        assertThrows(IllegalArgumentException.class, () -> service.getReservations(7,false,0,10,null,null));
        assertThrows(IllegalArgumentException.class, () -> service.getReservations(7,false,1,101,null,null));
        assertThrows(IllegalArgumentException.class, () -> service.getReservations(7,false,1,0,null,null));
        assertThrows(IllegalArgumentException.class, () -> service.getReservations(7,false,1,10,"UNKNOWN",null));
        assertThrows(IllegalArgumentException.class, () -> service.getReservations(7,false,1,10,null,-1));
        verifyNoInteractions(mapper);
    }
    @Test void anotherUsersDetailIsForbidden() {
        Reservation r = new Reservation(); r.setId(8); r.setUserId(4); when(mapper.selectById(8)).thenReturn(r);
        assertThrows(AccessDeniedException.class, () -> service.getReservation(8,7,false));
        assertSame(r, service.getReservation(8,4,false));
        assertSame(r, service.getReservation(8,9,true));
    }
    @Test void absentAndInvalidIdsAreDifferentFailures() {
        assertThrows(IllegalArgumentException.class, () -> service.getReservation(0,7,false));
        assertThrows(ReservationNotFoundException.class, () -> service.getReservation(999,7,false));
    }
    @Test void missingIdentityIsRejectedEvenForAdminFlag() {
        assertThrows(AccessDeniedException.class, () -> service.getReservations(null,true,1,10,null,null));
        assertThrows(AccessDeniedException.class, () -> service.getReservation(8,null,true));
        verifyNoInteractions(mapper);
    }
}
