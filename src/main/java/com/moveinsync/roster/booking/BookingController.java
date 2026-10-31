package com.moveinsync.roster.booking;

import com.moveinsync.roster.shared.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/bookings")
public class BookingController {
    private final BookingService service;private final TenantIdentity identity;
    public BookingController(BookingService service,TenantIdentity identity) { this.service=service;this.identity=identity; }
    @PostMapping @PreAuthorize("hasRole('EMPLOYEE')")
    public Map<String,UUID> reserve(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody BookingService.Reserve input) {
        return Map.of("bookingId",service.reserve(identity.from(jwt),identity.employee(jwt),input));
    }
}
