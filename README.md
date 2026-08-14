# VibeCodingPlatform

An AI-powered, microservices-based "vibe-coding" platform — a distributed clone of [Lovable](https://lovable.dev). Describe an app in natural language and an LLM generates a full React + TypeScript frontend, persists the files to object storage, and spins up a live, internet-accessible preview on Kubernetes.

Built with **Spring Boot 4**, **Spring Cloud**, **Spring AI**, and **Kubernetes**.

---

## Features

- **AI code generation** — chat with an LLM (Google Gemini via OpenRouter) that streams responses over SSE, uses a `read_files` tool to inspect your project, and emits structured `<file>` edits.
- **Live previews** — each project deploys to its own runner pod on Kubernetes with a wildcard subdomain (`project-{id}.previews.codingshuttle.in`).
- **Project collaboration** — role-based access control (OWNER / EDITOR / VIEWER) with invite-by-email member management.
- **Subscriptions & billing** — Stripe-powered plans with checkout, customer portal, and webhook-driven lifecycle.
- **Distributed architecture** — 7 microservices with Kafka sagas, Feign clients, Eureka discovery, and Spring Cloud Config.

---

## Tech Stack

| Concern | Technology |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.0.3 |
| Cloud | Spring Cloud 2025.1.0 (Config, Eureka, OpenFeign, Gateway) |
| AI | Spring AI 2.0.0-M2 → OpenRouter (`google/gemini-3-flash-preview`) |
| Database | PostgreSQL 16 via pgvector |
| Messaging | Apache Kafka (KRaft) |
| Cache | Redis 7 |
| Object storage | MinIO |
| Security | Spring Security + jjwt (HMAC-SHA JWT) |
| Payments | Stripe Java |
| Container build | Jib Maven Plugin |
| Orchestration | Kubernetes (Fabric8 client) |
| Mapping | MapStruct + Lombok |

---

## Architecture

```
                            ┌─────────────────┐
                            │   Frontend      │
                            │   (nginx)       │
                            └────────┬────────┘
                                     │
                            ┌────────▼────────┐
                            │   API Gateway   │  ← validates JWT, CORS, public-route whitelist
                            │   (port 8080)   │
                            └────────┬────────┘
                  ┌───────────────────┼───────────────────┐
                  │                   │                   │
         ┌────────▼────────┐ ┌────────▼────────┐ ┌────────▼────────┐
         │  Account Svc    │ │  Workspace Svc   │ │ Intelligence Svc│
         │  (port 9050)    │ │  (port 9020)     │ │  (port 9030)   │
         │  auth + Stripe  │ │  projects + K8s  │ │  Spring AI SSE │
         └────────┬────────┘ └────────┬────────┘ └────────┬────────┘
                  │                   │                   │
                  │           ┌───────┴────────┐         │ Kafka
                  └──────────►│   PostgreSQL   │◄────────┘ (file-storage saga)
                              │   (pgvector)   │
                              └────────────────┘

   Infrastructure: Config Server (8888) · Eureka (8761) · Kafka · Redis · MinIO · K8s runner pool + proxy
```

### Microservices

| Service | Port | Path | Database | Responsibility |
| --- | --- | --- | --- | --- |
| `api-gateway` | 8080 | — | — | Reactive gateway, JWT validation, routing, CORS |
| `account-service` | 9050 | `/account` | `account_db` | Auth (signup/login), users, Stripe subscriptions & billing |
| `workspace-service` | 9020 | `/workspace` | `workspace_db` | Projects, RBAC members, MinIO file storage, K8s preview deploys |
| `intelligence-service` | 9030 | `/intelligence` | `intelligence_db` | Spring AI chat, SSE streaming, code generation, usage tracking |
| `config-service` | 8888 | — | — | Spring Cloud Config Server (git-backed) |
| `discovery-service` | 8761 | — | — | Eureka service registry (local dev) |
| `common-lib` | — | — | — | Shared JWT filter, DTOs, enums, exception handler, Kafka events |

All business services depend on `common-lib`, which provides the shared JWT auth filter, `AuthUtil`, `GlobalExceptionHandler`, common DTOs/enums, and Kafka event records. A Feign `RequestInterceptor` auto-forwards the bearer token on every inter-service call so the authenticated user context propagates across hops.

### Inter-service communication

- **Feign**: `intelligence-service` and `workspace-service` call `account-service` for user lookups and plan/limit checks. `intelligence-service` calls `workspace-service` for file tree/contents and permission checks.
- **Kafka file-storage saga**:

  | Topic | Producer | Consumer | Event |
  | --- | --- | --- | --- |
  | `file-storage-request-event` | intelligence-service | workspace-service (`workspace-group`) | `FileStoreRequestEvent` |
  | `file-store-responses` | workspace-service | intelligence-service (`intelligence-group`) | `FileStoreResponseEvent` |

  Each AI `<file>` edit → async persist to MinIO → ack back → `ChatEvent` confirmed (PENDING → CONFIRMED), idempotently tracked via `processed_events`.

---

## REST API

All routes are prefixed `/api/v1/{service}` at the gateway.

### Auth & Billing
| Method | Endpoint | Auth |
| --- | --- | --- |
| `POST` | `/account/auth/signup` | public |
| `POST` | `/account/auth/login` | public |
| `POST` | `/account/webhooks/payment` | public (Stripe) |
| `GET` | `/account/api/me/subscription` | JWT |
| `POST` | `/account/api/payments/checkout` | JWT |
| `POST` | `/account/api/payments/portal` | JWT |

### Projects
| Method | Endpoint | Auth |
| --- | --- | --- |
| `GET` | `/workspace/projects` | JWT |
| `POST` | `/workspace/projects` | JWT |
| `GET` `PATCH` `DELETE` | `/workspace/projects/{id}` | JWT + RBAC |
| `POST` | `/workspace/projects/{id}/deploy` | JWT + RBAC (EDIT) |
| `GET` | `/workspace/projects/{projectId}/files` | JWT + RBAC (VIEW) |
| `GET` | `/workspace/projects/{projectId}/files/content?path=` | JWT + RBAC (VIEW) |
| `GET` `POST` | `/workspace/projects/{projectId}/members` | JWT + RBAC (MANAGE_MEMBERS) |
| `PATCH` `DELETE` | `/workspace/projects/{projectId}/members/{memberId}` | JWT + RBAC |

### AI Chat
| Method | Endpoint | Auth |
| --- | --- | --- |
| `POST` | `/intelligence/chat/stream` | JWT + RBAC (EDIT) → `text/event-stream` |
| `GET` | `/intelligence/chat/projects/{projectId}` | JWT + RBAC (VIEW) |

---

## How the AI works

`intelligence-service` uses **Spring AI** pointed at **OpenRouter** to call `google/gemini-3-flash-preview` (temperature 0.2).

1. **`POST /chat/stream`** opens an SSE stream. A `FileTreeContextAdvisor` injects the project's file tree as a `SystemMessage` so the model knows what exists.
2. The model is prompted (see `PromptUtils.CODE_GENERATION_SYSTEM_PROMPT`) to act as an elite React 18 + TypeScript + Vite + Tailwind 4 + daisyUI v5 architect. It emits a strict XML protocol: `<message>`, `<tool args="...">`, `<file path="...">`.
3. The model can call a `@Tool read_files` to fetch file contents from `workspace-service` before editing.
4. `LlmResponseParser` splits the streamed text into ordered `ChatEvent`s (MESSAGE / FILE_EDIT / TOOL_LOG).
5. Each `FILE_EDIT` triggers a `FileStoreRequestEvent` on Kafka → `workspace-service` persists it to MinIO → a `FileStoreResponseEvent` confirms the saga.
6. Token usage is tracked per user per day; the daily cap is enforced against the user's plan (`maxTokensPerDay`) with HTTP 429 on overflow.

---

## Live previews on Kubernetes

`POST /projects/{id}/deploy` orchestrates a preview:

1. An **idle runner pod** (label `status=idle`) is claimed from the `runner-pool` in the `lovable-previews` namespace and relabeled `status=busy` + `project-id={id}`.
2. The `workspace-service` `exec`s into the pod's `syncer` container (MinIO `mc`) to `mirror` the project files from MinIO, then starts a background `--watch` mirror for live updates.
3. It `exec`s into the `runner` container to `npm install && npm run dev` on port 5173.
4. A route `route:{domain}` → `{podIp}:5173` is registered in **Redis** (TTL 6h).
5. The Node.js **`lovable-me-proxy`** wildcard proxy looks up `route:{host}` in Redis on each request and proxies HTTP + WebSocket to the running Vite dev server.
6. Preview sandboxing: network policies restrict preview pods to only MinIO + DNS + public internet (private RFC1918 ranges blocked), accepting ingress only from the proxy.

---

## Repository structure

```
VibeCodingPlatform/
├── account-service/        # auth, users, Stripe billing
├── api-gateway/            # reactive gateway + JWT filter
├── common-lib/             # shared security, DTOs, enums, Kafka events
├── config-service/         # Spring Cloud Config Server (git-backed)
├── discovery-service/      # Eureka
├── intelligence-service/  # Spring AI chat, SSE streaming, code generation
├── workspace-service/      # projects, members, MinIO, K8s deployments
├── k8s/
│   ├── infra/              # namespaces, ingress, network policies, runner pool
│   ├── services/           # deployments + services for each Spring service
│   ├── stateful/           # pgvector, kafka, redis, minio StatefulSets
│   └── proxy/              # lovable-me-proxy (Node.js wildcard proxy)
└── .github/workflows/      # CI/CD (Jib → Docker Hub → GKE kubectl rollout)
```

---

## Build & Run

### Prerequisites
- Java 21
- Docker (for container builds via Jib)
- A Kubernetes cluster (for deployment) — or the local infra dependencies

### Build order

There is **no parent pom** — each module is an independent Maven project, and `account`/`workspace`/`intelligence` depend on `common-lib`. Build the shared library first:

```bash
# 1. Install the shared library to local Maven cache
cd common-lib
./mvnw clean install -DskipTests

# 2. Build any service (repeat per service)
cd ../account-service
./mvnw clean package
```

### Container images

Images are built by the **Jib Maven plugin** (bound to the `package` phase) — no Dockerfiles needed:

```bash
./mvnw compile jib:build -Djib.to.auth.username=$DOCKERHUB_USERNAME -Djib.to.auth.password=$DOCKERHUB_TOKEN
```

The Node.js preview proxy (`k8s/proxy/`) has its own `Dockerfile`.

### External dependencies (local profile)

| Dependency | Address |
| --- | --- |
| Config Server | `localhost:8888` |
| Eureka | `localhost:8761` |
| PostgreSQL | `localhost:9010` |
| Kafka | `localhost:29092` |
| Redis | `localhost:6379` |
| MinIO | `http://localhost:9000` (creds `minioadmin` / `minioadmin123`) |

Infra bindings and per-service config are served by the **config-service** from the git config repo; override via environment variables (`CONFIG_SERVER_URL`, `JWT_SECRET`, `AI_API_KEY`, `STRIPE_*`, `MINIO_*`, etc.).

### Kubernetes deployment

Apply in order:

```bash
kubectl apply -f k8s/infra/        # namespaces, secrets, ingress, network policies, runner pool
kubectl apply -f k8s/stateful/    # pgvector (creates 3 DBs), kafka, redis, minio
kubectl apply -f k8s/services/    # api-gateway, account, config, intelligence, workspace, frontend
kubectl apply -f k8s/proxy/       # lovable-me-proxy (preview wildcard proxy)
```

Services run with `SPRING_PROFILES_ACTIVE=k8s`; inter-service discovery switches from Eureka to k8s Service DNS, and secrets are injected from a `app-secrets` Secret.

---

## CI/CD

GitHub Actions workflows (`.github/workflows/`) build and deploy `api-gateway`, `account-service`, and `config-service`:

1. Trigger on push to `master` when the service folder changes.
2. `setup-java@v4` (JDK 21 temurin, Maven cache); account-service pre-installs `common-lib`.
3. `mvnw compile jib:build` → image `docker.io/anuj55149/lovable-{service}:{sha}` + `latest` (Docker Hub secrets).
4. GKE auth via Workload Identity Federation → `get-gke-credentials`.
5. `kubectl set image deployment/{service}` + `kubectl rollout status` in the `lovable-core` namespace.

---

## Security

- **Auth**: stateless JWT (jjwt, HMAC-SHA, ~100 min expiry). Issued by `account-service` on signup/login; validated at the gateway and re-validated at each downstream service via the shared `JwtAuthFilter`.
- **Token propagation**: a Feign `RequestInterceptor` forwards the bearer on every inter-service call, enabling cross-service `@PreAuthorize` permission checks.
- **RBAC**: project-scoped roles (OWNER / EDITOR / VIEWER) mapping to permission sets (`VIEW`, `EDIT`, `DELETE`, `MANAGE_MEMBERS`, `VIEW_MEMBERS`), enforced via `@PreAuthorize("@security.canXxx(#projectId)")`.
- **Stripe webhooks** are signature-verified.
- **All services**: `SessionCreationPolicy.STATELESS`, CSRF disabled, CORS configured at the gateway.

---

## License

This project is for educational purposes (Coding Shuttle / `codingshuttle.in`).