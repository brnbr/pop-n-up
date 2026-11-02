package com.popnup.popnupbackend.domain.reservation.dto.response;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {}
