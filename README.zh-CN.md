# ThaumicJEI Cleanroom 中文说明

本仓库是 **[Buuz135/Thaumic-JEI](https://github.com/Buuz135/Thaumic-JEI) 的代码重构版本**，沿用其
MIT 许可证，并保留上游 Git 历史作为基线。它与原作者没有隶属或背书关系。

## 目标平台

- Minecraft 1.12.2
- Cleanroom **0.6.13-alpha**
- Java **25**
- Thaumcraft 6.1.BETA26
- JEI 4.16.1 或 HEI 4.32.0+

不支持 Cleanroom 0.7+、Java 8，也不提供原版 ForgeGradle/Java 8 运行组合。

## 解决的问题

原版会在 JEI 查询和 tooltip 路径上按需推导物品要素。大型整合包里，对物品按 **R** 或 **U** 时
可能占用单个核心并造成明显停顿。本版本保留原有配方浏览功能，但把推导移出这条路径：

- tooltip 和配方查询只读不可变快照，未命中时不临时计算；
- 首次生成按客户端 tick 预算执行，可暂停、继续或取消；
- 缓存一次生成、长期只读复用，不做启动指纹、自动失效或未知物品补算；
- 只有完整生成并成功原子保存后才发布新快照，取消或失败保留旧缓存。

这是有意取舍：展示值可以跨启动保持固定，而不是每次跟随 TC 自身扫描的细小变化重新计算。

## 安装

1. 退出游戏，备份原 ThaumicJEI JAR 和缓存。
2. 只保留一个 modid 为 `thaumicjei` 的模组；JEI 与 HEI 不要并装。
3. 安装正式重映射 JAR，不要安装 `-dev`、`-sources` 或差分测试变体。
4. 没有缓存的首次启动需要等待完整生成；以后直接复用
   `config/thaumicjei-cache-v2.json`。

增删模组或修改配方后，是否需要重新生成由你决定：

```text
/thaumicjei status
/thaumicjei pause
/thaumicjei resume
/thaumicjei cancel
/thaumicjei rebuild
```

详细行为见[固定缓存文档](docs/cache.md)。

## 文档导航

- [架构](docs/architecture.md)
- [固定缓存](docs/cache.md)
- [构建](docs/building.md)
- [测试与验证边界](docs/testing.md)
- [上游与归因](docs/upstream/README.md)
- [本重构版变更记录](CHANGELOG.md)

## 构建

```bash
JAVA_HOME=/path/to/java25 ./gradlew \
  -Porg.gradle.java.installations.paths=/path/to/jdk8,/path/to/java25 build
```

JDK 8 只供旧 MCP 工具使用，模组本身编译为 Java 25。发布产物前请阅读[构建说明](docs/building.md)，
特别是差分变体与正式 JAR 的输出路径隔离要求。

## 验证范围

本版本有 58 项测试。beta.3 在隔离 118-mod 副本中验证了 1280×720 窗口和已有固定缓存暖加载：
18,813 个物品、22,702 条关系、650 个来源页，steps=0。重构版的独立实机启动通过，但本轮 R/U 查询未能
取得目标明确、可复核的结果；不把 beta.2 的历史截图当作本版新证据。详细边界见[测试文档](docs/testing.md)。

多人模式不在范围内：这是仅客户端模组，没有自定义网络协议。

许可证见 [LICENSE](LICENSE)，保留原 `Copyright (c) 2017 Buuz135` 声明。
