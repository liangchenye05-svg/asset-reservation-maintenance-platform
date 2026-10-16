package com.example.equipmentmanagement.mapper;

import com.example.equipmentmanagement.entity.User;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Service;

@Mapper
public interface UserMapper {
    @Select("select * from users where username=#{username}")
    public User selectByUsername(String username);

    @Select("select * from users where id=#{id}")
    public User selectById(Integer id);

    @Insert("insert into users(username, password_hash) " +
            "values (#{username},#{passwordHash})")
    public int insertUser(User user);
}
