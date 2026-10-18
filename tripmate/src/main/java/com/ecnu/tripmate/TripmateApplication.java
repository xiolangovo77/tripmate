package com.ecnu.tripmate;

import dev.langchain4j.community.store.embedding.redis.spring.RedisEmbeddingStoreAutoConfiguration;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(exclude = {RedisEmbeddingStoreAutoConfiguration.class})
@MapperScan("com.ecnu.tripmate.mapper")
@EnableTransactionManagement
public class TripmateApplication {

    public static void main(String[] args) {
        SpringApplication.run(TripmateApplication.class, args);
    }

}
