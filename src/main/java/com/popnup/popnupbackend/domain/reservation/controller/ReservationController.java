package com.popnup.popnupbackend.domain.reservation.controller;

import com.popnup.popnupbackend.domain.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;
}
