package interview.guide.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * S3客户端配置（用于RustFS）
 */
@Configuration  // 配置类：Spring 启动时会扫描这个类，执行里面的 @Bean 方法，创建对象并放入容器
//自动生成包含所有final字段的构造方法 public S3Config(StorageConfigProperties storageConfig) 比 @Autowired 更安全
@RequiredArgsConstructor
public class S3Config {

    private final StorageConfigProperties storageConfig;

    @Bean
    public S3Client s3Client() {
        // AwsBasicCredentials 基础认证凭证对象
        AwsBasicCredentials credentials = AwsBasicCredentials.create(
            storageConfig.getAccessKey(),
            storageConfig.getSecretKey()
        );

        return S3Client.builder()
            .endpointOverride(URI.create(storageConfig.getEndpoint()))
            .region(Region.of(storageConfig.getRegion()))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
                .forcePathStyle(true) // 关键配置：使用路径风格访问，否则 SDK 会使用虚拟主机风格（`bucket.endpoint`）导致 DNS 解析失败
            .build();
    }
}
