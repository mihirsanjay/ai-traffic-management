package com.mihir.traffic.ordersservice.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A request to place an order.
 *
 * <p>Validated because a realistic upstream validates — a load generator sending junk should get a
 * 400 from the simulator, not a 500 that looks like a platform fault.
 *
 * @param item what to order
 * @param quantity how many
 */
public record PlaceOrderRequest(
    @NotBlank @Size(max = 100) String item, @Min(1) @Max(1000) int quantity) {}
