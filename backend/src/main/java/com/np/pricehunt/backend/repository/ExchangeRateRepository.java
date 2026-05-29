package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findTopByOrderByAsOfDesc();

    List<ExchangeRate> findByAsOf(LocalDate asOf);
}
