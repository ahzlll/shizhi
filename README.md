# 拾知成图

把零散的提问和资料，整理成互相连接的知识网络，并按遗忘曲线在对的时间提醒回顾。

## 项目简介

日常学习里，遇到不懂的就问一下 AI，问完就过去了。三个月后同一个概念又不懂，再问一遍，还是记不住。知识是一块一块的碎片，互相不认识。

拾知成图想让每一次提问都留下结构：问题整理成笔记，笔记里的知识点拆出来连成图，再按记忆规律排复习。用久了得到的不是一堆聊天记录，而是一张能看见自己学过什么的知识图谱。

## 核心功能

- **结构化笔记**：提问自动整理成含概念、要点、例子、易错点的笔记
- **知识图谱**：知识点拆成最小单位并自动建立关联
- **间隔复习**：按 FSRS 算法为每个知识点排复习计划
- **检索增强**：下次问到相关问题，回答里带出之前记过的内容
- **自带模型**：可以填自己的接口地址与 key，用更好的模型回答

## 技术栈

| 层   | 选型                                       |
| --- | ---------------------------------------- |
| 语言  | Java 21                                  |
| 框架  | Spring Boot 4 + JdbcTemplate 手写 SQL      |
| 数据库 | PostgreSQL 16 + pgvector                 |
| 前端  | Vue 3 + Vite + TypeScript + Element Plus |
| 模型  | OpenAI 兼容接口，默认智谱 GLM-4-Flash             |
| 部署  | Nginx + systemd                          |

## 目录结构

```
src/main/java/com/ahz/shizhi/
  auth/      注册登录、JWT、当前用户注入
  chat/      会话、消息、流式生成、僵尸消息收敛
  model/     模型客户端与超时控制
  account/   用户自带模型配置（密钥加密存储）
  common/    统一响应、异常、加密工具
src/main/resources/db/migration/   Flyway 迁移脚本
web/        前端
```

## 快速开始

### 1. 起数据库

```bash
docker run -d --name shizhi-pg \
  -p 5432:5432 \
  -e POSTGRES_PASSWORD=123456 \
  -e POSTGRES_DB=shizhi \
  pgvector/pgvector:pg16
```

表结构由 Flyway 在启动时自动创建，不需要手动执行 SQL。  
跑测试用另一个库，创建一次即可：

```bash
docker exec shizhi-pg psql -U postgres -c "CREATE DATABASE shizhi_test"
```

### 2. 配环境变量

| 变量                     | 必填         | 说明                           |
| ---------------------- | ---------- | ---------------------------- |
| `SHIZHI_MODEL_API_KEY` | 接真模型时必填    | 模型服务的 key；不填时对话会报 5001       |
| `SHIZHI_SECRET_KEY`    | 用「自带模型」时必填 | 加密用户 key 的主密钥，base64 的 32 字节 |
| `SHIZHI_DB_PASSWORD`   | 否          | 默认 `123456`，生产必须覆盖           |
| `SHIZHI_JWT_SECRET`    | 生产必填       | 至少 32 字节，本地有默认值              |

生成主密钥：

```bash
openssl rand -base64 32
```

不配 `SHIZHI_SECRET_KEY` 时应用能正常启动，只是「自带模型」功能不可用，设置页会给出提示。

### 3. 后端

```bash
export SHIZHI_MODEL_API_KEY=你的key
export SHIZHI_SECRET_KEY=刚生成的密钥
./mvnw spring-boot:run
```

接口文档：<http://127.0.0.1:8080/swagger-ui/index.html>  
健康检查：<http://127.0.0.1:8080/api/health>

### 4. 前端

```bash
cd web
npm install
npm run dev
```

打开 <http://127.0.0.1:5173>。Vite 已把 `/api` 代理到 `127.0.0.1:8080`，不需要额外配置跨域。

## 常用命令

```bash
# 后端测试（需要数据库在跑）
./mvnw -B clean test

# 只跑一个测试类
./mvnw -B test -Dtest=ChatFlowIntegrationTest

# 前端构建与类型检查
cd web && npm run build
```

## 致谢

间隔重复调度参考 [FSRS](https://github.com/open-spaced-repetition)。
