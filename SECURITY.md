# 安全策略

## 报告漏洞

请**不要**通过公开 Issue 报告安全漏洞。

请使用 GitHub 的私密报告通道：
仓库页面 → **Security** → **Report a vulnerability**，或直接访问
`https://github.com/CiQian-04/AI-Meal-Planner/security/advisories/new`。

报告中请尽量包含：

- 受影响的版本或提交号
- 复现步骤（curl 命令、请求体、截图都可以）
- 影响范围（能否导致密钥泄露、任意文件读写、远程代码执行）

我们会在 **3 个工作日内**确认收到，并在修复发布后于 Release Notes 中致谢（除非你希望匿名）。

## 支持范围

| 版本 | 是否接受安全修复 |
| --- | --- |
| 最新的 minor 版本 | ✅ |
| 更早的版本 | ❌ 建议升级 |

## 这个项目里，哪些属于「安全边界」

**属于：**

- Spring AI 与 springdoc 的已知 CVE（由 CodeQL + Trivy + Dependabot 覆盖）
- 接口的输入校验绕过（Bean Validation 注解之外还能构造出的非法请求）
- 提示注入导致 `@Tool` 方法被诱导执行非预期操作
- 容器镜像的漏洞与不必要的高权限（镜像以非 root 用户运行）

**不属于：**

- 模型自己生成的内容不准确、菜谱不可口——这是模型能力问题，请提普通 Issue
- 你把自己配置的 `OPENAI_API_KEY` 泄露在客户端或截图里
- 部署时没有加鉴权就直接暴露到公网（本项目默认**不含**用户体系与鉴权，见下）

## 已知豁免的漏洞

`backend/.trivyignore` 中列出了 4 个 Spring Framework CVE（CVE-2026-47884 RCE、
CVE-2026-47890、CVE-2026-47892）。原因：Spring Framework **6.2.x 已停止开源维护**，
6.2.19 是该线最终版本，这些 CVE 的修复只存在于 **7.0.9**——也就是必须升级到
Spring Boot 4.x + Spring Framework 7 才能解决。项目当前有意停在稳定线
（Boot 3.5 + Spring AI 1.1.8），故按务实方案豁免并在此注明。

升级 Boot 4.x 时，删除 `backend/.trivyignore` 里的对应条目即可恢复严格扫描。
Tomcat 的 5 个 critical 已通过 `pom.xml` 的 `tomcat.version` 覆盖修复，无需豁免。

## 部署时务必知道的事

本项目定位是**技术演示与本地工具**，开箱状态下：

- 没有任何用户认证和鉴权，任何人只要能访问到 `/api/v1/plans` 就能消耗你的模型额度
- CORS 在 `WebConfig` 里写死只允许 `http://localhost:*` 与 `http://127.0.0.1:*`，
  这挡住了浏览器跨站调用，但**挡不住直接用 curl 打接口**

如果要对外提供服务，请至少做三件事：

1. 在网关或 Spring Security 中加鉴权
2. 在 `backend/src/main/java/com/mealplanner/config/WebConfig.java` 里把 `allowedOriginPatterns`
   改成你的前端域名
3. 对 `/api/v1/plans` 加限流（按 IP 或用户），并给模型调用设预算上限

具体配置项见 [运维与发布](docs/operations.md)。

## 密钥扫描

仓库启用了 Gitleaks（`secret-scan.yml`），每次 push 与 PR 都会扫描；镜像漏洞由 Trivy 扫描，
SAST 由 CodeQL 覆盖，依赖变更由 Dependency Review 拦截。
