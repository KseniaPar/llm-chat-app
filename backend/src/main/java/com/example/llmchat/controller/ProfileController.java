package com.example.llmchat.controller;

import com.example.llmchat.auth.AuthContext;
import com.example.llmchat.auth.AuthenticatedUser;
import com.example.llmchat.dto.UserProfileRequest;
import com.example.llmchat.dto.UserProfileResponse;
import com.example.llmchat.personalization.PersonalizationService;
import com.example.llmchat.personalization.UserProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/profile")
public class ProfileController {

    private static final Logger log = LoggerFactory.getLogger(ProfileController.class);

    private final PersonalizationService personalizationService;

    public ProfileController(PersonalizationService personalizationService) {
        this.personalizationService = personalizationService;
    }

    @GetMapping
    public UserProfileResponse getProfile() {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("GET /api/user/profile — user: {}", user.username());
        return toResponse(personalizationService.getProfile(user.userId()));
    }

    @PutMapping
    public UserProfileResponse saveProfile(@RequestBody UserProfileRequest request) {
        return saveProfileInternal(request);
    }

    @PostMapping
    public UserProfileResponse saveProfilePost(@RequestBody UserProfileRequest request) {
        return saveProfileInternal(request);
    }

    private UserProfileResponse saveProfileInternal(UserProfileRequest request) {
        AuthenticatedUser user = AuthContext.requireUser();
        log.info("POST/PUT /api/user/profile — user: {}", user.username());
        UserProfile saved = personalizationService.saveProfile(
                user.userId(),
                new UserProfile(
                        user.userId(),
                        request.displayName(),
                        request.responseStyle(),
                        request.responseFormat(),
                        request.constraints(),
                        null));
        return toResponse(saved);
    }

    private UserProfileResponse toResponse(UserProfile profile) {
        return new UserProfileResponse(
                profile.displayName(),
                profile.responseStyle(),
                profile.responseFormat(),
                profile.constraints(),
                profile.updatedAt(),
                !personalizationService.isEmpty(profile));
    }
}
