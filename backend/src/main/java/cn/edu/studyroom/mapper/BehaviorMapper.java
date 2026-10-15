package cn.edu.studyroom.mapper;
import cn.edu.studyroom.domain.Entities.Behavior;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Select;
public interface BehaviorMapper extends BaseMapper<Behavior> {
    @Select("SELECT b.* FROM user_behavior b JOIN reserve r ON r.id=b.reserve_id WHERE b.user_id=#{userId} AND r.status='ACTIVE' AND r.end_time<=#{now}")
    List<Behavior> history(long userId, LocalDateTime now);
}
