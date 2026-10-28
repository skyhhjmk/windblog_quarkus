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

    @Inject
    @Location("tools/cron.html")
    Template cronTemplate;

    @Inject
    @Location("tools/cron.content.html")
    Template cronContentTemplate;

    @Inject
    @Location("tools/regex.html")
    Template regexTemplate;

    @Inject
    @Location("tools/regex.content.html")
    Template regexContentTemplate;

    @Inject
    @Location("tools/hash.html")
    Template hashTemplate;

    @Inject
    @Location("tools/hash.content.html")
    Template hashContentTemplate;

    @Inject
    @Location("tools/json-diff.html")
    Template jsonDiffTemplate;

    @Inject
    @Location("tools/json-diff.content.html")
    Template jsonDiffContentTemplate;

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
            ),
            new ToolItem(
                    "cron-generator",
                    "Cron 表达式生成与执行模拟器",
                    "Linux Crontab 与 Quartz/Spring 6段式表达式可视化生成、人类自然语言释义、未来多次触发时间推算与语言模板速查。",
                    "fa-solid fa-calendar-check",
                    "/tools/cron",
                    "开发运维",
                    "blue"
            ),
            new ToolItem(
                    "regex-tester",
                    "正则表达式在线调试与速查",
                    "即时 RegExp 正则匹配、多色块高亮与捕获组拆解、字符串替换实时预览，内置常用经典正则速查库一键套用。",
                    "fa-solid fa-code-branch",
                    "/tools/regex",
                    "开发运维",
                    "rose"
            ),
            new ToolItem(
                    "hash-calculator",
                    "现代哈希计算与文件本地校验",
                    "支持 MD5、SHA-1/256/512 及国密 SM3 算法，纯本地浏览器沙箱极速计算、支持文本与大文件拖拽校验及 HMAC 密钥模式。",
                    "fa-solid fa-shield-halved",
                    "/tools/hash",
                    "安全加密",
                    "teal"
            ),
            new ToolItem(
                    "json-diff",
                    "JSON 格式化与可视化对比 (Diff)",
                    "JSON 语法美化、紧凑压缩、容错修复、转义清洗，以及双栏结构化差异可视化比对（增、删、改精准定位）。",
                    "fa-solid fa-code-compare",
                    "/tools/json-diff",
                    "数据处理",
                    "indigo"
            )
    );

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? toolsIndexContentTemplate : toolsIndexTemplate;
        return template
                .data("tools", ALL_TOOLS)
                .data("pageTitle", "实用在线工具箱 - 开发者与网工高效工具集合")
                .data("pageDescription", "WindBlog 实用在线工具箱，提供智能子网掩码、Unix 时间戳、日志格式化清洗、Base 编码全家桶、Cron 表达式模拟、正则表达式调试、MD5/SHA/SM3 哈希校验、JSON 可视化对比等纯前端隐私安全实用工具。")
                .data("pageKeywords", "在线工具,子网掩码计算器,时间戳转换,日志格式化,Base64,Cron表达式,正则测试,哈希计算,SM3,JSON对比");
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

    @GET
    @Path("/cron")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cronGenerator(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? cronContentTemplate : cronTemplate;
        return template
                .data("pageTitle", "Cron 表达式在线生成与执行时间模拟器 - Crontab/Quartz")
                .data("pageDescription", "全功能 Cron 表达式在线生成与推演模拟器。支持 Linux 5段式与 Quartz 6段式，提供直观分段选择、人类自然语言翻译、未来多次执行时间推算及编程语言代码模板。")
                .data("pageKeywords", "Cron表达式,Crontab生成器,Quartz Cron,定时任务,执行时间模拟,Cron在线解析");
    }

    @GET
    @Path("/regex")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance regexTester(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? regexContentTemplate : regexTemplate;
        return template
                .data("pageTitle", "正则表达式在线测试与速查工具 - RegExp 调试与替换")
                .data("pageDescription", "现代正则表达式在线调试与测试工具。支持多修饰符切换、即时多色块高亮、捕获组拆解、字符串替换预览及手机号/邮箱/IP等常用经典正则一键套用。")
                .data("pageKeywords", "正则表达式,Regex测试,RegExp调试,正则高亮,正则捕获组,常用正则表达式");
    }

    @GET
    @Path("/hash")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance hashCalculator(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? hashContentTemplate : hashTemplate;
        return template
                .data("pageTitle", "现代哈希散列计算与本地文件校验器 - MD5/SHA256/SM3/HMAC")
                .data("pageDescription", "纯本地隐私安全哈希计算器。支持 MD5、SHA-1、SHA-256、SHA-512 与国密 SM3 算法，原生硬件加速，支持纯文本与本地大文件拖拽校验、HMAC 密钥及校验码智能比对。")
                .data("pageKeywords", "哈希计算,MD5计算,SHA256,SHA512,国密SM3,文件校验,Checksum,HMAC");
    }

    @GET
    @Path("/json-diff")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance jsonDiff(@Context HttpHeaders headers) {
        Template template = PjaxHelper.isPjaxRequest(headers) ? jsonDiffContentTemplate : jsonDiffTemplate;
        return template
                .data("pageTitle", "JSON 在线格式化与可视化差分对比 (Diff) - 美化/清洗/修复/对比")
                .data("pageDescription", "全能 JSON 在线工具箱。支持 JSON 语法美化、紧凑压缩、单引号/尾随逗号容错修复、转义去转义，以及双栏结构化差异可视化比对（增、删、改精准高亮）。")
                .data("pageKeywords", "JSON格式化,JSON美化,JSON Diff,JSON对比,JSON压缩,JSON修复,JSONPath");
    }
}
