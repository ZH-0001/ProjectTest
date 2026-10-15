package cn.edu.studyroom.mapper;
import cn.edu.studyroom.domain.Entities.Room;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
public interface RoomMapper extends BaseMapper<Room> {
    @Select("SELECT * FROM study_room WHERE id=#{id} FOR UPDATE") Room lock(long id);
}
