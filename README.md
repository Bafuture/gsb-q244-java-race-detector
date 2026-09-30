# 数据竞争检测组件

Pair-wise GSB 标注任务仓库（第 16 批 / 244）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们的共享状态偶尔被并发读写，想用工具找出没有同步保护的访问对。请从零实现一个数据竞争检测组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持登记内存位置的读写访问（位置标识、线程、读写类型、访问序号）；2) 支持 happens-before 记录：显式登记同步事件（锁获取释放、volatile 读写、线程启动结束）用于建立先后关系;3) 竞争判定：同一位置存在两次访问，至少一次为写，且两者之间没有 happens-before 关系时判定为数据竞争；4) 无假阳性：存在正确同步的访问对不得报竞争，需用测试覆盖锁保护与 volatile 场景；5) 支持竞争报告：位置、线程、两条访问的调用点与缺失的同步类型；6) 支持与真实并发执行结合：在同一批并发操作上采样登记，输出实际检测到的竞争集合；7) 提供统计：登记访问数、同步事件数、比对次数与被剪枝的访问对；8) 测试覆盖竞争识别、锁保护不误报、volatile 不误报、报告内容与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

## 组件说明

数据竞争检测组件位于 `com.example.gsb.race` 包，基于向量时钟（vector clock）追踪 happens-before 关系：

- `RaceDetector`：核心检测器。`recordAccess(location, threadId, type[, callSite])` 登记读写访问；`onLockAcquire/onLockRelease`、`onVolatileWrite/onVolatileRead`、`onThreadStart/onThreadJoin` 登记同步事件；`getRaces()` 返回去重后的竞争集合；`statistics()` 返回登记访问数、同步事件数、比对次数与被剪枝的访问对。
- `SamplingRaceDetector`：按位置每 N 次访问采样登记一次的包装封装，同步事件始终全量转发，可直接用于真实并发执行。
- `Race`：竞争报告，包含位置、两条访问（线程、调用点、持锁集合）与缺失的同步类型（`LOCK` / `VOLATILE` / `THREAD_LIFECYCLE`）。

判定规则：同一位置两次访问、至少一次为写、且向量时钟判定两者无 happens-before 关系时报告竞争；被 happens-before 排序的访问对会被剪枝，不产生假阳性。
