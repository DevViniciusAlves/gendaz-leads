# Gendaz Leads

Plataforma privada de prospecção comercial com IA para uso próprio do **Gendaz**.
Informe **nicho + cidade + país + quantidade**, e o sistema descobre empresas reais (OpenStreetMap/Nominatim/Overpass), deduplica globalmente, enriquece contatos públicos, analisa cada lead com **GPT-OSS**, gera uma mensagem personalizada e controla campanhas de prospecção de ponta a ponta.

Este é um projeto de **produção**: todas as integrações são reais e nenhum dado é simulado.

---

## Stack

| Camada      | Tecnologia                                                        |
|-------------|------------------------------------------------------------------|
| Frontend    | React 18 + Vite (JavaScript)                                     |
| Backend     | Java 17 + Spring Boot 3.3 (API REST, camadas, segurança)         |
| Banco       | PostgreSQL (Neon) via Flyway migrations                          |
| IA          | Groq API (análise + geração de mensagem) — modelo **openai/gpt-oss-120b** |
| Descoberta  | OpenStreetMap / Nominatim / Overpass API (sem Google Places)     |
| Hospedagem  | Backend: Render · Frontend: Vercel · Banco: Neon PostgreSQL     |

---

---

---

## Estrutura do projeto

```
gendaz-leads/
├── backend/                # Spring Boot (pom.xml, src/main, src/test)
│   ├── src/main/java/com/gendaz/leads/
│   │   ├── config/         # WebConfig (rate limit)
│   │   ├── controller/     # Auth, Campaign, Lead, Dashboard, WhatsApp
│   │   ├── dto/            # requisição/resposta (auth, campaign, lead, dashboard, whatsapp, common)
│   │   ├── entity/         # User, Campaign, Lead, LeadSource, LeadAnalysis, LeadMessage, CampaignLead, LeadEvent, MessageSend
│   │   ├── repository/     # Spring Data JPA
│   │   ├── security/       # JWT, filtro, UserDetails, rate limit, CORS
│   │   ├── service/        # orquestração de domínio
│   │   │   ├── provider/   # LeadDiscoveryProvider: OpenStreetMapProvider
│   │   │   └── messaging/  # MessagingProvider: LogMessagingProvider + SendQueueProcessor
│   │   ├── whatsapp/       # WhatsAppService + BaileysWhatsAppProvider (HTTP interno ao whatsapp-service)
│   │   ├── util/           # Normalizer (normalização/deduplicação), SsrfGuard
│   │   └── exception/      # ApiException + GlobalExceptionHandler
│   └── src/main/resources/db/migration/V1__init.sql, V2__whatsapp_auth.sql, V3..V6  # schema Flyway
├── whatsapp-service/       # Node 20+ isolado (Baileys): sessao unica, QR, envio unitario
└── frontend/               # React + Vite
    └── src/                # api.js, pages (Dashboard, Campanhas, Leads, WhatsApp, Login), components
```

---

## Pré-requisitos

- Java 17, Maven 3.9+
- Node 18+ (usado Node 24)
- Contas/credenciais: **Neon PostgreSQL**, **Groq API key** (Google Maps API key **não é necessário**)

---

## Execução local

### Banco
Crie um banco Postgres (local ou Neon). Defina `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`.
O Flyway cria o schema automaticamente na subida.

### Backend
```bash
cd backend
export DATABASE_URL="jdbc:postgresql://localhost:5432/gendaz?user=postgres&password=postgres"
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=postgres
export JWT_SECRET="um-segredo-longo-aleatorio-de-pelo-menos-32-caracteres"
export GROQ_API_KEY="seu-groq-key"
./mvnw spring-boot:run        # ou: mvn spring-boot:run
```
Health check: `GET /actuator/health` → `{"status":"UP"}`.

### Frontend
```bash
cd frontend
npm install
npm run dev      # http://localhost:5173  (proxy /api -> :8080)
```
Para produção defina `VITE_API_URL` com a URL do backend Render (ver `.env.example`).

---

## Build

