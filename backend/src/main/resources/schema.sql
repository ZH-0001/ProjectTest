CREATE TABLE IF NOT EXISTS `user` (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, account VARCHAR(32) NOT NULL UNIQUE,
 name VARCHAR(50) NOT NULL, password_hash VARCHAR(100) NOT NULL, role VARCHAR(16) NOT NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT chk_user_role CHECK (role IN ('STUDENT','ADMIN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS study_room (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL, location VARCHAR(200) NOT NULL,
 open_time TIME NOT NULL DEFAULT '08:00:00', close_time TIME NOT NULL DEFAULT '22:00:00',
 enabled BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 CONSTRAINT chk_room_time CHECK (open_time < close_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS seat (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, room_id BIGINT NOT NULL, seat_no VARCHAR(32) NOT NULL,
 seat_type VARCHAR(16) NOT NULL, has_power BOOLEAN NOT NULL, is_quiet BOOLEAN NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_room_seat (room_id,seat_no), KEY ix_seat_room (room_id,deleted),
 FOREIGN KEY (room_id) REFERENCES study_room(id),
 CONSTRAINT chk_seat_type CHECK (seat_type IN ('WINDOW','AISLE','MIDDLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS reserve (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, room_id BIGINT NOT NULL, seat_id BIGINT NOT NULL,
 start_time DATETIME NOT NULL, end_time DATETIME NOT NULL, status VARCHAR(16) NOT NULL,
 request_id VARCHAR(36) NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, cancelled_at DATETIME NULL,
 UNIQUE KEY uk_user_request (user_id,request_id),
 KEY ix_reserve_seat (seat_id,status,start_time,end_time), KEY ix_reserve_user (user_id,status,start_time,end_time),
 KEY ix_reserve_room (room_id,status,end_time), KEY ix_reserve_created (user_id,created_at,id), KEY ix_reserve_date (start_time,status),
 FOREIGN KEY (user_id) REFERENCES `user`(id), FOREIGN KEY (room_id) REFERENCES study_room(id), FOREIGN KEY (seat_id) REFERENCES seat(id),
 CONSTRAINT chk_reserve_time CHECK (start_time < end_time), CONSTRAINT chk_reserve_status CHECK (status IN ('ACTIVE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE IF NOT EXISTS user_behavior (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, reserve_id BIGINT NOT NULL UNIQUE, user_id BIGINT NOT NULL, room_id BIGINT NOT NULL, seat_id BIGINT NOT NULL,
 start_time DATETIME NOT NULL, end_time DATETIME NOT NULL,
 room_name_snapshot VARCHAR(100) NOT NULL, seat_no_snapshot VARCHAR(32) NOT NULL, seat_type_snapshot VARCHAR(16) NOT NULL,
 has_power_snapshot BOOLEAN NOT NULL, is_quiet_snapshot BOOLEAN NOT NULL, operation_type VARCHAR(16) NOT NULL DEFAULT 'RESERVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, KEY ix_behavior_user (user_id,end_time),
 FOREIGN KEY (reserve_id) REFERENCES reserve(id), FOREIGN KEY (user_id) REFERENCES `user`(id),
 FOREIGN KEY (room_id) REFERENCES study_room(id), FOREIGN KEY (seat_id) REFERENCES seat(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
