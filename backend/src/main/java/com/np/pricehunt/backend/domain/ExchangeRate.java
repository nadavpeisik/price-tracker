package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "exchange_rate",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_exchange_rate_quote_as_of",
                        columnNames = {"quote", "as_of"}),
        indexes = @Index(name = "idx_exchange_rate_as_of", columnList = "as_of DESC"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "exchange_rate_seq")
    @SequenceGenerator(name = "exchange_rate_seq", sequenceName = "exchange_rate_seq", allocationSize = 50)
    private Long id;

    // EUR is the implicit base; one row per quote currency per as_of date.
    @Column(length = 3, nullable = false)
    private String quote;

    @Column(name = "as_of", nullable = false)
    private LocalDate asOf;

    @Column(precision = 19, scale = 8, nullable = false)
    private BigDecimal rate;
}
