package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.PjaxHelper;
import com.biliwind.blog.context.LanguageContext;
import com.biliwind.blog.i18n.I18n;
import com.biliwind.blog.service.CanonicalUrlService;
import com.biliwind.blog.service.PageRenderModel;
import com.biliwind.blog.service.PageRenderer;
import com.biliwind.blog.service.PageView;
import com.biliwind.blog.service.StructuredDataService;
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

@Path("/tools")
@ApplicationScoped
public class ToolsController {

    @Inject
    PageRenderer pageRenderer;

    @Inject
    I18n i18n;

    @Inject
    LanguageContext languageContext;

    @Inject
    StructuredDataService structuredDataService;

    @Inject
    CanonicalUrlService canonicalUrlService;

    @RegisterForReflection
    public static record ToolItem(
            String id,
            String name,
            String description,
            String icon,
            String url,
            String tag,
            String tagColor
    ) {
        public String nameKey() {
            return "tool." + id + ".name";
        }

        public String descriptionKey() {
            return "tool." + id + ".desc";
        }

        public String tagKey() {
            return "tool." + id + ".tag";
        }
    }

    private static final List<ToolItem> ALL_TOOLS = List.of(
            new ToolItem(
                    "file-transfer",
                    "免服务器 P2P 文件快传",
                    "基于 WebRTC DataChannel 与零服务器手动/二维码信令的点对点文件与文本快传。支持 ECDH+AES-GCM 防监听加密、Rust WASM Reed-Solomon 纠错、大文件流式落盘与 SHA-256 校验。",
                    "fa-solid fa-paper-plane",
                    "/tools/file-transfer",
                    "网络工具",
                    "emerald"
            ),
            new ToolItem(
                    "image-generator",
                    "程序化图像生成与全能图像处理工作台",
                    "内置 18+ 种程序化算法图像生成（柏林分形噪声、域扭曲流体、德劳内低多边形、曼德博/朱利亚分形、奇异吸引子、图灵反应扩散等），支持全链路像素级图像精修、卷积滤镜、复古抖动与纯本地批量转码。",
                    "fa-solid fa-wand-magic-sparkles",
                    "/tools/image-generator",
                    "图像视觉",
                    "purple"
            ),
            new ToolItem(
                    "image-converter",
                    "免费极速本地图像格式转换器 (全格式互转)",
                    "100% 免费、毫秒级极速、零上传纯本地图片格式互转工具。支持 WebP 转 PNG/JPG、PNG/JPG 转 WebP、AVIF 转 PNG/JPG、PNG/SVG 转多尺寸 Favicon ICO、BMP、GIF 静帧提取与 Base64 批量互转，自动剥离 EXIF 隐私。",
                    "fa-solid fa-images",
                    "/tools/image-converter",
                    "图像视觉",
                    "emerald"
            ),
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
            ),
            new ToolItem(
                    "web-serial",
                    "Web Serial 串口终端",
                    "通过 Web Serial API 在浏览器内直连 USB/串口设备，基于 xterm 终端，支持常见波特率、数据位、校验位、停止位与流控配置，串口数据完全留在本机。",
                    "fa-solid fa-terminal",
                    "/tools/serial",
                    "开发运维",
                    "cyan"
            ),
            new ToolItem(
                    "ai-token",
                    "AI Token 计算与 Prompt 调试台",
                    "实时估算 GPT/Claude/Gemini/DeepSeek 等模型的 Token 数量与 API 成本，支持 System/User Prompt 模板变量自动识别与一键渲染预览。",
                    "fa-solid fa-microchip",
                    "/tools/ai-token",
                    "AI 工具",
                    "purple"
            ),
            new ToolItem(
                    "jwt",
                    "JWT 解析与本地签名验证器",
                    "纯浏览器本地解析 JWT Header/Payload/Signature，自动高亮 exp/iat/nbf 过期状态，支持 HS256/384/512 本地签名校验与测试令牌生成。",
                    "fa-solid fa-key",
                    "/tools/jwt",
                    "安全加密",
                    "amber"
            ),
            new ToolItem(
                    "config-converter",
                    "YAML / JSON / Properties / .env 互转",
                    "纯前端零上传的配置文件格式双向转换与校验器，支持 YAML、JSON、Java/Quarkus application.properties 与 .env 环境变量一键互转。",
                    "fa-solid fa-sliders",
                    "/tools/config-converter",
                    "数据处理",
                    "indigo"
            ),
            new ToolItem(
                    "curl-converter",
                    "cURL 转代码与 URL 参数拆解器",
                    "将 cURL 命令一键转换为 fetch、Python requests、Java HttpClient 与 Go 代码，同时提供 URL 协议、路径与 Query 参数可视化编辑器。",
                    "fa-solid fa-right-left",
                    "/tools/curl-converter",
                    "开发运维",
                    "blue"
            ),
            new ToolItem(
                    "id-generator",
                    "UUID / ULID / 雪花 ID 生成与反解析",
                    "批量生成 UUID v4/v7、ULID 与 Snowflake 雪花 ID，并支持雪花 ID、ULID、UUID v7 反向提取精确毫秒时间戳与节点信息。",
                    "fa-solid fa-fingerprint",
                    "/tools/id-generator",
                    "常用转换",
                    "teal"
            ),
            new ToolItem(
                    "jsonl-to-table",
                    "JSONL (NDJSON) 转表格与导出",
                    "将 JSONL / NDJSON 日志或数据集实时解析为可筛选、可排序的交互式数据表，支持嵌套字段展开与一键导出 CSV / Markdown / JSON。",
                    "fa-solid fa-table",
                    "/tools/jsonl-to-table",
                    "数据处理",
                    "emerald"
            )
    );

    public static List<ToolItem> allTools() {
        return ALL_TOOLS;
    }

    public static ToolItem findTool(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        return ALL_TOOLS.stream()
                .filter(tool -> tool.id().equals(toolId))
                .findFirst()
                .orElse(null);
    }

    private static ToolItem findToolByPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return ALL_TOOLS.stream()
                .filter(tool -> tool.url().equals(path))
                .findFirst()
                .orElse(null);
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance index(@Context HttpHeaders headers) {
        String lang = languageContext.getLang();
        boolean en = isEnglish(lang);
        String title = en
                ? "Developer Toolbox - Privacy-First Online Utilities for Engineers"
                : "实用在线工具箱 - 开发者与网工高效工具集合";
        String desc = en
                ? "WindBlog privacy-first online developer toolbox: subnet calculator, Unix timestamp, log formatter, JWT decoder, YAML/JSON/Properties converter, cURL converter, JSONL to Table, AI token estimator, and serverless P2P file transfer."
                : "WindBlog 实用在线工具箱，提供智能子网掩码、Unix 时间戳、日志清洗、JWT 验证、YAML/JSON/Properties 互转、cURL 转换、JSONL 转表格、AI Token 估算与免服务器 P2P 快传等纯前端隐私安全工具。";
        String keywords = en
                ? "developer tools,subnet calculator,timestamp converter,jwt decoder,yaml json converter,curl converter,jsonl to table,ai token calculator,cron generator,regex tester"
                : "在线工具,子网掩码计算器,时间戳转换,日志格式化,JWT解析,YAML转JSON,cURL转换,JSONL转表格,AI Token计算,Cron表达式,正则测试";
        String jsonLd = structuredDataService.collection(
                i18n.t("tools.title"), "/tools", lang,
                List.of(new StructuredDataService.Breadcrumb(i18n.t("tools.title"), "/tools")));
        return pageRenderer.render(PageView.TOOLS, headers, toolModel(headers, title, desc, keywords, jsonLd)
                .put("tools", ALL_TOOLS));
    }

    @GET
    @Path("/subnet")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance subnetCalculator(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_SUBNET, "/tools/subnet",
                "新一代智能子网掩码计算器 - IPv4/IPv6 CIDR 可视化推演与 VLSM 划分",
                "新一代可视化子网掩码与网络推演计算器。支持按位逻辑与/或过程解析、32位位图色块标识、VLSM 自定义划分、通配符掩码、可用主机范围与反向 DNS 自动生成。",
                "子网掩码计算器,CIDR,VLSM,子网划分,网络地址,广播地址,通配符掩码,IPv4,IPv6",
                "Smart Subnet Calculator - IPv4/IPv6 CIDR & VLSM Visualizer",
                "Interactive IPv4/IPv6 CIDR subnet calculator with binary bitmaps, wildcard masks, VLSM splitting and usable host range analysis.",
                "subnet calculator,cidr calculator,vlsm,ipv4,ipv6,netmask,wildcard mask");
    }

    @GET
    @Path("/timestamp")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance timestampConverter(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_TIMESTAMP, "/tools/timestamp",
                "Unix 时间戳在线转换与时区推演 - 毫秒/秒/全球时区对照",
                "全功能在线 Unix 时间戳双向转换器。支持秒与毫秒快速互转、全球主要跨国时区对照、相对时间推算、日历星期分析及常用编程语言语法速查。",
                "时间戳转换,Unix timestamp,时区转换,毫秒时间戳,UTC时间,时间计算",
                "Unix Timestamp & Timezone Converter - Seconds/Millis/UTC",
                "Convert Unix epoch seconds and milliseconds bidirectionally, compare global timezones, calculate relative time and copy code snippets.",
                "unix timestamp converter,epoch converter,milliseconds,timezone converter,utc");
    }

    @GET
    @Path("/log-formatter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance logFormatter(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_LOG_FORMATTER, "/tools/log-formatter",
                "日志格式化与结构化清洗工具 - JSON/堆栈跟踪/Nginx日志",
                "开发者日志格式化与结构化清洗分析工具。支持 JSON/NDJSON 展开美化、Java Exception 堆栈跟踪高亮、Nginx 日志解析、级别过滤与即时关键词搜索。",
                "日志格式化,JSON日志,Java堆栈日志,Nginx日志,日志高亮,日志清洗,开发调试",
                "Log Formatter & Cleaner - JSON/NDJSON, Stack Trace & Nginx Logs",
                "Format JSON/NDJSON logs, highlight Java/Spring stack traces, parse Nginx access logs and filter log levels locally in your browser.",
                "log formatter,ndjson,java stack trace,nginx log parser,log cleaner");
    }

    @GET
    @Path("/base-encoder")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance baseEncoder(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_BASE_ENCODER, "/tools/base-encoder",
                "Base64 / Base 编码全家桶在线转换 - Base16/Base32/Base58/Base64",
                "全能 Base 编码全家桶转换器。支持标准 Base64、URL 安全 Base64、Base16 (Hex)、Base32、Base58 (区块链/比特币变体)，原生支持 UTF-8 中文与二进制安全处理。",
                "Base64编码,Base64解码,URL安全Base64,Base16,Hex,Base32,Base58,字符编码",
                "Base64 / Base16 / Base32 / Base58 Online Encoder & Decoder",
                "Encode and decode standard Base64, URL-safe Base64, Base16 (Hex), Base32 and Base58 locally with full UTF-8 support.",
                "base64 encoder,base64 decoder,url safe base64,base32,base58,hex");
    }

    @GET
    @Path("/cron")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance cronGenerator(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_CRON, "/tools/cron",
                "Cron 表达式在线生成与执行时间模拟器 - Crontab/Quartz",
                "全功能 Cron 表达式在线生成与推演模拟器。支持 Linux 5段式与 Quartz 6段式，提供直观分段选择、人类自然语言翻译、未来多次执行时间推算及编程语言代码模板。",
                "Cron表达式,Crontab生成器,Quartz Cron,定时任务,执行时间模拟,Cron在线解析",
                "Cron Expression Generator & Execution Simulator - Crontab & Quartz",
                "Build and validate 5-field Linux Crontab and 6-field Quartz/Spring Cron expressions, read plain-English meanings and preview next runs.",
                "cron generator,crontab,quartz cron,cron parser,cron schedule");
    }

    @GET
    @Path("/regex")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance regexTester(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_REGEX, "/tools/regex",
                "正则表达式在线测试与速查工具 - RegExp 调试与替换",
                "现代正则表达式在线调试与测试工具。支持多修饰符切换、即时多色块高亮、捕获组拆解、字符串替换预览及手机号/邮箱/IP等常用经典正则一键套用。",
                "正则表达式,Regex测试,RegExp调试,正则高亮,正则捕获组,常用正则表达式",
                "Regex Tester, Capture Group Debugger & Cheat Sheet",
                "Test JavaScript RegExp patterns with live match highlighting, capture group inspection, replacement preview and common pattern presets.",
                "regex tester,regexp debugger,capture groups,regex cheat sheet");
    }

    @GET
    @Path("/hash")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance hashCalculator(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_HASH, "/tools/hash",
                "现代哈希散列计算与本地文件校验器 - MD5/SHA256/SM3/HMAC",
                "纯本地隐私安全哈希计算器。支持 MD5、SHA-1、SHA-256、SHA-512 与国密 SM3 算法，原生硬件加速，支持纯文本与本地大文件拖拽校验、HMAC 密钥及校验码智能比对。",
                "哈希计算,MD5计算,SHA256,SHA512,国密SM3,文件校验,Checksum,HMAC",
                "Local Hash Calculator & File Checksum Verifier - MD5/SHA256/SM3/HMAC",
                "Compute MD5, SHA-1, SHA-256, SHA-512, SM3 and HMAC digests for text or local files directly in your browser without uploading.",
                "hash calculator,md5,sha256,sha512,sm3,hmac,file checksum");
    }

    @GET
    @Path("/json-diff")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance jsonDiff(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_JSON_DIFF, "/tools/json-diff",
                "JSON 在线格式化与可视化差分对比 (Diff) - 美化/清洗/修复/对比",
                "全能 JSON 在线工具箱。支持 JSON 语法美化、紧凑压缩、单引号/尾随逗号容错修复、转义去转义，以及双栏结构化差异可视化比对（增、删、改精准高亮）。",
                "JSON格式化,JSON美化,JSON Diff,JSON对比,JSON压缩,JSON修复,JSONPath",
                "JSON Formatter, Repair & Side-by-Side Visual Diff",
                "Format, minify, repair relaxed JSON, unescape strings and compare two JSON documents side by side with structured diff highlights.",
                "json formatter,json diff,json compare,json repair,json minifier");
    }

    @GET
    @Path("/serial")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance webSerial(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_SERIAL, "/tools/serial",
                "Web Serial 串口终端 - 浏览器内 USB 串口调试控制台",
                "纯前端 Web Serial 串口终端。无需安装驱动或客户端，直接在浏览器内配置波特率、数据位、校验位、停止位和流控，连接 USB 串口设备并进行 xterm 终端调试，数据完全留在本机。",
                "Web Serial,串口终端,在线串口调试,USB串口,波特率,xterm,串口助手",
                "Web Serial Terminal - Browser USB/UART Console",
                "Connect to USB and UART serial devices directly in your browser using the Web Serial API and xterm.js with zero data leaving your machine.",
                "web serial,serial terminal,uart debugger,usb serial,xterm");
    }

    @GET
    @Path("/file-transfer")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance fileTransfer(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_FILE_TRANSFER, "/tools/file-transfer",
                "免服务器 P2P 文件快传 - WebRTC 端到端加密与 WASM 纠错传输",
                "纯前端免服务器 P2P 文件快传工具。通过二维码或压缩连接码完成零服务器信令配对，支持 ECDH 密钥交换与 AES-GCM 防监听加密、Rust WASM Reed-Solomon 前向纠错、大文件流式落盘与 SHA-256 完整性校验。",
                "P2P文件传输,免服务器快传,WebRTC文件传输,二维码配对,端到端加密,ECDH,Reed-Solomon,WASM",
                "Serverless P2P File Transfer - Encrypted WebRTC & WASM ECC",
                "Send files and text peer-to-peer without a server using WebRTC DataChannel, QR/code signaling, ECDH+AES-GCM encryption and WASM Reed-Solomon ECC.",
                "p2p file transfer,webrtc file transfer,encrypted file sharing,serverless transfer");
    }

    @GET
    @Path("/image-generator")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance imageGenerator(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_IMAGE_GENERATOR, "/tools/image-generator",
                "程序化图像生成与全能图像处理工作台 - 免费在线图像处理与图像转码",
                "纯前端免费在线图像处理、程序化图像生成与免费图像转码工作台。支持柏林噪声/Low-Poly/分形等18种算法生图，卷积滤镜、复古抖动与 PNG/WebP/ICO 零上传转码。",
                "在线图像处理,免费图像转码,免费在线图像处理,在线图像生成,免费图片处理,图片格式转换,图片生成器,程序化图像生成,本地图片转码,在线图片滤镜,Perlin噪声,Low-Poly生成,分形生成,Floyd-Steinberg抖动,卷积滤镜,ICO生成",
                "Procedural Image Generator & Free Online Image Processor",
                "Free zero-upload online image processor, procedural art generator (Perlin fBm, Low-Poly, fractals) & local PNG/WebP/ICO transcoder.",
                "online image processor,free image transcoder,free online image editor,procedural image generator,local image converter,perlin noise generator,low poly generator,dithering tool,favicon ico generator");
    }

    @GET
    @Path("/image-converter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance imageConverter(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_IMAGE_CONVERTER, "/tools/image-converter",
                "免费在线图片格式转换器 - WebP/PNG/JPG/AVIF/ICO/SVG/BMP/Base64 极速纯本地批量互转",
                "100% 免费、毫秒级极速、零服务器上传的纯浏览器本地图片格式转换器。全面支持 WebP 转 PNG、WebP 转 JPG、PNG 转 WebP、JPG 转 WebP、AVIF 转 JPG/PNG、PNG/JPG/SVG 转多尺寸 Favicon.ico 图标、SVG 矢量转 PNG、BMP 无损位图互转、GIF 静帧转 PNG 以及图片与 Base64 DataURL 双向批量转换，自动清除 EXIF/GPS 隐私信息。",
                "图片格式转换器,免费图片格式转换,本地图片转码,在线图片转换,WebP转PNG,WebP转JPG,PNG转WebP,JPG转WebP,AVIF转JPG,AVIF转PNG,PNG转AVIF,PNG转ICO,JPG转ICO,SVG转ICO,Favicon在线生成,SVG转PNG,SVG转JPG,BMP转PNG,PNG转BMP,GIF转PNG,图片转Base64,Base64转图片,批量图片压缩转换,清除EXIF信息",
                "Free Fast Local Image Format Converter - WebP, PNG, JPG, AVIF, ICO, SVG, BMP & Base64 Batch Transcoder",
                "100% free, blazing-fast, zero-upload local browser image converter and compressor. Batch convert WebP to PNG, WebP to JPG, PNG/JPG to WebP, AVIF to PNG/JPG, PNG/SVG to multi-size Favicon ICO, SVG to PNG, BMP to PNG/JPG, and Image to Base64 DataURL with automatic EXIF privacy stripping.",
                "free image converter,local image transcoder,webp to png,webp to jpg,png to webp,jpg to webp,avif to png,avif to jpg,png to ico,svg to ico,favicon generator,svg to png,bmp to png,png to bmp,image to base64,base64 to image,batch image converter,remove exif metadata");
    }

    @GET
    @Path("/ai-token")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance aiToken(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_AI_TOKEN, "/tools/ai-token",
                "AI Token 计算器与 Prompt 变量调试台 - GPT/Claude/Gemini/DeepSeek 成本估算",
                "纯本地 AI Token 估算与 Prompt 调试工具。实时统计中英文字符、词数与各主流大模型 Token 估算，对比输入/输出 API 成本，支持 System/User Prompt {{变量}} 自动提取与渲染预览。",
                "AI Token计算,Token估算,LLM成本计算,Prompt调试,GPT Token,Claude Token,DeepSeek Token",
                "AI Token Calculator & Prompt Variable Playground",
                "Estimate LLM tokens and API costs for GPT, Claude, Gemini and DeepSeek models locally, with {{variable}} prompt template extraction and preview.",
                "ai token calculator,llm token counter,prompt playground,openai pricing,claude tokens");
    }

    @GET
    @Path("/jwt")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance jwtDecoder(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_JWT, "/tools/jwt",
                "JWT 在线解析与本地签名验证器 - HS256/HS384/HS512 离线校验",
                "纯浏览器本地 JWT (JSON Web Token) 解析与签名验证工具。无需上传敏感令牌，即时解析 Header/Payload、校验 exp/nbf/iat 过期时间，支持 HS256/384/512 密钥签名验证与测试 Token 生成。",
                "JWT解析,JWT解码,JWT签名验证,HS256校验,Token过期时间,JSON Web Token",
                "Local JWT Decoder & Signature Verifier - HS256/HS384/HS512",
                "Decode JSON Web Tokens locally in your browser without uploading secrets. Inspect claims, check exp/nbf/iat timestamps and verify HMAC signatures.",
                "jwt decoder,jwt verifier,json web token,hs256,jwt debugger");
    }

    @GET
    @Path("/config-converter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance configConverter(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_CONFIG_CONVERTER, "/tools/config-converter",
                "YAML / JSON / Properties / .env 配置文件在线互转与校验",
                "纯前端零上传的配置文件格式转换器。支持 YAML、JSON、Java/Quarkus/Spring application.properties 与 .env 环境变量双向互转、语法校验与一键复制。",
                "YAML转JSON,JSON转YAML,Properties转YAML,.env转换,Quarkus配置,Spring配置转换",
                "YAML / JSON / Properties / .env Online Config Converter",
                "Convert and validate YAML, JSON, Java/Quarkus application.properties and .env files locally in your browser with zero server uploads.",
                "yaml to json,json to yaml,properties to yaml,env converter,config converter");
    }

    @GET
    @Path("/curl-converter")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance curlConverter(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_CURL_CONVERTER, "/tools/curl-converter",
                "cURL 命令转代码与 URL 参数可视化拆解器 - Fetch/Python/Java/Go",
                "在线 cURL 命令解析与代码生成器，一键将 cURL 转换为 JavaScript fetch、Python requests、Java HttpClient 与 Go 代码，并提供 URL 协议、路径与 Query 参数可视化编辑。",
                "cURL转代码,cURL转fetch,cURL转Python,cURL转Java,URL解析,Query参数编辑",
                "cURL to Code Converter & URL Query Inspector",
                "Convert cURL commands to JavaScript fetch, Python requests, Java HttpClient and Go code, and inspect or edit URL query parameters visually.",
                "curl converter,curl to fetch,curl to python,curl to java,url parser");
    }

    @GET
    @Path("/id-generator")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance idGenerator(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_ID_GENERATOR, "/tools/id-generator",
                "UUID v4/v7、ULID 与雪花 ID (Snowflake) 批量生成与反解析",
                "在线批量生成 UUID v4、UUID v7、ULID 与分布式雪花 ID，支持自定义纪元与节点 ID，并可反向解析雪花 ID、ULID 与 UUID v7 内嵌的毫秒级时间戳。",
                "UUID生成器,UUID v7,ULID生成,雪花ID生成,Snowflake反解析,分布式ID",
                "UUID v4/v7, ULID & Snowflake ID Generator and Timestamp Inspector",
                "Batch generate UUID v4, UUID v7, ULID and Snowflake IDs locally, and decode embedded millisecond timestamps and worker metadata.",
                "uuid generator,uuid v7,ulid,snowflake id parser,distributed id");
    }

    @GET
    @Path("/jsonl-to-table")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance jsonlToTable(@Context HttpHeaders headers) {
        return renderTool(headers, PageView.TOOLS_JSONL_TABLE, "/tools/jsonl-to-table",
                "JSONL (NDJSON) 在线转表格与 CSV/Markdown 导出工具",
                "纯浏览器本地 JSONL / NDJSON 数据解析与表格可视化工具。支持嵌套对象自动扁平化、列筛选与排序、关键词检索，以及一键导出 CSV、Markdown 表格和标准 JSON 数组。",
                "JSONL转表格,NDJSON转表格,JSONL转CSV,JSONL转Markdown,JSON Lines查看器",
                "JSONL (NDJSON) to Table Viewer & CSV / Markdown Exporter",
                "Parse JSONL / NDJSON files into an interactive sortable and filterable table locally, flatten nested fields and export to CSV, Markdown or JSON.",
                "jsonl to table,ndjson viewer,jsonl to csv,jsonl to markdown,json lines");
    }

    private TemplateInstance renderTool(HttpHeaders headers, PageView view, String path,
                                        String zhTitle, String zhDesc, String zhKeywords,
                                        String enTitle, String enDesc, String enKeywords) {
        String lang = languageContext.getLang();
        boolean en = isEnglish(lang);
        String title = en ? enTitle : zhTitle;
        String desc = en ? enDesc : zhDesc;
        String keywords = en ? enKeywords : zhKeywords;
        String jsonLd = structuredDataService.toolApplication(title, desc, path, lang, i18n.t("tools.title"));
        ToolItem tool = findToolByPath(path);
        String ogImageUrl = tool == null
                ? null
                : canonicalUrlService.buildLocalizedPath("/og/tool/" + tool.id() + ".png", lang);
        return pageRenderer.render(view, headers, toolModel(headers, title, desc, keywords, jsonLd, ogImageUrl));
    }

    private static boolean isEnglish(String lang) {
        return lang != null && lang.toLowerCase().startsWith("en");
    }

    private PageRenderModel toolModel(HttpHeaders headers, String pageTitle,
                                      String pageDescription, String pageKeywords, String jsonLd) {
        return toolModel(headers, pageTitle, pageDescription, pageKeywords, jsonLd, null);
    }

    private PageRenderModel toolModel(HttpHeaders headers, String pageTitle,
                                      String pageDescription, String pageKeywords,
                                      String jsonLd, String ogImageUrl) {
        PageRenderModel model = new PageRenderModel()
                .put("pjaxRequest", PjaxHelper.isPjaxRequest(headers))
                .put("pageTitle", pageTitle)
                .put("pageDescription", pageDescription)
                .put("pageKeywords", pageKeywords)
                .put("jsonLd", jsonLd);
        if (ogImageUrl != null && !ogImageUrl.isBlank()) {
            model.put("ogImageUrl", ogImageUrl);
        }
        return model;
    }
}