Backend:
```bash
cd backend && mvn clean package -DskipTests
# artefato: target/gendaz-leads.jar
```
Frontend:
```bash
cd frontend && npm install && npm run build   # gera dist/
```

---

## Variáveis de ambiente (Render)

Veja `backend/.env.example`. Resumo (sem valores reais):

- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` — Neon Postgres
- `JWT_SECRET` (≥32 chars), `JWT_EXPIRATION_MS`
- `CORS_ALLOWED_ORIGINS` — origem da Vercel
- `MIN_LEADS_PER_REQUEST` (3), `MAX_LEADS_PER_REQUEST` (30)
- **Descoberta OSM** (sem Google Places):
  - `OSM_ENABLED`, `OSM_TIMEOUT_MS`, `OSM_NOMINATIM_TIMEOUT_MS`, `OSM_NOMINATIM_MAX_ATTEMPTS`
  - `OSM_NOMINATIM_CACHE_SECONDS`, `OSM_NOMINATIM_MIN_INTERVAL_MS`
  - `OSM_DISCOVERY_BASE_BUDGET_MS`, `OSM_DISCOVERY_PER_LEAD_BUDGET_MS`, `OSM_DISCOVERY_MAX_BUDGET_MS`
  - `OSM_QUERY_MIN_RAW_LIMIT`, `OSM_QUERY_RAW_PER_LEAD`
  - `OSM_ADAPTIVE_MAX_DEPTH`, `OSM_ADAPTIVE_MIN_EDGE_KM`
  - `OSM_OVERPASS_ENDPOINTS`, `OSM_OVERPASS_MAX_CONCURRENCY`, `OSM_CIRCUIT_OPEN_SECONDS`
- **Enriquecimento de contatos**:
  - `LEAD_ENRICHMENT_CONNECT_TIMEOUT_MS`, `LEAD_ENRICHMENT_READ_TIMEOUT_MS`, `LEAD_ENRICHMENT_MAX_BYTES`
- **Groq** (análise e geração de mensagens):
  - `GROQ_API_KEY`, `GROQ_MODEL` (padrão: `openai/gpt-oss-120b`), `GROQ_ENABLED`, `GROQ_TIMEOUT_MS`, `GROQ_MAX_RETRIES`
- Fila de envio: `MESSAGING_PROVIDER`, `SEND_INTERVAL_SECONDS`, `MAX_CONCURRENT_SENDS`
- WhatsApp: `WHATSAPP_SERVICE_URL`, `WHATSAPP_INTERNAL_TOKEN`, `WHATSAPP_SESSION_ID`, `WHATSAPP_TIMEOUT_MS`
- Rate limit: `RATE_LIMIT_REQUESTS`, `RATE_LIMIT_WINDOW`
- App: `APP_ENV`, `PORT`

### WhatsApp (infra técnica)

Arquitetura: `Spring Boot → HTTP interno autenticado → whatsapp-service (Node/Baileys) → WhatsApp`.
Baileys fica SOMENTE no serviço Node isolado (`whatsapp-service/`), nunca no Java.

```bash
cd whatsapp-service
cp .env.example .env   # preencha WHATSAPP_INTERNAL_TOKEN, WHATSAPP_DATABASE_URL, WHATSAPP_AUTH_ENCRYPTION_KEY
npm install
npm start              # porta 3001; GET /health -> {"status":"UP"}
```

- Sessão única e estável (`WHATSAPP_SESSION_ID`, padrão `gendaz-leads`).
- Auth state persistido em PostgreSQL (`whatsapp_auth_sessions`, `whatsapp_auth_keys`, migration `V2`),
  criptografado com AES-256-GCM (`WHATSAPP_AUTH_ENCRYPTION_KEY` = 32 bytes em base64/hex).
  Sem token configurado o serviço recusa iniciar (fail closed).
- Spring expõe ao frontend SOMENTE: `GET /api/whatsapp/status`, `POST /api/whatsapp/connect`,
  `GET /api/whatsapp/qr`, `POST /api/whatsapp/disconnect`, `POST /api/whatsapp/send`
  (envio técnico individual; fila/delay comercial continuam no Spring — próxima task).
- Frontend: página `/whatsapp` (Conectar → QR → Conectado → Desconectar), polling de 4s.

---

## Deploy

### Neon
1. Crie um projeto Neon, copie a connection string (com `sslmode=require`).
2. Preencha `DATABASE_URL` no Render. O Flyway roda na inicialização.

### Render (backend)
- Build command: `mvn clean package -DskipTests` (ou use o buildpack Java; `pom.xml` já define o jar)
- Start command: `java -jar target/gendaz-leads.jar`
- A porta é lida de `PORT` (Spring `server.port=${PORT:8080}`).
- Health check: `/actuator/health`
- Adicione todas as variáveis de `backend/.env.example`.

### Vercel (frontend)
- Framework: Vite. Build: `npm run build`. Output: `dist`.
- Defina a env `VITE_API_URL` = `https://<seu-backend>.onrender.com` (sem `/api` no final).
- `vercel.json` já faz rewrite de SPA para `index.html`.

