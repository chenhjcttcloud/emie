package com.emie.designpm.repository;

import com.emie.designpm.entity.ImageLibraryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ImageLibraryItemRepository extends JpaRepository<ImageLibraryItem, Long> {
    List<ImageLibraryItem> findAllByOrderByCreatedAtDesc();
}
