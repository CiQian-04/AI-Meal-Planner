# 贡献指南

## 开发流程

```bash
# 后端
cd backend && mvn spring-boot:run

# 前端
cd frontend && npm run dev
```

Or use the Dev Container: open the folder in VS Code and everything is set up automatically.

## 提交信息

项目使用 [Conventional Commits](https://www.conventionalcommits.org/)，Release Please 依赖它判断版本号：

| 前缀 | 用途 | 版本影响 |
| --- | --- | --- |
| `feat:` | 新功能 | 次版本 |
| `fix:` | 修复问题 | 修订号 |
| `perf:` | 性能优化 | 修订号 |
| `docs:` | 文档 | 修订号 |
| `refactor:` | 重构 | 修订号 |
| `chore:` `ci:` `build:` | 杂项 | 不影响 |

示例：

```bash
git commit -m "feat: 支持按人数自动换算食材用量"
git commit -m "fix: 修正克与千克换算时的除零错误"
```

## 合并前自检

```bash
# 后端测试
cd backend && mvn -B -ntp verify

# 前端类型检查与构建
cd frontend && npm run type-check && npm run build

# 端到端验证（需要 JDK 21 与 Python）
bash scripts/verify.sh
bash scripts/ui-verify.sh
```

## 代码风格

- **Java**：遵循 Spring 官方约定，4 空格缩进（Spring Boot 官方 formatter），注释用英文，说明「为什么」而非「做了什么」
- **Vue / TypeScript**：2 空格缩进，`<script setup lang="ts">`，组件名用 PascalCase
- **提交前**：确保 `mvn verify` 与 `npm run type-check` 都通过

## 改动了什么，就补什么文档

- 改了接口 → 更新 `docs/api.md`
- 改了架构 → 更新 `docs/architecture.md`
- 加了新配置项 → 更新 `docs/quickstart.md` 的配置表
- 加了新工具函数 → 更新 `docs/architecture.md` 里的工具清单
