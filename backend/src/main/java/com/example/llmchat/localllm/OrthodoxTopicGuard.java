package com.example.llmchat.localllm;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class OrthodoxTopicGuard {

    private static final Pattern ARITHMETIC = Pattern.compile(
            "(?i)(?:^|\\s)(?:сколько\\s+будет|посчитай|реши|вычисли|calculate|what\\s+is)\\s+.*\\d+\\s*[+\\-*/×÷]\\s*\\d+"
                    + "|\\d+\\s*[+\\-*/×÷]\\s*\\d+"
                    + "|^\\s*\\d+\\s*[+\\-*/×÷]\\s*\\d+\\s*[=?]?\\s*$");

    private static final Pattern TRIVIAL_OFF_TOPIC = Pattern.compile(
            "(?i)^\\s*(?:привет|hello|hi|как\\s+дела|кто\\s+ты|what\\s+are\\s+you)\\s*[!.?]*\\s*$");

    public static final String REFUSAL_MESSAGE =
            "Я могу ответить только на вопросы о православной вере и духовной жизни. "
                    + "Пожалуйста, переформулируйте вопрос уважительно и по теме. "
                    + "По личным духовным вопросам лучше обратиться к священнику вашего прихода.";

    public boolean isOffTopic(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return true;
        }
        String normalized = prompt.trim();
        if (ARITHMETIC.matcher(normalized).find()) {
            return true;
        }
        if (TRIVIAL_OFF_TOPIC.matcher(normalized).matches()) {
            return false;
        }
        return false;
    }
}
