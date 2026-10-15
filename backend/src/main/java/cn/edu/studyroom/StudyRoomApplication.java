package cn.edu.studyroom;

import java.time.Clock;
import java.time.ZoneId;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@MapperScan("cn.edu.studyroom.mapper")
public class StudyRoomApplication {
    public static void main(String[] args) { SpringApplication.run(StudyRoomApplication.class, args); }
    @Bean public Clock clock() { return Clock.system(ZoneId.of("Asia/Shanghai")); }
}
