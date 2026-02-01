package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RustFS (S3兼容) 存储配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.storage")  //将配置文件中的配置，批量注入到Java Bean中
public class StorageConfigProperties {

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucket;
    private String region = "us-east-1";
}
