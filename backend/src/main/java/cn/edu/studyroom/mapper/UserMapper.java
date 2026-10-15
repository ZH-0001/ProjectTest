package cn.edu.studyroom.mapper;
import cn.edu.studyroom.domain.Entities.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
public interface UserMapper extends BaseMapper<User> {
    @Select("SELECT * FROM `user` WHERE id=#{id} FOR UPDATE") User lock(long id);
}
