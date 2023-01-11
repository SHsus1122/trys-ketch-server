package com.project.trysketch.service;

import com.project.trysketch.dto.request.GameRoomRequestDto;
import com.project.trysketch.dto.response.GameRoomResponseDto;
import com.project.trysketch.global.dto.DataMsgResponseDto;
import com.project.trysketch.entity.GameRoom;
import com.project.trysketch.entity.GameRoomUser;
import com.project.trysketch.redis.dto.GamerKey;
import com.project.trysketch.repository.GameRoomRepository;
import com.project.trysketch.repository.GameRoomUserRepository;
import com.project.trysketch.global.dto.MsgResponseDto;
import com.project.trysketch.global.exception.CustomException;
import com.project.trysketch.global.exception.StatusMsgCode;
import com.project.trysketch.global.jwt.JwtUtil;
import com.project.trysketch.redis.entity.Guest;
import com.project.trysketch.redis.repositorty.GuestRepository;
import com.project.trysketch.entity.User;
import com.project.trysketch.repository.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 1. 기능   : 프로젝트 메인 로직
// 2. 작성자 : 김재영
@Slf4j
@RequiredArgsConstructor
@Service
public class GameRoomService {
    private final GameRoomRepository gameRoomRepository;
    private final GameRoomUserRepository gameRoomUserRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final GuestRepository guestRepository;

    // =========================== 비회원 헤더 로직 ============================
    public HashMap<String, String> guest(HttpServletRequest request) {
        String guestValue = request.getHeader(GamerKey.GAMER_NUM.key());            // request 안의 "guest" 라는 이름의 헤더의 값을 가져온다.
        HashMap<String, String> guestMap = new HashMap<>();

        if (guestValue != null) {
            guestValue = URLDecoder.decode(guestValue, StandardCharsets.UTF_8);     // 값이 null 이 아니면 UTF-8 형식으로 디코딩
            String[] guestInfo = guestValue.split(",");

            guestMap.put(GamerKey.GAMER_NUM.key(), guestInfo[0]);
            guestMap.put(GamerKey.GAMER_NICK.key(), guestInfo[1]);
            return guestMap;
        } else {
            return null;                                                            // 위에 해당하지 않을 시 null 을 반환
        }
    }

    // ======================== 회원 Id, Nickname 추출 ========================
    public HashMap<String, String> extValue(HttpServletRequest request) {
        Claims claims = jwtUtil.authorizeToken(request);                            // 회원 검증 로직 및 회원 정보 추출
        HashMap<String, String> guestMap = guest(request);                          // 비회원 정보 추출

        HashMap<String, String> result = new HashMap<>();                           // 결과물을 담기위한 HashMap

        // 회원, 비회원 분기처리 시작
        if (claims != null) {
            User user = userRepository.findByEmail(claims.get("email").toString()).orElseThrow(
                    () -> new CustomException(StatusMsgCode.USER_NOT_FOUND)
            );
            result.put(GamerKey.GAMER_NUM.key(), user.getId().toString());          // 회원 Id 를 key 값으로 value 추출 해서 result 에 주입
            result.put(GamerKey.GAMER_NICK.key(), user.getNickname());              // 회원 닉네임을 key 값으로 value 추출 해서 result 에 주입
        } else if (guestMap != null) {
            Long userId = Long.valueOf(guestMap.get(GamerKey.GAMER_NUM.key()));     // 비회원 고유번호 추출
            String nickname = guestMap.get(GamerKey.GAMER_NICK.key());              // 비회원 닉네임 추출

            Optional<Guest> guest = guestRepository.findById(userId);               // guest 정보가 DB 에 있는지 확인(검증)
            if (!guestRepository.existsById(guest.get().getId())) {
                throw new CustomException(StatusMsgCode.INVALID_AUTH_TOKEN);
            }

            result.put(GamerKey.GAMER_NUM.key(), userId.toString());                // guest Id 를 key 값으로 value 추출 해서 result 에 주입
            result.put(GamerKey.GAMER_NICK.key(), nickname);                        // guest 닉네임을 key 값으로 value 추출 해서 result 에 주입
        }
        return result;
    }

    // ============================== 게임방 조회 ==============================
    @Transactional // 전체 list, 각 방 title, 각 방 인원, 각 방 시작상태 반환할 것
    public List<GameRoomResponseDto> getAllGameRoom(Pageable pageable) {

        Page<GameRoom> rooms = gameRoomRepository.findAll(pageable);

        List<GameRoomResponseDto> gameRoomList = new ArrayList<>();

        // 총 페이지 수 가져오기
        HashMap<String, Integer> pageInfo = new HashMap<>();
        pageInfo.put("LastPage",rooms.getTotalPages());
        
        for (GameRoom gameRoom : rooms){

            GameRoomResponseDto gameRoomResponseDto = GameRoomResponseDto.builder()
                                .id(gameRoom.getId())
                                .title(gameRoom.getTitle())
                                .hostNick(gameRoom.getHostNick())
                                .GameRoomUserCount(gameRoom.getGameRoomUserList().size())
                                .status(gameRoom.getStatus())
                                .createdAt(gameRoom.getCreatedAt())
                                .modifiedAt(gameRoom.getModifiedAt())
                                .pageInfo(pageInfo)
                                .build();
            gameRoomList.add(gameRoomResponseDto);
        }
        
        return gameRoomList;
    }

