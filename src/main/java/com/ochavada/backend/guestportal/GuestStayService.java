package com.ochavada.backend.guestportal;

import com.ochavada.backend.config.PropertyProperties;
import com.ochavada.backend.guestportal.dto.GuestStayResponse;
import com.ochavada.backend.reservation.Reservation;
import com.ochavada.backend.reservation.ReservationStatus;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GuestStayService {

    private final GuestAccessService accessService;
    private final PropertyProperties property;

    public GuestStayService(GuestAccessService accessService, PropertyProperties property) {
        this.accessService = accessService;
        this.property = property;
    }

    @Transactional(readOnly = true)
    public GuestStayResponse currentStay() {
        Reservation r = accessService.currentReservation();
        boolean checkedIn = r.getStatus() == ReservationStatus.CHECKED_IN;
        return new GuestStayResponse(
                r.getCode(), r.getStatus(), r.getHolder().fullName(), r.getRoomType().getName(),
                checkedIn && r.getRoom() != null ? r.getRoom().getNumber() : null,
                r.getCheckInDate(), r.getCheckOutDate(), property.checkInTime(), property.checkOutTime(),
                r.nightCount(), r.getAdults(), r.getChildren(), r.getTotalAmount(), r.getPaidAmount(),
                r.getTotalAmount().subtract(r.getPaidAmount()).max(BigDecimal.ZERO), r.getCurrency(),
                r.getPaymentMode(), r.getOnlineCheckinCompletedAt() != null,
                new GuestStayResponse.Property(property.name(), property.address(), property.phone(),
                        property.email()));
    }
}
