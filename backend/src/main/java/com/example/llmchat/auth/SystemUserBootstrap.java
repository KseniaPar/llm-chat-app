package com.example.llmchat.auth;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class SystemUserBootstrap {

    public static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000001";
    public static final String SYSTEM_USERNAME = "system";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SystemUserBootstrap(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    void ensureSystemUser() {
        if (userRepository.findById(SYSTEM_USER_ID).isPresent()) {
            return;
        }
        if (userRepository.existsByUsername(SYSTEM_USERNAME)) {
            return;
        }
        userRepository.createWithId(
                SYSTEM_USER_ID,
                SYSTEM_USERNAME,
                passwordEncoder.encode("system-not-for-login"));
    }
}
