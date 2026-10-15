package cn.edu.studyroom.api;
import cn.edu.studyroom.service.*;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/admin")
public class AdminController {
    private final AdminService admin;private final CatalogService catalog;private final BookingService booking;private final StatisticsService statistics;
    public AdminController(AdminService admin,CatalogService catalog,BookingService booking,StatisticsService statistics){this.admin=admin;this.catalog=catalog;this.booking=booking;this.statistics=statistics;}
    @GetMapping("/room/list") public Api<?> rooms(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) Boolean enabled){return Api.ok(catalog.rooms(page,size,false,enabled));}
    @PostMapping("/room/add") public Api<?> addRoom(@Valid @RequestBody Requests.RoomInput in){return Api.ok(admin.addRoom(in));}
    @PostMapping("/room/update") public Api<?> updateRoom(@Valid @RequestBody Requests.RoomInput in){return Api.ok(admin.updateRoom(in));}
    @PostMapping("/room/status") public Api<?> status(@Valid @RequestBody Requests.RoomStatus in){return Api.ok(admin.status(in.roomId(),in.enabled()));}
    @PostMapping("/room/delete") public Api<?> deleteRoom(@Valid @RequestBody Requests.RoomDelete in){return Api.ok(admin.deleteRoom(in.roomId()));}
    @GetMapping("/seat/list") public Api<?> seats(@RequestParam long roomId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return Api.ok(catalog.adminSeats(roomId,page,size));}
    @PostMapping("/seat/add") public Api<?> addSeat(@Valid @RequestBody Requests.SeatInput in){return Api.ok(admin.addSeat(in));}
    @PostMapping("/seat/update") public Api<?> updateSeat(@Valid @RequestBody Requests.SeatInput in){return Api.ok(admin.updateSeat(in));}
    @PostMapping("/seat/delete") public Api<?> deleteSeat(@Valid @RequestBody Requests.SeatDelete in){return Api.ok(admin.deleteSeat(in.seatId()));}
    @GetMapping("/reserve/list") public Api<?> bookings(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String status,@RequestParam(required=false) String account,@RequestParam(required=false) LocalDate fromDate,@RequestParam(required=false) LocalDate toDate){return Api.ok(booking.list(null,page,size,status,account,fromDate,toDate));}
    @GetMapping("/statistics/daily") public Api<?> stats(@RequestParam LocalDate fromDate,@RequestParam LocalDate toDate){return Api.ok(statistics.daily(fromDate,toDate));}
}
