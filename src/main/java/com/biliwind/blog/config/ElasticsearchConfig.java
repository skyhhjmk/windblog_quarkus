package com.biliwind.blog.config;

import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.http.HttpClient;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * Elasticsearch 配置类
 * 提供 HttpClient 实例用于与 Elasticsearch 通信
 */
@Singleton
public class ElasticsearchConfig {

    @ConfigProperty(name = "elasticsearch.ssl-trust-all", defaultValue = "false")
    boolean sslTrustAll;

    /**
     * 生产 HttpClient 实例
     * 用于发送 HTTP 请求到 Elasticsearch
     * 根据配置决定是否信任所有 SSL 证书
     */
    @Produces
    @Singleton
    public HttpClient httpClient() {
        var builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);
        
        // 如果配置为信任所有证书（开发环境）
        if (sslTrustAll) {
            try {
                SSLContext sslContext = createTrustAllSSLContext();
                builder.sslContext(sslContext);
            } catch (Exception e) {
                throw new RuntimeException("创建 SSL 上下文失败", e);
            }
        }
        
        return builder.build();
    }
    
    /**
     * 创建信任所有证书的 SSL 上下文
     * 注意：仅用于开发环境，生产环境应该使用正式的 SSL 证书
     */
    private SSLContext createTrustAllSSLContext() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 信任所有客户端证书
                }
                
                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 信任所有服务器证书
                }
                
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }
        };
        
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustAllCerts, new SecureRandom());
        return sslContext;
    }
}
