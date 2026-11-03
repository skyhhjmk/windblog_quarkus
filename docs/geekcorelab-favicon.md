# GeekCoreLab 官方 Favicon 规范 (超频熔炉芯片 - Overclocked Core)

本文件定义了 **GeekCoreLab**（极客核心实验室）网站的官方 Favicon 与品牌徽标规范。

---

## 1. 核心意象与设计理念

![GeekCoreLab Favicon](/favicon.svg)

- **Geek（极客精神）**：外壳由 80 年代经典硅芯片（IC Package）构造而成，左右两侧延伸出 10 枚由于超频运算而泛着红铜/高能琥珀金光的引脚（Pins）；
- **Core（核心算力）**：芯片内部开凿出深沉的硅晶圆槽，中央由大写字母 **G** 环抱着一颗达到极高温度的太阳白炽等离子态能量核（Pure White-Yellow Fusion Core）；
- **Lab（实验室探索）**：顶部开有高热排气减压槽（Thermal Vent），四周微散着高能电子逃逸粒子，生动象征着实验室正在进行 1000% 极限超频测试的极客探索状态。

---

## 2. 色彩矩阵 (Color Tokens)

| 色彩角色 | 色值 (HEX) | 视觉意象 |
| :--- | :--- | :--- |
| **底座背景** | `#080302` | 极客深炭黑背景，保证在亮色与暗色浏览器标签栏上均有清晰独立边界 |
| **芯片外壳** | `#261009` / `#140805` | 哑光碳素高温陶瓷封装体 |
| **红铜引脚** | `#ea580c` -> `#f97316` -> `#fb923c` | 处于超频导电高热状态的金属引脚梯级色 |
| **晶圆凹槽** | `#431407` | 超频硅片深色腔体 |
| **等离子 G-Core** | `#ea580c` / `#f97316` / `#fdba74` | 字母 G 聚能导轨 |
| **白炽聚变核** | `#fde047` / `#ffffff` | 核心超高温白炽像素焦点（即使在 16×16 极小尺寸下依然极度耀眼） |

---

## 3. 工程部署与文件位置

在本项目中，该图标已正式部署并接入：

- **根级静态资源**：[`src/main/resources/META-INF/resources/favicon.svg`](file:///home/hhjmk/codes/windblog_quarkus/src/main/resources/META-INF/resources/favicon.svg)
- **静态资源归档**：[`src/main/resources/META-INF/resources/assets/img/geekcorelab-favicon.svg`](file:///home/hhjmk/codes/windblog_quarkus/src/main/resources/META-INF/resources/assets/img/geekcorelab-favicon.svg)
- **模板自动加载**：[`src/main/resources/templates/base.html`](file:///home/hhjmk/codes/windblog_quarkus/src/main/resources/templates/base.html) 已配置自动回退：
  ```html
  {#if config:faviconUrl}
      <link rel="icon" href="{config:faviconUrl}"/>
  {#else}
      <link rel="icon" type="image/svg+xml" href="/favicon.svg"/>
  {/if}
  ```

---

## 4. 完整矢量代码 (SVG Source)

```xml
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="100%" height="100%" shape-rendering="crispEdges">
  <!-- GeekCoreLab Favicon: Overclocked Molten Plasma Core Chip -->
  <rect x="0" y="0" width="512" height="512" rx="64" fill="#080302"/>

  <!-- Left Pins (Red-Hot Copper / Amber) -->
  <rect x="32" y="128" width="64" height="32" fill="#ea580c"/>
  <rect x="32" y="192" width="64" height="32" fill="#f97316"/>
  <rect x="32" y="256" width="64" height="32" fill="#fb923c"/>
  <rect x="32" y="320" width="64" height="32" fill="#f97316"/>
  <rect x="32" y="384" width="64" height="32" fill="#ea580c"/>

  <!-- Right Pins -->
  <rect x="416" y="128" width="64" height="32" fill="#ea580c"/>
  <rect x="416" y="192" width="64" height="32" fill="#f97316"/>
  <rect x="416" y="256" width="64" height="32" fill="#fb923c"/>
  <rect x="416" y="320" width="64" height="32" fill="#f97316"/>
  <rect x="416" y="384" width="64" height="32" fill="#ea580c"/>

  <!-- Dark Carbon Body -->
  <rect x="96" y="64" width="320" height="384" fill="#261009"/>
  <rect x="112" y="80" width="288" height="352" fill="#140805"/>

  <!-- Top Thermal Vent / Exhaust Notch -->
  <rect x="208" y="64" width="96" height="32" fill="#f97316"/>
  <rect x="224" y="64" width="64" height="16" fill="#fde047"/>

  <!-- Superheated Chamber Recess -->
  <rect x="160" y="128" width="192" height="224" fill="#431407"/>

  <!-- The Molten Plasma G-Core -->
  <!-- Top bar of G -->
  <rect x="192" y="160" width="128" height="32" fill="#ea580c"/>
  <!-- Left wall -->
  <rect x="192" y="192" width="32" height="128" fill="#f97316"/>
  <!-- Bottom bar -->
  <rect x="192" y="288" width="128" height="32" fill="#ea580c"/>
  <!-- Right leg -->
  <rect x="288" y="224" width="32" height="96" fill="#f97316"/>
  <!-- Shelf -->
  <rect x="256" y="224" width="32" height="32" fill="#fdba74"/>

  <!-- Incandescent Fusion Core (Sun-Like White-Yellow Core) -->
  <rect x="224" y="192" width="48" height="48" fill="#fde047"/>
  <rect x="232" y="200" width="32" height="32" fill="#ffffff"/>

  <!-- Thermal Venting Sparks / Energy Bleed -->
  <rect x="144" y="112" width="16" height="16" fill="#ea580c"/>
  <rect x="352" y="112" width="16" height="16" fill="#ea580c"/>
  <rect x="144" y="368" width="16" height="16" fill="#ea580c"/>
  <rect x="352" y="368" width="16" height="16" fill="#ea580c"/>
</svg>
```
