package com.emie.designpm.imagelibrary.repository;

import com.emie.designpm.entity.ImageLibraryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImageLibraryItemRepository extends JpaRepository<ImageLibraryItem, Long> {
    List<ImageLibraryItem> findAllByOrderByCreatedAtDesc();
}
