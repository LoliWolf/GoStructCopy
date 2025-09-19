# Go Struct Copy

一个用于 GoLand/IntelliJ IDEA 的插件，可以递归复制 Go 结构体及其所有嵌套依赖的结构体定义。

## 📋 功能特性

- **递归展开**：自动展开当前光标所在的 Go 结构体，包含所有嵌套的结构体依赖
- **智能过滤**：标准库类型（如 `time.Time`、`hash.Hash`）保持原样引用，不进行展开
- **标签清理**：字段标签仅保留 `json:"..."` 项，自动剔除其他冗余标签
- **匿名结构体处理**：为匿名结构体生成唯一名称并转换为独立的类型定义
- **一键复制**：处理结果自动复制到系统剪贴板，可直接粘贴使用

## 🚀 快速开始

### 安装要求

- GoLand 2024.1.2 或更高版本
- Java 17 或更高版本
- Go 插件（通常已预装）

### 使用方法

1. 在 GoLand 中打开包含 Go 结构体的文件
2. 将光标放在目标结构体声明处或结构体名称上
3. 使用以下任一方式触发复制：
   - 菜单栏：`Code` → `Copy Go Struct (Recursive)`
   - 右键菜单：选择 `Copy Go Struct (Recursive)`
   - 快捷键：可在设置中自定义
4. 复制的内容将包含完整的结构体定义，可直接粘贴到代码或文档中

### 示例

假设有以下 Go 代码：

```go
type User struct {
    ID       int64     `json:"id" db:"user_id"`
    Name     string    `json:"name"`
    Profile  Profile   `json:"profile"`
    Tags     []Tag     `json:"tags"`
    Created  time.Time `json:"created_at"`
}

type Profile struct {
    Avatar string `json:"avatar" validate:"url"`
    Bio    string `json:"bio"`
}

type Tag struct {
    Name  string `json:"name"`
    Color string `json:"color"`
}
```

将光标放在 `User` 结构体上并执行复制操作，将得到：

```go
type User struct {
	ID      int64     `json:"id"`
	Name    string    `json:"name"`
	Profile Profile   `json:"profile"`
	Tags    []Tag     `json:"tags"`
	Created time.Time `json:"created_at"`
}

type Profile struct {
	Avatar string `json:"avatar"`
	Bio    string `json:"bio"`
}

type Tag struct {
	Name  string `json:"name"`
	Color string `json:"color"`
}
```

注意：
- 非 `json` 标签被自动移除（如 `db:"user_id"`、`validate:"url"`）
- 标准库类型 `time.Time` 保持原样
- 所有相关结构体都被包含在输出中

## 🛠️ 开发环境设置

### 环境要求

- Java 17+
- Gradle 8.7+
- GoLand 2024.1.2+

### 克隆项目

```bash
git clone <repository-url>
cd GoStructCopy
```

### 构建项目

```bash
# Windows
./gradlew.bat build

# Linux/macOS
./gradlew build
```

### 运行测试

```bash
# Windows
./gradlew.bat test

# Linux/macOS
./gradlew test
```

### 开发调试

启动带有插件的 GoLand 沙箱环境：

```bash
# Windows
./gradlew.bat runIde

# Linux/macOS
./gradlew runIde
```

### 打包插件

```bash
# Windows
./gradlew.bat buildPlugin

# Linux/macOS
./gradlew buildPlugin
```

打包后的插件文件位于 `build/distributions/` 目录下。

## 📁 项目结构

```
GoStructCopy/
├── .gitignore                          # Git 忽略规则
├── README.md                           # 项目说明文档
├── USAGE.md                           # 详细使用指南
├── build.gradle.kts                   # Gradle 构建配置
├── settings.gradle.kts                # Gradle 设置
├── gradle/                            # Gradle Wrapper
├── gradlew                            # Gradle 执行脚本 (Unix)
├── gradlew.bat                        # Gradle 执行脚本 (Windows)
└── src/
    ├── main/
    │   ├── java/com/loliwolf/gostructcopy/
    │   │   ├── actions/
    │   │   │   └── GoStructCopyAction.java      # 主要动作入口
    │   │   └── core/
    │   │       └── GoStructCopyProcessor.java   # 核心处理逻辑
    │   └── resources/
    │       └── META-INF/
    │           └── plugin.xml                   # 插件配置文件
    └── test/
        └── java/com/loliwolf/gostructcopy/
            └── core/
                └── GoStructCopyProcessorTest.java # 单元测试
```

## 🔧 核心组件

### GoStructCopyAction
- 插件的入口点，负责处理用户交互
- 验证当前文件类型和光标位置
- 触发结构体复制流程

### GoStructCopyProcessor
- 核心处理器，包含主要的业务逻辑
- 解析 PSI（Program Structure Interface）结构
- 递归展开嵌套结构体
- 生成格式化的输出

## 🧪 测试

项目包含完整的单元测试，覆盖以下场景：
- 嵌套结构体展开
- 递归引用处理
- 标准库类型识别
- 匿名结构体处理
- 字段标签过滤

运行测试：
```bash
./gradlew.bat test
```

## 📝 配置说明

### plugin.xml 配置
- 插件 ID：`com.loliwolf.gostructcopy`
- 插件名称：`Go Struct Copy`
- 版本：`1.0.0`
- 依赖：GoLand 平台和 Go 插件

### build.gradle.kts 配置
- Java 版本：17
- IntelliJ 平台版本：2024.1.2
- 目标 IDE：GoLand
- 测试框架：JUnit 4.13.2 + Mockito 5.12.0

## 🤝 贡献指南

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 👨‍💻 作者

- **LoliWolf** - 初始开发

## 🐛 问题反馈

如果您发现任何问题或有改进建议，请在 [Issues](../../issues) 页面提交。

## 📚 更多信息

- [GoLand 插件开发文档](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [Go 语言官方文档](https://golang.org/doc/)