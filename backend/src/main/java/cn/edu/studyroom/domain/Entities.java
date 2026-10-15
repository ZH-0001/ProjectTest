package cn.edu.studyroom.domain;

import com.baomidou.mybatisplus.annotation.*;
import java.time.*;
import lombok.Data;

public final class Entities {
    private Entities() {}
    @Data @TableName("`user`")
    public static class User {
        @TableId(type=IdType.AUTO) private Long id;
        private String account, name, passwordHash, role;
        private LocalDateTime createdAt, updatedAt;
    }
    @Data @TableName("study_room")
    public static class Room {
        @TableId(type=IdType.AUTO) private Long id;
        private String name, location;
        private LocalTime openTime, closeTime;
        private Boolean enabled, deleted;
        private LocalDateTime createdAt, updatedAt;
    }
    @Data @TableName("seat")
    public static class Seat {
        @TableId(type=IdType.AUTO) private Long id;
        private Long roomId;
        private String seatNo, seatType;
        private Boolean hasPower, isQuiet, deleted;
        private LocalDateTime createdAt, updatedAt;
    }
    @Data @TableName("reserve")
    public static class Reserve {
        @TableId(type=IdType.AUTO) private Long id;
        private Long userId, roomId, seatId;
        private LocalDateTime startTime, endTime, createdAt, cancelledAt;
        private String status, requestId;
    }
    @Data @TableName("user_behavior")
    public static class Behavior {
        @TableId(type=IdType.AUTO) private Long id;
        private Long reserveId, userId, roomId, seatId;
        private LocalDateTime startTime, endTime, createdAt;
        private String roomNameSnapshot, seatNoSnapshot, seatTypeSnapshot, operationType;
        private Boolean hasPowerSnapshot, isQuietSnapshot;
    }
}
