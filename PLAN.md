# Boss 实体开发规划：1.20.1 Forge + GeckoLib

## 目标与成功准则
本项目旨在将现有资源转化为可交互的 Boss 实体。
成功标准：
1. Boss 实体能在游戏中成功生成。
2. 模型、纹理和动画在 GeckoLib 下正常加载并渲染。
3. 实现基本的 Boss 逻辑（如简单的 AI 行为和动画控制）。

## 环境决策
- **MC 版本**: 1.20.1
- **平台**: Forge
- **时代**: Modern (JDK 17)
- **依赖**: GeckoLib 4.4.2

## 文件所有权分配
| 文件/目录 | 负责人 | 职责 |
| :--- | :--- | :--- |
| `src/main/resources/assets/<mod_id>/geo/` | C (Content) | 存放 .geo.json 模型文件 |
| `src/main/resources/assets/<mod_id>/animations/` | C (Content) | 存放 .animation.json 动画文件 |
| `src/main/resources/assets/<mod_id>/textures/` | C (Content) | 存放 .png 贴图文件 |
| `src/main/java/<pkgPath>/entity/` | B (Skeleton) | 实体类与 GeckoLib 实现 |
| `src/main/java/<pkgPath>/client/` | B (Skeleton) | 模型渲染与动画控制器类 |
| `src/main/java/<pkgPath>/boss/` | C (Content) | Boss 逻辑、AI 与 Tick 驱动 |
| `src/main/resources/META-INF/mods.toml` | D (Review) | 依赖声明与前置检查 |

## API 与实现点
- **GeckoLib 接口**: `GeoEntity` (Entity), `GeoRenderer` (Client), `GeoModel` (Client)。
- **动画控制器**: 在 `GeoModel` 的 `getAnimationProcessor` 中定义动画控制器。
- **Tick 驱动**: 使用 `Entity#aiStep` 或 `TickEvent` 处理 Boss 特殊状态逻辑。

## 构建与验证步骤 (供 D 执行)
1. `gradlew clean build` - 验证环境配置是否正确。
2. `gradlew runClient` - 在客户端检查资源加载日志 (GeckoLib 会输出日志)。
3. 在游戏中生成实体（测试指令 `/summon <mod_id>:<entity_name>`）。

## 风险列表
- **资源规范**: 需确认现有资源是否符合 GeckoLib 4.x 的导出规范，如有必要需重新导出。
- **环境隔离**: 目前构建配置依赖本地缓存，D 执行时需确保网络连接或依赖完整。
- **API 变化**: 确保所有 GeckoLib 调用适配 4.4.2 版本 API。
