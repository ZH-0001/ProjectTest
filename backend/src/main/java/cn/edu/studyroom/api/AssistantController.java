package cn.edu.studyroom.api;

import cn.edu.studyroom.assistant.AssistantService;
import cn.edu.studyroom.security.CurrentUser;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/assistant")
public class AssistantController {
    public record Chat(@NotBlank String message,@NotBlank String requestId) {}
    public record Book(@NotBlank String cardId) {}
    private final AssistantService assistant;
    public AssistantController(AssistantService assistant) { this.assistant=assistant; }
    @GetMapping("/conversation") public Api<?> conversation(@AuthenticationPrincipal CurrentUser user,HttpSession session) {return Api.ok(assistant.conversation(session,user.id()));}
    @PostMapping("/chat") public Api<?> chat(@AuthenticationPrincipal CurrentUser user,HttpSession session,@Valid @RequestBody Chat input) {return Api.ok(assistant.chat(session,user.id(),input.message(),input.requestId()));}
    @PostMapping("/reset") public Api<?> reset(@AuthenticationPrincipal CurrentUser user,HttpSession session) {return Api.ok(assistant.reset(session,user.id()));}
    @PostMapping("/book") public Api<?> book(@AuthenticationPrincipal CurrentUser user,HttpSession session,@Valid @RequestBody Book input) {return Api.ok(assistant.book(session,user.id(),input.cardId()));}
}