    // ============================== 게임방 생성 ==============================
    @Transactional
    public DataMsgResponseDto createGameRoom(GameRoomRequestDto gameRoomRequestDto, HttpServletRequest request) {
        // 1. 받아온 헤더로부터 유저 또는 guest 정보를 받아온다.
        HashMap<String, String> extInfo = extValue(request);

        // 2. 요청을 한 유저가 이미 속한 방이 있으면 생성 불가능
        if (gameRoomUserRepository.existsByUserId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())))) {
            throw new CustomException(StatusMsgCode.ONE_MAN_ONE_ROOM);
        }

        // 3. 방 정보 생성
        GameRoom gameRoom = GameRoom.builder()
                .title(gameRoomRequestDto.getTitle())
                .hostId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())))
                .hostNick(extInfo.get(GamerKey.GAMER_NICK.key()))
                .status("false")
                .build();

        // 4. 방에 입장한 유저 정보 생성
        GameRoomUser gameRoomUser = GameRoomUser.builder()
                .gameRoom(gameRoom)
                .userId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())))
                .nickname(extInfo.get(GamerKey.GAMER_NICK.key()))
                .webSessionId(null)
                .build();

        // 5. 게임 방 DB에 저장 및 입장중인 유저 정보 저장
        gameRoomRepository.save(gameRoom);
        gameRoomUserRepository.save(gameRoomUser);

        HashMap<String, String> roomInfo = new HashMap<>();

        // 6. HashMap 형식으로 방 제목과 방 번호를 response 로 반환
        roomInfo.put("gameRoomtitle",gameRoom.getTitle());
        roomInfo.put("roomId", String.valueOf(gameRoom.getId()));

        return new DataMsgResponseDto(StatusMsgCode.OK,roomInfo);
    };

    // ============================== 게임방 입장 ==============================
    @Transactional
    public MsgResponseDto enterGameRoom(Long id, HttpServletRequest request) {
        // 1. 받아온 헤더로부터 유저 또는 guest 정보를 받아온다.
        HashMap<String, String> extInfo = extValue(request);

        // 2. id로 DB 에서 현재 들어갈 게임방 데이터 찾기
        Optional<GameRoom> enterGameRoom = gameRoomRepository.findById(id);

        // 3. 게임 방의 상태가 true 이면 게임이 시작중이니 입장 불가능
        if (enterGameRoom.get().getStatus().equals("true")){
            return new MsgResponseDto(StatusMsgCode.ALREADY_PLAYING);
        }

        // 4. 현재 방의 인원이 8명 이상이면 풀방임~
        Long checkUsers = gameRoomUserRepository.countByGameRoomIdOrderByUserId(enterGameRoom.get().getId());
        if (checkUsers >= 8) {
            return new MsgResponseDto(StatusMsgCode.FULL_BANG);
        }

        // 5. 현재 User 가 다른 방에 들어가 있다면
        if (gameRoomUserRepository.existsByUserId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())))) {
            return new MsgResponseDto(StatusMsgCode.ONE_MAN_ONE_ROOM);
        }

        // 6. 새롭게 게임방에 들어온 유저 생성
        GameRoomUser gameRoomUser = GameRoomUser.builder()
                .gameRoom(enterGameRoom.get())
                .userId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())))
                .nickname(extInfo.get(GamerKey.GAMER_NICK.key()))
                .webSessionId(null)
                .build();

        // 7. 게임방에 들어온 유저를 DB에 저장
        gameRoomUserRepository.save(gameRoomUser);

        return new MsgResponseDto(StatusMsgCode.SUCCESS_ENTER_GAME);
    }

    // ============================= 게임방 나가기 =============================
    @Transactional
    public MsgResponseDto exitGameRoom(Long id, HttpServletRequest request, String userUUID) {
        HashMap<String, String> extInfo = new HashMap<>();

        // 1. 분기처리 시작
        if (request != null && userUUID == null) {
            // 2. API 요청으로 게임방을 나가는 경우
            extInfo = extValue(request);
        } else {
            // 3. 소켓 연결이 종료됨으로 인해서 게임방에서 나가지는 경우
            // 예) 웹 브라우저 창 닫기, 팅겼을 경우
            GameRoomUser gameRoomUser = gameRoomUserRepository.findByWebSessionId(userUUID);
            extInfo.put(GamerKey.GAMER_NUM.key(), gameRoomUser.getUserId().toString());
            extInfo.put(GamerKey.GAMER_NICK.key(), gameRoomUser.getNickname());
            id = gameRoomUser.getGameRoom().getId();
        }

        // 4. 유저가 나가려고 하는 GameRoom(방) 정보 가져오기
        GameRoom enterGameRoom = gameRoomRepository.findById(id).orElseThrow(
                () -> new CustomException(StatusMsgCode.GAMEROOM_NOT_FOUND)
        );

        // 5. 나가려고 하는 GameRoomUser(유저) 정보 가져오기
        GameRoomUser gameRoomUser = gameRoomUserRepository.findByUserId(Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())));

        // 6. 나가려는 유저가 요청한 방에 존재하지 않으면 잘못된 요청
        if (!gameRoomUserRepository.existsByGameRoomIdAndUserId(id, gameRoomUser.getUserId())) {
            return new MsgResponseDto(StatusMsgCode.ONE_MAN_ONE_ROOM);
        }

        // 7. 해당 유저 를 GameRoomUser 에서 삭제
        gameRoomUserRepository.delete(gameRoomUser);

        // 8. 유저가 나간 방의 UserList 정보 가져오기
        List<GameRoomUser> leftGameRoomUserList = gameRoomUserRepository.findAllByGameRoom(enterGameRoom);

        // 9. 게임 방의 남은 인원이 0명이면 게임 방도 삭제
        if (leftGameRoomUserList.size() == 0){
            gameRoomRepository.delete(enterGameRoom);
        }

        // 10. 나간 User 와 해당 GameRoom 의 방장이 같으며 GameRoom 에 User 남아있을 경우
        if (Long.valueOf(extInfo.get(GamerKey.GAMER_NUM.key())).equals(enterGameRoom.getHostId()) && !leftGameRoomUserList.isEmpty()) {
            Long hostId = null;
            String hostNick = null;

            // 11. 게임 방 유저들중 현재 방장 다음으로 들어온 UserId 가져오기
            Long newHostId = leftGameRoomUserList.get(0).getId();

            // 12. UserId 를 들고 GameRoomUser 정보 가져오기
            GameRoomUser userHost = gameRoomUserRepository.findById(newHostId).orElse(null);
            Guest guestHost = guestRepository.findById(newHostId).orElse(null);

            // 13. null 값 여부로 회원, 비회원 판단후 host 에 닉네임 넣기
            if (userHost != null) {
                hostId = userHost.getId();
                hostNick = userHost.getNickname();
            } else if (guestHost != null) {
                hostId = guestHost.getId();
                hostNick = guestHost.getNickname();
            }

            // 14. 새로운 Host 가 선정되어 GameRoom 정보 빌드
            GameRoom updateGameRoom = GameRoom.builder()
                    .id(enterGameRoom.getId())
                    .hostId(hostId)
                    .hostNick(hostNick)
                    .title(enterGameRoom.getTitle())
                    .status("false")
                    .build();

            // 15. 기존 GameRoom 에 새로 빌드된 GameRoom 정보 업데이트
            gameRoomRepository.save(updateGameRoom);
        }
        return new MsgResponseDto(StatusMsgCode.SUCCESS_EXIT_GAME);
    }

    @Transactional
    public void webSessionIdUpdate(Long gameRoomId, String token, String userUUID) {
        // 유저 정보 인증부
        Claims claims = jwtUtil.authorizeSocketToken(token);
        String email = (String) claims.get("email");
        User user = userRepository.findByEmail(email).orElseThrow(
                () -> new CustomException(StatusMsgCode.USER_NOT_FOUND)
        );

        // 해당 User 데이터로 GameRoomUser 데이터 가져오기
        GameRoomUser gameRoomUser = gameRoomUserRepository.findByUserIdAndGameRoomId(user.getId(), gameRoomId);

        // 해당 GameRoomUser 업데이트
        GameRoomUser updateGameRoomUser = GameRoomUser.builder()
                .id(gameRoomUser.getId())
                .gameRoom(gameRoomUser.getGameRoom())
                .userId(gameRoomUser.getUserId())
                .nickname(gameRoomUser.getNickname())
                .webSessionId(userUUID)
                .build();
        gameRoomUserRepository.save(updateGameRoomUser);
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getAllGameRoomUsersExceptMe(Long roomId, String userUUID){

        // GameRoomId 데이터로 GameRoomUser 데이터의 List 가져오기
        List<GameRoomUser> gameRoomUserList = gameRoomUserRepository.findAllByGameRoomId(roomId);

        // [ { id : userUUID1 }, { id : userUUID1 }, ...  ]
        // 해당 방에 본인을 제외한 전체 유저 리스트 생성
        List<Map<String, String>> originGameRoomUserUUIDList = new ArrayList<>();

        for (GameRoomUser gameRoomUser : gameRoomUserList){

            // GameRoomUser 의 WebSessionId 가져오기
            String userSessionId = gameRoomUser.getWebSessionId();

            // 만약 가져온 WebSessionId 와 본인( userUUID ) 가 같지않다면
            if (!userSessionId.equals(userUUID)) {
                // userMap 선언
                Map<String, String> userMap = new HashMap<>();

                // 본인을 제외한 GameRoomUser 의 WebSessionId 추가
                userMap.put("Id", userSessionId);

                // userMap 을 List 의 객체로 추가
                originGameRoomUserUUIDList.add(userMap);
            }
        }
        return originGameRoomUserUUIDList;
    }

}
