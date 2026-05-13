# 阿里云 OSS SDK for Java 2.0 开发者参考文档

> 文档来源: https://www.alibabacloud.com/help/zh/oss/developer-reference/oss-sdk-for-java-2-0
> 最后更新: 2026-05-13

使用 OSS Java SDK V2 在 Java 应用中接入阿里云对象存储 OSS,实现文件的上传、下载和管理功能,适合网站、企业和开发者进行云端文件存储操作。

[Github](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2) | [OSS SDK for Java API](https://javadoc.io/doc/com.aliyun/alibabacloud-oss-v2/latest/index.html) | [mvnrepository](https://mvnrepository.com/artifact/com.aliyun/alibabacloud-oss-v2) | [deepwiki](https://deepwiki.com/aliyun/alibabacloud-oss-java-sdk-v2/1-alibaba-cloud-oss-java-sdk-v2-overview)

## 快速接入

接入OSS Java SDK V2的流程如下:

1. 环境准备 (Java 8+)
2. 安装SDK (Maven依赖)
3. 配置访问凭证 (环境变量)
4. 初始化客户端 (OssClient)

### 环境准备

Java 8 及以上版本。

### 安装SDK

建议使用 Maven 方式安装 OSS Java SDK V2。

在 `pom.xml` 添加如下依赖,并将 `<version>`
替换为在 [Maven Repository](https://mvnrepository.com/artifact/com.aliyun/alibabacloud-oss-v2) 查询到的最新版本号:

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>alibabacloud-oss-v2</artifactId>
    <version><!-- 填写最新版本号--></version>
</dependency>
```

### 配置访问凭证

将 RAM 用户的 AccessKey 写入环境变量作为凭证。

**Linux/macOS:**

```bash
export OSS_ACCESS_KEY_ID='YOUR_ACCESS_KEY_ID'
export OSS_ACCESS_KEY_SECRET='YOUR_ACCESS_KEY_SECRET'
```

**Windows (PowerShell):**

```powershell
[Environment]::SetEnvironmentVariable("OSS_ACCESS_KEY_ID", "YOUR_ACCESS_KEY_ID", [EnvironmentVariableTarget]::User)
[Environment]::SetEnvironmentVariable("OSS_ACCESS_KEY_SECRET", "YOUR_ACCESS_KEY_SECRET", [EnvironmentVariableTarget]::User)
```

### 初始化客户端

**重要注意事项:**

* OSSClient 实现了 AutoCloseable,采用 try resource 使用时会自动释放资源
* OSSClient 创建和销毁是耗时的,可采用单例模式复用,但应用终止前必须手动调用 close,否则资源会泄漏

**同步 OSSClient 示例:**

```java
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.OSSClientBuilder;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.*;
import com.aliyun.sdk.service.oss2.paginator.ListBucketsIterable;

public class Example {
    public static void main(String[] args) {
        String region = "<region-id>";

        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();
        OSSClientBuilder clientBuilder = OSSClient.newBuilder()
                .credentialsProvider(provider)
                .region(region);

        try (OSSClient client = clientBuilder.build()) {

            ListBucketsIterable paginator = client.listBucketsPaginator(
                    ListBucketsRequest.newBuilder()
                            .build());

            for (ListBucketsResult result : paginator) {
                for (BucketSummary info : result.buckets()) {
                    System.out.printf("bucket: name:%s, region:%s, storageClass:%s\n", info.name(), info.region(), info.storageClass());
                }
            }

        } catch (Exception e) {
            System.out.printf("error:\n%s", e);
        }
    }
}
```

**异步 OSSClient 示例:**

```java
import com.aliyun.sdk.service.oss2.OSSAsyncClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;
import com.aliyun.sdk.service.oss2.models.*;

import java.util.concurrent.CompletableFuture;

public class ExampleAsync {
    public static void main(String[] args) {
        String region = "<region-id>";
        CredentialsProvider provider = new EnvironmentVariableCredentialsProvider();

        try (OSSAsyncClient client = OSSAsyncClient.newBuilder()
                .region(region)
                .credentialsProvider(provider)
                .build()) {

            CompletableFuture<ListBucketsResult> future = client.listBucketsAsync(
                    ListBucketsRequest.newBuilder().build()
            );

            future.thenAccept(result -> {
                        for (BucketSummary info : result.buckets()) {
                            System.out.printf("bucket: name:%s, region:%s, storageClass:%s\n",
                                    info.name(), info.region(), info.storageClass());
                        }
                    })
                    .exceptionally(e -> {
                        System.out.printf("async error:\n%s\n", e);
                        return null;
                    });

            future.join();

        } catch (Exception e) {
            System.out.printf("main error:\n%s\n", e);
        }
    }
}
```

## 客户端配置

### 支持的配置参数

| 参数名                   | 说明                               |
|-----------------------|----------------------------------|
| region                | (必选)请求发送的区域                      |
| credentialsProvider   | (必选)设置访问凭证                       |
| endpoint              | 访问域名                             |
| httpClient            | HTTP客户端                          |
| retryMaxAttempts      | HTTP请求时的最大尝试次数,默认值为 3            |
| retryer               | HTTP请求时的重试实现                     |
| connectTimeout        | 建立连接的超时时间,默认值为 5 秒               |
| readWriteTimeout      | 应用读写数据的超时时间,默认值为 20 秒            |
| insecureSkipVerify    | 是否跳过SSL证书校验,默认检查SSL证书            |
| enabledRedirect       | 是否开启HTTP重定向,默认不开启                |
| signatureVersion      | 签名版本,默认值为v4                      |
| disableSsl            | 不使用https请求,默认使用https             |
| usePathStyle          | 使用路径请求风格,即二级域名请求风格,默认为bucket托管域名 |
| useCName              | 是否使用自定义域名访问,默认不使用                |
| useDualStackEndpoint  | 是否使用双栈域名访问,默认不使用                 |
| useAccelerateEndpoint | 是否使用传输加速域名访问,默认不使用               |
| useInternalEndpoint   | 是否使用内网域名访问,默认不使用                 |
| additionalHeaders     | 指定额外的签名请求头,V4签名下有效               |
| userAgent             | 指定额外的User-Agent信息                |

### 使用自定义域名

```java
import com.aliyun.sdk.service.oss2.*;
import com.aliyun.sdk.service.oss2.credentials.*;

public class Example {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        String region = "<region-id>";
        String endpoint = "https://www.example-***.com";

        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .endpoint(endpoint)
                .useCName(true)
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
        }
    }
}
```

### 超时控制

```java
import com.aliyun.sdk.service.oss2.*;
import com.aliyun.sdk.service.oss2.credentials.*;
import java.time.Duration;

