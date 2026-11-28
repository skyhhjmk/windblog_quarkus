package com.biliwind.blog.common.helper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站内搜索内置词典与停用词目录（写在 Java 源码中，统一服务于 Elasticsearch 索引模板、
 * 查询词切分与数据库降级搜索）。
 *
 * <p>包含：
 * <ul>
 *   <li>中英文停用词表（用于过滤“的”“我”“the”“is”等高频无区分度虚词）</li>
 *   <li>中英文及各领域技术名词、专有名词自定义词典（如“我的世界”“Spring Boot”“Kubernetes”）</li>
 *   <li>默认同义词与中英/缩写映射（如“我的世界, minecraft”“k8s, kubernetes”）</li>
 * </ul>
 */
public final class SearchDictionaryCatalog {

    private static final Pattern ASCII_TOKEN_PATTERN = Pattern.compile("[a-z0-9][a-z0-9.+#/_-]*");

    private static final List<String> CHINESE_STOPWORDS = List.of(
            "的", "了", "和", "是", "就", "都", "而", "及", "与", "着", "或", "一个", "没有",
            "我们", "你们", "他们", "她们", "它们", "自己", "这个", "那个", "这些", "那些",
            "这样", "那样", "什么", "怎么", "如何", "为什么", "为何", "可以", "能够", "不能",
            "因为", "所以", "但是", "然而", "如果", "假如", "虽然", "尽管", "而且", "并且",
            "或者", "还是", "只是", "已经", "正在", "将要", "可能", "应该", "需要", "必须",
            "通过", "关于", "对于", "根据", "按照", "由于", "为了", "针对", "除了", "以及",
            "之", "其", "于", "以", "为", "在", "从", "到", "把", "被", "让", "给", "向", "往",
            "对", "比", "跟", "同", "我", "你", "您", "他", "她", "它", "咱", "咱们",
            "吗", "呢", "吧", "啊", "呀", "哦", "哈", "啦", "嘛", "么", "罢了",
            "也", "还", "又", "再", "才", "只", "就", "很", "太", "更", "最", "挺", "非常",
            "十分", "比较", "有点", "一些", "许多", "很多", "大量", "各种", "所有", "每个",
            "任何", "某", "某个", "其中", "之一", "等等", "之类", "的话", "来说", "而言",
            "方面", "时候", "地方", "这里", "那里", "哪里", "这儿", "那儿", "哪儿",
            "是否", "能否", "是否需要", "进行", "使用", "实现", "相关", "基于", "一种", "一下"
    );

    private static final List<String> ENGLISH_STOPWORDS = List.of(
            "a", "an", "the", "and", "or", "but", "if", "then", "else", "when", "where",
            "why", "how", "all", "any", "both", "each", "few", "more", "most", "other",
            "some", "such", "no", "nor", "not", "only", "own", "same", "so", "than",
            "too", "very", "can", "will", "just", "should", "now", "is", "am", "are",
            "was", "were", "be", "been", "being", "have", "has", "had", "having",
            "do", "does", "did", "doing", "would", "could", "might", "must", "shall",
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your",
            "yours", "yourself", "yourselves", "he", "him", "his", "himself", "she",
            "her", "hers", "herself", "it", "its", "itself", "they", "them", "their",
            "theirs", "themselves", "what", "which", "who", "whom", "this", "that",
            "these", "those", "of", "at", "by", "for", "with", "about", "against",
            "between", "into", "through", "during", "before", "after", "above", "below",
            "to", "from", "up", "down", "in", "out", "on", "off", "over", "under",
            "again", "further", "once", "here", "there", "as", "until", "while"
    );

