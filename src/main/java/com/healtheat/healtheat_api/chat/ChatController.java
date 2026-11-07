package com.healtheat.healtheat_api.chat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/chat")
@Tag(name="식습관 챗봇") @SecurityRequirement(name="accessCookie")
class ChatController {
    private final ChatService chat;
    ChatController(ChatService chat) { this.chat=chat; }
    @PostMapping("/messages")
    @Operation(summary="챗봇 메시지 전송",description="건강정보 변경은 마이페이지에서, 식단 재추천은 식단 화면에서 처리합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    ChatDtos.Response send(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ChatDtos.MessageRequest request) {
        return chat.send(userId(jwt),request);
    }
    @PostMapping("/sessions")
    @Operation(summary="채팅 시작 또는 기존 대화 이어가기",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    ChatDtos.Session start(@AuthenticationPrincipal Jwt jwt) { return chat.start(userId(jwt)); }
    @DeleteMapping("/sessions/{sessionId}")
    @Operation(summary="채팅 종료 및 대화 삭제",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void end(@AuthenticationPrincipal Jwt jwt,@PathVariable long sessionId) { chat.end(userId(jwt),sessionId); }
    @GetMapping("/sessions") @Operation(summary="내 채팅 목록")
    List<ChatDtos.Session> sessions(@AuthenticationPrincipal Jwt jwt) { return chat.sessions(userId(jwt)); }
    @GetMapping("/sessions/{sessionId}/messages") @Operation(summary="내 채팅 메시지 조회")
    List<ChatDtos.Message> messages(@AuthenticationPrincipal Jwt jwt,@PathVariable long sessionId) { return chat.messages(userId(jwt),sessionId); }
    private long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
