package com.example.llmchat.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
@ConditionalOnProperty(prefix = "app.platform.demo-user", name = "enabled", havingValue = "true")
public class VpsDemoUserBootstrap {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;

    public VpsDemoUserBootstrap(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.platform.demo-user.username:student}") String username,
            @Value("${app.platform.demo-user.password:student}") String password) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.username = username.trim().toLowerCase();
        this.password = password;
    }

    @PostConstruct
    void ensureDemoUser() {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        userRepository.create(username, passwordEncoder.encode(password));
    }
}
