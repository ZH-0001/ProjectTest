package cn.edu.studyroom.service;
import cn.edu.studyroom.domain.Entities.*;
import cn.edu.studyroom.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SeedData implements ApplicationRunner {
    private final UserMapper users;private final RoomMapper rooms;private final SeatMapper seats;private final PasswordEncoder passwords;
    @Value("${app.seed-enabled}") private boolean enabled;
    @Value("${app.admin-password}") private String adminPassword;
    public SeedData(UserMapper users,RoomMapper rooms,SeatMapper seats,PasswordEncoder passwords){this.users=users;this.rooms=rooms;this.seats=seats;this.passwords=passwords;}
    @Override @Transactional public void run(ApplicationArguments args){
        if(!enabled)return;
        if(users.selectCount(new QueryWrapper<User>().eq("role","ADMIN"))==0){
            if(adminPassword.codePointCount(0,adminPassword.length())<8||adminPassword.getBytes(StandardCharsets.UTF_8).length>72)throw new IllegalStateException("Please configure ADMIN_PASSWORD locally (8 characters minimum, 72 UTF-8 bytes maximum)");
            User u=new User();u.setAccount("admin");u.setName("系统管理员");u.setRole("ADMIN");u.setPasswordHash(passwords.encode(adminPassword));users.insert(u);
        }
        if(rooms.selectCount(null)==0){
            String[] names={"图书馆 · 静思室","教学楼 · 晨光自习室","综合楼 · 研习空间"};
            for(int r=0;r<3;r++){
                Room room=new Room();room.setName(names[r]);room.setLocation(new String[]{"图书馆三层东区","第一教学楼二层","综合楼四层"}[r]);room.setOpenTime(LocalTime.of(8,0));room.setCloseTime(LocalTime.of(22,0));room.setEnabled(true);room.setDeleted(false);rooms.insert(room);
                for(int i=1;i<=24;i++){Seat s=new Seat();s.setRoomId(room.getId());s.setSeatNo(String.format("%02d",i));s.setSeatType(i%3==1?"WINDOW":i%3==2?"MIDDLE":"AISLE");s.setHasPower(i%2==1);s.setIsQuiet(r!=1);s.setDeleted(false);seats.insert(s);}
            }
        }
    }
}