public class Example {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        String region = "<region-id>";

        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .connectTimeout(Duration.ofSeconds(30))
                .readWriteTimeout(Duration.ofSeconds(30))
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
        }
    }
}
```

### 重试策略

```java
import com.aliyun.sdk.service.oss2.*;
import com.aliyun.sdk.service.oss2.credentials.*;
import com.aliyun.sdk.service.oss2.retry.*;
import java.time.Duration;

public class Example {
    public static void main(String[] args) {
        /*
         * SDK 重试策略配置说明:
         *
         * 默认重试策略:
         * 当没有配置重试策略时,SDK 使用 StandardRetryer 作为客户端的默认实现,其默认配置如下:
         * - maxAttempts:设置最大尝试次数。默认为3次
         * - maxBackoff:设置最大退避时间(单位:秒)。默认为20秒
         * - baseDelay:设置基础延迟时间(单位:秒)。默认为0.2秒
         * - backoffDelayer:设置退避算法。默认使用FullJitter退避算法
         *   计算公式为:[0.0, 1.0) * min(2^attempts * baseDelay, maxBackoff)
         * - errorRetryables:可重试的错误类型,包括HTTP状态码、服务错误码、客户端错误等
         */

        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        String region = "<region-id>";

        // 自定义最大重试次数(默认为3次,这里设置为5次)
        Retryer customRetryer = StandardRetryer.newBuilder()
                .maxAttempts(5)
                .build();

        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .retryer(customRetryer)
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
        }
    }
}
```

### 使用内网域名

```java
import com.aliyun.sdk.service.oss2.*;
import com.aliyun.sdk.service.oss2.credentials.*;

public class Example {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        String region = "<region-id>";

        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .useInternalEndpoint(true)
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
        }
    }
}
```

### 使用传输加速域名

```java
import com.aliyun.sdk.service.oss2.*;
import com.aliyun.sdk.service.oss2.credentials.*;

public class Example {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        String region = "<region-id>";

        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .useAccelerateEndpoint(true)
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("Error occurred: " + e.getMessage());
        }
    }
}
```

## 访问凭证配置

### 如何选择访问凭证?

| 凭证提供者初始化方式      | 适用场景                    | Java SDK V2支持情况 | 底层实现基于的凭证 | 凭证有效期 | 凭证轮转或刷新方式 |
|-----------------|-------------------------|-----------------|-----------|-------|-----------|
| 使用RAM用户的AK      | 安全稳定的环境,长期访问云服务         | 内置支持            | AK        | 长期    | 手动轮转      |
| 使用STS临时访问凭证     | 不可信环境,控制有效期和权限          | 内置支持            | STS Token | 临时    | 手动刷新      |
| 使用RAMRoleARN凭证  | 跨账号访问,通过扮演RAM角色获取临时凭证   | 扩展支持            | STS Token | 临时    | 自动刷新      |
| 使用ECSRAMRole凭证  | 运行在ECS实例、ECI实例或容器服务     | 扩展支持            | STS Token | 临时    | 自动刷新      |
| 使用OIDCRoleARN凭证 | 容器服务Kubernetes版中的RRSA功能 | 扩展支持            | STS Token | 临时    | 自动刷新      |
| 使用自定义访问凭证       | 以上凭证配置方式都不满足要求          | 内置支持            | 自定义       | 自定义   | 自定义       |
| 匿名访问            | 访问公共读取权限的OSS资源          | 内置支持            | 无         | 无     | 无         |

### 使用RAM用户的AK (环境变量配置)

```java
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.EnvironmentVariableCredentialsProvider;

