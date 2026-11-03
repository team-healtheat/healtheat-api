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
    @Operation(summary="챗봇 메시지 전송",description="건강 정보 변경은 승인 대기 요청으로 반환하며, 확인하기 전까지 저장하지 않습니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    ChatDtos.Response send(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ChatDtos.MessageRequest request) {
        return chat.send(userId(jwt),request);
    }
    @GetMapping("/sessions") @Operation(summary="내 채팅 목록")
    List<ChatDtos.Session> sessions(@AuthenticationPrincipal Jwt jwt) { return chat.sessions(userId(jwt)); }
    @GetMapping("/sessions/{sessionId}/messages") @Operation(summary="내 채팅 메시지 조회")
    List<ChatDtos.Message> messages(@AuthenticationPrincipal Jwt jwt,@PathVariable long sessionId) { return chat.messages(userId(jwt),sessionId); }
    @GetMapping("/actions/pending") @Operation(summary="확인 대기 중인 건강 정보 변경 조회")
    List<ChatDtos.PendingAction> pending(@AuthenticationPrincipal Jwt jwt) { return chat.pending(userId(jwt)); }
    @PostMapping("/actions/{actionId}/confirm")
    @Operation(summary="건강 정보 변경 확인",description="확인 대기 당시의 건강 정보가 바뀌었다면 409를 반환합니다.",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    ChatDtos.ActionResult confirm(@AuthenticationPrincipal Jwt jwt,@PathVariable long actionId) { return chat.confirm(userId(jwt),actionId); }
    @PostMapping("/actions/{actionId}/cancel")
    @Operation(summary="건강 정보 변경 취소",parameters=@Parameter(name="X-XSRF-TOKEN",in=ParameterIn.HEADER,required=true))
    ChatDtos.ActionResult cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable long actionId) { return chat.cancel(userId(jwt),actionId); }
    private long userId(Jwt jwt) { return Long.parseLong(jwt.getSubject()); }
}
