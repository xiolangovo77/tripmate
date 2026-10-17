
package com.ecnu.tripmate.ai.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;

@Configuration
public class TrainMcpConfig {

    /**
     * 创建12306火车票查询 MCP 客户端
     */
    @Bean(destroyMethod = "close")
    public McpClient trainMcpClient() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        List<String> command;
        if (osName.contains("win")) {
            command = List.of("cmd.exe", "/c", "npx", "-y", "12306-mcp");
        } else {
            command = List.of("npx", "-y", "12306-mcp");
        }
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(command)
                .logEvents(true)
                .build();
        return new DefaultMcpClient.Builder()
                .key("12306-train")
                .transport(transport)
                .build();
    }
}
