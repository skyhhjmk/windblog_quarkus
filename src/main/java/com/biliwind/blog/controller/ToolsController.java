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
}
