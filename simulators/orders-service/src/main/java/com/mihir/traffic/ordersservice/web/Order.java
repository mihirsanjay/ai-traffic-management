package com.mihir.traffic.ordersservice.web;

import java.time.Instant;
import java.util.UUID;

/**
 * A placed order.
 *
 * @param orderId server-assigned identity
 * @param item what was ordered
 * @param quantity how many
 * @param placedAt when it was placed
 */
public record Order(UUID orderId, String item, int quantity, Instant placedAt) {}
