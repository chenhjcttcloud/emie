package com.emie.designpm.points.repository;

import com.emie.designpm.entity.PointTaskForecast;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointTaskForecastRepository extends JpaRepository<PointTaskForecast, Long> {
    List<PointTaskForecast> findByStatusOrderByMonthKeyAscCreatedAtAsc(String status);

    List<PointTaskForecast> findAllByOrderByMonthKeyDescCreatedAtDesc();
}
