package com.statement.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared Anthropic API client for the whole app. Reads ANTHROPIC_API_KEY
 * from the environment. Used by the direct-API categorizer and by the agentic
 * orchestrator + subagents (which always talk to Claude directly, not Bedrock).
 */
@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient() {
        return AnthropicOkHttpClient.fromEnv();
    }
}
