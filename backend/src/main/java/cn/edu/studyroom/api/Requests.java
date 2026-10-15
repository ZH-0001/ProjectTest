package cn.edu.studyroom.api;
import jakarta.validation.constraints.*;
import java.time.*;

public final class Requests {
    public record Register(@NotBlank @Size(max=32) String account,@NotBlank @Size(max=50) String name,@NotNull String password) {}
    public record Login(@NotBlank String account,@NotNull String password) {}
    public record Booking(@NotNull @Positive Long seatId,@NotNull OffsetDateTime startTime,@NotNull OffsetDateTime endTime,@NotBlank String requestId) {}
    public record Cancel(@NotNull @Positive Long reserveId) {}
    public record RoomInput(Long roomId,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=200) String location,LocalTime openTime,LocalTime closeTime) {}
    public record RoomStatus(@NotNull @Positive Long roomId,@NotNull Boolean enabled) {}
    public record RoomDelete(@NotNull @Positive Long roomId) {}
    public record SeatInput(Long seatId,Long roomId,@NotBlank @Size(max=32) String seatNo,@NotBlank String seatType,@NotNull Boolean hasPower,@NotNull Boolean isQuiet) {}
    public record SeatDelete(@NotNull @Positive Long seatId) {}
}
