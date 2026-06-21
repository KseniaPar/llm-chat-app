package com.example.llmchat.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public AuthResult register(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Имя пользователя обязательно.");
        }
        if (password == null || password.length() < 4) {
            throw new IllegalArgumentException("Пароль должен быть не короче 4 символов.");
        }
        String normalized = username.trim().toLowerCase();
        if (userRepository.existsByUsername(normalized)) {
            throw new IllegalArgumentException("Пользователь уже существует.");
        }

        UserRecord user = userRepository.create(normalized, passwordEncoder.encode(password));
        String token = jwtService.generateToken(user.id(), user.username());
        return new AuthResult(token, user.id(), user.username());
    }

    public AuthResult login(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("Имя пользователя и пароль обязательны.");
        }
        UserRecord user = userRepository.findByUsername(username.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Неверные учётные данные."));
        if (!passwordEncoder.matches(password, user.passwordHash())) {
            throw new IllegalArgumentException("Неверные учётные данные.");
        }
        String token = jwtService.generateToken(user.id(), user.username());
        return new AuthResult(token, user.id(), user.username());
    }

    public record AuthResult(String token, String userId, String username) {
    }
}
