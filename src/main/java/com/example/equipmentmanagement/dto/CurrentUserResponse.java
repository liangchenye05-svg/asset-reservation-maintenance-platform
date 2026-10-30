package com.example.equipmentmanagement.dto;

/** Deliberately excludes the user's password hash. */
public record CurrentUserResponse(Integer id, String username, String role) {}
