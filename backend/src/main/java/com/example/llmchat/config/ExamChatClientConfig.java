package com.example.llmchat.config;

import com.example.llmchat.exam.ExamLectureTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class ExamChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ExamChatClientConfig.class);

    @Bean(name = "examToolCallbacks")
    @Lazy
    ToolCallback[] examToolCallbacks(ExamLectureTools lectureTools) {
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(lectureTools)
                .build()
                .getToolCallbacks();
        ToolCallback[] wrapped = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            wrapped[i] = new RecordingToolCallback(raw[i], ExamLectureTools.TOOL_SERVER_NAME);
        }
        log.info("Exam tools: {}",
                java.util.Arrays.stream(wrapped).map(t -> t.getToolDefinition().name()).toList());
        return wrapped;
    }

    @Bean(name = "examChatClient")
    @Lazy
    ChatClient examChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
