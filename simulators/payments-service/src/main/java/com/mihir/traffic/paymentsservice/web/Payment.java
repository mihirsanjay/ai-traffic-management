package com.mihir.traffic.paymentsservice.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A taken payment.
 *
 * @param paymentId server-assigned identity
 * @param reference the payer's reference for this payment
 * @param amount how much
 * @param takenAt when it was taken
 */
public record Payment(UUID paymentId, String reference, BigDecimal amount, Instant takenAt) {}
