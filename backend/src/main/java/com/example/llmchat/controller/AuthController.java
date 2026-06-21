package com.example.llmchat.controller;

import com.example.llmchat.auth.AuthService;
import com.example.llmchat.dto.AuthRequest;
import com.example.llmchat.dto.AuthResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public AuthResponse register(@RequestBody AuthRequest request) {
        log.info("POST /api/auth/register — username: {}", request.username());
        AuthService.AuthResult result = authService.register(request.username(), request.password());
        return new AuthResponse(result.token(), result.userId(), result.username());
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        log.info("POST /api/auth/login — username: {}", request.username());
        AuthService.AuthResult result = authService.login(request.username(), request.password());
        return new AuthResponse(result.token(), result.userId(), result.username());
    }
}