    /**
     * 自定义词典：覆盖中英文专有名词、游戏/文化名词、编程语言、框架、数据库、云原生、AI/LLM、网络与安全等。
     * 按长度降序排列以支持最长前缀匹配。
     */
    private static final List<String> CUSTOM_TERMS = List.of(
            // 游戏 / 文化 / 常见中文专有名词
            "我的世界", "Minecraft", "基岩版", "Java版", "红石电路", "整合包", "光影包",
            "塞尔达传说", "黑神话悟空", "原神", "星露谷物语", "泰拉瑞亚", "艾尔登法环",
            "赛博朋克2077", "哔哩哔哩", "B站",

            // 编程语言与运行时
            "Java 21", "Java", "JDK", "JVM", "GraalVM", "Native Image",
            "Kotlin", "Scala", "Groovy", "Python", "TypeScript", "JavaScript", "ECMAScript",
            "Node.js", "Deno", "Bun", "Golang", "Go语言", "Rust", "C++", "C#", ".NET",
            "Swift", "Dart", "PHP", "Ruby", "Lua", "WebAssembly", "WASM",

            // 后端与全栈框架
            "Spring Boot", "Spring Cloud", "Spring Framework", "Spring Security",
            "Quarkus", "Jakarta EE", "Hibernate ORM", "Hibernate", "Panache",
            "Netty", "Vert.x", "gRPC", "GraphQL", "RESTful", "FastAPI", "Django",
            "Flask", "Express", "NestJS", "Gin", "Actix",

            // 前端与跨端开发
            "Tailwind CSS", "React Native", "Next.js", "Nuxt", "Vue 3", "Vue",
            "React", "Angular", "Svelte", "Vite", "Webpack", "Turbopack",
            "Flutter", "Electron", "Tauri", "PJAX", "HTMX", "SSR", "CSR", "SSG", "ISR", "PWA", "SPA",

            // 数据库与搜索引擎
            "PostgreSQL", "MySQL", "MariaDB", "SQLite", "Redis", "Valkey",
            "Elasticsearch", "OpenSearch", "IK分词器", "Lucene", "MongoDB",
            "ClickHouse", "DuckDB", "TiDB", "OceanBase", "Liquibase", "Flyway",
            "pgvector", "pg_trgm",

            // 消息队列与大数据
            "Apache Kafka", "Kafka", "RabbitMQ", "RocketMQ", "Pulsar", "NATS",
            "Flink", "Spark", "Hadoop", "Debezium", "CDC",

            // 云原生、容器与运维 (DevOps)
            "Docker Compose", "Docker", "Kubernetes", "K8s", "Helm", "Containerd", "Podman",
            "Linux", "Ubuntu", "Debian", "Alpine", "CentOS", "Systemd",
            "Nginx", "OpenResty", "Caddy", "Traefik", "HAProxy", "Envoy", "Istio",
            "GitHub Actions", "GitLab CI", "Jenkins", "ArgoCD", "Terraform", "Ansible",
            "Prometheus", "Grafana", "OpenTelemetry", "Loki", "Jaeger", "Kibana", "Filebeat",
            "Cloudflare", "CDN", "WAF", "反向代理", "负载均衡", "容器化", "云原生",
            "微服务", "分布式系统", "高并发", "高可用", "灰度发布", "蓝绿部署",

            // AI / 大模型 / 数据科学
            "Model Context Protocol", "Prompt Engineering", "Function Calling",
            "Vector Database", "Fine-tuning", "检索增强生成", "自然语言处理",
            "大语言模型", "人工智能", "机器学习", "深度学习", "强化学习", "计算机视觉",
            "提示词工程", "向量数据库", "智能体", "Hugging Face", "LangChain", "LlamaIndex",
            "PyTorch", "TensorFlow", "Transformer", "Embedding", "LLM", "RAG", "MCP",
            "vLLM", "Ollama", "OpenAI", "Gemini", "Claude", "DeepSeek", "Qwen", "通义千问", "LoRA",

            // 网络协议与安全
            "Server-Sent Events", "Zero Trust", "HTTP/3", "HTTP/2", "HTTPS", "QUIC",
            "TLS", "SSL", "mTLS", "DNS", "DoH", "BGP", "TCP/IP", "WebSocket", "SSE",
            "OAuth2", "OIDC", "JWT", "SAML", "Passkey", "WebAuthn", "CSRF", "XSS", "CORS",
            "SQL注入", "零信任", "端到端加密", "非对称加密", "数字签名",

            // SEO / 博客与内容工程
            "Schema.org", "OpenGraph", "JSON-LD", "Sitemap", "Markdown", "WindBlog", "SEO", "GEO", "AMP", "RSS"
    );

    /**
     * 默认同义词与多语言映射规则（Elasticsearch synonym_graph 格式）。
     */
    private static final List<String> DEFAULT_SYNONYMS = List.of(
            "我的世界, minecraft",
            "塞尔达, 塞尔达传说",
            "黑神话, 黑神话悟空",
            "b站, bilibili, 哔哩哔哩",
            "k8s, kubernetes",
            "pg, postgres, postgresql",
            "es, elasticsearch",
            "ts, typescript",
            "js, javascript",
            "py, python",
            "golang, go语言",
            "wasm, webassembly",
            "llm, 大语言模型",
            "rag, 检索增强生成",
            "ai, 人工智能",
            "ml, 机器学习",
            "dl, 深度学习",
            "nlp, 自然语言处理",
            "db, 数据库",
            "mq, 消息队列",
            "docker compose, docker-compose",
            "spring boot, springboot",
            "tailwind, tailwindcss, tailwind css"
    );

    private static final Set<String> STOPWORD_SET;
    private static final List<String> CUSTOM_TERMS_BY_LENGTH_DESC;

