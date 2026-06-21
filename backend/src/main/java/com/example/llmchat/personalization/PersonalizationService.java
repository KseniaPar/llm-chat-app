package com.example.llmchat.personalization;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class PersonalizationService {

    private final UserProfileRepository userProfileRepository;

    public PersonalizationService(UserProfileRepository userProfileRepository) {
        this.userProfileRepository = userProfileRepository;
    }

    public UserProfile getProfile(String userId) {
        return userProfileRepository.findByUserId(userId).orElse(UserProfile.empty(userId));
    }

    public UserProfile saveProfile(String userId, UserProfile profile) {
        UserProfile toSave = new UserProfile(
                userId,
                trimToNull(profile.displayName()),
                trimToNull(profile.responseStyle()),
                trimToNull(profile.responseFormat()),
                trimToNull(profile.constraints()),
                null);
        return userProfileRepository.upsert(toSave);
    }

    public String formatProfileBlock(String userId) {
        return formatProfileBlock(getProfile(userId));
    }

    public String formatProfileBlock(UserProfile profile) {
        if (isEmpty(profile)) {
            return null;
        }
        StringBuilder builder = new StringBuilder("Профиль студента:\n");
        if (hasText(profile.displayName())) {
            builder.append("- Имя: ").append(profile.displayName().trim()).append("\n");
        }
        if (hasText(profile.responseStyle())) {
            builder.append("- Стиль ответов: ").append(profile.responseStyle().trim()).append("\n");
        }
        if (hasText(profile.responseFormat())) {
            builder.append("- Формат: ").append(profile.responseFormat().trim()).append("\n");
        }
        if (hasText(profile.constraints())) {
            builder.append("- Ограничения: ").append(profile.constraints().trim()).append("\n");
        }
        return builder.toString().trim();
    }

    public List<String> buildPersonalizationLogs(UserProfile profile, boolean appliedToPrompt) {
        List<String> logs = new ArrayList<>();
        if (!appliedToPrompt) {
            logs.add("PROFILE: профиль не задан — используется только базовый промпт");
            return logs;
        }
        logs.add("PROFILE: профиль добавлен в промпт");
        if (hasText(profile.displayName())) {
            logs.add("PROFILE → имя: " + profile.displayName().trim());
        }
        if (hasText(profile.responseStyle())) {
            logs.add("PROFILE → стиль: " + profile.responseStyle().trim());
        }
        if (hasText(profile.responseFormat())) {
            logs.add("PROFILE → формат: " + profile.responseFormat().trim());
        }
        if (hasText(profile.constraints())) {
            logs.add("PROFILE → ограничения: " + profile.constraints().trim());
        }
        return logs;
    }

    public boolean isEmpty(UserProfile profile) {
        if (profile == null) {
            return true;
        }
        return !hasText(profile.displayName())
                && !hasText(profile.responseStyle())
                && !hasText(profile.responseFormat())
                && !hasText(profile.constraints());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
