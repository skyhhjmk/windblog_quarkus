package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;
import com.biliwind.blog.model.LinkMonitorLog;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class LinkMonitorService {
    private static final Pattern INVALID_HREF_PATTERN = Pattern.compile("^(#|javascript:void|javascript:;|$)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Logger LOG = Logger.getLogger(LinkMonitorService.class);

    /**
     * 提取所有潜在可点击的元素
     */
    private static Elements extractAllClickableElements(Document doc) {
        // 组合选择器：覆盖a标签、按钮、自定义可点击元素
        String clickableSelector =
                // 1. 有href的a标签（先粗筛，后续排除无效href）
                "a[href]," +
                        // 2. 按钮类（button/input按钮）有onclick跳转
                        "button[onclick*=location.href], input[type=button][onclick*=location.href], input[type=submit][onclick*=location.href]," +
                        // 3. 普通元素有跳转逻辑 + 可点击标识
                        "*[onclick*=window.location], *[onclick*=window.open], *[role=link][tabindex], *[data-href], *[data-link]";

        return doc.select(clickableSelector);
    }

    /**
     * 过滤出真正可见的元素
     */
    private static Elements filterVisibleElements(Elements elements) {
        Elements visibleElements = new Elements();
        for (Element elem : elements) {
            if (isElementTrulyVisible(elem)) {
                visibleElements.add(elem);
            }
        }
        return visibleElements;
    }

    /**
     * 递归判断元素是否真正可见
     */
    private static boolean isElementTrulyVisible(Element elem) {
        // 终止条件：到html根节点则返回true
        if (elem == null || "html".equals(elem.tagName())) {
            return true;
        }

        // 1. 检查隐藏属性
        if (elem.hasAttr("hidden") || "true".equals(elem.attr("aria-hidden"))) {
            return false;
        }

        // 2. 解析样式（兼容内联style和可能的style属性拆分）
        String style = elem.attr("style").toLowerCase().replaceAll("\\s+", "");
        // 检查display/visibility/opacity
        if (style.contains("display:none") || style.contains("visibility:hidden") || style.contains("opacity=0")) {
            return false;
        }
        // 检查尺寸隐藏
        if (style.contains("width=0") && style.contains("height=0")) {
            return false;
        }

        // 3. 检查定位隐藏（视觉外偏移）
        if (style.contains("position:absolute") && (style.contains("left=-9999px") || style.contains("top=-9999px"))) {
            return false;
        }

        // 4. 递归检查父元素（关键！父元素隐藏则子元素也不可见）
        return isElementTrulyVisible(elem.parent());
    }

    /**
     * 提取元素的有效链接
     */
    private static String extractLinkFromElement(Element elem) {
        String tag = elem.tagName().toLowerCase();

        // 处理a标签（排除无效href）
        if ("a".equals(tag)) {
            String href = elem.attr("abs:href"); // 转为绝对URL
            return INVALID_HREF_PATTERN.matcher(href).matches() ? "无效链接" : href;
        }

        // 处理onclick中的链接（简单正则提取，可根据实际场景优化）
        String onclick = elem.attr("onclick");
        if (!onclick.isEmpty()) {
            // 匹配 window.location.href='xxx' 或 window.open('xxx')
            Pattern pattern = Pattern.compile("(location\\.href|window\\.open)\\(['\"]([^'\"]+)['\"]\\)");
            var matcher = pattern.matcher(onclick);
            if (matcher.find()) {
                return matcher.group(2);
            }
        }

        // 处理自定义属性
        if (elem.hasAttr("data-href")) {
            return elem.attr("abs:data-href");
        }
        if (elem.hasAttr("data-link")) {
            return elem.attr("abs:data-link");
        }

        return "未提取到有效链接";
    }

    // 建议使用Quarkus的RestClient或注入ManagedClient，避免手动创建
    private Client createHttpClient() {
        return ClientBuilder.newBuilder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 定时任务入口 - 每6小时执行一次
     */
    @Scheduled(every = "6h", identity = "link-monitor")
    @Transactional
    public void scheduleCheck() {
        LOG.info("=== Link Monitor Scheduler: Triggered ===");
        try {
            checkAllLinks();
            LOG.info("=== Link Monitor Scheduler: Completed ===");
        } catch (Exception e) {
            LOG.error("=== Link Monitor Scheduler: Failed ===", e);
        }
    }

    /**
     * 检查所有链接状态（可手动调用）
     */
    @Transactional
    public void checkAllLinks() {
        LOG.info("Link Monitor: Starting to check all links...");

        // 获取所有需要监控的链接
        List<Link> links = Link.listAll();
        LOG.info(String.format("Link Monitor: Found %d links to check", links.size()));

        if (links.isEmpty()) {
            LOG.warn("Link Monitor: No links found in database");
            return;
        }

        // 遍历检查每个链接
        int successCount = 0;
        int failCount = 0;
        for (Link link : links) {
            LOG.info(String.format("Link Monitor: Checking link [%s] - %s", link.id, link.url));
            try {
                checkLink(link, false);
                successCount++;
            } catch (Exception e) {
                LOG.error(String.format("Link Monitor: Failed to check link [%s] - %s", link.id, link.url), e);
                failCount++;
            }
        }

        LOG.info(String.format("Link Monitor: Check completed. Success: %d, Failed: %d", successCount, failCount));
    }

    /**
     * 检查单个链接状态并记录日志
     *
     * @param link     当前检查的链接
     * @param readOnly 是否只读（不更新链接状态）
     */
    @Transactional
    public void checkLink(Link link, boolean readOnly) {
        // 创建监控日志对象
        LinkMonitorLog log = new LinkMonitorLog();
        log.link = link;
        log.checkTime = OffsetDateTime.now();

        long start = System.currentTimeMillis();
        try (Client client = createHttpClient()) {
            // 发送HTTP请求
            try (Response response = client.target(link.url).request().get()) {
                log.statusCode = response.getStatus();
                log.ok = (log.statusCode >= 200 && log.statusCode < 300);
                log.loadTimeMs = (int) (System.currentTimeMillis() - start);

                // 状态码正常则检查反向链接
                if (log.ok) {
                    String content = response.readEntity(String.class);

                    String target = "biliwind.com";
                    // TODO: 改为从配置表中读取
                    log.backlinkFound = this.checkHtmlDom(content, target);
                } else {
                    log.backlinkFound = false;
                }

                LOG.debug(String.format("Link [%s] checked: Status=%d, OK=%s, LoadTime=%dms",
                        link.id, log.statusCode, log.ok, log.loadTimeMs));
            }
        } catch (Exception e) {
            // 处理异常情况
            log.ok = false;
            log.statusCode = 0;
            log.loadTimeMs = (int) (System.currentTimeMillis() - start);
            log.backlinkFound = false;

            Map<String, Object> errorData = new HashMap<>();
            errorData.put("error", e.getMessage());
            errorData.put("error_type", e.getClass().getSimpleName());
            log.rawData = errorData;

            LOG.warn(String.format("Link [%s] check failed: %s", link.id, e.getMessage()));
        }

        // 保存监控日志
        log.persist();
        LOG.debug(String.format("Link Monitor Log saved for link [%s]", link.id));

        // TODO: 更新链接状态
        if (!readOnly) {
//            link.lastCheckTime = log.checkTime;
//            link.lastStatusCode = log.statusCode;
//            link.isAvailable = log.ok;
//            link.lastLoadTimeMs = log.loadTimeMs;
//            link.persist(); // 更新链接状态到数据库
            LOG.debug(String.format("Link [%s] status updated: Available=%s", link.id, log.ok));
        }
    }

    /**
     * @param html   要检测的 HTML
     * @param target 目标链接
     * @return 是否找到目标链接
     */
    private boolean checkHtmlDom(String html, String target) {
        Document doc = Jsoup.parse(html);

        // 提取所有潜在可点击元素
        Elements allClickableElements = extractAllClickableElements(doc);

        // 过滤出真正可见的元素
        Elements visibleClickableElements = filterVisibleElements(allClickableElements);

        if (!visibleClickableElements.isEmpty()) {
            for (Element elem : visibleClickableElements) {
                String link = extractLinkFromElement(elem);
                if (link != null && !INVALID_HREF_PATTERN.matcher(link).find() && link.contains(target)) {
                    return true;
                }
            }
        }
        return false;
    }
}