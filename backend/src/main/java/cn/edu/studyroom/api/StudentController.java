package cn.edu.studyroom.api;
import cn.edu.studyroom.security.CurrentUser;
import cn.edu.studyroom.service.*;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class StudentController {
    private final CatalogService catalog;private final BookingService booking;private final RecommendationService recommendations;
    public StudentController(CatalogService catalog,BookingService booking,RecommendationService recommendations){this.catalog=catalog;this.booking=booking;this.recommendations=recommendations;}
    @GetMapping("/room/list") public Api<?> rooms(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size){return Api.ok(catalog.rooms(page,size,true,null));}
    @GetMapping("/seat/list/{roomId}") public Api<?> seats(@AuthenticationPrincipal CurrentUser user,@PathVariable long roomId,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime){return Api.ok(catalog.seats(user.id(),roomId,startTime,endTime));}
    @PostMapping("/reserve/add") public Api<?> add(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody Requests.Booking input){return Api.ok(booking.add(user.id(),input));}
    @PostMapping("/reserve/cancel") public Api<?> cancel(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody Requests.Cancel input){return Api.ok(booking.cancel(user.id(),input.reserveId()));}
    @GetMapping("/reserve/myList") public Api<?> mine(@AuthenticationPrincipal CurrentUser user,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int size,@RequestParam(required=false) String status){return Api.ok(booking.list(user.id(),page,size,status,null,null,null));}
    @GetMapping("/ai/recommend") public Api<?> recommend(@AuthenticationPrincipal CurrentUser user,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime startTime,@RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) OffsetDateTime endTime){return Api.ok(recommendations.recommend(user.id(),startTime,endTime));}
}
