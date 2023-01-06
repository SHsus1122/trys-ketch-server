package com.project.trysketch.image;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;


// 1. 기능   : ImageFile Repository
// 2. 작성자 : 황미경
public interface ImageFileRepository extends JpaRepository<ImageFile, Long> {
    List<ImageFile> findByUserId(Long userId);

}
