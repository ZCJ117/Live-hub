package com.hmdp.rag.llm;

import com.hmdp.rag.dto.ChatMessage;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ILlmClient {
    Flux<String> streamChat(String systemPrompt, List<ChatMessage> history, String userQuestion);
}
