# 运维与发布

## Docker Compose

```bash
# 构建并启动
docker compose up --build -d

# 查看日志
docker compose logs -f backend

# 停止
docker compose down

# 连同数据卷一起清理
docker compose down -v
```

编排包含两个服务：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| `backend` | 8080 | Spring Boot，内置健康检查 |
| `frontend` | 5173 | nginx 托管静态资源，反向代理 `/api` |

前端 nginx 同时代理 `/api`、`/v3`、`/swagger-ui`，所以浏览器只需访问 5173 一个端口。

### 环境变量

```bash
# .env 文件
OPENAI_API_KEY=sk-xxxx
OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_MODEL=deepseek-chat
```

`docker-compose.yml` 里已配置 `host.docker.internal:host-gateway`，
所以容器可以访问宿主机上运行的模型服务（比如 Ollama）：

```yaml
environment:
  OPENAI_BASE_URL: http://host.docker.internal:11434
```

## 镜像构建

```bash
# 后端
docker build -t meal-planner-backend:local ./backend

# 前端
docker build -t meal-planner-frontend:local ./frontend
```

两个 Dockerfile 都是多阶段构建：

- **后端**：Maven 构建 → `eclipse-temurin:21-jre-jammy` 运行，只带最终 jar，非 root 用户启动。
- **前端**：Node 构建 → `nginx:1.27-alpine` 托管，带 gzip 与静态资源缓存策略。

## CI 流水线

`.github/workflows/ci.yml` 在 push 和 PR 时触发：

| Job | 内容 |
| --- | --- |
| `backend` | JDK 21 + Maven 构建测试，上传测试报告 |
| `frontend` | Node 22 + 类型检查 + 构建 |
| `integration` | 启动 mock 与后端，跑 `scripts/verify.sh` 做端到端接口验证 |
| `docker` | 构建两个镜像，校验 `docker compose` 配置 |

## 安全扫描

三层扫描，分别覆盖不同的风险面：

### 1. CodeQL（SAST）

`.github/workflows/codeql.yml`

- 扫描 Java 与 JavaScript/TypeScript
- 使用 `security-and-quality` 查询集
- 每周一自动跑一次，捕获新披露的漏洞

### 2. Trivy（容器镜像）

`.github/workflows/container-scan.yml`

- 对构建出的后端、前端镜像做漏洞扫描
- 结果以 SARIF 格式上传到 GitHub Security 面板
- 存在 CRITICAL 漏洞时任务失败

### 3. Gitleaks（密钥泄露）

`.github/workflows/secret-scan.yml`

- 扫描完整 git 历史中的硬编码密钥

### 4. Dependency Review（依赖变更）

`.github/workflows/dependency-review.yml`

- PR 场景下检查新增依赖的许可证与已知漏洞
- 拒绝 GPL-3.0 / AGPL-3.0 许可证

### Dependabot

`.github/dependabot.yml` 每周检查 Maven、npm 和 GitHub Actions 的依赖更新。

## Release Please 发版

推送符合 Conventional Commits 的提交到 `main` 后，Release Please 会：

1. 根据提交信息判断版本号（`feat` → 次版本，`fix` → 修订号）
2. 更新 `CHANGELOG.md`
3. 提交一个 release PR
4. 合并后创建 Git tag 与 GitHub Release

```bash
# 本地预览将要发布的版本
npx release-please --dry-run
```

合并 release PR 后，`release-please.yml` 会：

- 构建后端 jar 与前端 dist，打包成 tar.gz 作为 Release 附件
- 构建并推送镜像到 GitHub Container Registry：

```bash
docker pull ghcr.io/<owner>/<repo>/backend:latest
docker pull ghcr.io/<owner>/<repo>/frontend:latest
```

### 版本号同步

`release-please-config.json` 里配置了 `extra-files`，发版时会同时更新三处版本号：
`frontend/package.json` 的 `version` 字段、`backend/pom.xml` 的 `<project><version>`，
以及 `CHANGELOG.md`。

### 第一次推仓库前要做的事

默认分支叫 `main`，7 个工作流全部只监听 `main`。如果仓库是 `git init` 出来的，先改名：

```bash
git branch -m master main
git push -u origin main
```

还要改掉仓库地址占位符，否则 Pages 站点、徽章和「编辑此页」链接都指向不存在的仓库：

```bash
grep -rn "example.github.io\|github.com/example\|<owner>" --include="*.md" --include="*.yml" .
```

| 文件 | 要改的字段 |
| --- | --- |
| `docs/mkdocs.yml` | `site_url`、`repo_url`、`repo_name`（`edit_uri` 里的分支名要对齐） |
| `README.md` | 「文档」表格里的文档站点地址 |
| `docs/quickstart.md` | `git clone` 的仓库地址 |
| `.github/ISSUE_TEMPLATE/config.yml` | 两处 URL 里的 `<owner>` |

最后去 GitHub 仓库的 **Settings → Pages** 把 Source 设为 **GitHub Actions**，
否则 `docs-pages.yml` 部署会失败。

## 文档站点

```bash
pip install -r docs/requirements.txt
mkdocs serve --config-file docs/mkdocs.yml   # 本地预览 http://127.0.0.1:8000
mkdocs build --config-file docs/mkdocs.yml   # 构建到 site/
```

`docs-pages.yml` 在 `docs/` 变更时自动构建并发布到 GitHub Pages。

### 导出 OpenAPI 规范

```bash
# 先启动后端
python docs/export_openapi.py
```

脚本会把 `/v3/api-docs` 的内容保存到 `docs/openapi.json`，随文档一起发布。

## 监控

后端暴露了标准 Actuator 端点：

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
```

生产环境建议在 `application-prod.yml` 中关闭不需要的端点：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never
```

## 健康检查

Docker Compose 依赖后端的健康状态：

```yaml
depends_on:
  backend:
    condition: service_healthy
```

后端镜像内置 HEALTHCHECK，探测 `/actuator/health`。
