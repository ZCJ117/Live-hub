package com.hmdp.rag.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI ragOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("RAG Service API")
                        .description("RAG智能知识问答服务接口文档")
                        .version("1.0.0"));
    }
}
