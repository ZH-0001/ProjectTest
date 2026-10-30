package cn.edu.studyroom.security;
import cn.edu.studyroom.api.Api;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder(){ return new BCryptPasswordEncoder(10); }
    @Bean HttpSessionSecurityContextRepository contextRepository(){ return new HttpSessionSecurityContextRepository(); }
    @Bean HttpSessionCsrfTokenRepository csrfRepository(){
        var r=new HttpSessionCsrfTokenRepository(); r.setHeaderName("X-CSRF-Token"); return r;
    }
    @Bean SecurityFilterChain filterChain(HttpSecurity http,ObjectMapper json,HttpSessionSecurityContextRepository contexts,HttpSessionCsrfTokenRepository csrf) throws Exception {
        http.formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
          .requestCache(AbstractHttpConfigurer::disable)
          .securityContext(c->c.securityContextRepository(contexts))
          .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
          .authorizeHttpRequests(a->a.requestMatchers("/user/register","/user/login","/user/logout","/user/csrf").permitAll()
             .requestMatchers("/admin/**").hasRole("ADMIN")
             .requestMatchers("/room/**","/seat/**","/reserve/**","/ai/**","/assistant/**").hasRole("STUDENT")
             .requestMatchers("/user/me").authenticated().anyRequest().denyAll())
          .exceptionHandling(e->e.authenticationEntryPoint((req,res,err)->write(json,res,401,"UNAUTHENTICATED","请先登录"))
             .accessDeniedHandler((req,res,err)->write(json,res,403,err instanceof CsrfException?"CSRF_INVALID":"FORBIDDEN",err instanceof CsrfException?"请求令牌无效":"没有访问权限")));
        return http.build();
    }
    private void write(ObjectMapper json,HttpServletResponse response,int status,String code,String message) throws java.io.IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); json.writeValue(response.getWriter(),Api.error(code,message));
    }
}
