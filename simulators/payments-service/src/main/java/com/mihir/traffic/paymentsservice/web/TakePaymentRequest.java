package com.mihir.traffic.paymentsservice.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * A request to take a payment.
 *
 * <p>Validated because a realistic upstream validates — a load generator sending junk should get a
 * 400 from the simulator, not a 500 that looks like a platform fault.
 *
 * @param reference the payer's reference
 * @param amount how much to take
 */
public record TakePaymentRequest(
    @NotBlank @Size(max = 100) String reference,
    @NotNull @DecimalMin("0.01") @DecimalMax("1000000.00") BigDecimal amount) {}
