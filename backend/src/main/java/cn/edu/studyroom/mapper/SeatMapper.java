package cn.edu.studyroom.mapper;
import cn.edu.studyroom.domain.Entities.Seat;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
public interface SeatMapper extends BaseMapper<Seat> {
    @Select("SELECT * FROM seat WHERE id=#{id} FOR UPDATE") Seat lock(long id);
}
