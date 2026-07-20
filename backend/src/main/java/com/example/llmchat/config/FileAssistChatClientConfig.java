package com.example.llmchat.config;

import com.example.llmchat.fileassist.FileAssistTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class FileAssistChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(FileAssistChatClientConfig.class);

    @Bean(name = "fileAssistToolCallbacks")
    @Lazy
    ToolCallback[] fileAssistToolCallbacks(FileAssistTools fileAssistTools) {
        List<ToolCallback> wrapped = new ArrayList<>();
        ToolCallback[] raw = MethodToolCallbackProvider.builder()
                .toolObjects(fileAssistTools)
                .build()
                .getToolCallbacks();
        for (ToolCallback callback : raw) {
            wrapped.add(new FileAssistRecordingToolCallback(callback, FileAssistTools.TOOL_SERVER_NAME));
        }
        log.info("FileAssist tools (in-process): {}",
                wrapped.stream().map(t -> t.getToolDefinition().name()).toList());
        return wrapped.toArray(ToolCallback[]::new);
    }

    @Bean(name = "fileAssistChatClient")
    @Lazy
    ChatClient fileAssistChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }
}
