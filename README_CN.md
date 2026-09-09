# NeoForge 版 Conflux Map

这是 Conflux Map 的独立 NeoForge 分支，与 Fabric/Paper 主线分开维护。
本分支可以同时维护多个 NeoForge Minecraft 版本，不再依赖主线的版本矩阵。

## 支持版本

| 模块 | Minecraft | NeoForge | Java |
|---|---|---|---|
| `26.1` | 26.1 | 26.1.0.19-beta | 25 |

后续版本以 `versions/<minecraft-version>` 模块加入。版本目录只保存版本属性
和薄构建包装；NeoForge 的主体源码统一位于根目录 `src/`。

## 安装

从 Releases 下载对应 Minecraft 版本的 JAR，放入 `mods/` 目录。客户端和独立
客户端和服务端使用同一个 JAR，不需要额外的加载器或伴随 API。

客户端提供小地图、全屏地图、地图补全、路径点、结构查找、绘图、PNG 导出和
实体雷达。服务端组件根据 `config/confluxmap/server.json` 提供共享地图、共享
路径点、地形校正、区块加载信息和网页地图。

## 构建

在仓库根目录使用 JDK 25：

```sh
./gradlew :26.1:build
./gradlew :26.1:runClient
./gradlew :26.1:runServer
```

Windows 可使用 `gradlew.bat` 执行相同任务。构建产物位于
`versions/26.1/build/libs/`。

## 目录结构

- `common/`：与加载器无关的协议、存储、预测和网页地图核心。
- `src/`：各 NeoForge 版本共用的客户端与服务端实现。
- `versions/`：Minecraft 版本属性和每版本的构建包装。
- `native/`：预测器源码及已提交的各平台二进制。

## 许可证

GPL-3.0，详见 [`LICENSE`](LICENSE) 和 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
