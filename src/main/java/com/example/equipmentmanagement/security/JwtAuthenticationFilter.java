package com.example.equipmentmanagement.security;
import com.example.equipmentmanagement.entity.User;
import com.example.equipmentmanagement.mapper.UserMapper;
import com.example.equipmentmanagement.util.JwtUtil;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

import java.util.List;


@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(
            JwtUtil jwtUtil,
            UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
              String authorization = request.getHeader("Authorization");

              if (authorization != null&&authorization.startsWith("Bearer ") ) {
                  String token = authorization.substring(7);

                  if (!token.isBlank()) {
                      try {
                          Integer userId = jwtUtil.parseToken(token);

                          User user = userMapper.selectById(userId);

                          if (user == null) {
                              SecurityContextHolder.clearContext();
                              response.sendError(
                                      HttpServletResponse.SC_UNAUTHORIZED,
                                      "用户身份无效"
                              );
                              return;
                          }


                              UsernamePasswordAuthenticationToken authentication =
                                      new UsernamePasswordAuthenticationToken(
                                              user.getId(),
                                              null,
                                              List.of(
                                                      new SimpleGrantedAuthority(
                                                              "ROLE_" + user.getRole()
                                                      )
                                              )
                                      );
                          SecurityContext context =
                                  SecurityContextHolder.createEmptyContext();

                          context.setAuthentication(authentication);

                          SecurityContextHolder.setContext(context);



                      }catch (JwtException | IllegalArgumentException e) {
                          SecurityContextHolder.clearContext();
                          response.sendError(
                                  HttpServletResponse.SC_UNAUTHORIZED,
                                  "无效或过期的登录凭证"
                          );
                          return;
                      }


                  }
              }

        filterChain.doFilter(request, response);
    }
}