---

## Fluxo completo

1. Usuário informa **nicho + cidade + país + quantidade** (3–30) e cria uma campanha.
2. Backend dispara processamento **assíncrono** (`@Async`):
   - **Descoberta**: Nominatim geocodifica cidade+país → `country_code` ISO + bbox da cidade inteira → **root bbox first** (uma query ampla); se **saturar** (`elements >= rawLimit`) ou **timeout/504** e a região puder ser dividida → **adaptive split** recursivo (máx depth configurável, aresta mínima em km); failover por endpoint; sticky healthy endpoint; circuit breaker por host; **NÃO** grid fixo de tiles; deadline adaptativo (`base + perLead * qty`, cap).
   - **Normalização + Enriquecimento**: `LeadCandidate` com nome, categoria, endereço, cidade, país, telefone, **email**, website, Instagram, sourceId. Enriquecimento de website (tel:, mailto:, Instagram) **após** dedupe global; SSRF guard (CIDR corretos), redirect validation (máx 3, revalida SSRF a cada hop), cache por execução.
   - **Deduplicação global**: sourceId → Instagram → website → telefone → nome+cidade+país; **email NÃO é chave global** (franquias podem compartilhar). Dedupe em memória (sourceId) antes de enriquecimento; constraints únicos no banco.
   - **Lead útil**: só conta para a quantidade se tiver telefone **OU** Instagram **OU** email **OU** website. Sem contato → continua buscando (não consome vaga).
   - **Criação**: novos leads entram como `NEW`.
   - **Análise (Groq GPT-OSS)**: fora de transação longa → tipo, serviços, presença digital, sistema de agendamento, dores, oportunidade, score 0–100.
   - **Mensagem (Groq GPT-OSS)**: curta, personalizada, nome real.
   - Lead passa a `MESSAGE_READY`.
3. Frontend acompanha progresso real: `Localizando cidade` → `Buscando leads (X/Y)` → `Complementando resultados` → `Analisando leads` → `Gerando mensagens`.
4. Usuário **revisa, edita, copia, abre Instagram, aprova** ou marca **Não prospectar**.
5. Ao aprovar, o lead entra na **fila de envio** (`message_sends`). `SendQueueProcessor` respeita intervalo/concorrência e chama `MessagingProvider`.
6. Status: `SENT → REPLIED → INTERESTED → CONVERTED`, além de `NOT_INTERESTED` e `DO_NOT_CONTACT`. Tudo auditado em `lead_events`.

---

## Endpoints principais

```
POST   /api/auth/register        {fullName, email, password}
POST   /api/auth/login           {email, password}
GET    /api/auth/me

POST   /api/campaigns            {niche, city, country, quantity}
GET    /api/campaigns?page=&size=
GET    /api/campaigns/{id}
POST   /api/campaigns/{id}/retry

GET    /api/leads?campaignId=&status=&search=&page=&size=
GET    /api/leads/{id}
GET    /api/leads/{id}/events
PATCH  /api/leads/{id}/message   {messageText}
POST   /api/leads/{id}/approve
POST   /api/leads/{id}/do-not-contact
POST   /api/leads/{id}/status    {status}
POST   /api/leads/{id}/regenerate

GET    /api/dashboard

GET    /actuator/health
```

