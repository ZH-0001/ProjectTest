package cn.edu.studyroom.api;
import cn.edu.studyroom.domain.Entities.User;
import cn.edu.studyroom.mapper.UserMapper;
import cn.edu.studyroom.security.CurrentUser;
import cn.edu.studyroom.service.Views;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/user")
public class AuthController {
    private final UserMapper users;private final PasswordEncoder passwords;private final HttpSessionSecurityContextRepository contexts;private final HttpSessionCsrfTokenRepository csrf;
    public AuthController(UserMapper users,PasswordEncoder passwords,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf){this.users=users;this.passwords=passwords;this.contexts=contexts;this.csrf=csrf;}
    @GetMapping("/csrf") public Api<?> csrf(CsrfToken token){return Api.ok(Api.map("csrfToken",token.getToken()));}
    @PostMapping("/register") public Api<?> register(@Valid @RequestBody Requests.Register in){
        Api.require(in.password().codePointCount(0,in.password().length())>=8&&in.password().getBytes(StandardCharsets.UTF_8).length<=72,400,"INVALID_PARAMETER","密码至少8字符且UTF-8编码不超过72字节");
        User u=new User();u.setAccount(in.account().trim());u.setName(in.name().trim());u.setPasswordHash(passwords.encode(in.password()));u.setRole("STUDENT");
        try{users.insert(u);}catch(DuplicateKeyException e){throw new Api.Failure(409,"ACCOUNT_EXISTS","该学号已注册");}return Api.ok(Views.user(u));
    }
    @PostMapping("/login") public Api<?> login(@Valid @RequestBody Requests.Login in,HttpServletRequest request,HttpServletResponse response){
        User u=users.selectOne(new QueryWrapper<User>().eq("account",in.account().trim()));
        Api.require(u!=null&&in.password().getBytes(StandardCharsets.UTF_8).length<=72&&passwords.matches(in.password(),u.getPasswordHash()),401,"AUTH_FAILED","账号或密码错误");
        request.getSession();request.changeSessionId();var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new CurrentUser(u.getId(),u.getAccount(),u.getName(),u.getRole()),null,List.of(new SimpleGrantedAuthority("ROLE_"+u.getRole()))));
        SecurityContextHolder.setContext(context);contexts.saveContext(context,request,response);csrf.saveToken(null,request,response);return Api.ok(Views.user(u));
    }
    @PostMapping("/logout") public Api<?> logout(HttpServletRequest request,HttpServletResponse response){
        var session=request.getSession(false);if(session!=null)session.invalidate();SecurityContextHolder.clearContext();
        response.addHeader("Set-Cookie",ResponseCookie.from("JSESSIONID","").path("/").maxAge(0).httpOnly(true).sameSite("Lax").secure(request.isSecure()).build().toString());return Api.ok(null);
    }
    @GetMapping("/me") public Api<?> me(@AuthenticationPrincipal CurrentUser u){return Api.ok(Api.map("id",Long.toString(u.id()),"account",u.account(),"name",u.name(),"role",u.role()));}
}
