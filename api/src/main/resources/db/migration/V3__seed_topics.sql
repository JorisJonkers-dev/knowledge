-- One-time seed of `kb_topics` / `kb_topic_aliases` from the
-- contents of the knowledge-curator-topics ConfigMap as of the V2
-- migration. After this runs the table is the source of truth;
-- the ConfigMap stays in the repo as an emergency reseed only.
--
-- Hand-translated so the seed is auditable in code review — diffing
-- this file against the ConfigMap is what reviewers compare.
--
-- Subsequent edits land via the `knowledge.add_topic` /
-- `knowledge.update_topic` MCP admin tools and any future
-- `merge_topics` / topic-deactivation flow.

-- ===== languages =====
INSERT INTO kb_topics (slug, description) VALUES
    ('python',     'Python ecosystem — packaging, uv, pytest, asyncio, type hints.'),
    ('kotlin',     'Kotlin ecosystem — Spring, jOOQ, Gradle, coroutines.'),
    ('typescript', 'TypeScript + browser/node ecosystem.'),
    ('bash',       'POSIX shell scripts, bash quirks, command pipelines.'),
    ('sql',        'SQL — query plans, DDL, DML, dialect differences.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('python',  'python'),
    ('py',      'python'),
    ('kotlin',  'kotlin'),
    ('kt',      'kotlin'),
    ('typescript', 'typescript'),
    ('ts',      'typescript'),
    ('bash',    'bash'),
    ('shell',   'bash'),
    ('sh',      'bash'),
    ('sql',     'sql'),
    ('postgres-sql', 'sql');

-- ===== frameworks / runtimes =====
INSERT INTO kb_topics (slug, description) VALUES
    ('spring-boot', 'Spring Boot, Spring AMQP, Spring Web MVC, autoconfiguration.'),
    ('vue',         'Vue 3 + Composition API + Pinia.'),
    ('gradle',      'Gradle build conventions, multi-module setups, plugins.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('spring-boot', 'spring-boot'),
    ('spring',  'spring-boot'),
    ('vue',     'vue'),
    ('vue3',    'vue'),
    ('gradle',  'gradle');

-- ===== infrastructure / cluster =====
INSERT INTO kb_topics (slug, description) VALUES
    ('kubernetes',             'Kubernetes objects, controllers, resource patterns.'),
    ('flux-cd',                'Flux CD reconciliation, Kustomizations, HelmReleases.'),
    ('traefik',                'Traefik IngressRoutes, Middlewares, EntryPoints.'),
    ('vault',                  'HashiCorp Vault — KV-v2, agent inject, dynamic secrets, policies.'),
    ('vault-secrets-operator', 'VaultStaticSecret / VaultDynamicSecret reconciliation.'),
    ('postgres',               'PostgreSQL ops — extensions, replication, performance.'),
    ('pgvector',               'pgvector — ANN indexes, HALFVEC, ivfflat/hnsw.'),
    ('rabbitmq',               'RabbitMQ exchanges, queues, bindings, federation.'),
    ('nixos',                  'NixOS modules, flakes, hosts, deploy-host.sh.'),
    ('tailscale',              'Tailscale subnets, advertise-routes, MagicDNS.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('kubernetes',  'kubernetes'),
    ('k8s',         'kubernetes'),
    ('k3s',         'kubernetes'),
    ('flux-cd',     'flux-cd'),
    ('flux',        'flux-cd'),
    ('fluxcd',      'flux-cd'),
    ('traefik',     'traefik'),
    ('vault',       'vault'),
    ('hashicorp-vault', 'vault'),
    ('vault-agent', 'vault'),
    ('vault-secrets-operator', 'vault-secrets-operator'),
    ('vso',         'vault-secrets-operator'),
    ('postgres',    'postgres'),
    ('postgresql',  'postgres'),
    ('pg',          'postgres'),
    ('pgvector',    'pgvector'),
    ('rabbitmq',    'rabbitmq'),
    ('amqp',        'rabbitmq'),
    ('nixos',       'nixos'),
    ('nix',         'nixos'),
    ('tailscale',   'tailscale');

-- ===== AI / agents =====
INSERT INTO kb_topics (slug, description) VALUES
    ('ollama',      'Ollama runtime — model pulls, /v1/* compatibility, quantization.'),
    ('lightrag',    'LightRAG retrieval, AGE backend, mix-mode queries.'),
    ('mcp',         'Model Context Protocol — tools/list, tools/call, content envelope.'),
    ('claude-code', 'Claude Code CLI — settings, hooks, skills, MCP setup.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('ollama',                  'ollama'),
    ('lightrag',                'lightrag'),
    ('lightrag-hku',            'lightrag'),
    ('mcp',                     'mcp'),
    ('model-context-protocol',  'mcp'),
    ('claude-code',             'claude-code'),
    ('claude',                  'claude-code');

-- ===== tooling =====
INSERT INTO kb_topics (slug, description) VALUES
    ('git',      'git plumbing, safe.directory, gitpython quirks.'),
    ('obsidian', 'Obsidian — desktop / LiveSync / Git plugin.'),
    ('gh-cli',   '`gh` cli — pr create, run rerun, variadic flag traps.'),
    ('docker',   'Dockerfile patterns, multi-stage builds, slim-image surprises.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('git',       'git'),
    ('obsidian',  'obsidian'),
    ('livesync',  'obsidian'),
    ('gh-cli',    'gh-cli'),
    ('gh',        'gh-cli'),
    ('github-cli','gh-cli'),
    ('docker',    'docker');

-- ===== software engineering fundamentals =====
INSERT INTO kb_topics (slug, description) VALUES
    ('design-patterns',       'Reusable OO design patterns — creational, structural, behavioural; intent + applicability + trade-offs.'),
    ('oop',                   'Object-oriented programming — encapsulation, inheritance, polymorphism, composition over inheritance.'),
    ('functional-programming','Functional programming — immutability, pure functions, higher-order functions, algebraic data types.'),
    ('paradigms',             'Cross-cutting programming paradigms — imperative, declarative, reactive, dataflow, logic.'),
    ('algorithms',            'Algorithms — sorting, searching, graph traversal, dynamic programming, complexity analysis.'),
    ('data-structures',       'Data structures — arrays, lists, trees, heaps, hash tables, graphs and their performance trade-offs.'),
    ('software-architecture', 'Software architecture — layering, hexagonal, ports-and-adapters, event-driven, microservices, monolith trade-offs.'),
    ('concurrency',           'Concurrency primitives — threads, coroutines, locks, channels, actors, memory models, race conditions.'),
    ('distributed-systems',   'Distributed systems — consensus, replication, CAP, idempotency, retries, sagas, sharding.'),
    ('testing',               'Testing strategy — unit, integration, end-to-end, property-based, contract, flaky-test triage.'),
    ('refactoring',           'Refactoring — code smells, extract-method, replace-conditional-with-polymorphism, safe transformation sequences.'),
    ('solid',                 'SOLID principles — SRP, OCP, LSP, ISP, DIP; how they shape OO design.'),
    ('security',              'Application & infrastructure security — authn, authz, secrets handling, supply chain, OWASP top 10.');

INSERT INTO kb_topic_aliases (alias_lower, slug) VALUES
    ('design-patterns', 'design-patterns'),
    ('gof',             'design-patterns'),
    ('gang-of-four',    'design-patterns'),
    ('design-pattern',  'design-patterns'),
    ('oop',             'oop'),
    ('object-oriented', 'oop'),
    ('object-oriented-programming', 'oop'),
    ('functional-programming', 'functional-programming'),
    ('fp',              'functional-programming'),
    ('functional',      'functional-programming'),
    ('paradigms',           'paradigms'),
    ('programming-paradigms','paradigms'),
    ('paradigm',            'paradigms'),
    ('algorithms',          'algorithms'),
    ('algorithm',           'algorithms'),
    ('algos',               'algorithms'),
    ('data-structures',     'data-structures'),
    ('data-structure',      'data-structures'),
    ('ds',                  'data-structures'),
    ('software-architecture','software-architecture'),
    ('architecture',        'software-architecture'),
    ('arch',                'software-architecture'),
    ('concurrency',         'concurrency'),
    ('concurrent',          'concurrency'),
    ('parallelism',         'concurrency'),
    ('parallel',            'concurrency'),
    ('distributed-systems', 'distributed-systems'),
    ('distributed',         'distributed-systems'),
    ('dist-sys',            'distributed-systems'),
    ('testing',             'testing'),
    ('tests',               'testing'),
    ('qa',                  'testing'),
    ('test-strategy',       'testing'),
    ('refactoring',         'refactoring'),
    ('refactor',            'refactoring'),
    ('refactor-patterns',   'refactoring'),
    ('solid',               'solid'),
    ('solid-principles',    'solid'),
    ('security',            'security'),
    ('appsec',              'security'),
    ('infosec',             'security');
