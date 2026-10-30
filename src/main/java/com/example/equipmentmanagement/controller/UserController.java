package com.example.equipmentmanagement.controller;


import com.example.equipmentmanagement.dto.LoginRequest;
import com.example.equipmentmanagement.dto.RegisterRequest;
import com.example.equipmentmanagement.entity.User;
import com.example.equipmentmanagement.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @org.springframework.web.bind.annotation.GetMapping("/me")
    public com.example.equipmentmanagement.dto.CurrentUserResponse currentUser(
            @org.springframework.security.core.annotation.AuthenticationPrincipal Integer currentUserId) {
        return userService.currentUser(currentUserId);
    }

    @PostMapping("/register")
    public int registerUser(@RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @PostMapping("/login")
    public String login(@RequestBody LoginRequest request) {
        return userService.login(request);
    }
}
