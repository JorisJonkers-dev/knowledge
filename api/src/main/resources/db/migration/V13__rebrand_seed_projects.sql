UPDATE kb_projects
SET slug = 'jorisjonkers-dev/homelab-deploy',
    description = 'Homelab GitOps repository.',
    github_org = 'JorisJonkers-dev',
    updated_at = NOW()
WHERE slug = 'extratoast/personal-stack';

UPDATE kb_project_aliases
SET slug = 'jorisjonkers-dev/homelab-deploy'
WHERE slug = 'extratoast/personal-stack';
