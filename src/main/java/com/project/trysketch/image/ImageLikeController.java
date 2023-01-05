package com.project.trysketch.image;

import com.project.trysketch.global.dto.MsgResponseDto;
import com.project.trysketch.global.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/image")
public class ImageLikeController {
    private final ImageLikeService imageLikeService;

    // 이미지 좋아요 클릭시 S3에 업로드
    @PostMapping(value = "/like",consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<MsgResponseDto> imageLike(@RequestPart(value = "data") ImageLikeRequestDto requestDto,
                                                    @RequestPart(value = "file") List<MultipartFile> multipartFilelist,
                                                    @AuthenticationPrincipal UserDetailsImpl userDetails) throws IOException {
        return ResponseEntity.ok(imageLikeService.saveImage(requestDto, multipartFilelist, userDetails.getUser()));
    }


    // 마이페이지에서 좋아요 누른 사진 조회
    @GetMapping("/like")
    public List<String> getImageLike(@AuthenticationPrincipal UserDetailsImpl userDetails)  {
        return imageLikeService.getImage(userDetails.getUser());
    }




}