    static {
        Set<String> stopwords = new LinkedHashSet<>();
        for (String w : CHINESE_STOPWORDS) {
            stopwords.add(w.toLowerCase(Locale.ROOT));
        }
        for (String w : ENGLISH_STOPWORDS) {
            stopwords.add(w.toLowerCase(Locale.ROOT));
        }
        STOPWORD_SET = Set.copyOf(stopwords);

        List<String> sortedTerms = new ArrayList<>(new LinkedHashSet<>(CUSTOM_TERMS));
        sortedTerms.sort((a, b) -> Integer.compare(b.length(), a.length()));
        CUSTOM_TERMS_BY_LENGTH_DESC = List.copyOf(sortedTerms);
    }

    private SearchDictionaryCatalog() {
    }

    public static List<String> allStopwords() {
        return List.copyOf(STOPWORD_SET);
    }

    public static List<String> customTerms() {
        return CUSTOM_TERMS_BY_LENGTH_DESC;
    }

    public static List<String> defaultSynonyms() {
        return DEFAULT_SYNONYMS;
    }

    public static boolean isStopword(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return STOPWORD_SET.contains(term.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 使用 Java 内置词典与停用词对查询文本进行预处理切分：
     * 1. 优先匹配自定义词典中的中英文复合词/技术名词（如“我的世界”“Spring Boot”“Docker Compose”）；
     * 2. 提取剩余的英文/数字技术词与连续中文片段；
     * 3. 过滤停用词；若过滤后为空（例如用户只搜了停用词），则回退保留原始词项。
     */
    public static List<String> segmentQuery(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String remaining = query.trim();
        String lowerRemaining = remaining.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();

        for (String term : CUSTOM_TERMS_BY_LENGTH_DESC) {
            String lowerTerm = term.toLowerCase(Locale.ROOT);
            int idx = lowerRemaining.indexOf(lowerTerm);
            while (idx >= 0) {
                if (isBoundaryMatch(lowerRemaining, idx, lowerTerm.length())) {
                    matched.add(remaining.substring(idx, idx + term.length()));
                    String spaces = " ".repeat(term.length());
                    remaining = remaining.substring(0, idx) + spaces + remaining.substring(idx + term.length());
                    lowerRemaining = remaining.toLowerCase(Locale.ROOT);
                    idx = lowerRemaining.indexOf(lowerTerm, idx + term.length());
                } else {
                    idx = lowerRemaining.indexOf(lowerTerm, idx + 1);
                }
            }
        }

        // 处理剩余文本中的空格分隔词与中英文片段
        for (String part : remaining.trim().split("\\s+")) {
            if (part.isBlank()) {
                continue;
            }
            extractSubTokens(part, matched);
        }

        List<String> filtered = matched.stream()
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .filter(token -> !isStopword(token))
                .distinct()
                .limit(12)
                .toList();

        if (!filtered.isEmpty()) {
            return filtered;
        }
        return matched.stream()
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .distinct()
                .limit(12)
                .toList();
    }

    private static void extractSubTokens(String segment, List<String> out) {
        String lower = segment.toLowerCase(Locale.ROOT);
        Matcher asciiMatcher = ASCII_TOKEN_PATTERN.matcher(lower);
        int cursor = 0;
        while (asciiMatcher.find()) {
            if (asciiMatcher.start() > cursor) {
                addChineseSegment(segment.substring(cursor, asciiMatcher.start()), out);
            }
            out.add(segment.substring(asciiMatcher.start(), asciiMatcher.end()));
            cursor = asciiMatcher.end();
        }
        if (cursor < segment.length()) {
            addChineseSegment(segment.substring(cursor), out);
        }
    }

    private static void addChineseSegment(String raw, List<String> out) {
        String cleaned = raw.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "").trim();
        if (cleaned.isEmpty()) {
            return;
        }
        String stripped = stripEdgeChineseStopwords(cleaned);
        if (!stripped.isEmpty()) {
            out.add(stripped);
        }
    }

    private static String stripEdgeChineseStopwords(String text) {
        String current = text;
        boolean changed = true;
        while (changed && !current.isEmpty()) {
            changed = false;
            if (isStopword(current)) {
                return "";
            }
            for (String stopword : CHINESE_STOPWORDS) {
                if (stopword.length() > current.length()) {
                    continue;
                }
                if (current.startsWith(stopword)) {
                    current = current.substring(stopword.length());
                    changed = true;
                    break;
                }
                if (current.endsWith(stopword)) {
                    current = current.substring(0, current.length() - stopword.length());
                    changed = true;
                    break;
                }
            }
        }
        return current;
    }

    private static boolean isBoundaryMatch(String text, int start, int length) {
        int end = start + length;
        boolean asciiTerm = isAsciiWord(text.substring(start, end));
        if (!asciiTerm) {
            return true;
        }
        if (start > 0 && isAsciiAlphaNum(text.charAt(start - 1))) {
            return false;
        }
        return end >= text.length() || !isAsciiAlphaNum(text.charAt(end));
    }

    private static boolean isAsciiWord(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiAlphaNum(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9');
    }
}