public class OSSExample {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new EnvironmentVariableCredentialsProvider();
        
        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region("<region-id>")
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("操作失败: " + e.getMessage());
        }
    }
}
```

### 使用自定义访问凭证

**通过Supplier接口实现:**

```java
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.Credentials;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProviderSupplier;

public class OSSExample {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new CredentialsProviderSupplier(() -> {
            // TODO: 实现您的自定义凭证获取逻辑
            return new Credentials("access_key_id", "access_key_secret");
        });
        
        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region("<region-id>")
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("操作失败: " + e.getMessage());
        }
    }
}
```

**实现CredentialsProvider接口:**

```java
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.Credentials;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;

public class CustomCredentialsProvider implements CredentialsProvider {
    
    @Override
    public Credentials getCredentials() {
        // TODO: 实现您的自定义凭证获取逻辑
        return new Credentials("access_key_id", "access_key_secret");
    }
}

public class OSSExample {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new CustomCredentialsProvider();
        
        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region("<region-id>")
                .build()) {
            // 使用创建好的client执行后续操作...
        } catch (Exception e) {
            System.err.println("操作失败: " + e.getMessage());
        }
    }
}
```

### 匿名访问

```java
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.AnonymousCredentialsProvider;

public class OSSExample {
    public static void main(String[] args) {
        CredentialsProvider credentialsProvider = new AnonymousCredentialsProvider();
        
        try (OSSClient client = OSSClient.newBuilder()
                .credentialsProvider(credentialsProvider)
                .region("<region-id>")
                .build()) {
            // 注意:匿名访问只能访问具有公共读取权限的资源
        } catch (Exception e) {
            System.err.println("操作失败: " + e.getMessage());
        }
    }
}
```

## 示例代码索引

### 存储空间操作

-
创建存储空间: [PutBucket.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutBucket.java)
-
列举存储空间: [ListBuckets.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/ListBuckets.java)
-
获取存储空间信息: [GetBucketInfo.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetBucketInfo.java)
-
删除存储空间: [DeleteBucket.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/DeleteBucket.java)

### 文件上传操作

-
简单上传: [PutObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutObject.java)
-
追加上传: [AppendObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/AppendObject.java)
-
分片上传: [MultipartUpload.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/MultipartUpload.java)
-
取消分片上传: [AbortMultipartUpload.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/AbortMultipartUpload.java)

### 文件下载操作

-
简单下载: [GetObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetObject.java)

### 文件管理操作

-
拷贝文件: [CopyObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/CopyObject.java)
-
判断文件是否存在: [HeadObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/HeadObject.java)
-
列举文件: [ListObjects.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/ListObjects.java)
-
删除文件: [DeleteObject.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/DeleteObject.java)
-
批量删除文件: [DeleteMultipleObjects.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/DeleteMultipleObjects.java)
-
获取文件元数据: [GetObjectMeta.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetObjectMeta.java)

### 对象标签操作

-
设置对象标签: [PutObjectTagging.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutObjectTagging.java)
-
获取对象标签: [GetObjectTagging.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetObjectTagging.java)
-
删除对象标签: [DeleteObjectTagging.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/DeleteObjectTagging.java)

### 访问控制操作

-
设置存储空间ACL: [PutBucketAcl.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutBucketAcl.java)
-
获取存储空间ACL: [GetBucketAcl.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetBucketAcl.java)
-
设置文件ACL: [PutObjectAcl.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutObjectAcl.java)
-
获取文件ACL: [GetObjectAcl.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetObjectAcl.java)

### 版本控制操作

-
设置版本控制: [PutBucketVersioning.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutBucketVersioning.java)
-
获取版本控制状态: [GetBucketVersioning.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetBucketVersioning.java)
-
列举文件版本: [ListObjectVersions.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/ListObjectVersions.java)

### 跨域访问操作

-
设置CORS规则: [PutBucketCors.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/PutBucketCors.java)
-
获取CORS规则: [GetBucketCors.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/GetBucketCors.java)
-
删除CORS规则: [DeleteBucketCors.java](https://github.com/aliyun/alibabacloud-oss-java-sdk-v2/blob/main/samples/src/main/java/com/example/oss/DeleteBucketCors.java)

更多示例请访问官方 GitHub 仓库: https://github.com/aliyun/alibabacloud-oss-java-sdk-v2
