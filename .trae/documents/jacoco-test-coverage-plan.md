# JaCoCo 测试覆盖率配置计划

## 当前状态分析

1. **已存在的配置**：

    * `pom.xml` 中已包含 `quarkus-jacoco` 依赖（第132-134行）

    * 项目使用 Maven 构建工具

    * 已有一个单元测试：`LanguageHelperTest.java`

2. **需要完善的内容**：

    * 配置 Surefire 插件以支持 JaCoCo 覆盖率报告

    * 添加集成测试支持（可选）

***

## 实施步骤

### 步骤 1：更新 Surefire 插件配置

修改 `pom.xml` 中的 `maven-surefire-plugin` 配置，添加 JaCoCo 相关的系统属性：

```xml
<plugin>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>${surefire-plugin.version}</version>
    <configuration>
        <argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
        <systemPropertyVariables>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            <maven.home>${maven.home}</maven.home>
            <!-- JaCoCo 配置 -->
            <quarkus.jacoco.data-file>${project.build.directory}/jacoco.exec</quarkus.jacoco.data-file>
            <quarkus.jacoco.report-location>${project.build.directory}/coverage</quarkus.jacoco.report-location>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

### 步骤 2：添加 Failsafe 插件的 JaCoCo 配置（用于集成测试）

```xml
<plugin>
    <artifactId>maven-failsafe-plugin</artifactId>
    <version>${surefire-plugin.version}</version>
    <executions>
        <execution>
            <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <argLine>--add-opens java.base/java.lang=ALL-UNNAMED</argLine>
        <systemPropertyVariables>
            <native.image.path>${project.build.directory}/${project.build.finalName}-runner</native.image.path>
            <java.util.logging.manager>org.jboss.logmanager.LogManager</java.util.logging.manager>
            <maven.home>${maven.home}</maven.home>
            <!-- JaCoCo 配置 -->
            <quarkus.jacoco.data-file>${project.build.directory}/jacoco.exec</quarkus.jacoco.data-file>
            <quarkus.jacoco.report-location>${project.build.directory}/coverage</quarkus.jacoco.report-location>
        </systemPropertyVariables>
    </configuration>
</plugin>
```

### 步骤 3：验证配置

运行测试并生成覆盖率报告：

```bash
mvn clean test
```

覆盖率报告将生成在：

* 数据文件：`target/jacoco.exec`

* HTML 报告：`target/coverage/index.html`

完成后确认报告文件存在，否则重新检查问题

***

## 关键配置说明

| 属性                               | 说明              |
|----------------------------------|-----------------|
| `quarkus.jacoco.data-file`       | JaCoCo 执行数据文件路径 |
| `quarkus.jacoco.report-location` | 覆盖率报告输出目录       |

***

## 使用方式

1. **运行测试并生成覆盖率报告**：

   ```bash
   mvn clean test
   ```

2. **查看覆盖率报告**：
   打开 `target/coverage/index.html` 文件

3. **运行集成测试**（如果有）：

   ```bash
   mvn clean verify
   ```

***

## 注意事项

1. **原生模式不支持**：JaCoCo 不支持原生镜像模式下的代码覆盖率
2. **不要同时使用 JaCoCo Maven 插件**：`quarkus-jacoco` 扩展已包含所有必要功能，无需额外配置 JaCoCo Maven 插件
3. **argLine 配置**：如果配置了 `argLine`，需要确保 JaCoCo 代理能正确添加（通过系统

