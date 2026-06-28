-- One-time seed of `kb_projects` / `kb_project_aliases` with the
-- two real repos in scope today:
--
--   ExtraToast/personal-stack    — the homelab GitOps monorepo.
--   ESA-Blueshell/website        — the student-association web site.
--
-- Canonical slug is `<org>/<repo>` lowercase so the on-disk vault
-- groups every repo from the same org under one folder
-- (`projects/extratoast/personal-stack/`,
-- `projects/esa-blueshell/website/`). Aliases cover every bogus
-- shape the classifier emitted in production:
--
--   - the bare repo name (`website`, `personal-stack`),
--   - org+repo mashed with a dash (`esa-blueshell-website`),
--   - org+repo mashed with a dot (`esa-blueshell.website`),
--   - the IDE working-copy folder suffix (`personal-stack-2`),
--   - free-form mistakes that became frequent (`home-direct`).
--
-- New repos belong to a future `knowledge.add_project` admin tool
-- in the same shape as `add_topic`.

INSERT INTO kb_projects (slug, description, github_org) VALUES
    ('extratoast/personal-stack', 'Homelab GitOps monorepo. Kotlin/Spring services, Flux CD, k3s, NixOS hosts.', 'ExtraToast'),
    ('esa-blueshell/website',     'ESA-Blueshell student association website.',                                  'ESA-Blueshell');

INSERT INTO kb_project_aliases (alias_lower, slug) VALUES
    ('extratoast/personal-stack',            'extratoast/personal-stack'),
    ('extratoast.personal-stack',            'extratoast/personal-stack'),
    ('extratoast-personal-stack',            'extratoast/personal-stack'),
    ('personal-stack',                       'extratoast/personal-stack'),
    ('personal-stack-2',                     'extratoast/personal-stack'),
    ('personal-stack.',                      'extratoast/personal-stack'),
    ('personalstack',                        'extratoast/personal-stack'),
    ('home-direct',                          'extratoast/personal-stack'),
    ('homelab',                              'extratoast/personal-stack'),
    ('esa-blueshell/website',                'esa-blueshell/website'),
    ('esa-blueshell.website',                'esa-blueshell/website'),
    ('esa-blueshell-website',                'esa-blueshell/website'),
    ('website',                              'esa-blueshell/website'),
    ('blueshell-website',                    'esa-blueshell/website');