Todos os endpoints (exceto auth) exigem `Authorization: Bearer <token>`.

---

## Banco de dados (tabelas)

- **users** — contas de acesso (BCrypt).
- **campaigns** — campanha + contadores derivados (discovered/analyzed/message/approved/sent/replied/interested/converted/blocked) + progresso + **city, country** (location mantido para compat).
- **leads** — lead normalizado; constraints únicos para deduplicação (sourceId, instagram, website, phone, nome+cidade+país); **email NÃO tem constraint único** (franquias podem compartilhar); **country sem default BR**; `do_not_contact`.
- **lead_sources** — proveniência (OSM) de cada lead.
- **lead_analysis** — resultado da IA (score, sistema detectado, dores, oportunidade, **model**).
- **lead_messages** — mensagem gerada/editada/aprovada.
- **campaign_leads** — relação campanha↔lead (unique).
- **lead_events** — auditoria (lead_found, lead_duplicate, lead_analysis_completed, message_generated, message_approved, lead_status_changed, …).
- **message_sends** — fila de envio (status, tentativas, retry).

---

## Segurança

- Autenticação JWT (HS256) + senhas com **BCrypt** (custo 12).
- CORS restrito à origem configurada; `Authorization`/headers allowlist.
- **Rate limiting** por IP (token bucket) em `/api/**` (login/registro fora para permitir acesso).
- Validação de entrada (Bean Validation) e tratamento global de exceções — **nenhum stack trace** exposto; respostas `{timestamp, status, code, message}`.
- Consultas parametrizadas (JPA/Specification) → sem SQL injection.
- React escapa por padrão → sem XSS; sem dados sensíveis no frontend.
- **SSRF guard** nas buscas de Instagram/contatos a partir do site da empresa (bloqueia localhost/ IPs privados/metadata).
- Timeouts e retry (com backoff) em todas as APIs externas (Nominatim, Overpass, website enrichment); falha de um lead não derruba a campanha.
- Logs sem secrets; `actuator/health` sem detalhes internos.
- Deduplicação reforçada por constraints únicos no banco (seguro sob concorrência); ordem: sourceId → Instagram → website → telefone → nome+cidade+país; email **não** é chave global.

---

## Decisões honestas / pendências

- **Envio de mensagens**: o `MessagingProvider` atual (`log`) **registra a mensagem em log e não envia de fato** por nenhum canal, para não violar termos de plataforma nem depender de credenciais não fornecidas. A fila, o agendamento, as tentativas/retry e a transição de status (`SENT`) estão implementados. Para envio real, implemente um `MessagingProvider` (ex.: Instagram/WhatsApp compatível) e troque `MESSAGING_PROVIDER`. Isso exige credenciais e aprovação do canal.
- **Descoberta de Instagram/contatos**: best-effort — extrai o perfil do site da empresa (quando público e acessível). Se não confirmado, `instagramStatus = NOT_FOUND`. Nunca inventa usuários.
- **Detecção de sistema de agendamento**: baseada em heurística real sobre o domínio do site (lista de provedores conhecidos). Quando não detectado, estado `UNKNOWN`/`NOT_IDENTIFIED` — nunca assumido.
- **Groq**: sem a chave, a análise falha (a campanha fica `FAILED`/`PARTIAL`); a descoberta via OpenStreetMap continua funcionando sem chave.
- **Fonte tipográfica "Faktum Medium Fina"**: adicione `frontend/public/fonts/Faktum-MediumFina.woff2` (licenciada). Há fallback tipográfico enquanto o arquivo não estiver presente.

---

## Testes

Backend (`mvn test`): normalização (`NormalizerTest`), pontuação (`ScoringServiceTest`) e
deduplicação (`DeduplicationServiceTest`) — críticos para qualidade e integridade.
