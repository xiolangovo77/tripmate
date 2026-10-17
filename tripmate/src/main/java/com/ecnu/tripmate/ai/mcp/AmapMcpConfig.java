package com.ecnu.tripmate.ai.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Configuration
public class AmapMcpConfig {

    @Value("${amap.mcp.api-key}")
    private String apiKey;

    /**
     * 创建高德地图 MCP 客户端
     */
    @Bean(destroyMethod = "close")
    public McpClient amapMcpClient() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<String> command;
        if (osName.contains("win")) {
            command = List.of(
                    "cmd.exe",
                    "/c",
                    "npx",
                    "-y",
                    "@amap/amap-maps-mcp-server"
            );
        } else {
            command = List.of(
                    "npx",
                    "-y",
                    "@amap/amap-maps-mcp-server"
            );
        }
        // 1. 创建 stdio 传输
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(command)
                .environment(Map.of(
                        "AMAP_MAPS_API_KEY", apiKey
                ))
                .logEvents(true)
                .build();
        // 2. 创建 MCP 客户端
        return new DefaultMcpClient.Builder()
                .key("amap-maps")
                .transport(transport)
                .build();
    }
}
