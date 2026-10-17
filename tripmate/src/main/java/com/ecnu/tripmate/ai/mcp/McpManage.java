package com.ecnu.tripmate.ai.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class McpManage {

    @Resource(name = "amapMcpClient")
    private McpClient amapMcpClient;

    @Resource(name = "trainMcpClient")
    private McpClient trainMcpClient;

    /**
     * 将 MCP 工具转换成 LangChain4j 的工具提供者
     */

    @Bean
    public McpToolProvider mcpToolProvider() {
        return McpToolProvider.builder()
                .mcpClients(List.of(amapMcpClient, trainMcpClient))
                .build();
    }
}
