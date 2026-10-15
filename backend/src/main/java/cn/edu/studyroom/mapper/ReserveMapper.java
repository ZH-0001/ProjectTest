package cn.edu.studyroom.mapper;
import cn.edu.studyroom.domain.Entities.Reserve;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
public interface ReserveMapper extends BaseMapper<Reserve> {
    @Select("SELECT * FROM reserve WHERE id=#{id} FOR UPDATE") Reserve lock(long id);
}
