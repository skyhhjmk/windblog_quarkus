package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

@Path("/tools")
@ApplicationScoped
public class ToolsController {

    @Inject
    @Location("tools/index.html")
    Template toolsIndexTemplate;

    @Inject
    @Location("tools/index.content.html")
    Template toolsIndexContentTemplate;

    @Inject
    @Location("tools/subnet.html")
    Template subnetTemplate;

    @Inject
    @Location("tools/subnet.content.html")
    Template subnetContentTemplate;

    @Inject
    @Location("tools/timestamp.html")
    Template timestampTemplate;

    @Inject
    @Location("tools/timestamp.content.html")
    Template timestampContentTemplate;

    @Inject
    @Location("tools/log-formatter.html")
    Template logFormatterTemplate;

    @Inject
    @Location("tools/log-formatter.content.html")
    Template logFormatterContentTemplate;

    @Inject
    @Location("tools/base-encoder.html")
    Template baseEncoderTemplate;

    @Inject
    @Location("tools/base-encoder.content.html")
    Template baseEncoderContentTemplate;

    @RegisterForReflection
    public static record ToolItem(
            String id,
            String name,
            String description,
            String icon,
            String url,
            String tag,
            String tagColor
    ) {}

    private static final List<ToolItem> ALL_TOOLS = List.of(
            new ToolItem(
                    "subnet-calculator",
                    "新一代智能子网掩码计算器",
                    "可视化 IP/CIDR 掩码计算与网络划分工具。支持 IPv4/IPv6、二/十/十六进制位图对比、通配符计算、可变长子网 (VLSM) 划分与层级可视化。",
                    "fa-solid fa-network-wired",
                    "/tools/subnet",
                    "网络工具",
                    "cyan"
            ),
            new ToolItem(
                    "timestamp-converter",
                    "Unix 时间戳与时区转换器",
                    "毫秒(13位)/秒(10位)双向转换，全球跨国时区对照，相对时间推算及常见编程语言获取时间戳代码速查。",
                    "fa-regular fa-clock",
                    "/tools/timestamp",
                    "常用转换",
                    "emerald"
            ),
            new ToolItem(
                    "log-formatter",
                    "日志格式化与结构化清洗",
                    "支持 JSON/NDJSON 展开美化、Spring/Java 异常堆栈追踪提取、Nginx 访问日志解析、ERROR/WARN 着色及瞬时关键词筛选。",
                    "fa-solid fa-file-code",
                    "/tools/log-formatter",
                    "开发运维",
                    "purple"
            ),
            new ToolItem(
                    "base-encoder",
                    "Base64 / Base 编码全家桶",
                    "支持标准 Base64、URL 安全 Base64、Base32、Base16(Hex)、Base58 (区块链/比特币变体)，完整支持中文字符与二进制安全。",
                    "fa-solid fa-barcode",
                    "/tools/base-encoder",
                    "常用转换",
                    "amber"
            )
    );

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? toolsIndexContentTemplate : toolsIndexTemplate;
        return template
                .data("tools", ALL_TOOLS)
                .data("pageTitle", "实用工具箱 - 开发者与网工常用工具")
                .data("pageDescription", "WindBlog 常用工具箱，提供网络、格式转换、开发调试等高交互、可视化在线实用工具。");
    }

    @GET
    @Path("/subnet")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subnetCalculator(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? subnetContentTemplate : subnetTemplate;
        return template
                .data("pageTitle", "新一代智能子网掩码计算器 - IPv4/IPv6 VLSM 可视化推演")
                .data("pageDescription", "新一代可视化子网掩码与网络推演计算器。支持按位逻辑与/或过程解析、位图色块标识、VLSM 自定义划分、通配符掩码与反向 DNS 自动生成。");
    }

    @GET
    @Path("/timestamp")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance timestampConverter(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? timestampContentTemplate : timestampTemplate;
        return template
                .data("pageTitle", "Unix 时间戳与时区转换器 - 在线时间推算与代码速查")
                .data("pageDescription", "在线 Unix 时间戳双向转换工具，支持秒与毫秒、全球主要城市时区对照、相对时间推算及编程语言语法。");
    }

    @GET
    @Path("/log-formatter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance logFormatter(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? logFormatterContentTemplate : logFormatterTemplate;
        return template
                .data("pageTitle", "日志格式化与结构化清洗 - JSON/堆栈跟踪/Nginx日志")
                .data("pageDescription", "开发者日志格式化与结构化清洗工具，支持 JSON展开、Java Exception 堆栈美化、Nginx日志解析与级别过滤。");
    }

    @GET
    @Path("/base-encoder")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance baseEncoder(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? baseEncoderContentTemplate : baseEncoderTemplate;
        return template
                .data("pageTitle", "Base64 / Base 编码全家桶在线转换 - Base16/Base32/Base58/Base64")
                .data("pageDescription", "全面支持 Base64、URL 安全 Base64、Base32、Hex (Base16) 与 Base58 的编解码工具，支持中文字符与二进制安全。");
    }
}
