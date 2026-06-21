package com.example.llmchat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.example.llmchat.invariants.InvariantsProperties;

@SpringBootApplication
@EnableConfigurationProperties(InvariantsProperties.class)
public class LlmChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(LlmChatApplication.class, args);
    }
}
