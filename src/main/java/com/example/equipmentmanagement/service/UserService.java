package com.example.equipmentmanagement.service;

import com.example.equipmentmanagement.dto.LoginRequest;
import com.example.equipmentmanagement.dto.RegisterRequest;
import com.example.equipmentmanagement.entity.User;
import com.example.equipmentmanagement.mapper.UserMapper;
import com.example.equipmentmanagement.util.JwtUtil;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

@Service
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder =
            new BCryptPasswordEncoder();
    public UserService(UserMapper userMapper, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.jwtUtil = jwtUtil;
    }





    public int register(RegisterRequest request) {
             if(request==null){
                 throw new IllegalArgumentException("注册信息不能为空");
             }
             if(request.getUsername()==null||request.getUsername().isBlank()){
                 throw new IllegalArgumentException("用户名不能为空或空白");
             }
             if(request.getPassword()==null||request.getPassword().isBlank()){
                 throw new IllegalArgumentException("密码不能为空或空白");
             }
             if (request.getUsername().codePointCount(0, request.getUsername().length()) > 50) {
                 throw new IllegalArgumentException("用户名长度不能超过50个字符");
             }

             User user=userMapper.selectByUsername(request.getUsername());
             if(user!=null){
                 throw new IllegalArgumentException("用户名已存在");
             }

             String passwordHash=encoder.encode(request.getPassword());

             User users=new User();
             users.setUsername(request.getUsername());
             users.setPasswordHash(passwordHash);
             users.setRole("USER");

             int rows;
             try {
                 rows = userMapper.insertUser(users);
             } catch (DuplicateKeyException e) {
                 // A competing registration can insert after the username precheck.
                 throw new IllegalArgumentException("用户名已存在");
             }
             if(rows!=1){
                 throw new IllegalStateException("用户注册保存失败");
             }
             return rows;

    }

    public String login(LoginRequest request) {
        if(request==null){
            throw new IllegalArgumentException("登录信息不存在");
        }
        if(request.getUsername()==null||request.getUsername().isBlank()){
            throw new IllegalArgumentException("用户名不能为空或空白");
        }
        if(request.getPassword()==null||request.getPassword().isBlank()){
            throw new IllegalArgumentException("密码不能为空或空白");
        }
        User user=userMapper.selectByUsername(request.getUsername());
        if(user==null){
            throw new IllegalArgumentException("用户名或密码错误");
        }
        boolean match=encoder.matches(request.getPassword(),user.getPasswordHash());
        if(!match){
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return jwtUtil.generateToken(user.getId());
    }

}
