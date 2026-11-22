# MCArgus

[English](README.md) | **简体中文**

Minecraft 服务端智能启动器：自动定位正确的启动目标（Forge / NeoForge 参数文件、Fabric / Quilt / 原版 Jar），顺手整理 mods 目录，并用宿主机原生 Java 启动服务端。

为 Pterodactyl 等面板场景设计，也能在任何有 Java 的环境中运行。

## 功能特性

- **自动发现启动目标**
  - 自动查找 Forge/NeoForge 的 `unix_args.txt` / `win_args.txt`，并按当前操作系统选择。
  - 结果会缓存；路径失效、文件变化或整合包/加载器更新时自动重扫。
  - 找不到参数文件时，退化为 `fabric-server-launch.jar`、`quilt-server-launch.jar` 或 `server.jar` 启动。
  - 能识别 Forge 安装器 Jar，并提示先执行 `--installServer`，而不是给出难以理解的报错。
- **使用系统原生 Java，不折腾系统库**
  - 始终用系统 Java 启动，不替换或干预宿主机的动态链接器与 libc。
  - 若模组原生库要求的 GLIBC 版本高于宿主机，日志会给出说明并建议升级操作系统。
- **mods/ 文件名清理**
  - 清理下载站前缀装饰，如 `[JEI背包管理器[Forge]]jei.jar` → `jei.jar`。
  - 重名时跳过并警告，避免引入重复模组。
  - 启动前执行，并通过指纹快速跳过未变化的目录。
- **压缩包自动安装**
  - 没有可用启动目标时，自动查找服务端压缩包（`zip` / `tar.gz` / `tgz` / `tar`）并安装。
  - 绝不覆盖已存在的文件。
  - 能识别 CurseForge 客户端整合包并明确拒绝（请改用 Server Pack）。
  - 存在多个压缩包且无法判定时直接中止，不盲目选择。
- **便于运维**
  - `--doctor` 输出环境诊断报告（Java、系统、GLIBC、启动目标、mods 预览）。
  - `--dry-run` 只打印最终命令，不启动。
  - 退出码：`0` 正常、`2` 用法错误、`3` 前置检查失败，其余为服务端自身退出码。
  - 控制台 + 滚动日志文件（`mcargus/logs/mcargus.log`，按天滚动，保留 14 天）。
- **界面本地化**
  - 默认简体中文，内置英语。
  - 在配置文件中设置 `locale=en_us` 即可切换（见下文）。

## 环境要求

- **运行：** MCArgus 本身需要 Java 8 或更高版本。Minecraft 服务端仍需其对应版本（例如 Java 17 或 21），可通过 `--java` 或 `java.path` 指定。
- **构建：** JDK 8+（推荐 JDK 17）以及项目自带的 Gradle Wrapper。

## 构建

```bash
# Fat Jar：build/libs/mcargus.jar
./gradlew jar

# 分发包：build/dist/mcargus.jar 与 build/dist/server.jar
./gradlew packageAll
```

`server.jar` 与 `mcargus.jar` 内容相同，只是换了个名字，可直接用于 Pterodactyl 默认 egg（`java -jar server.jar`）。

## 使用

把 `mcargus.jar` 放到服务器根目录，然后运行：

```bash
java -jar mcargus.jar
```

也可以把它改名为 `server.jar`，面板的启动命令无需修改。

### 选项

| 选项 | 说明 |
| --- | --- |
| `--dir <目录>` | 服务器根目录（默认：当前目录；Pterodactyl 下为 `/home/container`） |
| `--java <路径>` | 服务端使用的 Java（可执行文件或 `JAVA_HOME` 目录；默认自动） |
| `--args-file <文件>` | 手动指定参数文件，跳过自动发现 |
| `--jvm "<参数>"` | 追加 JVM 参数（空格分隔），优先于 `jvm.extraArgs` |
| `--program-args "<参数>"` | 服务端程序参数（默认 `nogui`；传空字符串可清空） |
| `--rescan` | 忽略缓存与扫描状态，强制完整重扫 |
| `--sanitize` | 完整扫描并修复 `mods/` 文件名，然后退出 |
| `--no-sanitize` | 本次跳过 mods 扫描与清理 |
| `--dry-run` | 只打印将执行的完整命令，不启动 |
| `--doctor` | 输出环境诊断报告 |
| `--config <文件>` | 配置文件路径（默认 `<服务器目录>/mcargus/mcargus.properties`） |
| `--version`、`--help` | 显示版本或帮助 |

`--` 之后的参数会透传给服务端。

### 示例

```bash
# 诊断环境
java -jar mcargus.jar --dir /srv/mc --doctor

# 查看将要执行的命令
java -jar mcargus.jar --dir /srv/mc --dry-run

# 只清理 mod 文件名
java -jar mcargus.jar --dir /srv/mc --sanitize

# 指定 Java 并强制完整重扫
java -jar mcargus.jar --dir /srv/mc --java /usr/lib/jvm/java-21/bin/java --rescan
```

## 配置

首次运行会生成 `<服务器目录>/mcargus/mcargus.properties`，其中包含用户设置和自动维护的缓存。

常用配置项：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `locale` | `zh_cn` | 界面语言：`zh_cn`（简体中文）或 `en_us`（英语） |
| `java.path` | *（空）* | 服务端 Java（可执行文件或 `JAVA_HOME`）；留空 = 自动 |
| `jvm.extraArgs` | *（空）* | 追加 JVM 参数 |
| `jvm.forwardParentArgs` | `true` | 转发启动 MCArgus 时收到的内存/GC 参数（如 `-Xmx`） |
| `jvm.useUserJvmArgs` | `true` | 读取 `user_jvm_args.txt`（Forge/NeoForge 标准做法） |
| `program.args` | `nogui` | 传给服务端的程序参数 |
| `mods.sanitize` | `true` | 启动前清理 `mods/` 文件名中的下载站前缀 |
| `install.enabled` | `true` | 没有启动目标时，自动从服务端压缩包安装 |
| `install.zipEncoding` | `GBK` | 非 UTF-8 zip 条目名的编码（中文打包工具常见） |

`cache.*` 与 `scan.*` 由程序自动维护。删除配置文件可强制完整重扫。

### 切换界面语言

1. 先启动一次 MCArgus，让配置文件生成。
2. 编辑 `mcargus/mcargus.properties`：

   ```properties
   locale=en_us
   ```

3. 重新启动 MCArgus。此时 `--help`、日志、诊断与错误提示均为英语。

改回 `locale=zh_cn`（或删除该键）即可恢复简体中文。

## 工作流程

1. 解析选项并初始化界面语言。
2. 加载（或创建）配置。
3. 查找启动目标：`--args-file` → 根层参数文件 → 缓存目标 → 完整扫描 → Jar 兜底。
4. 检查 `mods/` 指纹，仅在需要时执行完整扫描与文件名清理。
5. 若仍无可启动目标且 `install.enabled=true`，检查服务端压缩包并安装最合适的候选。
6. 使用选定的 Java 组装最终命令并启动，转发控制台 I/O 与停止信号。

## 许可证

[MIT](LICENSE) © 2026 AweiMC
