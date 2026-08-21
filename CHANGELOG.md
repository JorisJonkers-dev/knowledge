# Changelog

## [0.6.0](https://github.com/JorisJonkers-dev/knowledge/compare/v0.5.0...v0.6.0) (2026-08-21)


### Features

* **ci:** add the deploy-preview workflow PLATFORM.md already documents ([#31](https://github.com/JorisJonkers-dev/knowledge/issues/31)) ([3c4eac2](https://github.com/JorisJonkers-dev/knowledge/commit/3c4eac2951448ae850a2f73148229a470058424b))
* **platform:** add the render-local.sh that PLATFORM.md already documents ([#25](https://github.com/JorisJonkers-dev/knowledge/issues/25)) ([f1dd9e6](https://github.com/JorisJonkers-dev/knowledge/commit/f1dd9e6a40f3699aa15b2522fbd0541371d90ebe))
* **platform:** declare the ingest worker exempt from the health check ([#30](https://github.com/JorisJonkers-dev/knowledge/issues/30)) ([2d4f689](https://github.com/JorisJonkers-dev/knowledge/commit/2d4f689dd4758464a3680776538af4fa5113c0dd))

## [0.5.0](https://github.com/JorisJonkers-dev/knowledge/compare/v0.4.3...v0.5.0) (2026-08-20)


### Features

* **ci:** publish images for arm64 as well as amd64 ([#22](https://github.com/JorisJonkers-dev/knowledge/issues/22)) ([cc86003](https://github.com/JorisJonkers-dev/knowledge/commit/cc860035330af0f6d565401420136c92bfdf10d8))

## [0.4.3](https://github.com/JorisJonkers-dev/knowledge/compare/v0.4.2...v0.4.3) (2026-08-19)


### Bug Fixes

* **ci:** bump the reusable workflow pins so job timeouts apply ([#20](https://github.com/JorisJonkers-dev/knowledge/issues/20)) ([b9b99b7](https://github.com/JorisJonkers-dev/knowledge/commit/b9b99b7f910500ce8177e012803fe6376b8c0455))

## [0.4.2](https://github.com/JorisJonkers-dev/knowledge/compare/v0.4.1...v0.4.2) (2026-07-12)


### Bug Fixes

* **ci:** bump cluster context-ref to 64d00fe ([#15](https://github.com/JorisJonkers-dev/knowledge/issues/15)) ([4fb1db7](https://github.com/JorisJonkers-dev/knowledge/commit/4fb1db7507b00dcd085a6c770c21d63b78cbe29e))

## [0.4.1](https://github.com/JorisJonkers-dev/knowledge/compare/v0.4.0...v0.4.1) (2026-07-12)


### Bug Fixes

* **platform:** authMode forward-auth for public-frankfurt tier ([#13](https://github.com/JorisJonkers-dev/knowledge/issues/13)) ([96940b6](https://github.com/JorisJonkers-dev/knowledge/commit/96940b661420ba7a32836d1f35a40b6890bbf500))

## [0.4.0](https://github.com/JorisJonkers-dev/knowledge/compare/v0.3.0...v0.4.0) (2026-07-12)


### Features

* **platform:** add deploy-platform adoption files ([#12](https://github.com/JorisJonkers-dev/knowledge/issues/12)) ([59f8c72](https://github.com/JorisJonkers-dev/knowledge/commit/59f8c725c7b2b383016a93d1802fd44a12ba10c8))


### Bug Fixes

* dead-letter parse and handler failures; add DLQ binding ([#10](https://github.com/JorisJonkers-dev/knowledge/issues/10)) ([d0b715f](https://github.com/JorisJonkers-dev/knowledge/commit/d0b715f10b1b71954d1a5cb350213a4bb6933bcc))

## [0.3.0](https://github.com/JorisJonkers-dev/knowledge/compare/v0.2.0...v0.3.0) (2026-06-29)


### Features

* **deploy:** add knowledge deploy bundle source ([#3](https://github.com/JorisJonkers-dev/knowledge/issues/3)) ([354a8a2](https://github.com/JorisJonkers-dev/knowledge/commit/354a8a23c3c4ace2958790b793a945f71fbf2072))

## [0.2.0](https://github.com/JorisJonkers-dev/knowledge/compare/v0.1.0...v0.2.0) (2026-06-28)


### Features

* adopt published gradle-conventions plugins ([#627](https://github.com/JorisJonkers-dev/knowledge/issues/627)) ([71fa84d](https://github.com/JorisJonkers-dev/knowledge/commit/71fa84d51939b34ed2904587c1b426b7ab6e61cc))
* adopt published kotlin-spring-commons modules; remove local libs/kotlin-common ([#628](https://github.com/JorisJonkers-dev/knowledge/issues/628)) ([ad85611](https://github.com/JorisJonkers-dev/knowledge/commit/ad856112efd252e3753822f2f5d08f9828ef70f2))
* **agent-kit:** default KB recall to span all curated scopes ([#723](https://github.com/JorisJonkers-dev/knowledge/issues/723)) ([4a8001e](https://github.com/JorisJonkers-dev/knowledge/commit/4a8001ea1dc1eb517cde17364a4602e7becb172d))
* build-time OpenAPI export via slice (no app boot) ([#630](https://github.com/JorisJonkers-dev/knowledge/issues/630)) ([bf77128](https://github.com/JorisJonkers-dev/knowledge/commit/bf771285152101477d31e0f84322a9c10c80e2c1))
* cut over to ExtraToast/agents published images ([#657](https://github.com/JorisJonkers-dev/knowledge/issues/657)) ([a7acd5f](https://github.com/JorisJonkers-dev/knowledge/commit/a7acd5f956aced729fa19f0ea6a50e6fdc72e9ac))
* **knowledge-api:** auto-wire Claude hooks in install-agents.sh ([#668](https://github.com/JorisJonkers-dev/knowledge/issues/668)) ([c289c0a](https://github.com/JorisJonkers-dev/knowledge/commit/c289c0afe00b2f5316bdb5f56695f537b7508a8e))
* **knowledge-api:** lite mode exposing only recall + capture ([#694](https://github.com/JorisJonkers-dev/knowledge/issues/694)) ([95033e8](https://github.com/JorisJonkers-dev/knowledge/commit/95033e8bc6638892db7a68d5cdbff0d8b1c599cb))
* **knowledge-api:** register the portable MCP fleet in install-agents.sh ([#669](https://github.com/JorisJonkers-dev/knowledge/issues/669)) ([09bad3d](https://github.com/JorisJonkers-dev/knowledge/commit/09bad3db8852216d4d0ea805d1a8c044ef82313a))
* **knowledge-api:** serve install-agents.sh full agents installer ([#667](https://github.com/JorisJonkers-dev/knowledge/issues/667)) ([3de02f3](https://github.com/JorisJonkers-dev/knowledge/commit/3de02f34d270cb41f1fc20140124869e99762dbb))


### Bug Fixes

* **rebrand:** publish knowledge under JorisJonkers-dev coordinates ([#1](https://github.com/JorisJonkers-dev/knowledge/issues/1)) ([b24cc36](https://github.com/JorisJonkers-dev/knowledge/commit/b24cc369695aa01ef1c16eb804fe26be58fabf14))
