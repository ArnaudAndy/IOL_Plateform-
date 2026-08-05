package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.PasswordResetCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetCodeRepository extends JpaRepository<PasswordResetCode, Long> {
    Optional<PasswordResetCode> findFirstByUserIdAndConsumedFalseOrderByCreatedAtDesc(Long userId);
    List<PasswordResetCode> findByUserIdAndConsumedFalse(Long userId);
}
