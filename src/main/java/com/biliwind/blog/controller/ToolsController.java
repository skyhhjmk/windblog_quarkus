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
                .data("pageTitle", "实用在线工具箱 - 开发者与网工高效工具集合")
                .data("pageDescription", "WindBlog 常用在线工具箱，提供新一代子网掩码计算器、Unix 时间戳转换、日志格式化清洗、Base64/Base 变体全家桶等高交互、高可读性的在线实用工具。")
                .data("pageKeywords", "在线工具,子网掩码计算器,VLSM划分,时间戳转换,日志格式化,Base64编码,Base58,开发工具");
    }

    @GET
    @Path("/subnet")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subnetCalculator(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? subnetContentTemplate : subnetTemplate;
        return template
                .data("pageTitle", "新一代智能子网掩码计算器 - IPv4/IPv6 CIDR 可视化推演与 VLSM 划分")
                .data("pageDescription", "新一代可视化子网掩码与网络推演计算器。支持按位逻辑与/或过程解析、32位位图色块标识、VLSM 自定义划分、通配符掩码、可用主机范围与反向 DNS 自动生成。")
                .data("pageKeywords", "子网掩码计算器,CIDR,VLSM,子网划分,网络地址,广播地址,通配符掩码,IPv4,IPv6");
    }

    @GET
    @Path("/timestamp")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance timestampConverter(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? timestampContentTemplate : timestampTemplate;
        return template
                .data("pageTitle", "Unix 时间戳在线转换与时区推演 - 毫秒/秒/全球时区对照")
                .data("pageDescription", "全功能在线 Unix 时间戳双向转换器。支持秒与毫秒快速互转、全球主要跨国时区对照、相对时间推算、日历星期分析及常用编程语言语法速查。")
                .data("pageKeywords", "时间戳转换,Unix timestamp,时区转换,毫秒时间戳,UTC时间,时间计算");
    }

    @GET
    @Path("/log-formatter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance logFormatter(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? logFormatterContentTemplate : logFormatterTemplate;
        return template
                .data("pageTitle", "日志格式化与结构化清洗工具 - JSON/堆栈跟踪/Nginx日志")
                .data("pageDescription", "开发者日志格式化与结构化清洗分析工具。支持 JSON/NDJSON 展开美化、Java Exception 堆栈跟踪高亮、Nginx 日志解析、级别过滤与即时关键词搜索。")
                .data("pageKeywords", "日志格式化,JSON日志,Java堆栈日志,Nginx日志,日志高亮,日志清洗,开发调试");
    }

    @GET
    @Path("/base-encoder")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance baseEncoder(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? baseEncoderContentTemplate : baseEncoderTemplate;
        return template
                .data("pageTitle", "Base64 / Base 编码全家桶在线转换 - Base16/Base32/Base58/Base64")
                .data("pageDescription", "全能 Base 编码全家桶转换器。支持标准 Base64、URL 安全 Base64、Base16 (Hex)、Base32、Base58 (区块链/比特币变体)，原生支持 UTF-8 中文与二进制安全处理。")
                .data("pageKeywords", "Base64编码,Base64解码,URL安全Base64,Base16,Hex,Base32,Base58,字符编码");
    }
}
